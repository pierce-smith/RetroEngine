package com.retro.engine.backend.metal;

import com.retro.engine.backend.metal.MetalBuffer;
import com.retro.engine.backend.metal.MetalDevice;
import com.retro.engine.backend.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Delegate managing GPU mesh VRAM buffer caches, texture handle caches, and ring-buffered vertex mesh state
 * (grid, UI overlays, viewport quad) with zero per-frame native GPU allocations and triple-buffered GPU memory isolation.
 */
public class MetalGpuCache {

    private static final int RING_SLOTS = 3;

    private final Map<String, Long> gpuMeshCache = new HashMap<>();
    private final Map<String, Long> gpuTextureCache = new HashMap<>();

    // Triple-buffered (Ring) Grid Mesh Buffers
    private final long[] gridVertexBufferRing = new long[RING_SLOTS];
    private final int[] gridVertexCapacityRing = new int[RING_SLOTS];
    private int gridRingIndex = 0;
    private int gridVertexCount = 0;

    // Triple-buffered (Ring) UI Overlay Mesh Buffers
    private final long[] uiVertexBufferRing = new long[RING_SLOTS];
    private final int[] uiVertexCapacityRing = new int[RING_SLOTS];
    private int uiRingIndex = 0;
    private int uiVertexCount = 0;

    private long viewportQuadBuffer = 0L;

    public long getGridVertexBuffer() {
        return gridVertexBufferRing[gridRingIndex];
    }

    public int getGridVertexCount() {
        return gridVertexCount;
    }

    public long getUiVertexBuffer() {
        return uiVertexBufferRing[uiRingIndex];
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
        if (vertices == null || count <= 0 || metalDevice == null) {
            this.gridVertexCount = 0;
            return;
        }

        long device = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (device == 0L || send == 0L) return;

        // Advance triple-buffer slot index to isolate in-flight GPU frames
        this.gridRingIndex = (this.gridRingIndex + 1) % RING_SLOTS;

        int requiredFloats = vertices.length;
        long currentBuffer = this.gridVertexBufferRing[this.gridRingIndex];
        int currentCapacity = this.gridVertexCapacityRing[this.gridRingIndex];

        // Allocate or reallocate persistent GPU buffer for this slot if needed
        if (currentBuffer == 0L || requiredFloats > currentCapacity) {
            if (currentBuffer != 0L) {
                invokePPP(currentBuffer, sel_registerName("release"), send);
            }
            int newCap = Math.max(requiredFloats, 2048 * 9);
            this.gridVertexCapacityRing[this.gridRingIndex] = newCap;
            long byteSize = (long) newCap * 4L;
            currentBuffer = MetalBuffer.createUniformBuffer(device, send, byteSize);
            this.gridVertexBufferRing[this.gridRingIndex] = currentBuffer;
        }

        // Copy vertex floats directly into current ring slot's persistent memory (data-race free)
        if (currentBuffer != 0L) {
            long pContents = invokePPP(currentBuffer, sel_registerName("contents"), send);
            if (pContents != 0L) {
                FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, vertices.length);
                fb.clear();
                fb.put(vertices);
            }
            this.gridVertexCount = count;
        }
    }

    public void updateUiMesh(MetalDevice metalDevice, float[] vertices, int count) {
        if (vertices == null || count <= 0 || metalDevice == null) {
            this.uiVertexCount = 0;
            return;
        }

        long device = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (device == 0L || send == 0L) return;

        // Advance triple-buffer slot index to isolate in-flight GPU frames
        this.uiRingIndex = (this.uiRingIndex + 1) % RING_SLOTS;

        int requiredFloats = vertices.length;
        long currentBuffer = this.uiVertexBufferRing[this.uiRingIndex];
        int currentCapacity = this.uiVertexCapacityRing[this.uiRingIndex];

        // Allocate or reallocate persistent GPU buffer for this slot if needed
        if (currentBuffer == 0L || requiredFloats > currentCapacity) {
            if (currentBuffer != 0L) {
                invokePPP(currentBuffer, sel_registerName("release"), send);
            }
            int newCap = Math.max(requiredFloats, 16384 * 6);
            this.uiVertexCapacityRing[this.uiRingIndex] = newCap;
            long byteSize = (long) newCap * 4L;
            currentBuffer = MetalBuffer.createUniformBuffer(device, send, byteSize);
            this.uiVertexBufferRing[this.uiRingIndex] = currentBuffer;
        }

        // Copy vertex floats directly into current ring slot's persistent memory (data-race free)
        if (currentBuffer != 0L) {
            long pContents = invokePPP(currentBuffer, sel_registerName("contents"), send);
            if (pContents != 0L) {
                FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, vertices.length);
                fb.clear();
                fb.put(vertices);
            }
            this.uiVertexCount = count;
        }
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
