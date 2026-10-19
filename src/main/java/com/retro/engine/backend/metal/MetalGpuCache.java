package com.retro.engine.backend.metal;

import com.retro.engine.backend.metal.MetalBuffer;
import com.retro.engine.backend.metal.MetalDevice;
import com.retro.engine.backend.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Delegate managing GPU mesh VRAM buffer caches, texture handle caches, and ring-buffered vertex mesh state
 * (grid, UI overlays, viewport quad) with zero per-frame native GPU allocations and triple-buffered GPU memory isolation.
 */
public class MetalGpuCache {

    private static final int RING_SLOTS = 3;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 128;

    private final int maxMeshCacheSize;
    private final int maxTextureCacheSize;

    private final Map<String, Long> gpuMeshCache;
    private final Map<String, Long> gpuTextureCache;

    private final long[] gridVertexBufferRing = new long[RING_SLOTS];
    private final int[] gridVertexCapacityRing = new int[RING_SLOTS];
    private int gridRingIndex = 0;
    private int gridVertexCount = 0;

    private final long[] uiVertexBufferRing = new long[RING_SLOTS];
    private final int[] uiVertexCapacityRing = new int[RING_SLOTS];
    private int uiRingIndex = 0;
    private int uiVertexCount = 0;

    private long viewportQuadBuffer = 0L;

    public MetalGpuCache() {
        this(DEFAULT_MAX_CACHE_ENTRIES, DEFAULT_MAX_CACHE_ENTRIES);
    }

    public MetalGpuCache(int maxMeshCacheSize, int maxTextureCacheSize) {
        this.maxMeshCacheSize = Math.max(8, maxMeshCacheSize);
        this.maxTextureCacheSize = Math.max(8, maxTextureCacheSize);

        this.gpuMeshCache = Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(16, 0.75f, true)
        );
        this.gpuTextureCache = Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(16, 0.75f, true)
        );
    }

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

        this.gridRingIndex = (this.gridRingIndex + 1) % RING_SLOTS;

        int requiredFloats = vertices.length;
        long currentBuffer = this.gridVertexBufferRing[this.gridRingIndex];
        int currentCapacity = this.gridVertexCapacityRing[this.gridRingIndex];

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

        this.uiRingIndex = (this.uiRingIndex + 1) % RING_SLOTS;

        int requiredFloats = vertices.length;
        long currentBuffer = this.uiVertexBufferRing[this.uiRingIndex];
        int currentCapacity = this.uiVertexCapacityRing[this.uiRingIndex];

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

        Long existing = gpuMeshCache.get(meshPath);
        if (existing != null) return existing;

        evictLruMeshesIfNeeded(metalDevice);

        int stride = (vertices.length % 14 == 0) ? 14 : 9;
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, stride);
        long handle = buf.getHandle();
        if (handle != 0L) {
            gpuMeshCache.put(meshPath, handle);
        }
        return handle;
    }

    public long getOrCreateTexture(MetalDevice metalDevice, String texturePath, byte[] pixels, int texW, int texH) {
        if (texturePath == null || pixels == null || pixels.length == 0) return 0L;

        Long existing = gpuTextureCache.get(texturePath);
        if (existing != null) return existing;

        evictLruTexturesIfNeeded(metalDevice);

        MetalTexture tex = new MetalTexture();
        tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, texW, texH);
        long handle = tex.getBindableTexture();
        if (handle != 0L) {
            gpuTextureCache.put(texturePath, handle);
        }
        return handle;
    }

    private void evictLruMeshesIfNeeded(MetalDevice metalDevice) {
        synchronized (gpuMeshCache) {
            while (gpuMeshCache.size() >= maxMeshCacheSize) {
                Iterator<Map.Entry<String, Long>> it = gpuMeshCache.entrySet().iterator();
                if (!it.hasNext()) break;

                Map.Entry<String, Long> lru = it.next();
                Long handle = lru.getValue();
                it.remove();

                if (handle != null && handle != 0L && metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
                    invokePPP(handle, sel_registerName("release"), metalDevice.getObjcMsgSend());
                }
            }
        }
    }

    private void evictLruTexturesIfNeeded(MetalDevice metalDevice) {
        synchronized (gpuTextureCache) {
            while (gpuTextureCache.size() >= maxTextureCacheSize) {
                Iterator<Map.Entry<String, Long>> it = gpuTextureCache.entrySet().iterator();
                if (!it.hasNext()) break;

                Map.Entry<String, Long> lru = it.next();
                Long handle = lru.getValue();
                it.remove();

                if (handle != null && handle != 0L && metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
                    invokePPP(handle, sel_registerName("release"), metalDevice.getObjcMsgSend());
                }
            }
        }
    }

    public void clearCaches() {
        gpuMeshCache.clear();
        gpuTextureCache.clear();
    }
}
