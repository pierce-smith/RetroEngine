package com.retro.engine.backend.metal;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching VulkanGpuCache and EditorWorkspace):
 * Coordinates static VBO mesh buffers, viewport quad, ground quad VBOs, and delegates texture
 * management to MetalTextureRegistry and dynamic geometry buffering to MetalRingBuffer.
 */
public class MetalGpuCache {

    private static final int RING_SLOTS = 3;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 128;

    public static class DeferredHandle {
        public final long handle;
        public final long safeFrameIndex;

        public DeferredHandle(long handle, long safeFrameIndex) {
            this.handle = handle;
            this.safeFrameIndex = safeFrameIndex;
        }
    }

    private final Queue<DeferredHandle> deferredDisposalQueue = new ConcurrentLinkedQueue<>();
    private long currentFrameCounter = 0L;

    private final int maxMeshCacheSize;
    private final Map<String, Long> gpuMeshCache;

    // Subsystem Domain Managers
    private final MetalTextureRegistry textureRegistry;
    private final MetalRingBuffer gridRingBuffer = new MetalRingBuffer();
    private final MetalRingBuffer uiRingBuffer = new MetalRingBuffer();

    private long viewportQuadBuffer = 0L;
    private long groundQuadBuffer = 0L;

    public MetalGpuCache() {
        this(DEFAULT_MAX_CACHE_ENTRIES, DEFAULT_MAX_CACHE_ENTRIES);
    }

    public MetalGpuCache(int maxMeshCacheSize, int maxTextureCacheSize) {
        this.maxMeshCacheSize = Math.max(8, maxMeshCacheSize);
        this.textureRegistry = new MetalTextureRegistry(maxTextureCacheSize);

        this.gpuMeshCache = Collections.synchronizedMap(
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

    public void enqueueDeferredDisposal(long handle) {
        if (handle != 0L) {
            deferredDisposalQueue.add(new DeferredHandle(handle, currentFrameCounter + RING_SLOTS));
        }
    }

    public long getGridVertexBuffer() {
        return gridRingBuffer.getCurrentBufferHandle();
    }

    public int getGridVertexCount() {
        return gridRingBuffer.getVertexCount();
    }

    public long getUiVertexBuffer() {
        return uiRingBuffer.getCurrentBufferHandle();
    }

    public int getUiVertexCount() {
        return uiRingBuffer.getVertexCount();
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
            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,
            -1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 1.0f,  0.0f, 1.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,

            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,
             1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 0.0f,  1.0f, 0.0f
        };
        MetalBuffer groundBuf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), groundVerts, 14);
        this.groundQuadBuffer = groundBuf.getHandle();

        textureRegistry.initDefaultTextureAndSampler(metalDevice);
    }

    public void updateGridMesh(MetalDevice metalDevice, float[] vertices, int count) {
        gridRingBuffer.updateBuffer(metalDevice, vertices, count, 9, deferredDisposalQueue, currentFrameCounter);
    }

    public void updateUiMesh(MetalDevice metalDevice, float[] vertices, int count) {
        uiRingBuffer.updateBuffer(metalDevice, vertices, count, 6, deferredDisposalQueue, currentFrameCounter);
    }

    public long getOrCreateMeshBuffer(MetalDevice metalDevice, String meshPath, float[] vertices, int vertexCount) {
        if (meshPath == null || vertices == null || vertexCount <= 0) return 0L;

        Long existing = gpuMeshCache.get(meshPath);
        if (existing != null) return existing;

        evictLruMeshesIfNeeded(metalDevice);

        int stride = vertices.length / vertexCount;
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, stride);
        long handle = buf.getHandle();
        if (handle != 0L) {
            gpuMeshCache.put(meshPath, handle);
        }
        return handle;
    }

    public long getOrCreateTexture(MetalDevice metalDevice, String texturePath, byte[] pixels, int texW, int texH) {
        return textureRegistry.getOrCreateTexture(metalDevice, texturePath, pixels, texW, texH, deferredDisposalQueue, currentFrameCounter);
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

            textureRegistry.cleanup(metalDevice);
            gridRingBuffer.cleanup(metalDevice);
            uiRingBuffer.cleanup(metalDevice);
        } else {
            gpuMeshCache.clear();
        }
        System.out.println("[MetalGpuCache] VRAM mesh and texture caches released.");
    }
}
