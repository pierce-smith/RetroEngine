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
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching EditorWorkspace):
 * Coordinates static VBO mesh buffers, Light UBO allocations, ground shadow quad VBOs, the directional shadow map,
 * and delegates texture management to VulkanTextureRegistry and dynamic geometry buffering to VulkanRingBuffer.
 * Registered meshes and textures are owned by opaque-handle tables; command recording resolves them by handle.
 */
public class VulkanGpuCache {

    private static final int RING_SLOTS = 3;

    // Bone palettes: 64 column-major mat4 per skinned entity, read by blinn_phong_skinned.vert at binding 3.
    // A single slot set suffices because VulkanBackend waits on the in-flight fence before recording.
    public static final int MAX_PALETTE_BONES = 64;
    public static final long BONE_PALETTE_STRIDE = MAX_PALETTE_BONES * 64L;
    public static final int MAX_SKINNED_ENTITIES_PER_FRAME = 64;

    // Directional shadow map: persistent 2048x2048 depth target sampled through binding 2 with a comparison sampler
    public static final int SHADOW_MAP_SIZE = DirectionalShadowFrustum.SHADOW_MAP_SIZE;
    public static final int SHADOW_MAP_FORMAT = VK_FORMAT_D32_SFLOAT;

    // LightBuffer (binding 0) std140 layout: lightSpaceMatrix at byte 304, 368 bytes total
    public static final int LIGHT_UBO_LIGHT_SPACE_FLOAT_INDEX = 76;
    public static final long LIGHT_UBO_SIZE = 368L;

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

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, int frameEnqueued) {
            this(bufferHandle, memoryHandle, 0L, 0L, 0L, frameEnqueued);
        }

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, long imageHandle,
                                      long imageViewHandle, long samplerHandle, int frameEnqueued) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.imageHandle = imageHandle;
            this.imageViewHandle = imageViewHandle;
            this.samplerHandle = samplerHandle;
            this.frameEnqueued = frameEnqueued;
        }
    }

    public static class SingleTimeCmdContext {
        public final VkCommandBuffer cmd;
        public final long pool;

        public SingleTimeCmdContext(VkCommandBuffer cmd, long pool) {
            this.cmd = cmd;
            this.pool = pool;
        }
    }

    // Subsystem Domain Managers
    private final VulkanTextureRegistry textureRegistry = new VulkanTextureRegistry();
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

    private volatile int totalFrameCount = 0;

    // Per-frame descriptor state built by prepareSceneDescriptors before recording; reused to keep frames allocation-free
    private long[] entityDescriptorSets = new long[256];
    private int[] entityPaletteOffsets = new int[256];
    // Texture-view -> set cache, fixed capacity so recording never reallocates; overflow views get uncached sets
    private static final int MAX_UNIQUE_SETS = 256;
    private final long[] uniqueSets = new long[MAX_UNIQUE_SETS];
    private final long[] uniqueSetViews = new long[MAX_UNIQUE_SETS];
    private int uniqueSetCount = 0;
    private long shadowDescriptorSet = 0L;

    public long getUiVertexBuffer() { return uiRingBuffer.getCurrentBufferHandle(); }
    public int getUiVertexCount() { return uiRingBuffer.getVertexCount(); }

    public long getGridVertexBuffer() { return gridRingBuffer.getCurrentBufferHandle(); }
    public int getGridVertexCount() { return gridRingBuffer.getVertexCount(); }

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
        totalFrameCount++;
        processDeferredDisposals(device);
        uiRingBuffer.updateBuffer(device, vertices, count, 6, deferredDisposalQueue, totalFrameCount);
    }

    public void updateGridMesh(VulkanDevice device, float[] vertices, int count) {
        totalFrameCount++;
        processDeferredDisposals(device);
        gridRingBuffer.updateBuffer(device, vertices, count, 9, deferredDisposalQueue, totalFrameCount);
    }

    private void processDeferredDisposals(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        synchronized (deferredDisposalQueue) {
            while (!deferredDisposalQueue.isEmpty()) {
                DeferredVulkanResource res = deferredDisposalQueue.peek();
                if (totalFrameCount - res.frameEnqueued >= RING_SLOTS) {
                    deferredDisposalQueue.poll();
                    destroyDeferred(vkDevice, res);
                } else {
                    break;
                }
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

    /** Uploads image data into a VkImage + VkImageView + VkSampler once (or returns the live handle). Thread-safe. */
    public long registerTexture(VulkanDevice device, String assetKey, byte[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height * 4) return HandleRegistry.NULL_HANDLE;
        return textureHandles.register(assetKey, () -> textureRegistry.createTextureFromPixels(device, pixels, width, height));
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
        if (tex == null) return;
        synchronized (deferredDisposalQueue) {
            deferredDisposalQueue.add(new DeferredVulkanResource(0L, tex.memoryHandle, tex.imageHandle,
                    tex.imageViewHandle, tex.samplerHandle, totalFrameCount));
        }
    }

    /** Allocation-free handle lookup for command recording; null for 0L, stale, or unregistered handles. */
    public VulkanBuffer getMesh(long meshHandle) { return meshHandles.get(meshHandle); }

    public VulkanTexture getTexture(long textureHandle) { return textureHandles.get(textureHandle); }

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        return textureRegistry.getDefaultWhiteTexture(device);
    }

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
     * Permanent one-slot identity palette bound to binding 3 whenever the real palette buffer is unavailable, so every
     * 3D descriptor set writes all four bindings and always matches the layout's two dynamic offsets.
     */
    public VulkanBuffer getOrCreateDummyBonePaletteBuffer(VulkanDevice device) {
        if (dummyBonePaletteBuffer != null) return dummyBonePaletteBuffer;
        dummyBonePaletteBuffer = createIdentityPaletteBuffer(device, 1, null);
        return dummyBonePaletteBuffer;
    }

    /** Creates long-lived resources that descriptor writes depend on; call once after the logical device exists. */
    public void initialize(VulkanDevice device) {
        getOrCreateDummyBonePaletteBuffer(device);
        if (!createShadowMap(device)) {
            System.err.println("[VulkanGpuCache] ERROR: Failed to allocate the " + SHADOW_MAP_SIZE + "x" + SHADOW_MAP_SIZE + " directional shadow map.");
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
     * Allocates and writes every 3D descriptor set the frame needs, plus each entity's bone palette, before command
     * recording begins so no vkUpdateDescriptorSets ever lands inside a render pass. One set is shared per unique
     * texture view; the per-entity palette slot is selected at bind time through binding 3's dynamic offset.
     * Must run after the in-flight fence wait and descriptor pool reset.
     */
    public void prepareSceneDescriptors(VulkanDevice device, long descriptorPool, long setLayout,
                                        List<VulkanPassEncoder.RenderEntity> sceneQueue, int sceneQueueCount) {
        uniqueSetCount = 0;
        shadowDescriptorSet = 0L;
        if (entityDescriptorSets.length < sceneQueueCount) {
            int cap = Math.max(sceneQueueCount, entityDescriptorSets.length * 2);
            entityDescriptorSets = new long[cap];
            entityPaletteOffsets = new int[cap];
        }
        Arrays.fill(entityDescriptorSets, 0, sceneQueueCount, 0L);
        Arrays.fill(entityPaletteOffsets, 0, sceneQueueCount, 0);

        VkDevice vkDevice = (device != null) ? device.getLogicalDevice() : null;
        VulkanTexture whiteTex = getDefaultWhiteTexture(device);
        VulkanBuffer lightUbo = getOrCreateLightUniformBuffer(device);
        VulkanBuffer bonePaletteBuf = getOrCreateBonePaletteBuffer(device);
        // Binding 3 is always written: fall back to the one-slot identity palette (dynamic offsets then stay 0)
        VulkanBuffer binding3Buf = (bonePaletteBuf != null) ? bonePaletteBuf : getOrCreateDummyBonePaletteBuffer(device);
        // Binding 2 always samples the shadow map, which the pass encoder leaves in SHADER_READ_ONLY_OPTIMAL every frame
        if (vkDevice == null || descriptorPool == 0L || setLayout == 0L || lightUbo == null || whiteTex == null || binding3Buf == null || !hasShadowMap()) return;

        shadowDescriptorSet = acquireTextureSet(vkDevice, descriptorPool, setLayout, whiteTex.imageViewHandle, whiteTex, lightUbo, binding3Buf);

        int skinnedIndex = 0;
        for (int i = 0; i < sceneQueueCount; i++) {
            VulkanPassEncoder.RenderEntity entity = sceneQueue.get(i);
            VulkanBuffer mesh = getMesh(entity.meshHandle);
            if (mesh == null || mesh.bufferHandle == 0L || mesh.vertexCount <= 0) continue;

            // slot 0 stays identity for palette-less skinned meshes
            if (mesh.floatStride >= 30 && bonePaletteBuf != null && entity.bonePalette != null
                    && skinnedIndex < MAX_SKINNED_ENTITIES_PER_FRAME - 1) {
                int paletteSlot = ++skinnedIndex;
                writeBonePalette(paletteSlot, entity.bonePalette);
                entityPaletteOffsets[i] = (int) (paletteSlot * BONE_PALETTE_STRIDE);
            }

            VulkanTexture entityTex = getTexture(entity.textureHandle);
            long texView = (entityTex != null && entityTex.imageViewHandle != 0L) ? entityTex.imageViewHandle : whiteTex.imageViewHandle;
            entityDescriptorSets[i] = acquireTextureSet(vkDevice, descriptorPool, setLayout, texView, whiteTex, lightUbo, binding3Buf);
        }
    }

    /** Descriptor set prepared for the given scene-queue index this frame, or 0 if the entity has none. */
    public long getEntityDescriptorSet(int index) { return index < entityDescriptorSets.length ? entityDescriptorSets[index] : 0L; }

    /** Byte offset into the bone palette buffer for the given scene-queue index, passed as binding 3's dynamic offset. */
    public int getEntityPaletteOffset(int index) { return index < entityPaletteOffsets.length ? entityPaletteOffsets[index] : 0; }

    /** White-textured set shared by every shadow blob draw this frame. */
    public long getShadowDescriptorSet() { return shadowDescriptorSet; }

    private long acquireTextureSet(VkDevice vkDevice, long descriptorPool, long setLayout, long texView,
                                   VulkanTexture whiteTex, VulkanBuffer lightUbo, VulkanBuffer bonePaletteBuf) {
        for (int s = 0; s < uniqueSetCount; s++) {
            if (uniqueSetViews[s] == texView) return uniqueSets[s];
        }

        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocSetInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(setLayout));

            LongBuffer pSet = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(vkDevice, allocSetInfo, pSet) != VK_SUCCESS) return 0L;
            long set = pSet.get(0);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(lightUbo.bufferHandle).offset(0).range(LIGHT_UBO_SIZE);
            VkDescriptorImageInfo.Buffer imageInfoTex = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(texView).sampler(whiteTex.samplerHandle);
            VkDescriptorImageInfo.Buffer imageInfoShadow = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(shadowMapImageView).sampler(shadowMapSampler);

            // Base offset 0 with a one-slot range; the entity's slot is chosen by the dynamic offset at bind time
            VkDescriptorBufferInfo.Buffer boneInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(bonePaletteBuf.bufferHandle)
                    .offset(0)
                    .range(BONE_PALETTE_STRIDE);

            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(4, stack);
            descriptorWrites.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(bufferInfo);
            descriptorWrites.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoTex);
            descriptorWrites.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoShadow);
            descriptorWrites.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC).pBufferInfo(boneInfo);

            vkUpdateDescriptorSets(vkDevice, descriptorWrites, null);

            if (uniqueSetCount < MAX_UNIQUE_SETS) {
                uniqueSets[uniqueSetCount] = set;
                uniqueSetViews[uniqueSetCount] = texView;
                uniqueSetCount++;
            }
            return set;
        }
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
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) return null;
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
                                        float[] viewMatrix, float[] projMatrix, float[] lightSpaceMatrix) {
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

                // Camera matrices live here (byte offset 176) rather than in push constants to keep those at 80 bytes
                putMatrixOrIdentity(floatBuf, 44, viewMatrix);
                putMatrixOrIdentity(floatBuf, 60, projMatrix);
                putMatrixOrIdentity(floatBuf, LIGHT_UBO_LIGHT_SPACE_FLOAT_INDEX, lightSpaceMatrix);

                vkUnmapMemory(vkDevice, buf.memoryHandle);
            }
        }
    }

    private static void putMatrixOrIdentity(FloatBuffer dst, int index, float[] matrix) {
        boolean valid = matrix != null && matrix.length == 16;
        for (int i = 0; i < 16; i++) dst.put(index + i, valid ? matrix[i] : ((i % 5 == 0) ? 1.0f : 0.0f));
    }

    public static SingleTimeCmdContext beginSingleTimeCommands(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(device.getGraphicsQueueFamilyIndex());

            LongBuffer pPool = stack.mallocLong(1);
            vkCreateCommandPool(vkDevice, poolInfo, null, pPool);
            long pool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(vkDevice, allocInfo, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vkDevice);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmd, beginInfo);
            return new SingleTimeCmdContext(cmd, pool);
        }
    }

    public static void endSingleTimeCommands(VulkanDevice device, SingleTimeCmdContext stc) {
        if (stc == null || device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(stc.cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);

            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(vkDevice, fenceInfo, null, pFence) == VK_SUCCESS) {
                long fence = pFence.get(0);

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(stc.cmd));

                synchronized (device.getQueueLock()) {
                    vkQueueSubmit(device.getGraphicsQueue(), submitInfo, fence);
                }
                vkWaitForFences(vkDevice, fence, true, 1000000000L);
                vkDestroyFence(vkDevice, fence, null);
            } else {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(stc.cmd));

                synchronized (device.getQueueLock()) {
                    vkQueueSubmit(device.getGraphicsQueue(), submitInfo, VK_NULL_HANDLE);
                    vkQueueWaitIdle(device.getGraphicsQueue());
                }
            }

            if (stc.pool != 0L) {
                vkFreeCommandBuffers(vkDevice, stc.pool, stc.cmd);
                vkDestroyCommandPool(vkDevice, stc.pool, null);
            }
        }
    }

    public static int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypes().capacity(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        return 0;
    }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

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
