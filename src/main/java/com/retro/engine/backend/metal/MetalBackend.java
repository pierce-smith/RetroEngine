package com.retro.engine.backend.metal;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.DirectionalShadowFrustum;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.MaterialBinding;
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
 * addCompletedHandler: block that returns its in-flight permit once the GPU has consumed the frame. The same handler
 * advances MetalGpuCache's completion fence, which gates every deferred MTLBuffer / MTLTexture release.
 */
public class MetalBackend implements IRenderBackend {

    // Selectors are interned once at class load so frame submission never performs a string lookup
    private static final long SEL_ALLOC = sel_registerName("alloc");
    private static final long SEL_INIT = sel_registerName("init");
    private static final long SEL_DRAIN = sel_registerName("drain");
    private static final long SEL_NEXT_DRAWABLE = sel_registerName("nextDrawable");
    private static final long SEL_COMMAND_BUFFER = sel_registerName("commandBuffer");
    private static final long SEL_TEXTURE = sel_registerName("texture");
    private static final long SEL_PRESENT_DRAWABLE = sel_registerName("presentDrawable:");
    private static final long SEL_ADD_COMPLETED_HANDLER = sel_registerName("addCompletedHandler:");
    private static final long SEL_COMMIT = sel_registerName("commit");
    private static final long SEL_CONTENTS = sel_registerName("contents");

    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    private final MetalTargetPass framebuffer = new MetalTargetPass();
    // Picture-in-picture render target for the selected Camera entity; projection aspect is set by the editor, so a
    // fixed 16:9 resolution is only stretched, never distorted
    private final MetalTargetPass cameraPreviewTarget = new MetalTargetPass();
    private static final int CAMERA_PREVIEW_WIDTH = 640;
    private static final int CAMERA_PREVIEW_HEIGHT = 360;
    private final MetalGpuCache resourceCache = new MetalGpuCache();
    private final MetalUniformStream uniformStreamer = new MetalUniformStream();
    private final MetalPassEncoder passEncoder = new MetalPassEncoder();

    // Color-ID picking: RGBA8Uint target matching the scene target, plus one shared readback slot per frame in flight.
    // Each slot records the frame serial and texel it was written for; slots are harvested once that frame completes.
    private final MetalTargetPass pickTarget = new MetalTargetPass(MetalPipeline.MTL_PIXEL_FORMAT_RGBA8_UINT);
    private static final long PICK_READBACK_STRIDE = 256L;
    private long pickReadbackBuffer = 0L;
    private float pickProbeU = -1.0f, pickProbeV = -1.0f;
    private final long[] pickSlotSerial = new long[3];
    private final int[] pickSlotX = new int[3], pickSlotY = new int[3], pickSlotW = new int[3], pickSlotH = new int[3];
    private long lastPickSerial = 0L;
    private int lastPickX = -1, lastPickY = -1, lastPickW, lastPickH;
    private int lastPickId = PICK_UNAVAILABLE;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;
    private long currentAutoreleasePool = 0L;
    private long autoreleasePoolClass = 0L;

    // Matches MetalUniformStream.RING_SLOTS: the CPU never writes a uniform slot the GPU may still be reading
    private static final int MAX_FRAMES_IN_FLIGHT = 3;
    private final Semaphore inFlightFrames = new Semaphore(MAX_FRAMES_IN_FLIGHT);
    private boolean framePermitHeld = false;
    private long completedHandlerCallback = 0L;
    private long completedHandlerBlock = 0L;

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

    private boolean cameraPreviewActive = false;
    private final float[] cameraPreviewViewProj = new float[16];
    private final float[] cameraPreviewPos = new float[3];

    private final List<MetalPassEncoder.RenderEntity> sceneQueuePool = new ArrayList<>();
    private final List<MetalPassEncoder.RenderEntity> activeSceneQueueView = new ArrayList<>();
    private int activeSceneQueueCount = 0;

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        if (!metalDevice.initialize(windowInfo)) {
            shutdown();
            return false;
        }
        if (!metalDevice.supportsMetal3()) {
            System.err.println("[MetalBackend] ERROR: This GPU does not support Metal 3 (MTLGPUFamilyMetal3), which the argument-buffer "
                    + "material path requires. Triggering automatic Vulkan fallback...");
            shutdown();
            return false;
        }
        if (!metalPipeline.initialize(metalDevice)) {
            shutdown();
            return false;
        }

        framebuffer.initFramebuffer(metalDevice, windowInfo.width() > 0 ? windowInfo.width() : 712, windowInfo.height() > 0 ? windowInfo.height() : 488);
        cameraPreviewTarget.initFramebuffer(metalDevice, CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
        resourceCache.initViewportQuad(metalDevice);
        resourceCache.initShadowMap(metalDevice);
        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        uniformStreamer.initialize(metalDevice);
        pickReadbackBuffer = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), PICK_READBACK_STRIDE * MAX_FRAMES_IN_FLIGHT);
        if (!initCompletedHandlerBlock()) {
            shutdown();
            return false;
        }

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
            @Override public void callback(long ret, long args) {
                // Advance the disposal fence before the permit lets the render thread start another frame
                resourceCache.onFrameCompleted();
                inFlightFrames.release();
            }
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
    @Override public long getCameraPreviewTexture() { return cameraPreviewTarget.getOffscreenColorTexture(); }

    @Override
    public void setCameraPreview(boolean active, float[] viewProj, float camX, float camY, float camZ) {
        cameraPreviewActive = active && viewProj != null && viewProj.length == 16;
        if (!cameraPreviewActive) return;
        System.arraycopy(viewProj, 0, cameraPreviewViewProj, 0, 16);
        cameraPreviewPos[0] = camX; cameraPreviewPos[1] = camY; cameraPreviewPos[2] = camZ;
    }

    @Override public void resizeViewport(int width, int height) { framebuffer.resizeViewport(metalDevice, width, height, resourceCache); }
    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }
    @Override public void setCameraProjection(float fovDeg, float near, float far) {
        uniformStreamer.setProjection(fovDeg, near, far);
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
    public long registerMesh(String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount) {
        return resourceCache.registerMesh(metalDevice, assetKey, vertices, vertexCount, indices, indexCount);
    }

    @Override
    public long registerTexture(String assetKey, byte[] pixels, int width, int height) {
        return resourceCache.registerTexture(metalDevice, assetKey, pixels, width, height);
    }

    @Override
    public boolean setUiFontAtlas(long textureHandle) {
        MetalTexture tex = (textureHandle != 0L) ? resourceCache.getTexture(textureHandle) : null;
        if (tex == null || tex.getHasActiveTexture() != 1) return false;
        resourceCache.setUiFontTexture(tex.getBindableTexture());
        return true;
    }

    @Override public void unregisterMesh(long meshHandle) { resourceCache.unregisterMesh(meshHandle); }
    @Override public void unregisterTexture(long textureHandle) { resourceCache.unregisterTexture(textureHandle); }

    @Override
    public void submitEntity(long meshHandle, long textureHandle, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength, float[][] bonePalette) {
        MetalPassEncoder.RenderEntity item = queueEntity(meshHandle, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflectionStrength, bonePalette);
        if (item == null) return;
        // Legacy single-texture submissions carry no extra channels; reset what a pooled item may still hold
        item.materialMask = 0;
        setMaterialChannel(item, 0, MetalUniformStream.MATERIAL_ALBEDO, textureHandle);
        for (int c = 1; c < MetalUniformStream.MATERIAL_CHANNELS; c++) setMaterialChannel(item, c, 0, 0L);
        item.specularStrength = 0.0f;
        item.roughness = 0.6f;
        item.normalStrength = 1.0f;
        item.emissiveIntensity = 1.0f;
        item.pickId = PICK_ID_NONE;
    }

    @Override
    public void submitEntity(long meshHandle, MaterialBinding material, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float[][] bonePalette) {
        if (material == null) {
            submitEntity(meshHandle, 0L, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, 0.0f, bonePalette);
            return;
        }
        MetalPassEncoder.RenderEntity item = queueEntity(meshHandle, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, material.reflect, bonePalette);
        if (item == null) return;
        item.materialMask = 0;
        setMaterialChannel(item, 0, MetalUniformStream.MATERIAL_ALBEDO, material.albedoTexture);
        setMaterialChannel(item, 1, MetalUniformStream.MATERIAL_NORMAL, material.normalTexture);
        setMaterialChannel(item, 2, MetalUniformStream.MATERIAL_SPECULAR, material.specularTexture);
        setMaterialChannel(item, 3, MetalUniformStream.MATERIAL_LIGHTMAP, material.lightmapTexture);
        setMaterialChannel(item, 4, MetalUniformStream.MATERIAL_EMISSIVE, material.emissiveTexture);
        item.specularStrength = material.specularStrength;
        item.roughness = material.roughness;
        item.normalStrength = material.normalStrength;
        item.emissiveIntensity = material.emissiveIntensity;
        item.pickId = PICK_ID_NONE;
    }

    @Override
    public void submitEntity(long meshHandle, MaterialBinding material, int pickId, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float[][] bonePalette) {
        int before = activeSceneQueueCount;
        submitEntity(meshHandle, material, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, bonePalette);
        if (activeSceneQueueCount != before) sceneQueuePool.get(activeSceneQueueCount - 1).pickId = pickId;
    }

    /**
     * Resolves a texture handle to its raw id<MTLTexture> and gpuResourceID here, so MetalPassEncoder writes argument
     * buffers without lookups. A 0L or stale handle leaves the channel absent.
     */
    private void setMaterialChannel(MetalPassEncoder.RenderEntity item, int channel, int maskBit, long textureHandle) {
        MetalTexture tex = (textureHandle != 0L) ? resourceCache.getTexture(textureHandle) : null;
        if (tex != null && tex.getBindableResourceId() != 0L) {
            item.materialTextures[channel] = tex.getBindableTexture();
            item.materialResourceIds[channel] = tex.getBindableResourceId();
            item.materialMask |= maskBit;
        } else {
            item.materialTextures[channel] = 0L;
            item.materialResourceIds[channel] = 0L;
        }
    }

    /** Claims a pooled queue entry for a live mesh and fills its geometry and transform; null skips the draw. */
    private MetalPassEncoder.RenderEntity queueEntity(long meshHandle, float px, float py, float pz, float rx, float ry, float rz,
                                                      float sx, float sy, float sz, float groundY, float reflectionStrength, float[][] bonePalette) {
        MetalBuffer mesh = resourceCache.getMesh(meshHandle);
        if (mesh == null || mesh.getElementCount() <= 0) return null;

        MetalPassEncoder.RenderEntity item;
        if (activeSceneQueueCount < sceneQueuePool.size()) {
            item = sceneQueuePool.get(activeSceneQueueCount);
        } else {
            item = new MetalPassEncoder.RenderEntity();
            sceneQueuePool.add(item);
        }
        activeSceneQueueCount++;

        item.vertexBufferHandle = mesh.getHandle();
        item.vertexBufferAddress = (mesh instanceof MetalGpuCache.MetalMesh m) ? m.vertexAddress
                : MetalBuffer.gpuAddress(mesh.getHandle(), metalDevice.getObjcMsgSend());
        item.vertexCount = mesh.getElementCount();
        if (mesh instanceof MetalGpuCache.MetalMesh indexed && indexed.isIndexed()) {
            item.indexBufferHandle = indexed.indexBufferHandle;
            item.indexCount = indexed.indexCount;
            item.indexType = indexed.indexType;
        } else {
            item.indexBufferHandle = 0L;
            item.indexCount = 0;
            item.indexType = MetalGpuCache.MTL_INDEX_TYPE_UINT32;
        }
        item.stride = mesh.getFloatStride();
        item.boundsRadius = (mesh instanceof MetalGpuCache.MetalMesh m) ? m.boundsRadius : 1.0f;
        item.px = px; item.py = py; item.pz = pz;
        item.rx = rx; item.ry = ry; item.rz = rz;
        item.sx = sx; item.sy = sy; item.sz = sz;
        item.groundY = groundY;
        item.reflectionStrength = reflectionStrength;
        item.bonePalette = bonePalette;
        return item;
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        if (autoreleasePoolClass == 0L) {
            autoreleasePoolClass = objc_getClass("NSAutoreleasePool");
        }
        if (autoreleasePoolClass != 0L && send != 0L) {
            long alloc = invokePPP(autoreleasePoolClass, SEL_ALLOC, send);
            currentAutoreleasePool = invokePPP(alloc, SEL_INIT, send);
        }

        // Block until the GPU has retired the frame that last used the uniform slot we are about to advance into
        inFlightFrames.acquireUninterruptibly();
        framePermitHeld = true;

        // Release deferred resources whose last referencing frame has signalled completion
        resourceCache.beginFrame(metalDevice);
        harvestPickReadbacks();

        uniformStreamer.advanceFrameIndex();

        metalDevice.syncContentsScale();
        currentDrawable = invokePPP(metalLayer, SEL_NEXT_DRAWABLE, send);
        if (currentDrawable == 0L) {
            releaseUnsubmittedFramePermit();
            if (currentAutoreleasePool != 0L && send != 0L) {
                invokePPP(currentAutoreleasePool, SEL_DRAIN, send);
                currentAutoreleasePool = 0L;
            }
            return;
        }

        currentCommandBuffer = invokePPP(commandQueue, SEL_COMMAND_BUFFER, send);

        computeLightSpaceMatrix();
        armPickPass();

        passEncoder.encodeOffscreen3DPass(
            currentCommandBuffer, metalDevice, metalPipeline, metalTexture,
            framebuffer, cameraPreviewTarget, resourceCache, uniformStreamer,
            getActiveSceneQueue(), camPanX, camPanY, camZoom, camPitch, camYaw,
            cameraPreviewActive ? cameraPreviewViewProj : null, cameraPreviewPos,
            activeLightType, activeLightPosX, activeLightPosY, activeLightPosZ,
            activeLightDirX, activeLightDirY, activeLightDirZ,
            activeLightR, activeLightG, activeLightB, activeLightIntensity
        );

        long drawableTexture = invokePPP(currentDrawable, SEL_TEXTURE, send);
        passEncoder.encodeWindowCompositionPass(
            currentCommandBuffer, drawableTexture, metalDevice, metalPipeline, metalTexture,
            framebuffer, resourceCache, uniformStreamer,
            cameraPreviewActive ? cameraPreviewTarget.getOffscreenColorTexture() : 0L
        );
    }

    /** Arms (or disarms) the pick pass for the frame about to be encoded into its frame-in-flight readback slot. */
    private void armPickPass() {
        boolean armed = pickReadbackBuffer != 0L && metalPipeline.getPipelineStatePick() != 0L
                && pickProbeU >= 0.0f && pickProbeU < 1.0f && pickProbeV >= 0.0f && pickProbeV < 1.0f;
        if (!armed) {
            passEncoder.setPickPass(null, 0L, 0L, 0, 0);
            return;
        }
        int w = framebuffer.getWidth(), h = framebuffer.getHeight();
        pickTarget.resizeViewport(metalDevice, w, h, resourceCache);
        long serial = resourceCache.getFramesSubmitted() + 1;
        int slot = (int) (serial % MAX_FRAMES_IN_FLIGHT);
        int x = pickTexel(pickProbeU, w), y = pickTexel(pickProbeV, h);
        pickSlotSerial[slot] = serial;
        pickSlotX[slot] = x; pickSlotY[slot] = y; pickSlotW[slot] = w; pickSlotH[slot] = h;
        passEncoder.setPickPass(pickTarget, pickReadbackBuffer, slot * PICK_READBACK_STRIDE, x, y);
    }

    /** Publishes the newest readback whose frame the GPU has completed; shared storage is coherent after completion. */
    private void harvestPickReadbacks() {
        if (pickReadbackBuffer == 0L) return;
        long completed = resourceCache.getFramesCompleted();
        int newest = -1;
        for (int slot = 0; slot < pickSlotSerial.length; slot++) {
            long serial = pickSlotSerial[slot];
            if (serial > lastPickSerial && serial <= completed && (newest < 0 || serial > pickSlotSerial[newest])) newest = slot;
        }
        if (newest < 0) return;
        long contents = invokePPP(pickReadbackBuffer, SEL_CONTENTS, metalDevice.getObjcMsgSend());
        if (contents == 0L) return;
        // entity_id.metal stores the alpha byte XOR 1 so the default (0, 0, 0, 1) clear reads back as PICK_ID_NONE
        lastPickId = MemoryUtil.memGetInt(contents + newest * PICK_READBACK_STRIDE) ^ 0x01000000;
        lastPickSerial = pickSlotSerial[newest];
        lastPickX = pickSlotX[newest]; lastPickY = pickSlotY[newest];
        lastPickW = pickSlotW[newest]; lastPickH = pickSlotH[newest];
    }

    private static int pickTexel(float uv, int size) {
        return Math.max(0, Math.min(size - 1, (int) (uv * size)));
    }

    @Override
    public void setPickProbe(float u, float v) {
        boolean inside = u >= 0.0f && u < 1.0f && v >= 0.0f && v < 1.0f;
        pickProbeU = inside ? u : -1.0f;
        pickProbeV = inside ? v : -1.0f;
    }

    @Override
    public int queryPick(float u, float v) {
        harvestPickReadbacks();
        if (lastPickX < 0 || u < 0.0f || u >= 1.0f || v < 0.0f || v >= 1.0f) return PICK_UNAVAILABLE;
        int w = framebuffer.getWidth(), h = framebuffer.getHeight();
        if (w != lastPickW || h != lastPickH || pickTexel(u, w) != lastPickX || pickTexel(v, h) != lastPickY) return PICK_UNAVAILABLE;
        return lastPickId;
    }

    /** Orthographic directional light view-projection fitted around the bounding spheres of every queued entity. */
    private void computeLightSpaceMatrix() {
        DirectionalShadowFrustum frustum = uniformStreamer.getShadowFrustum();
        frustum.reset();
        for (int i = 0; i < activeSceneQueueCount; i++) {
            MetalPassEncoder.RenderEntity item = sceneQueuePool.get(i);
            float scale = Math.max(Math.abs(item.sx), Math.max(Math.abs(item.sy), Math.abs(item.sz)));
            frustum.include(item.px, item.py, item.pz, item.boundsRadius * scale);
        }
        uniformStreamer.updateLightSpaceMatrix(activeLightDirX, activeLightDirY, activeLightDirZ);
    }

    @Override
    public void endFrame() {
        if (currentCommandBuffer == 0L || currentDrawable == 0L) return;
        invokePPPP(currentCommandBuffer, SEL_PRESENT_DRAWABLE, currentDrawable, metalDevice.getObjcMsgSend());
    }

    @Override
    public void present() {
        long send = metalDevice.getObjcMsgSend();

        if (currentCommandBuffer != 0L && send != 0L) {
            // The completion handler owns this frame's permit from here; it is released once the GPU finishes.
            if (framePermitHeld) {
                invokePPPP(currentCommandBuffer, SEL_ADD_COMPLETED_HANDLER, completedHandlerBlock, send);
                framePermitHeld = false;
            }
            invokePPP(currentCommandBuffer, SEL_COMMIT, send);
            resourceCache.onFrameSubmitted();
            currentCommandBuffer = 0L;
            currentDrawable = 0L;
        }
        releaseUnsubmittedFramePermit();

        if (currentAutoreleasePool != 0L && send != 0L) {
            invokePPP(currentAutoreleasePool, SEL_DRAIN, send);
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
        passEncoder.dispose();
        pickTarget.dispose(metalDevice);
        metalDevice.release(pickReadbackBuffer);
        pickReadbackBuffer = 0L;
        inFlightFrames.release(MAX_FRAMES_IN_FLIGHT);
    }
    @Override public long createBuffer(BufferDesc createInfo) { return 102L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) {}
    @Override public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override public String getDeviceName() { return "Native Apple Metal Driver (Raycast Ground Shadows Active)"; }
}
