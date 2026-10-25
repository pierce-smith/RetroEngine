package com.retro.engine.backend.metal;

import com.retro.engine.rhi.HandleRegistry;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching VulkanGpuCache and EditorWorkspace):
 * Coordinates static VBO mesh buffers, viewport quad, ground quad VBOs, and delegates texture
 * management to MetalTextureRegistry and dynamic geometry buffering to MetalRingBuffer.
 * Registered meshes and textures live in opaque-handle tables; unregistered resources are released
 * through the deferred disposal queue once no in-flight frame can still reference them.
 */
public class MetalGpuCache {

    private static final int RING_SLOTS = 3;

    public static class DeferredHandle {
        public final long handle;
        public final long safeFrameIndex;

        public DeferredHandle(long handle, long safeFrameIndex) {
            this.handle = handle;
            this.safeFrameIndex = safeFrameIndex;
        }
    }

    private final Queue<DeferredHandle> deferredDisposalQueue = new ConcurrentLinkedQueue<>();
    private volatile long currentFrameCounter = 0L;

    // Opaque handle tables for registered assets; written from any thread, resolved lock-free per frame
    private final HandleRegistry<MetalBuffer> meshHandles = new HandleRegistry<>();
    private final HandleRegistry<MetalTexture> textureHandles = new HandleRegistry<>();

    // Subsystem Domain Managers
    private final MetalTextureRegistry textureRegistry = new MetalTextureRegistry();
    private final MetalRingBuffer gridRingBuffer = new MetalRingBuffer();
    private final MetalRingBuffer uiRingBuffer = new MetalRingBuffer();

    private long viewportQuadBuffer = 0L;
    private float quadMinX = -0.55f, quadMinY = -0.50f, quadMaxX = 0.55f, quadMaxY = 0.88f;
    private boolean viewportQuadVisible = true;
    private long groundQuadBuffer = 0L;

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

    public boolean isViewportQuadVisible() {
        return viewportQuadVisible;
    }

    private static float[] buildViewportQuadVerts(float minX, float minY, float maxX, float maxY) {
        return new float[] {
            minX, maxY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
            minX, minY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f,
            maxX, minY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,

            minX, maxY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
            maxX, minY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
            maxX, maxY, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f
        };
    }

    /**
     * Re-targets the scene composite quad onto the docked Scene tile. The quad is only rebuilt when
     * the rect changes; the previous buffer is released once in-flight frames no longer reference it.
     */
    public void updateViewportQuad(MetalDevice metalDevice, float minX, float minY, float maxX, float maxY) {
        viewportQuadVisible = (maxX - minX) > 0.0001f && (maxY - minY) > 0.0001f;
        if (!viewportQuadVisible || metalDevice == null) return;
        if (viewportQuadBuffer != 0L && minX == quadMinX && minY == quadMinY && maxX == quadMaxX && maxY == quadMaxY) return;

        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(),
                buildViewportQuadVerts(minX, minY, maxX, maxY), 9);
        if (buf.getHandle() == 0L) return;
        enqueueDeferredDisposal(viewportQuadBuffer);
        this.viewportQuadBuffer = buf.getHandle();
        quadMinX = minX; quadMinY = minY; quadMaxX = maxX; quadMaxY = maxY;
    }

    public void initViewportQuad(MetalDevice metalDevice) {
        if (metalDevice == null) return;
        float[] quadVerts = buildViewportQuadVerts(quadMinX, quadMinY, quadMaxX, quadMaxY);
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

    /** Uploads a static VBO once (or returns the live handle for assetKey). Thread-safe. */
    public long registerMesh(MetalDevice metalDevice, String assetKey, float[] vertices, int vertexCount) {
        if (metalDevice == null || vertices == null || vertexCount <= 0 || vertices.length < vertexCount) return HandleRegistry.NULL_HANDLE;
        return meshHandles.register(assetKey, () -> {
            MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, vertices.length / vertexCount);
            return buf.getHandle() != 0L ? buf : null;
        });
    }

    /** Uploads RGBA8 pixels into an MTLTexture once (or returns the live handle for assetKey). Thread-safe. */
    public long registerTexture(MetalDevice metalDevice, String assetKey, byte[] pixels, int width, int height) {
        if (metalDevice == null || pixels == null || width <= 0 || height <= 0 || pixels.length < width * height * 4) return HandleRegistry.NULL_HANDLE;
        return textureHandles.register(assetKey, () -> {
            // Registration may run on a streaming thread with no autorelease pool; drain the descriptor locally
            long pool = pushAutoreleasePool(metalDevice);
            try {
                MetalTexture tex = new MetalTexture();
                tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, width, height);
                return tex.getHasActiveTexture() == 1 ? tex : null;
            } finally {
                drainAutoreleasePool(metalDevice, pool);
            }
        });
    }

    public void unregisterMesh(long meshHandle) {
        MetalBuffer buf = meshHandles.remove(meshHandle);
        if (buf != null) enqueueHandleDisposal(buf.getHandle());
    }

    public void unregisterTexture(long textureHandle) {
        MetalTexture tex = textureHandles.remove(textureHandle);
        if (tex != null) enqueueHandleDisposal(tex.getBindableTexture());
    }

    /** Allocation-free handle lookups; null for 0L, stale, or unregistered handles. */
    public MetalBuffer getMesh(long meshHandle) { return meshHandles.get(meshHandle); }

    public MetalTexture getTexture(long textureHandle) { return textureHandles.get(textureHandle); }

    /**
     * Unregistration can race with the submitEntity that resolves the handle for the next frame, so the
     * release waits one extra frame beyond the ring depth compared to buffers retired by the render thread.
     */
    private void enqueueHandleDisposal(long handle) {
        if (handle != 0L) {
            deferredDisposalQueue.add(new DeferredHandle(handle, currentFrameCounter + RING_SLOTS + 1));
        }
    }

    private static long pushAutoreleasePool(MetalDevice metalDevice) {
        long send = metalDevice.getObjcMsgSend();
        long poolClass = objc_getClass("NSAutoreleasePool");
        if (send == 0L || poolClass == 0L) return 0L;
        long alloc = invokePPP(poolClass, sel_registerName("alloc"), send);
        return invokePPP(alloc, sel_registerName("init"), send);
    }

    private static void drainAutoreleasePool(MetalDevice metalDevice, long pool) {
        if (pool != 0L) invokePPP(pool, sel_registerName("drain"), metalDevice.getObjcMsgSend());
    }

    public void clearCaches(MetalDevice metalDevice) {
        if (metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
            long send = metalDevice.getObjcMsgSend();
            long selRelease = sel_registerName("release");

            meshHandles.clear(buf -> invokePPP(buf.getHandle(), selRelease, send));
            textureHandles.clear(tex -> invokePPP(tex.getBindableTexture(), selRelease, send));

            textureRegistry.cleanup(metalDevice);
            gridRingBuffer.cleanup(metalDevice);
            uiRingBuffer.cleanup(metalDevice);
        } else {
            meshHandles.clear(null);
            textureHandles.clear(null);
        }
        System.out.println("[MetalGpuCache] VRAM mesh and texture caches released.");
    }
}
