package com.retro.engine.backend.metal;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;

import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.macosx.LibSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalBackend Facade Bridge routing scene queue and downward-raycast shadow parameters to MetalPassEncoder.
 * Hardened with zero-allocation scene queue views and triple-buffered uniform streaming.
 * Frames in flight are capped at the uniform ring depth: each committed command buffer carries an
 * addCompletedHandler: block that returns its in-flight permit once the GPU has consumed the frame.
 */
public class MetalBackend implements IRenderBackend {

    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    private final MetalTargetPass framebuffer = new MetalTargetPass();
    private final MetalGpuCache resourceCache = new MetalGpuCache();
    private final MetalUniformStream uniformStreamer = new MetalUniformStream();
    private final MetalPassEncoder passEncoder = new MetalPassEncoder();

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;
    private long currentAutoreleasePool = 0L;

    // Matches MetalUniformStream.RING_SLOTS: the CPU never writes a uniform slot the GPU may still be reading
    private static final int MAX_FRAMES_IN_FLIGHT = 3;
    private final Semaphore inFlightFrames = new Semaphore(MAX_FRAMES_IN_FLIGHT);
    private boolean framePermitHeld = false;
    private long completedHandlerCallback = 0L;
    private long completedHandlerBlock = 0L;
    private long frameCounter = 0L;

    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    // Active Scene Lighting Parameters
    private int activeLightType = 0; // 0 = DIRECTIONAL, 1 = POINT, 2 = SPOT
    private float activeLightPosX = 0.0f;
    private float activeLightPosY = 5.0f;
    private float activeLightPosZ = 0.0f;
    private float activeLightDirX = -0.5f;
    private float activeLightDirY = -1.0f;
    private float activeLightDirZ = -0.5f;
    private float activeLightR = 1.0f;
    private float activeLightG = 0.95f;
    private float activeLightB = 0.85f;
    private float activeLightIntensity = 1.2f;

    private final List<MetalPassEncoder.RenderEntity> sceneQueuePool = new ArrayList<>();
    private final List<MetalPassEncoder.RenderEntity> activeSceneQueueView = new ArrayList<>();
    private int activeSceneQueueCount = 0;

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        if (!metalDevice.initialize(windowInfo)) return false;
        if (!metalPipeline.initialize(metalDevice)) return false;

        framebuffer.initFramebuffer(metalDevice, windowInfo.width() > 0 ? windowInfo.width() : 712, windowInfo.height() > 0 ? windowInfo.height() : 488);
        resourceCache.initViewportQuad(metalDevice);
        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        uniformStreamer.initialize(metalDevice);
        if (!initCompletedHandlerBlock()) return false;

        System.out.println("[MetalBackend] Sub-step 4B: Downward Raycast Dynamic Surface Shadows Active.");
        return true;
    }

    /**
     * Builds a global Objective-C block literal (void (^)(id<MTLCommandBuffer>)) whose invoke pointer is an
     * LWJGL libffi closure. Global blocks are never copied or disposed by the runtime, so one instance is
     * reused for every frame. Metal calls it on its own dispatch thread; LWJGL attaches that thread to the JVM.
     */
    private boolean initCompletedHandlerBlock() {
        long nsConcreteGlobalBlock = LibSystem.getLibrary().getFunctionAddress("_NSConcreteGlobalBlock");
        if (nsConcreteGlobalBlock == 0L) {
            System.err.println("[MetalBackend] ERROR: _NSConcreteGlobalBlock not found; cannot register command buffer completion handlers.");
            return false;
        }

        final FFICIF cif = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_void, ffi_type_pointer, ffi_type_pointer);
        CallbackI onCompleted = new CallbackI() {
            @Override public FFICIF getCallInterface() { return cif; }
            @Override public void callback(long ret, long args) { inFlightFrames.release(); }
        };
        completedHandlerCallback = onCompleted.address();

        // struct Block_literal { void *isa; int flags; int reserved; void *invoke; Block_descriptor *descriptor; }
        // struct Block_descriptor { unsigned long reserved; unsigned long size; } is packed after the literal.
        final int BLOCK_IS_GLOBAL = 1 << 28;
        completedHandlerBlock = MemoryUtil.nmemCalloc(1, 48);
        long descriptor = completedHandlerBlock + 32;
        MemoryUtil.memPutAddress(completedHandlerBlock, nsConcreteGlobalBlock);
        MemoryUtil.memPutInt(completedHandlerBlock + 8, BLOCK_IS_GLOBAL);
        MemoryUtil.memPutInt(completedHandlerBlock + 12, 0);
        MemoryUtil.memPutAddress(completedHandlerBlock + 16, completedHandlerCallback);
        MemoryUtil.memPutAddress(completedHandlerBlock + 24, descriptor);
        MemoryUtil.memPutLong(descriptor, 0L);
        MemoryUtil.memPutLong(descriptor + 8, 32L);
        return true;
    }

    @Override public long getOffscreenViewportTexture() { return framebuffer.getOffscreenColorTexture(); }
    @Override public void resizeViewport(int width, int height) { framebuffer.resizeViewport(metalDevice, width, height); }
    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    @Override
    public void setSceneLight(int type, float posX, float posY, float posZ, float dirX, float dirY, float dirZ, float r, float g, float b, float intensity) {
        this.activeLightType = type;
        this.activeLightPosX = posX;
        this.activeLightPosY = posY;
        this.activeLightPosZ = posZ;
        this.activeLightDirX = dirX;
        this.activeLightDirY = dirY;
        this.activeLightDirZ = dirZ;
        this.activeLightR = r;
        this.activeLightG = g;
        this.activeLightB = b;
        this.activeLightIntensity = intensity;
    }

    @Override
    public void setSceneViewport(float minX, float minY, float maxX, float maxY, float aspect) {
        resourceCache.updateViewportQuad(metalDevice, minX, minY, maxX, maxY);
        passEncoder.setSceneAspect(aspect);
    }

    @Override
    public void updateGridMesh(float[] vertices, int count) {
        resourceCache.updateGridMesh(metalDevice, vertices, count);
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        resourceCache.updateUiMesh(metalDevice, vertices, count);
    }

    @Override public void clearSceneQueue() { activeSceneQueueCount = 0; }

    public List<MetalPassEncoder.RenderEntity> getActiveSceneQueue() {
        activeSceneQueueView.clear();
        for (int i = 0; i < activeSceneQueueCount; i++) {
            activeSceneQueueView.add(sceneQueuePool.get(i));
        }
        return activeSceneQueueView;
    }

    @Override
    public long registerMesh(String assetKey, float[] vertices, int vertexCount) {
        return resourceCache.registerMesh(metalDevice, assetKey, vertices, vertexCount);
    }

    @Override
    public long registerTexture(String assetKey, byte[] pixels, int width, int height) {
        return resourceCache.registerTexture(metalDevice, assetKey, pixels, width, height);
    }

    @Override public void unregisterMesh(long meshHandle) { resourceCache.unregisterMesh(meshHandle); }
    @Override public void unregisterTexture(long textureHandle) { resourceCache.unregisterTexture(textureHandle); }

    @Override
    public void submitEntity(long meshHandle, long textureHandle, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength, float[][] bonePalette) {
        MetalBuffer mesh = resourceCache.getMesh(meshHandle);
        if (mesh == null || mesh.getElementCount() <= 0) return;

        // Resolve to raw id<MTLTexture> here so MetalPassEncoder can batch-sort and bind without lookups
        MetalTexture tex = resourceCache.getTexture(textureHandle);
        long texHandle = (tex != null) ? tex.getBindableTexture() : 0L;

        MetalPassEncoder.RenderEntity item;
        if (activeSceneQueueCount < sceneQueuePool.size()) {
            item = sceneQueuePool.get(activeSceneQueueCount);
        } else {
            item = new MetalPassEncoder.RenderEntity();
            sceneQueuePool.add(item);
        }
        activeSceneQueueCount++;

        item.vertexBufferHandle = mesh.getHandle();
        item.vertexCount = mesh.getElementCount();
        item.stride = mesh.getFloatStride();
        item.textureHandle = texHandle;
        item.hasTexture = (texHandle != 0L);
        item.px = px; item.py = py; item.pz = pz;
        item.rx = rx; item.ry = ry; item.rz = rz;
        item.sx = sx; item.sy = sy; item.sz = sz;
        item.groundY = groundY;
        item.reflectionStrength = reflectionStrength;
        item.bonePalette = bonePalette;
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        long poolClass = objc_getClass("NSAutoreleasePool");
        if (poolClass != 0L && send != 0L) {
            long alloc = invokePPP(poolClass, sel_registerName("alloc"), send);
            currentAutoreleasePool = invokePPP(alloc, sel_registerName("init"), send);
        }

        // Block until the GPU has retired the frame that last used the uniform slot we are about to advance into
        inFlightFrames.acquireUninterruptibly();
        framePermitHeld = true;

        // With the permit held, frames older than the ring depth have retired; release their deferred resources
        resourceCache.beginFrame(metalDevice, ++frameCounter);

        uniformStreamer.advanceFrameIndex();

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), send);
        if (currentDrawable == 0L) {
            releaseUnsubmittedFramePermit();
            if (currentAutoreleasePool != 0L && send != 0L) {
                invokePPP(currentAutoreleasePool, sel_registerName("drain"), send);
                currentAutoreleasePool = 0L;
            }
            return;
        }

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), send);

        passEncoder.encodeOffscreen3DPass(
            currentCommandBuffer, metalDevice, metalPipeline, metalTexture,
            framebuffer, resourceCache, uniformStreamer,
            getActiveSceneQueue(), camPanX, camPanY, camZoom, camPitch, camYaw,
            activeLightType, activeLightPosX, activeLightPosY, activeLightPosZ,
            activeLightDirX, activeLightDirY, activeLightDirZ,
            activeLightR, activeLightG, activeLightB, activeLightIntensity
        );

        long drawableTexture = invokePPP(currentDrawable, sel_registerName("texture"), send);
        passEncoder.encodeWindowCompositionPass(
            currentCommandBuffer, drawableTexture, metalDevice, metalPipeline, metalTexture,
            framebuffer, resourceCache, uniformStreamer
        );
    }

    @Override
    public void endFrame() {
        if (currentCommandBuffer == 0L || currentDrawable == 0L) return;
        invokePPPP(currentCommandBuffer, sel_registerName("presentDrawable:"), currentDrawable, metalDevice.getObjcMsgSend());
    }

    @Override
    public void present() {
        long send = metalDevice.getObjcMsgSend();

        if (currentCommandBuffer != 0L && send != 0L) {
            // The completion handler owns this frame's permit from here; it is released once the GPU finishes.
            if (framePermitHeld) {
                invokePPPP(currentCommandBuffer, sel_registerName("addCompletedHandler:"), completedHandlerBlock, send);
                framePermitHeld = false;
            }
            invokePPP(currentCommandBuffer, sel_registerName("commit"), send);
            currentCommandBuffer = 0L;
            currentDrawable = 0L;
        }
        releaseUnsubmittedFramePermit();

        if (currentAutoreleasePool != 0L && send != 0L) {
            invokePPP(currentAutoreleasePool, sel_registerName("drain"), send);
            currentAutoreleasePool = 0L;
        }
    }

    private void releaseUnsubmittedFramePermit() {
        if (framePermitHeld) {
            framePermitHeld = false;
            inFlightFrames.release();
        }
    }

    @Override
    public void shutdown() {
        releaseUnsubmittedFramePermit();
        // Drain in-flight frames before freeing the block and closure the GPU completion handlers still reference
        try {
            if (!inFlightFrames.tryAcquire(MAX_FRAMES_IN_FLIGHT, 1, TimeUnit.SECONDS)) {
                System.err.println("[MetalBackend] WARNING: GPU frames still in flight at shutdown; leaking completion handler block.");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (completedHandlerBlock != 0L) {
            MemoryUtil.nmemFree(completedHandlerBlock);
            completedHandlerBlock = 0L;
        }
        if (completedHandlerCallback != 0L) {
            Callback.free(completedHandlerCallback);
            completedHandlerCallback = 0L;
        }
        inFlightFrames.release(MAX_FRAMES_IN_FLIGHT);
    }
    @Override public long createBuffer(BufferDesc createInfo) { return 102L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) {}
    @Override public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override public String getDeviceName() { return "Native Apple Metal Driver (Raycast Ground Shadows Active)"; }
}
