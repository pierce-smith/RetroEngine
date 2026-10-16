package com.retro.engine.backend.metal;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Transform;

import com.retro.engine.backend.metal.MetalTargetPass;
import com.retro.engine.backend.metal.MetalPassEncoder;
import com.retro.engine.backend.metal.MetalGpuCache;
import com.retro.engine.backend.metal.MetalUniformStream;
import com.retro.engine.rhi.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalBackend implements IRenderBackend as a lightweight Facade Bridge.
 * Delegates framebuffer targets, VRAM caching, uniform streaming, and pass encoding.
 */
public class MetalBackend implements IRenderBackend {

    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    // RHI Subsystem Delegates
    private final MetalTargetPass framebuffer = new MetalTargetPass();
    private final MetalGpuCache resourceCache = new MetalGpuCache();
    private final MetalUniformStream uniformStreamer = new MetalUniformStream();
    private final MetalPassEncoder passEncoder = new MetalPassEncoder();

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    // Camera Transform State
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    // Multi-entity Batch Queue
    private final List<MetalPassEncoder.RenderEntity> sceneQueuePool = new ArrayList<>();
    private int activeSceneQueueCount = 0;

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        if (!metalDevice.initialize(windowInfo)) return false;
        if (!metalPipeline.initialize(metalDevice)) return false;

        framebuffer.initFramebuffer(metalDevice, 712, 488);
        resourceCache.initViewportQuad(metalDevice);
        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        uniformStreamer.initialize(metalDevice);

        System.out.println("[MetalBackend] RHI Modular Pipeline Initialized.");
        return true;
    }

    @Override public long getOffscreenViewportTexture() { return framebuffer.getOffscreenColorTexture(); }
    @Override public void resizeViewport(int width, int height) { framebuffer.resizeViewport(metalDevice, width, height); }
    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
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
        return sceneQueuePool.subList(0, activeSceneQueueCount);
    }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        if (meshPath == null || vertices == null || vertexCount <= 0) return;

        long vbo = resourceCache.getOrCreateMeshBuffer(metalDevice, meshPath, vertices);

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
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        uniformStreamer.advanceFrameIndex();

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), send);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), send);

        // Pass 1: Offscreen 3D Scene Pass
        passEncoder.encodeOffscreen3DPass(
            currentCommandBuffer, metalDevice, metalPipeline, metalTexture,
            framebuffer, resourceCache, uniformStreamer,
            getActiveSceneQueue(), camPanX, camPanY, camZoom, camPitch, camYaw
        );

        // Pass 2: Window Composition Pass
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
        if (currentCommandBuffer == 0L) return;
        invokePPP(currentCommandBuffer, sel_registerName("commit"), metalDevice.getObjcMsgSend());
        currentCommandBuffer = 0L;
        currentDrawable = 0L;
    }

    @Override public void shutdown() { System.out.println("[MetalBackend] Releasing Metal offscreen and pipeline resources..."); }
    @Override public long createBuffer(BufferDesc createInfo) { return 102L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) {}
    @Override public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override public String getDeviceName() { return "Native Apple Metal Driver (Modular Phase 2 Active)"; }
}
