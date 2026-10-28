package com.retro.engine.backend.vulkan;

import com.retro.engine.rhi.DirectionalShadowFrustum;
import com.retro.engine.rhi.HandleRegistry;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching EditorWorkspace):
 * Coordinates static VBO mesh buffers, the Light UBO, ground shadow quad VBOs, the directional shadow map, bone
 * palettes and the bindless material descriptor set, and delegates texture creation to VulkanTextureRegistry,
 * staging uploads to VulkanUploadRing and dynamic geometry buffering to VulkanRingBuffer.
 * Registered meshes and textures are owned by opaque-handle tables; command recording resolves them by handle.
 *
 * <p>Bindless model: one persistent descriptor set (set 0) holds the light UBO, the shared material sampler, the
 * shadow map, the bone palette buffer and a partially bound table of every registered texture. Draws select their
 * material channels and bone palette through push constant slot indices, so the set is bound once per pass and
 * never rewritten per draw. Texture registrations (any thread) only queue table writes; the render thread applies
 * them in {@link #beginFrame} after the in-flight fence wait, so the set is never updated while the GPU reads it.
 */
public class VulkanGpuCache {

    // Deferred disposal latency in frames; ticks once per frame after the in-flight fence wait
    private static final int RING_SLOTS = 3;

    // Bone palettes: 64 column-major mat4 per skinned entity packed into one storage buffer at binding 3.
    // A single buffer suffices because VulkanBackend waits on the in-flight fence before rewriting it.
    public static final int MAX_PALETTE_BONES = 64;
    public static final long BONE_PALETTE_STRIDE = MAX_PALETTE_BONES * 64L;
    public static final int MAX_SKINNED_ENTITIES_PER_FRAME = 64;

    // Directional shadow map: persistent 2048x2048 depth target sampled through binding 2 with a comparison sampler
    public static final int SHADOW_MAP_SIZE = DirectionalShadowFrustum.SHADOW_MAP_SIZE;
    public static final int SHADOW_MAP_FORMAT = VK_FORMAT_D32_SFLOAT;

    // LightBuffer (binding 0) std140 layout: lightSpaceMatrix at byte 304, camera preview view-projection at 368 and
    // its world position at 432, 448 bytes total
    public static final int LIGHT_UBO_LIGHT_SPACE_FLOAT_INDEX = 76;
    public static final int LIGHT_UBO_PREVIEW_VIEW_PROJ_FLOAT_INDEX = 92;
    public static final int LIGHT_UBO_PREVIEW_VIEW_POS_FLOAT_INDEX = 108;
    public static final long LIGHT_UBO_SIZE = 448L;

    // Set 0 bindings shared by every 3D pipeline (see blinn_phong.vert/.frag, blinn_phong_skinned.vert, shadow_depth.frag)
    public static final int BINDING_LIGHT_UBO = 0;
    public static final int BINDING_MATERIAL_SAMPLER = 1;
    public static final int BINDING_SHADOW_MAP = 2;
    public static final int BINDING_BONE_PALETTES = 3;
    public static final int BINDING_TEXTURE_TABLE = 4;

    // Material channels, in push constant order: texIndices.xyzw then texIndices2.x
    public static final int CHANNEL_ALBEDO = 0;
    public static final int CHANNEL_NORMAL = 1;
    public static final int CHANNEL_SPECULAR = 2;
    public static final int CHANNEL_LIGHTMAP = 3;
    public static final int CHANNEL_EMISSIVE = 4;
    public static final int CHANNEL_COUNT = 5;

    /** Push constant slot value for an absent channel; the shaders substitute the channel's neutral value. */
    public static final int NO_TEXTURE = -1;
    /** Table slot permanently holding the 1x1 white texture; released slots are pointed back at it. */
    public static final int WHITE_TEXTURE_SLOT = 0;

    public static class VulkanBuffer {
        public long bufferHandle;
        public long memoryHandle;
        public int vertexCount;
        public int floatStride;
        // Optional index buffer; indexBufferHandle == 0L means the mesh is drawn non-indexed
        public long indexBufferHandle;
        public long indexMemoryHandle;
        public int indexCount;
        public int indexType = VK_INDEX_TYPE_UINT32;
        // Local-space bounding sphere radius around the mesh origin, used to fit the shadow frustum
        public float boundsRadius = 1.0f;

        public VulkanBuffer(long bufferHandle, long memoryHandle, int vertexCount) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.vertexCount = vertexCount;
        }
    }

    public static class VulkanTexture {
        public long imageHandle;
        public long memoryHandle;
        public long imageViewHandle;
        public long samplerHandle;
        /** Timeline value of VulkanUploadRing that signals when the texels have landed (0 = no pending upload). */
        public long uploadValue;
        /** Slot in the bindless texture table, or NO_TEXTURE when the table is full. */
        public int bindlessSlot = NO_TEXTURE;
        /** Set by the render thread once the table write for bindlessSlot has been applied. */
        public volatile boolean resident;

        public VulkanTexture(long imageHandle, long memoryHandle, long imageViewHandle, long samplerHandle) {
            this.imageHandle = imageHandle;
            this.memoryHandle = memoryHandle;
            this.imageViewHandle = imageViewHandle;
            this.samplerHandle = samplerHandle;
        }
    }

    public static class DeferredVulkanResource {
        public final long bufferHandle;
        public final long memoryHandle;
        public final long imageHandle;
        public final long imageViewHandle;
        public final long samplerHandle;
        public final int frameEnqueued;
        /** Upload timeline value that must have completed before the resource can be destroyed. */
        public final long uploadValue;
        /** Bindless table slot to recycle once the resource is gone, or NO_TEXTURE. */
        public final int bindlessSlot;

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, int frameEnqueued) {
            this(bufferHandle, memoryHandle, 0L, 0L, 0L, frameEnqueued, 0L, NO_TEXTURE);
        }

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, long imageHandle,
                                      long imageViewHandle, long samplerHandle, int frameEnqueued) {
            this(bufferHandle, memoryHandle, imageHandle, imageViewHandle, samplerHandle, frameEnqueued, 0L, NO_TEXTURE);
        }

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, long imageHandle, long imageViewHandle,
                                      long samplerHandle, int frameEnqueued, long uploadValue, int bindlessSlot) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.imageHandle = imageHandle;
            this.imageViewHandle = imageViewHandle;
            this.samplerHandle = samplerHandle;
            this.frameEnqueued = frameEnqueued;
            this.uploadValue = uploadValue;
            this.bindlessSlot = bindlessSlot;
        }
    }

    /** Texture table write queued by registration/disposal and applied on the render thread. */
    private record PendingTableWrite(int slot, long imageView, VulkanTexture texture, boolean releaseSlot) {}

    // Subsystem Domain Managers
    private final VulkanTextureRegistry textureRegistry = new VulkanTextureRegistry();
    private final VulkanUploadRing uploadRing = new VulkanUploadRing();
    private final VulkanRingBuffer uiRingBuffer = new VulkanRingBuffer();
    private final VulkanRingBuffer gridRingBuffer = new VulkanRingBuffer();

    // Opaque handle tables for registered assets; written from any thread, resolved lock-free during recording
    private final HandleRegistry<VulkanBuffer> meshHandles = new HandleRegistry<>();
    private final HandleRegistry<VulkanTexture> textureHandles = new HandleRegistry<>();
    private final Queue<DeferredVulkanResource> deferredDisposalQueue = new ArrayDeque<>();

    private VulkanBuffer lightUniformBuffer = null;
    private VulkanBuffer groundQuadBuffer = null;
    private VulkanBuffer bonePaletteBuffer = null;
    private long bonePaletteMapped = 0L;
    private VulkanBuffer dummyBonePaletteBuffer = null;

    private long shadowMapImage = 0L;
    private long shadowMapMemory = 0L;
    private long shadowMapImageView = 0L;
    private long shadowMapSampler = 0L;

    // Bindless descriptor state: a dedicated one-set pool that is never reset
    private long materialSampler = 0L;
    private long bindlessPool = 0L;
    private long bindlessSet = 0L;
    private int bindlessCapacity = 0;
    private final Queue<PendingTableWrite> pendingTableWrites = new ArrayDeque<>();
    // Render-thread scratch for flushTextureTableWrites, reused so idle frames stay allocation-free
    private final PendingTableWrite[] tableWriteBatch = new PendingTableWrite[64];
    // Slot allocator: recycled slots first, then never-used ones; guarded by slotLock
    private final Object slotLock = new Object();
    private int[] freeSlots = new int[64];
    private int freeSlotCount = 0;
    private int nextUnusedSlot = WHITE_TEXTURE_SLOT + 1;
    private boolean tableFullLogged = false;

    private volatile int totalFrameCount = 0;

    // Per-frame draw state built by prepareScene before recording; reused to keep frames allocation-free
    private int[] entityPaletteSlots = new int[256];
    private int[] entityTextureSlots = new int[256 * CHANNEL_COUNT];

    /** Texture handle of the editor UI's SDF font atlas, or 0L when the UI uses its bitmap font. */
    private volatile long uiFontTexture = 0L;

    public long getUiFontTexture() { return uiFontTexture; }
    public void setUiFontTexture(long textureHandle) { this.uiFontTexture = textureHandle; }

    public long getUiVertexBuffer() { return uiRingBuffer.getCurrentBufferHandle(); }
    public int getUiVertexCount() { return uiRingBuffer.getVertexCount(); }

    public long getGridVertexBuffer() { return gridRingBuffer.getCurrentBufferHandle(); }
    public int getGridVertexCount() { return gridRingBuffer.getVertexCount(); }

    public VulkanUploadRing getUploadRing() { return uploadRing; }

    public long getGroundQuadBuffer(VulkanDevice device) {
        if (groundQuadBuffer != null) return groundQuadBuffer.bufferHandle;
        if (device == null || device.getLogicalDevice() == null) return 0L;

        // 22-float stride (88 bytes) ground quad VBO for dynamic shadow blob projection
        float[] groundVerts = new float[] {
            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            -1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 1.0f,  0.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,

            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 0.0f,  1.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f
        };

        groundQuadBuffer = createHostVisibleBuffer(device, groundVerts, 22);
        return (groundQuadBuffer != null) ? groundQuadBuffer.bufferHandle : 0L;
    }

    public void updateUiMesh(VulkanDevice device, float[] vertices, int count) {
        uiRingBuffer.updateBuffer(device, vertices, count, 8, deferredDisposalQueue, totalFrameCount);
    }

    public void updateGridMesh(VulkanDevice device, float[] vertices, int count) {
        gridRingBuffer.updateBuffer(device, vertices, count, 9, deferredDisposalQueue, totalFrameCount);
    }

    /**
     * Render-thread frame prologue; must run after the in-flight fence wait and before command recording. Advances the
     * disposal clock, destroys resources no in-flight work can reference, and applies queued texture table writes,
     * so neither destruction nor descriptor updates ever land between recording and submission.
     */
    public void beginFrame(VulkanDevice device) {
        totalFrameCount++;
        processDeferredDisposals(device);
        flushTextureTableWrites(device);
    }

    private void processDeferredDisposals(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        synchronized (deferredDisposalQueue) {
            while (!deferredDisposalQueue.isEmpty()) {
                DeferredVulkanResource res = deferredDisposalQueue.peek();
                if (totalFrameCount - res.frameEnqueued < RING_SLOTS || !uploadRing.isComplete(res.uploadValue)) break;
                deferredDisposalQueue.poll();
                destroyDeferred(vkDevice, res);
                // The slot still names the destroyed view; repoint it at white before it can be handed out again
                if (res.bindlessSlot != NO_TEXTURE) queueTableWrite(new PendingTableWrite(res.bindlessSlot, 0L, null, true));
            }
        }
    }

    private static void destroyDeferred(VkDevice vkDevice, DeferredVulkanResource res) {
        if (res.imageViewHandle != 0L) vkDestroyImageView(vkDevice, res.imageViewHandle, null);
        if (res.samplerHandle != 0L) vkDestroySampler(vkDevice, res.samplerHandle, null);
        if (res.imageHandle != 0L) vkDestroyImage(vkDevice, res.imageHandle, null);
        if (res.bufferHandle != 0L) vkDestroyBuffer(vkDevice, res.bufferHandle, null);
        if (res.memoryHandle != 0L) vkFreeMemory(vkDevice, res.memoryHandle, null);
    }

    /** Uploads a static VBO once (or returns the live handle for assetKey). Thread-safe. */
    public long registerMesh(VulkanDevice device, String assetKey, float[] vertices, int vertexCount) {
        return registerMesh(device, assetKey, vertices, vertexCount, null, 0);
    }

    /** Uploads a static VBO plus an optional IBO once (or returns the live handle for assetKey). Thread-safe. */
    public long registerMesh(VulkanDevice device, String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount) {
        if (vertices == null || vertexCount <= 0 || vertices.length < vertexCount) return HandleRegistry.NULL_HANDLE;
        return meshHandles.register(assetKey, () -> {
            VulkanBuffer buf = createHostVisibleBuffer(device, vertices, vertices.length / vertexCount);
            if (buf != null) buf.boundsRadius = DirectionalShadowFrustum.computeLocalBoundsRadius(vertices, buf.floatStride, vertexCount);
            if (buf == null || indices == null || indexCount <= 0) return buf;
            if (!attachIndexBuffer(device, buf, indices, Math.min(indexCount, indices.length))) {
                vkDestroyBuffer(device.getLogicalDevice(), buf.bufferHandle, null);
                vkFreeMemory(device.getLogicalDevice(), buf.memoryHandle, null);
                return null;
            }
            return buf;
        });
    }

    /** Uploads indices as uint16 when every index fits, uint32 otherwise, and records them on the mesh. */
    private boolean attachIndexBuffer(VulkanDevice device, VulkanBuffer mesh, int[] indices, int indexCount) {
        int maxIndex = 0;
        for (int i = 0; i < indexCount; i++) {
            int idx = indices[i];
            if (idx < 0 || idx >= mesh.vertexCount) return false;
            if (idx > maxIndex) maxIndex = idx;
        }
        boolean use16 = maxIndex <= 0xFFFF;
        long bufferSize = (long) indexCount * (use16 ? Short.BYTES : Integer.BYTES);

        long[] handles = allocateHostVisibleBuffer(device, bufferSize, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        if (handles == null) return false;

        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, handles[1], 0, bufferSize, 0, pData);
            long dst = pData.get(0);
            if (use16) {
                for (int i = 0; i < indexCount; i++) MemoryUtil.memPutShort(dst + (long) i * Short.BYTES, (short) indices[i]);
            } else {
                MemoryUtil.memIntBuffer(dst, indexCount).put(indices, 0, indexCount);
            }
            vkUnmapMemory(vkDevice, handles[1]);
        }

        mesh.indexBufferHandle = handles[0];
        mesh.indexMemoryHandle = handles[1];
        mesh.indexCount = indexCount;
        mesh.indexType = use16 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
        return true;
    }

    /**
     * Creates a VkImage + VkImageView, queues its texel upload on the staging ring without waiting for it, and reserves
     * a bindless table slot (or returns the live handle for assetKey). Thread-safe. The texture becomes visible to
     * shaders on the first frame whose prologue applies its table write.
     */
    public long registerTexture(VulkanDevice device, String assetKey, byte[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height * 4) return HandleRegistry.NULL_HANDLE;
        return textureHandles.register(assetKey, () -> {
            VulkanTexture tex = textureRegistry.createTextureFromPixels(device, uploadRing, pixels, width, height);
            if (tex == null) return null;
            if (tex.imageViewHandle == 0L) {
                // The upload is already in flight, so the image must outlive it
                deferTextureDisposal(tex);
                return null;
            }
            tex.bindlessSlot = allocateTableSlot();
            if (tex.bindlessSlot != NO_TEXTURE) {
                queueTableWrite(new PendingTableWrite(tex.bindlessSlot, tex.imageViewHandle, tex, false));
            }
            return tex;
        });
    }

    public void unregisterMesh(long meshHandle) {
        VulkanBuffer buf = meshHandles.remove(meshHandle);
        if (buf == null) return;
        synchronized (deferredDisposalQueue) {
            deferredDisposalQueue.add(new DeferredVulkanResource(buf.bufferHandle, buf.memoryHandle, totalFrameCount));
            if (buf.indexBufferHandle != 0L || buf.indexMemoryHandle != 0L) {
                deferredDisposalQueue.add(new DeferredVulkanResource(buf.indexBufferHandle, buf.indexMemoryHandle, totalFrameCount));
            }
        }
    }

    public void unregisterTexture(long textureHandle) {
        VulkanTexture tex = textureHandles.remove(textureHandle);
        if (tex != null) deferTextureDisposal(tex);
    }

    private void deferTextureDisposal(VulkanTexture tex) {
        synchronized (deferredDisposalQueue) {
            deferredDisposalQueue.add(new DeferredVulkanResource(0L, tex.memoryHandle, tex.imageHandle, tex.imageViewHandle,
                    tex.samplerHandle, totalFrameCount, tex.uploadValue, tex.bindlessSlot));
        }
    }

    /** Allocation-free handle lookup for command recording; null for 0L, stale, or unregistered handles. */
    public VulkanBuffer getMesh(long meshHandle) { return meshHandles.get(meshHandle); }

    public VulkanTexture getTexture(long textureHandle) { return textureHandles.get(textureHandle); }

    /** Bindless slot for a texture handle, or NO_TEXTURE when the handle is 0L/stale or its table write is still pending. */
    public int resolveTextureSlot(long textureHandle) {
        if (textureHandle == 0L) return NO_TEXTURE;
        VulkanTexture tex = textureHandles.get(textureHandle);
        return (tex != null && tex.resident) ? tex.bindlessSlot : NO_TEXTURE;
    }

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        return textureRegistry.getDefaultWhiteTexture(device, uploadRing);
    }

    // ---------------------------------------------------------------------------------
    // Bindless texture table
    // ---------------------------------------------------------------------------------

    private int allocateTableSlot() {
        synchronized (slotLock) {
            if (freeSlotCount > 0) return freeSlots[--freeSlotCount];
            if (nextUnusedSlot < bindlessCapacity) return nextUnusedSlot++;
            if (!tableFullLogged) {
                tableFullLogged = true;
                System.err.println("[VulkanGpuCache] WARNING: Bindless texture table full (" + bindlessCapacity
                        + " slots); further textures render with their channel's fallback until slots are released.");
            }
            return NO_TEXTURE;
        }
    }

    private void releaseTableSlot(int slot) {
        synchronized (slotLock) {
            if (freeSlotCount == freeSlots.length) {
                int[] grown = new int[freeSlots.length * 2];
                System.arraycopy(freeSlots, 0, grown, 0, freeSlotCount);
                freeSlots = grown;
            }
            freeSlots[freeSlotCount++] = slot;
        }
    }

    private void queueTableWrite(PendingTableWrite write) {
        synchronized (pendingTableWrites) {
            pendingTableWrites.add(write);
        }
    }

    /**
     * Applies queued table writes in FIFO order (so a slot's release always lands before its reuse) in batches. Runs on
     * the render thread after the in-flight fence wait, when no pending GPU work references the set.
     */
    private void flushTextureTableWrites(VulkanDevice device) {
        if (bindlessSet == 0L || device == null || device.getLogicalDevice() == null) return;
        VulkanTexture white = textureRegistry.peekDefaultWhiteTexture();
        if (white == null || white.imageViewHandle == 0L) return;

        PendingTableWrite[] batch = tableWriteBatch;
        int batchSize = batch.length;
        while (true) {
            int count = 0;
            synchronized (pendingTableWrites) {
                while (count < batchSize && !pendingTableWrites.isEmpty()) batch[count++] = pendingTableWrites.poll();
            }
            if (count == 0) return;

            try (MemoryStack stack = stackPush()) {
                VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(count, stack);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
                for (int i = 0; i < count; i++) {
                    PendingTableWrite w = batch[i];
                    long view = (w.imageView() != 0L) ? w.imageView() : white.imageViewHandle;
                    imageInfos.get(i).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    writes.get(i)
                            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                            .dstSet(bindlessSet)
                            .dstBinding(BINDING_TEXTURE_TABLE)
                            .dstArrayElement(w.slot())
                            .descriptorCount(1)
                            .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(imageInfos.address(i), 1));
                }
                vkUpdateDescriptorSets(device.getLogicalDevice(), writes, null);
            }

            for (int i = 0; i < count; i++) {
                PendingTableWrite w = batch[i];
                if (w.texture() != null) w.texture().resident = true;
                if (w.releaseSlot()) releaseTableSlot(w.slot());
                batch[i] = null;
            }
        }
    }

    /**
     * Publishes a backend-owned image view (a render target) in the bindless texture table and returns its slot, or
     * NO_TEXTURE when the table is unavailable or full. The write lands at the next beginFrame; the owner must keep the
     * image in SHADER_READ_ONLY_OPTIMAL whenever a draw samples the slot.
     */
    public int registerRenderTargetView(long imageView) {
        if (imageView == 0L || bindlessSet == 0L) return NO_TEXTURE;
        int slot = allocateTableSlot();
        if (slot != NO_TEXTURE) queueTableWrite(new PendingTableWrite(slot, imageView, null, false));
        return slot;
    }

    /** The persistent bindless set 0 shared by every 3D pipeline; 0L if initialization failed. */
    public long getBindlessDescriptorSet() { return bindlessSet; }

    public VulkanBuffer createHostVisibleBuffer(VulkanDevice device, float[] vertices, int floatStride) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || vertices.length == 0 || floatStride <= 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = (long) vertices.length * Float.BYTES;

        long[] handles = allocateHostVisibleBuffer(device, bufferSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        if (handles == null) return null;
        long buffer = handles[0];
        long bufferMemory = handles[1];

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, bufferMemory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, vertices.length);
            floatBuf.put(vertices);
            vkUnmapMemory(vkDevice, bufferMemory);
        }

        VulkanBuffer created = new VulkanBuffer(buffer, bufferMemory, vertices.length / floatStride);
        created.floatStride = floatStride;
        return created;
    }

    /** Creates a host-visible, coherent buffer with bound memory; returns {buffer, memory} or null on failure. */
    private static long[] allocateHostVisibleBuffer(VulkanDevice device, long bufferSize, int usage) {
        if (device == null || device.getLogicalDevice() == null || bufferSize <= 0) return null;
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return null;
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pBufferMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pBufferMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, buffer, null);
                return null;
            }
            long bufferMemory = pBufferMemory.get(0);

            vkBindBufferMemory(vkDevice, buffer, bufferMemory, 0);
            return new long[] { buffer, bufferMemory };
        }
    }

    public VulkanBuffer getOrCreateBonePaletteBuffer(VulkanDevice device) {
        if (bonePaletteBuffer != null) return bonePaletteBuffer;
        long[] mapped = new long[1];
        bonePaletteBuffer = createIdentityPaletteBuffer(device, MAX_SKINNED_ENTITIES_PER_FRAME, mapped);
        bonePaletteMapped = mapped[0];
        return bonePaletteBuffer;
    }

    /**
     * Permanent one-slot identity palette bound to binding 3 whenever the real palette buffer is unavailable, so the
     * bindless set always has a valid storage buffer there (every draw then uses palette slot 0).
     */
    public VulkanBuffer getOrCreateDummyBonePaletteBuffer(VulkanDevice device) {
        if (dummyBonePaletteBuffer != null) return dummyBonePaletteBuffer;
        dummyBonePaletteBuffer = createIdentityPaletteBuffer(device, 1, null);
        return dummyBonePaletteBuffer;
    }

    /**
     * Creates every long-lived resource set 0 references and writes the bindless set. Call once after the logical
     * device and VulkanPipeline (which owns the set layout and sizes the texture table) exist.
     */
    public boolean initialize(VulkanDevice device, long setLayout, int textureTableCapacity) {
        if (device == null || device.getLogicalDevice() == null || setLayout == 0L || textureTableCapacity <= WHITE_TEXTURE_SLOT) return false;

        if (!uploadRing.initialize(device)) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to create the staging upload ring.");
            return false;
        }
        if (!createShadowMap(device)) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to allocate the " + SHADOW_MAP_SIZE + "x" + SHADOW_MAP_SIZE + " directional shadow map.");
            return false;
        }
        if (!createMaterialSampler(device)) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to create the shared material sampler.");
            return false;
        }

        VulkanTexture white = getDefaultWhiteTexture(device);
        VulkanBuffer lightUbo = getOrCreateLightUniformBuffer(device);
        VulkanBuffer palette = getOrCreateBonePaletteBuffer(device);
        if (palette == null) palette = getOrCreateDummyBonePaletteBuffer(device);
        if (white == null || white.imageViewHandle == 0L || lightUbo == null || palette == null) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to create resources required by the bindless descriptor set.");
            return false;
        }

        this.bindlessCapacity = textureTableCapacity;
        if (!createBindlessSet(device, setLayout, white, lightUbo, palette)) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to allocate the bindless descriptor set (" + textureTableCapacity + " texture slots).");
            return false;
        }
        white.bindlessSlot = WHITE_TEXTURE_SLOT;
        white.resident = true;
        System.out.println("[VulkanGpuCache] Bindless material table ready (" + textureTableCapacity + " texture slots).");
        return true;
    }

    private boolean createMaterialSampler(VulkanDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(device.getLogicalDevice(), samplerInfo, null, pSampler) != VK_SUCCESS) return false;
            materialSampler = pSampler.get(0);
            return true;
        }
    }

    /** Allocates set 0 from a dedicated pool and writes its static bindings plus the white texture in slot 0. */
    private boolean createBindlessSet(VulkanDevice device, long setLayout, VulkanTexture white, VulkanBuffer lightUbo, VulkanBuffer palette) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(1);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            poolSizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(4).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(bindlessCapacity);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(vkDevice, poolInfo, null, pPool) != VK_SUCCESS) return false;
            bindlessPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(bindlessPool)
                    .pSetLayouts(stack.longs(setLayout));

            LongBuffer pSet = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(vkDevice, allocInfo, pSet) != VK_SUCCESS) return false;
            bindlessSet = pSet.get(0);

            VkDescriptorBufferInfo.Buffer lightInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(lightUbo.bufferHandle).offset(0).range(LIGHT_UBO_SIZE);
            VkDescriptorImageInfo.Buffer samplerInfo = VkDescriptorImageInfo.calloc(1, stack).sampler(materialSampler);
            VkDescriptorImageInfo.Buffer shadowInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(shadowMapImageView).sampler(shadowMapSampler);
            VkDescriptorBufferInfo.Buffer paletteInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(palette.bufferHandle).offset(0).range(VK_WHOLE_SIZE);
            VkDescriptorImageInfo.Buffer whiteInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(white.imageViewHandle);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(bindlessSet).dstBinding(BINDING_LIGHT_UBO).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(lightInfo);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(bindlessSet).dstBinding(BINDING_MATERIAL_SAMPLER).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).pImageInfo(samplerInfo);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(bindlessSet).dstBinding(BINDING_SHADOW_MAP).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(shadowInfo);
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(bindlessSet).dstBinding(BINDING_BONE_PALETTES).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(paletteInfo);
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(bindlessSet).dstBinding(BINDING_TEXTURE_TABLE).dstArrayElement(WHITE_TEXTURE_SLOT).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).pImageInfo(whiteInfo);

            vkUpdateDescriptorSets(vkDevice, writes, null);
            return true;
        }
    }

    /** Device-local D32 depth image + view + comparison sampler for the directional shadow map. */
    private boolean createShadowMap(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return false;
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(SHADOW_MAP_FORMAT)
                    .extent(e -> e.width(SHADOW_MAP_SIZE).height(SHADOW_MAP_SIZE).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) return false;
            shadowMapImage = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, shadowMapImage, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) {
                destroyShadowMap(vkDevice);
                return false;
            }
            shadowMapMemory = pMemory.get(0);
            vkBindImageMemory(vkDevice, shadowMapImage, shadowMapMemory, 0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(shadowMapImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(SHADOW_MAP_FORMAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pView) != VK_SUCCESS) {
                destroyShadowMap(vkDevice);
                return false;
            }
            shadowMapImageView = pView.get(0);

            // Hardware 2x2 comparison filtering under each PCF tap when the format supports linear filtering
            VkFormatProperties formatProps = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), SHADOW_MAP_FORMAT, formatProps);
            int filter = (formatProps.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;

            // Outside the map the opaque white border reads as depth 1.0, so everything there stays lit
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .compareEnable(true)
                    .compareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                    .minLod(0.0f)
                    .maxLod(0.0f)
                    .maxAnisotropy(1.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(vkDevice, samplerInfo, null, pSampler) != VK_SUCCESS) {
                destroyShadowMap(vkDevice);
                return false;
            }
            shadowMapSampler = pSampler.get(0);
            return true;
        }
    }

    private void destroyShadowMap(VkDevice vkDevice) {
        if (shadowMapSampler != 0L) { vkDestroySampler(vkDevice, shadowMapSampler, null); shadowMapSampler = 0L; }
        if (shadowMapImageView != 0L) { vkDestroyImageView(vkDevice, shadowMapImageView, null); shadowMapImageView = 0L; }
        if (shadowMapImage != 0L) { vkDestroyImage(vkDevice, shadowMapImage, null); shadowMapImage = 0L; }
        if (shadowMapMemory != 0L) { vkFreeMemory(vkDevice, shadowMapMemory, null); shadowMapMemory = 0L; }
    }

    public long getShadowMapImage() { return shadowMapImage; }
    public long getShadowMapImageView() { return shadowMapImageView; }
    public boolean hasShadowMap() { return shadowMapImageView != 0L && shadowMapSampler != 0L; }

    /**
     * Host-visible storage buffer of {@code slots} identity-seeded palettes. When {@code mappedOut} is non-null the
     * memory stays persistently mapped and its address is returned there; otherwise it is unmapped after seeding.
     */
    private VulkanBuffer createIdentityPaletteBuffer(VulkanDevice device, int slots, long[] mappedOut) {
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = BONE_PALETTE_STRIDE * slots;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return null;
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, buffer, null);
                return null;
            }
            long memory = pMemory.get(0);
            vkBindBufferMemory(vkDevice, buffer, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, memory, 0, bufferSize, 0, pData) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, buffer, null);
                vkFreeMemory(vkDevice, memory, null);
                return null;
            }
            long mapped = pData.get(0);

            // Seed every slot with identity matrices so an unwritten slot never skins with garbage
            FloatBuffer fb = MemoryUtil.memFloatBuffer(mapped, (int) (bufferSize / Float.BYTES));
            for (int m = 0; m < MAX_PALETTE_BONES * slots; m++) {
                for (int i = 0; i < 16; i++) fb.put((i % 5 == 0) ? 1.0f : 0.0f);
            }

            // Persistently mapped palettes are rewritten every frame after the in-flight fence wait
            if (mappedOut != null) mappedOut[0] = mapped;
            else vkUnmapMemory(vkDevice, memory);

            return new VulkanBuffer(buffer, memory, 0);
        }
    }

    /** Copies up to 64 column-major 4x4 matrices into the given skinned-entity slot of the mapped palette buffer. */
    public void writeBonePalette(int skinnedIndex, float[][] palette) {
        if (bonePaletteMapped == 0L || palette == null) return;
        FloatBuffer fb = MemoryUtil.memFloatBuffer(bonePaletteMapped + skinnedIndex * BONE_PALETTE_STRIDE, MAX_PALETTE_BONES * 16);
        int count = Math.min(palette.length, MAX_PALETTE_BONES);
        for (int b = 0; b < count; b++) {
            fb.put(palette[b], 0, 16);
        }
    }

    /**
     * Resolves every queued entity's material channel slots and bone palette slot, writing the palettes, before command
     * recording begins. Nothing here touches descriptors: draws hand these slots to the shaders as push constants.
     * Must run after {@link #beginFrame} so freshly flushed textures are already resident.
     */
    public void prepareScene(VulkanDevice device, List<VulkanPassEncoder.RenderEntity> sceneQueue, int sceneQueueCount) {
        if (entityPaletteSlots.length < sceneQueueCount) {
            int cap = Math.max(sceneQueueCount, entityPaletteSlots.length * 2);
            entityPaletteSlots = new int[cap];
            entityTextureSlots = new int[cap * CHANNEL_COUNT];
        }

        VulkanBuffer bonePaletteBuf = getOrCreateBonePaletteBuffer(device);
        int skinnedIndex = 0;
        for (int i = 0; i < sceneQueueCount; i++) {
            VulkanPassEncoder.RenderEntity entity = sceneQueue.get(i);
            entityPaletteSlots[i] = 0;

            int base = i * CHANNEL_COUNT;
            entityTextureSlots[base + CHANNEL_ALBEDO] = resolveTextureSlot(entity.textureHandle);
            entityTextureSlots[base + CHANNEL_NORMAL] = resolveTextureSlot(entity.normalTextureHandle);
            entityTextureSlots[base + CHANNEL_SPECULAR] = resolveTextureSlot(entity.specularTextureHandle);
            entityTextureSlots[base + CHANNEL_LIGHTMAP] = resolveTextureSlot(entity.lightmapTextureHandle);
            entityTextureSlots[base + CHANNEL_EMISSIVE] = resolveTextureSlot(entity.emissiveTextureHandle);

            VulkanBuffer mesh = getMesh(entity.meshHandle);
            if (mesh == null || mesh.bufferHandle == 0L || mesh.vertexCount <= 0) continue;

            // slot 0 stays identity for palette-less skinned meshes
            if (mesh.floatStride >= 30 && bonePaletteBuf != null && entity.bonePalette != null
                    && skinnedIndex < MAX_SKINNED_ENTITIES_PER_FRAME - 1) {
                int paletteSlot = ++skinnedIndex;
                writeBonePalette(paletteSlot, entity.bonePalette);
                entityPaletteSlots[i] = paletteSlot;
            }
        }
    }

    /** Bone palette slot (index into binding 3) for the given scene-queue index this frame. */
    public int getEntityPaletteSlot(int index) { return index < entityPaletteSlots.length ? entityPaletteSlots[index] : 0; }

    /** Bindless slot of one material channel for the given scene-queue index, or NO_TEXTURE when absent. */
    public int getEntityTextureSlot(int index, int channel) {
        int i = index * CHANNEL_COUNT + channel;
        return i < entityTextureSlots.length ? entityTextureSlots[i] : NO_TEXTURE;
    }

    public VulkanBuffer getOrCreateLightUniformBuffer(VulkanDevice device) {
        if (lightUniformBuffer != null) return lightUniformBuffer;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = 512L;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return null;
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, buffer, null);
                return null;
            }
            long memory = pMemory.get(0);

            vkBindBufferMemory(vkDevice, buffer, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, memory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, 128);
            floatBuf.put(new float[]{-0.5f, -1.0f, -0.5f, 0.0f, 1.0f, 0.95f, 0.85f, 1.2f});
            vkUnmapMemory(vkDevice, memory);

            lightUniformBuffer = new VulkanBuffer(buffer, memory, 0);
            return lightUniformBuffer;
        }
    }

    public void updateLightUniformBuffer(VulkanDevice device, int lightType, float posX, float posY, float posZ,
                                        float dirX, float dirY, float dirZ,
                                        float r, float g, float b, float intensity,
                                        float camX, float camY, float camZ, float groundY,
                                        float[] viewMatrix, float[] projMatrix, float[] lightSpaceMatrix,
                                        float[] previewViewProj, float previewX, float previewY, float previewZ) {
        VulkanBuffer buf = getOrCreateLightUniformBuffer(device);
        if (buf == null || device == null || device.getLogicalDevice() == null) return;

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, buf.memoryHandle, 0, 512L, 0, pData) == VK_SUCCESS) {
                FloatBuffer floatBuf = pData.getFloatBuffer(0, 128);

                float numDir = (lightType == 0) ? 1.0f : 0.0f;
                float numPoint = (lightType == 1) ? 1.0f : 0.0f;
                float numSpot = (lightType == 2) ? 1.0f : 0.0f;

                floatBuf.put(0, dirX); floatBuf.put(1, dirY); floatBuf.put(2, dirZ); floatBuf.put(3, 0.0f);
                floatBuf.put(4, r);    floatBuf.put(5, g);    floatBuf.put(6, b);    floatBuf.put(7, intensity);

                floatBuf.put(8, posX); floatBuf.put(9, posY); floatBuf.put(10, posZ); floatBuf.put(11, 0.0f);
                floatBuf.put(12, r);   floatBuf.put(13, g);   floatBuf.put(14, b);    floatBuf.put(15, intensity);
                floatBuf.put(16, 1.0f);floatBuf.put(17, 0.09f);floatBuf.put(18, 0.032f);floatBuf.put(19, 0.0f);

                floatBuf.put(20, posX); floatBuf.put(21, posY); floatBuf.put(22, posZ); floatBuf.put(23, 0.93f);
                floatBuf.put(24, dirX); floatBuf.put(25, dirY); floatBuf.put(26, dirZ); floatBuf.put(27, 0.82f);
                floatBuf.put(28, r);    floatBuf.put(29, g);    floatBuf.put(30, b);    floatBuf.put(31, intensity);
                floatBuf.put(32, 1.0f); floatBuf.put(33, 0.09f);floatBuf.put(34, 0.032f);floatBuf.put(35, 0.0f);

                floatBuf.put(36, numDir); floatBuf.put(37, numPoint); floatBuf.put(38, numSpot); floatBuf.put(39, DirectionalShadowFrustum.SHADOW_BIAS);

                floatBuf.put(40, camX); floatBuf.put(41, camY); floatBuf.put(42, camZ); floatBuf.put(43, groundY);

                // Camera matrices live here (byte offset 176) rather than in push constants, which carry material slots
                putMatrixOrIdentity(floatBuf, 44, viewMatrix);
                putMatrixOrIdentity(floatBuf, 60, projMatrix);
                putMatrixOrIdentity(floatBuf, LIGHT_UBO_LIGHT_SPACE_FLOAT_INDEX, lightSpaceMatrix);
                putMatrixOrIdentity(floatBuf, LIGHT_UBO_PREVIEW_VIEW_PROJ_FLOAT_INDEX, previewViewProj);
                int pos = LIGHT_UBO_PREVIEW_VIEW_POS_FLOAT_INDEX;
                floatBuf.put(pos, previewX); floatBuf.put(pos + 1, previewY); floatBuf.put(pos + 2, previewZ); floatBuf.put(pos + 3, 0.0f);

                vkUnmapMemory(vkDevice, buf.memoryHandle);
            }
        }
    }

    private static void putMatrixOrIdentity(FloatBuffer dst, int index, float[] matrix) {
        boolean valid = matrix != null && matrix.length == 16;
        for (int i = 0; i < 16; i++) dst.put(index + i, valid ? matrix[i] : ((i % 5 == 0) ? 1.0f : 0.0f));
    }

    public static int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        // Never fall back to type 0: on discrete GPUs it is DEVICE_LOCAL-only, so mapping it as a staging buffer crashes
        throw new IllegalStateException("[VulkanGpuCache] No memory type matches filter 0x" + Integer.toHexString(typeFilter)
                + " with properties 0x" + Integer.toHexString(properties));
    }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        // Waits for outstanding uploads before anything they write is destroyed
        uploadRing.cleanup(vkDevice);

        if (bindlessPool != 0L) {
            vkDestroyDescriptorPool(vkDevice, bindlessPool, null);
            bindlessPool = 0L;
            bindlessSet = 0L;
        }
        if (materialSampler != 0L) {
            vkDestroySampler(vkDevice, materialSampler, null);
            materialSampler = 0L;
        }

        textureRegistry.cleanup(vkDevice);
        destroyShadowMap(vkDevice);
        uiRingBuffer.cleanup(vkDevice);
        gridRingBuffer.cleanup(vkDevice);

        meshHandles.clear(buf -> {
            if (buf.bufferHandle != 0L) vkDestroyBuffer(vkDevice, buf.bufferHandle, null);
            if (buf.memoryHandle != 0L) vkFreeMemory(vkDevice, buf.memoryHandle, null);
            if (buf.indexBufferHandle != 0L) vkDestroyBuffer(vkDevice, buf.indexBufferHandle, null);
            if (buf.indexMemoryHandle != 0L) vkFreeMemory(vkDevice, buf.indexMemoryHandle, null);
        });
        textureHandles.clear(tex -> {
            if (tex.imageViewHandle != 0L) vkDestroyImageView(vkDevice, tex.imageViewHandle, null);
            if (tex.samplerHandle != 0L) vkDestroySampler(vkDevice, tex.samplerHandle, null);
            if (tex.imageHandle != 0L) vkDestroyImage(vkDevice, tex.imageHandle, null);
            if (tex.memoryHandle != 0L) vkFreeMemory(vkDevice, tex.memoryHandle, null);
        });

        // Device is idle at shutdown, so anything still awaiting deferred disposal can be destroyed now
        synchronized (deferredDisposalQueue) {
            DeferredVulkanResource res;
            while ((res = deferredDisposalQueue.poll()) != null) {
                destroyDeferred(vkDevice, res);
            }
        }
        synchronized (pendingTableWrites) {
            pendingTableWrites.clear();
        }

        if (lightUniformBuffer != null) {
            if (lightUniformBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, lightUniformBuffer.bufferHandle, null);
            if (lightUniformBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, lightUniformBuffer.memoryHandle, null);
            lightUniformBuffer = null;
        }

        if (bonePaletteBuffer != null) {
            if (bonePaletteBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, bonePaletteBuffer.bufferHandle, null);
            if (bonePaletteBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, bonePaletteBuffer.memoryHandle, null);
            bonePaletteBuffer = null;
            bonePaletteMapped = 0L;
        }

        if (dummyBonePaletteBuffer != null) {
            if (dummyBonePaletteBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, dummyBonePaletteBuffer.bufferHandle, null);
            if (dummyBonePaletteBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, dummyBonePaletteBuffer.memoryHandle, null);
            dummyBonePaletteBuffer = null;
        }

        if (groundQuadBuffer != null) {
            if (groundQuadBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, groundQuadBuffer.bufferHandle, null);
            if (groundQuadBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, groundQuadBuffer.memoryHandle, null);
            groundQuadBuffer = null;
        }
    }
}
