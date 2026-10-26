package com.retro.engine.backend.metal;

import com.retro.engine.rhi.DirectionalShadowFrustum;
import com.retro.engine.rhi.HandleRegistry;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPP;
import static org.lwjgl.system.JNI.invokePPPPPP;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching VulkanGpuCache and EditorWorkspace):
 * Coordinates static VBO mesh buffers, viewport quad, ground quad VBOs, the directional shadow map, and delegates texture
 * management to MetalTextureRegistry and dynamic geometry buffering to MetalRingBuffer.
 * Registered meshes and textures live in opaque-handle tables; unregistered resources are released
 * through the deferred disposal queue once no in-flight frame can still reference them.
 * <p>
 * Deferred disposal is fenced on real GPU completion: MetalBackend reports every committed command buffer through
 * {@link #onFrameSubmitted()} and every completion handler through {@link #onFrameCompleted()}. A handle retired while
 * frame N may still be encoded is tagged N and released only once the completion count reaches N. With at most three
 * frames in flight, a handle waits for no more than the three frames that could reference it.
 */
public class MetalGpuCache {

    public static final long MTL_INDEX_TYPE_UINT16 = 0L;
    public static final long MTL_INDEX_TYPE_UINT32 = 1L;

    public static final int SHADOW_MAP_SIZE = DirectionalShadowFrustum.SHADOW_MAP_SIZE;
    private static final long MTL_PIXEL_FORMAT_DEPTH32_FLOAT = 252L;

    /** Registered mesh: the vertex MTLBuffer plus an optional index MTLBuffer (0L when non-indexed). */
    public static class MetalMesh extends MetalBuffer {
        public final long indexBufferHandle;
        public final int indexCount;
        public final long indexType;
        // Local-space bounding sphere radius around the mesh origin, used to fit the shadow frustum
        public float boundsRadius = 1.0f;
        // Vertex buffer gpuAddress, written into DrawArgs.vertices
        public long vertexAddress;

        public MetalMesh(MetalBuffer vertices, long indexBufferHandle, int indexCount, long indexType) {
            super(vertices.getHandle(), vertices.getElementCount(), vertices.getFloatStride());
            this.indexBufferHandle = indexBufferHandle;
            this.indexCount = indexCount;
            this.indexType = indexType;
        }

        public boolean isIndexed() { return indexBufferHandle != 0L && indexCount > 0; }
    }

    /** A retired MTLBuffer / MTLTexture released once {@code framesCompleted >= retireAfterFrame}. */
    private record DeferredHandle(long handle, long retireAfterFrame) {}

    private final Queue<DeferredHandle> deferredDisposalQueue = new ConcurrentLinkedQueue<>();
    // Written only by the render thread at commit time; read by streaming threads that unregister assets
    private volatile long framesSubmitted = 0L;
    // Advanced from Metal's completion handler thread; command buffers on one queue complete in commit order
    private final AtomicLong framesCompleted = new AtomicLong();

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
    private long groundQuadAddress = 0L;

    // Persistent 2048x2048 Depth32Float shadow map (RenderTarget | ShaderRead) and its comparison sampler
    private long shadowMapTexture = 0L;
    private long shadowSamplerState = 0L;

    public void beginFrame(MetalDevice metalDevice) {
        processDeferredDisposals(metalDevice);
    }

    /** Render thread: a frame's command buffer was committed. */
    public void onFrameSubmitted() {
        framesSubmitted++;
    }

    /** Metal completion handler thread: the oldest in-flight command buffer finished on the GPU. */
    public void onFrameCompleted() {
        framesCompleted.incrementAndGet();
    }

    public void processDeferredDisposals(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getObjcMsgSend() == 0L) return;
        long completed = framesCompleted.get();

        Iterator<DeferredHandle> it = deferredDisposalQueue.iterator();
        while (it.hasNext()) {
            DeferredHandle item = it.next();
            if (completed >= item.retireAfterFrame()) {
                metalDevice.release(item.handle());
                it.remove();
            }
        }
    }

    /**
     * Retires a handle the render thread stops referencing: the frame currently being encoded (or the next one, when
     * called between frames) is the last that can use it.
     */
    public void enqueueDeferredDisposal(long handle) {
        if (handle != 0L) {
            deferredDisposalQueue.add(new DeferredHandle(handle, framesSubmitted + 1));
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

    public long getGroundQuadAddress() {
        return groundQuadAddress;
    }

    public long getShadowMapTexture() { return shadowMapTexture; }
    public long getShadowSamplerState() { return shadowSamplerState; }
    public boolean hasShadowMap() { return shadowMapTexture != 0L && shadowSamplerState != 0L; }

    /** Allocates the directional shadow map depth texture and its LessEqual comparison sampler; call once at startup. */
    public void initShadowMap(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getDevice() == 0L || metalDevice.getObjcMsgSend() == 0L) return;
        long d = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        long selRelease = sel_registerName("release");

        long texDesc = invokePPP(objc_getClass("MTLTextureDescriptor"), sel_registerName("new"), send);
        invokePPPP(texDesc, sel_registerName("setTextureType:"), 2L, send);                         // MTLTextureType2D
        invokePPPP(texDesc, sel_registerName("setPixelFormat:"), MTL_PIXEL_FORMAT_DEPTH32_FLOAT, send);
        invokePPPP(texDesc, sel_registerName("setWidth:"), (long) SHADOW_MAP_SIZE, send);
        invokePPPP(texDesc, sel_registerName("setHeight:"), (long) SHADOW_MAP_SIZE, send);
        invokePPPP(texDesc, sel_registerName("setUsage:"), 5L, send);                               // RenderTarget (4) | ShaderRead (1)
        invokePPPP(texDesc, sel_registerName("setStorageMode:"), 2L, send);                         // Private
        shadowMapTexture = invokePPPP(d, sel_registerName("newTextureWithDescriptor:"), texDesc, send);
        invokePPP(texDesc, selRelease, send);

        // Outside the map the opaque white border reads as depth 1.0, so everything there stays lit
        long samplerDesc = invokePPP(objc_getClass("MTLSamplerDescriptor"), sel_registerName("new"), send);
        invokePPPP(samplerDesc, sel_registerName("setMinFilter:"), 1L, send);                       // Linear: hardware 2x2 compare filtering per tap
        invokePPPP(samplerDesc, sel_registerName("setMagFilter:"), 1L, send);
        invokePPPP(samplerDesc, sel_registerName("setSAddressMode:"), 5L, send);                    // ClampToBorderColor
        invokePPPP(samplerDesc, sel_registerName("setTAddressMode:"), 5L, send);
        invokePPPP(samplerDesc, sel_registerName("setBorderColor:"), 2L, send);                     // OpaqueWhite
        invokePPPP(samplerDesc, sel_registerName("setCompareFunction:"), 3L, send);                 // LessEqual
        shadowSamplerState = invokePPPP(d, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, send);
        invokePPP(samplerDesc, selRelease, send);

        if (!hasShadowMap()) {
            System.err.println("[MetalGpuCache] ERROR: Failed to allocate the " + SHADOW_MAP_SIZE + "x" + SHADOW_MAP_SIZE + " directional shadow map.");
        }
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
        this.groundQuadAddress = MetalBuffer.gpuAddress(groundQuadBuffer, metalDevice.getObjcMsgSend());

        textureRegistry.initDefaultTextureAndSampler(metalDevice);
    }

    public void updateGridMesh(MetalDevice metalDevice, float[] vertices, int count) {
        gridRingBuffer.updateBuffer(metalDevice, vertices, count, 9, this);
    }

    public void updateUiMesh(MetalDevice metalDevice, float[] vertices, int count) {
        uiRingBuffer.updateBuffer(metalDevice, vertices, count, 6, this);
    }

    /** Uploads a static VBO once (or returns the live handle for assetKey). Thread-safe. */
    public long registerMesh(MetalDevice metalDevice, String assetKey, float[] vertices, int vertexCount) {
        return registerMesh(metalDevice, assetKey, vertices, vertexCount, null, 0);
    }

    /** Uploads a static VBO plus an optional IBO once (or returns the live handle for assetKey). Thread-safe. */
    public long registerMesh(MetalDevice metalDevice, String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount) {
        if (metalDevice == null || vertices == null || vertexCount <= 0 || vertices.length < vertexCount) return HandleRegistry.NULL_HANDLE;
        return meshHandles.register(assetKey, () -> {
            MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, vertices.length / vertexCount);
            if (buf.getHandle() == 0L) return null;

            MetalMesh mesh = (indices == null || indexCount <= 0)
                    ? new MetalMesh(buf, 0L, 0, MTL_INDEX_TYPE_UINT32)
                    : createIndexedMesh(metalDevice, buf, indices, Math.min(indexCount, indices.length));
            if (mesh == null) {
                invokePPP(buf.getHandle(), sel_registerName("release"), metalDevice.getObjcMsgSend());
                return null;
            }
            mesh.boundsRadius = DirectionalShadowFrustum.computeLocalBoundsRadius(vertices, buf.getFloatStride(), vertexCount);
            mesh.vertexAddress = MetalBuffer.gpuAddress(buf.getHandle(), metalDevice.getObjcMsgSend());
            return mesh;
        });
    }

    /** Uploads indices as uint16 when every index fits, uint32 otherwise; null on invalid indices or upload failure. */
    private static MetalMesh createIndexedMesh(MetalDevice metalDevice, MetalBuffer vertexBuf, int[] indices, int indexCount) {
        int maxIndex = 0;
        for (int i = 0; i < indexCount; i++) {
            int idx = indices[i];
            if (idx < 0 || idx >= vertexBuf.getElementCount()) return null;
            if (idx > maxIndex) maxIndex = idx;
        }
        boolean use16 = maxIndex <= 0xFFFF;
        long byteLength = (long) indexCount * (use16 ? Short.BYTES : Integer.BYTES);

        ByteBuffer staging = MemoryUtil.memAlloc((int) byteLength);
        try {
            if (use16) {
                for (int i = 0; i < indexCount; i++) staging.putShort(i * Short.BYTES, (short) indices[i]);
            } else {
                staging.asIntBuffer().put(indices, 0, indexCount);
            }
            long handle = invokePPPPPP(metalDevice.getDevice(), sel_registerName("newBufferWithBytes:length:options:"),
                    MemoryUtil.memAddress(staging), byteLength, 0L, metalDevice.getObjcMsgSend());
            if (handle == 0L) return null;
            return new MetalMesh(vertexBuf, handle, indexCount, use16 ? MTL_INDEX_TYPE_UINT16 : MTL_INDEX_TYPE_UINT32);
        } finally {
            MemoryUtil.memFree(staging);
        }
    }

    /** Uploads RGBA8 pixels into an MTLTexture once (or returns the live handle for assetKey). Thread-safe. */
    public long registerTexture(MetalDevice metalDevice, String assetKey, byte[] pixels, int width, int height) {
        if (metalDevice == null || pixels == null || width <= 0 || height <= 0 || pixels.length < width * height * 4) return HandleRegistry.NULL_HANDLE;
        return textureHandles.register(assetKey, () -> {
            // Registration may run on a streaming thread with no autorelease pool; drain the descriptor locally
            long pool = metalDevice.pushAutoreleasePool();
            try {
                MetalTexture tex = new MetalTexture();
                tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, width, height);
                return tex.getHasActiveTexture() == 1 ? tex : null;
            } finally {
                metalDevice.drainAutoreleasePool(pool);
            }
        });
    }

    public void unregisterMesh(long meshHandle) {
        MetalBuffer buf = meshHandles.remove(meshHandle);
        if (buf == null) return;
        enqueueHandleDisposal(buf.getHandle());
        if (buf instanceof MetalMesh mesh) enqueueHandleDisposal(mesh.indexBufferHandle);
    }

    public void unregisterTexture(long textureHandle) {
        MetalTexture tex = textureHandles.remove(textureHandle);
        if (tex != null) enqueueHandleDisposal(tex.getBindableTexture());
    }

    /** Allocation-free handle lookups; null for 0L, stale, or unregistered handles. */
    public MetalBuffer getMesh(long meshHandle) { return meshHandles.get(meshHandle); }

    public MetalTexture getTexture(long textureHandle) { return textureHandles.get(textureHandle); }

    /**
     * Unregistration runs on streaming threads and can race with the submitEntity that resolves the handle for the
     * frame after the one being encoded, so the release also waits for that frame to complete.
     */
    private void enqueueHandleDisposal(long handle) {
        if (handle != 0L) {
            deferredDisposalQueue.add(new DeferredHandle(handle, framesSubmitted + 2));
        }
    }

    public void clearCaches(MetalDevice metalDevice) {
        if (metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
            long send = metalDevice.getObjcMsgSend();
            long selRelease = sel_registerName("release");

            meshHandles.clear(buf -> {
                invokePPP(buf.getHandle(), selRelease, send);
                if (buf instanceof MetalMesh mesh && mesh.indexBufferHandle != 0L) invokePPP(mesh.indexBufferHandle, selRelease, send);
            });
            textureHandles.clear(tex -> invokePPP(tex.getBindableTexture(), selRelease, send));

            textureRegistry.cleanup(metalDevice);
            if (shadowMapTexture != 0L) { invokePPP(shadowMapTexture, selRelease, send); shadowMapTexture = 0L; }
            if (shadowSamplerState != 0L) { invokePPP(shadowSamplerState, selRelease, send); shadowSamplerState = 0L; }
            gridRingBuffer.cleanup(metalDevice);
            uiRingBuffer.cleanup(metalDevice);

            // Callers drain in-flight frames first, so every retired handle is safe to release now
            for (DeferredHandle item; (item = deferredDisposalQueue.poll()) != null; ) {
                metalDevice.release(item.handle());
            }
        } else {
            meshHandles.clear(null);
            textureHandles.clear(null);
        }
        System.out.println("[MetalGpuCache] VRAM mesh and texture caches released.");
    }
}
