package com.retro.engine.backend.metal;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalBackend Facade Bridge routing scene queue and downward-raycast shadow parameters to MetalPassEncoder.
 * Hardened with zero-allocation scene queue views and triple-buffered uniform streaming.
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

        System.out.println("[MetalBackend] Sub-step 4B: Downward Raycast Dynamic Surface Shadows Active.");
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
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflectionStrength, null);
    }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength, float[][] bonePalette) {
        if (meshPath == null || vertices == null || vertexCount <= 0) return;

        long vbo = resourceCache.getOrCreateMeshBuffer(metalDevice, meshPath, vertices, vertexCount);

        long texHandle = 0L;
        boolean hasTex = false;
        if (texturePath != null && pixels != null && pixels.length > 0) {
            texHandle = resourceCache.getOrCreateTexture(metalDevice, texturePath, pixels, texW, texH);
            hasTex = (texHandle != 0L);
        }

        MetalPassEncoder.RenderEntity item;
        if (activeSceneQueueCount < sceneQueuePool.size()) {
            item = sceneQueuePool.get(activeSceneQueueCount);
        } else {
            item = new MetalPassEncoder.RenderEntity();
            sceneQueuePool.add(item);
        }
        activeSceneQueueCount++;

        item.vertexBufferHandle = vbo;
        item.vertexCount = vertexCount;
        item.textureHandle = texHandle;
        item.hasTexture = hasTex;
        item.px = px; item.py = py; item.pz = pz;
        item.rx = rx; item.ry = ry; item.rz = rz;
        item.sx = sx; item.sy = sy; item.sz = sz;
        item.groundY = groundY;
        item.reflectionStrength = reflectionStrength;
        item.stride = vertices.length / vertexCount;
        item.bonePalette = bonePalette;
    }

    public void submitEntityWithShadow(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflectionStrength);
    }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, px, py, pz, rx, ry, rz, sx, sy, sz, 0.0f, 0.0f);
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

        uniformStreamer.advanceFrameIndex();

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), send);
        if (currentDrawable == 0L) {
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
            invokePPP(currentCommandBuffer, sel_registerName("commit"), send);
            currentCommandBuffer = 0L;
            currentDrawable = 0L;
        }

        if (currentAutoreleasePool != 0L && send != 0L) {
            invokePPP(currentAutoreleasePool, sel_registerName("drain"), send);
            currentAutoreleasePool = 0L;
        }
    }

    @Override public void shutdown() {}
    @Override public long createBuffer(BufferDesc createInfo) { return 102L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) {}
    @Override public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override public String getDeviceName() { return "Native Apple Metal Driver (Raycast Ground Shadows Active)"; }
}
