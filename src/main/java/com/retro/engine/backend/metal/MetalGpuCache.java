package com.retro.engine.backend.metal;

import com.retro.engine.backend.metal.MetalBuffer;
import com.retro.engine.backend.metal.MetalDevice;
import com.retro.engine.backend.metal.MetalTexture;

import java.util.HashMap;
import java.util.Map;

/**
 * Delegate managing GPU mesh VRAM buffer caches, texture handle caches, and vertex mesh state
 * (grid, UI overlays, viewport quad).
 */
public class MetalGpuCache {

    private final Map<String, Long> gpuMeshCache = new HashMap<>();
    private final Map<String, Long> gpuTextureCache = new HashMap<>();

    private long gridVertexBuffer = 0L;
    private int gridVertexCount = 0;

    private long uiVertexBuffer = 0L;
    private int uiVertexCount = 0;

    private long viewportQuadBuffer = 0L;

    public long getGridVertexBuffer() {
        return gridVertexBuffer;
    }

    public int getGridVertexCount() {
        return gridVertexCount;
    }

    public long getUiVertexBuffer() {
        return uiVertexBuffer;
    }

    public int getUiVertexCount() {
        return uiVertexCount;
    }

    public long getViewportQuadBuffer() {
        return viewportQuadBuffer;
    }

    public void initViewportQuad(MetalDevice metalDevice) {
        if (metalDevice == null) return;
        float[] quadVerts = new float[] {
            // X,       Y,      Z,    R,    G,    B,    A,    U,    V
            -0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
            -0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f,
             0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,

            -0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
             0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
             0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f
        };
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), quadVerts, 9);
        this.viewportQuadBuffer = buf.getHandle();
    }

    public void updateGridMesh(MetalDevice metalDevice, float[] vertices, int count) {
        if (vertices == null || count <= 0) {
            this.gridVertexCount = 0;
            return;
        }
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
        this.gridVertexBuffer = buf.getHandle();
        this.gridVertexCount = count;
    }

    public void updateUiMesh(MetalDevice metalDevice, float[] vertices, int count) {
        if (vertices == null || count <= 0) {
            this.uiVertexCount = 0;
            return;
        }
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 6);
        this.uiVertexBuffer = buf.getHandle();
        this.uiVertexCount = count;
    }

    public long getOrCreateMeshBuffer(MetalDevice metalDevice, String meshPath, float[] vertices) {
        if (meshPath == null || vertices == null) return 0L;
        return gpuMeshCache.computeIfAbsent(meshPath, k -> {
            MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
            return buf.getHandle();
        });
    }

    public long getOrCreateTexture(MetalDevice metalDevice, String texturePath, byte[] pixels, int texW, int texH) {
        if (texturePath == null || pixels == null || pixels.length == 0) return 0L;
        return gpuTextureCache.computeIfAbsent(texturePath, k -> {
            MetalTexture tex = new MetalTexture();
            tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, texW, texH);
            return tex.getBindableTexture();
        });
    }

    public void clearCaches() {
        gpuMeshCache.clear();
        gpuTextureCache.clear();
    }
}
