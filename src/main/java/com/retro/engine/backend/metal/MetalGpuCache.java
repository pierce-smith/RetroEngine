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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Delegate managing GPU mesh VRAM buffer caches, texture handle caches, ring-buffered vertex mesh state,
 * 14-float ground quad buffer for dynamic entity shadow blob projections, and fence-guarded deferred resource disposal.
 */
public class MetalGpuCache {

    private static final int RING_SLOTS = 3;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 128;

    private static class DeferredHandle {
        final long handle;
        final long safeFrameIndex;

        DeferredHandle(long handle, long safeFrameIndex) {
            this.handle = handle;
            this.safeFrameIndex = safeFrameIndex;
        }
    }

    private final Queue<DeferredHandle> deferredDisposalQueue = new ConcurrentLinkedQueue<>();
    private long currentFrameCounter = 0L;

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
    private long groundQuadBuffer = 0L;

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

    public void beginFrame(MetalDevice metalDevice, long frameIndex) {
        this.currentFrameCounter = frameIndex;
        processDeferredDisposals(metalDevice);
    }

    public void processDeferredDisposals(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getObjcMsgSend() == 0L) return;
        long send = metalDevice.getObjcMsgSend();
        long selRelease = sel_registerName("release");

        Iterator<DeferredHandle> it = deferredDisposalQueue.iterator();
        while (it.hasNext()) {
            DeferredHandle item = it.next();
            if (currentFrameCounter >= item.safeFrameIndex) {
                if (item.handle != 0L) {
                    invokePPP(item.handle, selRelease, send);
                }
                it.remove();
            }
        }
    }

    private void enqueueDeferredDisposal(long handle) {
        if (handle != 0L) {
            deferredDisposalQueue.add(new DeferredHandle(handle, currentFrameCounter + RING_SLOTS));
        }
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

    public long getGroundQuadBuffer() {
        return groundQuadBuffer;
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

        // 14-float stride ground quad for dynamic entity shadow blob projections
        float[] groundVerts = new float[] {
            // X, Y, Z,  NX, NY, NZ,  R, G, B, A,  U1, V1,  U2, V2
            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,
            -1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 1.0f,  0.0f, 1.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,

            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,
             1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 0.0f,  1.0f, 0.0f
        };
        MetalBuffer groundBuf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), groundVerts, 14);
        this.groundQuadBuffer = groundBuf.getHandle();
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
                enqueueDeferredDisposal(currentBuffer);
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
                enqueueDeferredDisposal(currentBuffer);
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

        int stride = (vertices.length % 22 == 0) ? 22 : ((vertices.length % 14 == 0) ? 14 : 9);
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

                if (handle != null && handle != 0L) {
                    enqueueDeferredDisposal(handle);
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

                if (handle != null && handle != 0L) {
                    enqueueDeferredDisposal(handle);
                }
            }
        }
    }

    
    public void clearCaches(MetalDevice metalDevice) {
        if (metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
            long send = metalDevice.getObjcMsgSend();
            long selRelease = sel_registerName("release");

            synchronized (gpuMeshCache) {
                for (Long handle : gpuMeshCache.values()) {
                    if (handle != null && handle != 0L) {
                        invokePPP(handle, selRelease, send);
                    }
                }
                gpuMeshCache.clear();
            }

            synchronized (gpuTextureCache) {
                for (Long handle : gpuTextureCache.values()) {
                    if (handle != null && handle != 0L) {
                        invokePPP(handle, selRelease, send);
                    }
                }
                gpuTextureCache.clear();
            }
        } else {
            gpuMeshCache.clear();
            gpuTextureCache.clear();
        }
        System.out.println("[MetalGpuCache] VRAM mesh and texture caches released.");
    }

}
