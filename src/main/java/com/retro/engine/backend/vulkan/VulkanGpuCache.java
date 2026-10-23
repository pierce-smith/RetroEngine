package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles VRAM allocations for 3D Mesh Vertex Buffers (VBOs), 2D Textures, Light UBOs,
 * 3-Slot Ring-Buffered 2D UI Overlay Buffers, 3-Slot Ring-Buffered 3D Grid Buffers,
 * and 22-float (88-byte stride) ground quad buffer for dynamic entity shadow blob projections.
 * Hardened with fence-synchronized single-time commands and deferred Vulkan resource cleanup queues.
 */
public class VulkanGpuCache {

    private static final int RING_SLOTS = 3;

    public static class VulkanBuffer {
        public long bufferHandle;
        public long memoryHandle;
        public int vertexCount;

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

    private static class DeferredVulkanResource {
        final long bufferHandle;
        final long memoryHandle;
        final int frameEnqueued;

        DeferredVulkanResource(long bufferHandle, long memoryHandle, int frameEnqueued) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.frameEnqueued = frameEnqueued;
        }
    }

    private final Map<String, VulkanBuffer> meshCache = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, VulkanTexture> textureCache = Collections.synchronizedMap(new HashMap<>());
    private final Queue<DeferredVulkanResource> deferredDisposalQueue = new ArrayDeque<>();

    private VulkanBuffer lightUniformBuffer = null;
    private VulkanTexture defaultWhiteTexture = null;
    private VulkanBuffer groundQuadBuffer = null;

    // 3-Slot Ring Buffer for Dynamic 2D UI Overlay Geometry
    private final long[] uiVertexBufferRing = new long[RING_SLOTS];
    private final long[] uiMemoryBufferRing = new long[RING_SLOTS];
    private final int[] uiVertexCapacityRing = new int[RING_SLOTS];
    private int uiRingIndex = 0;
    private int uiVertexCount = 0;

    // 3-Slot Ring Buffer for Dynamic 3D Infinite Grid Geometry
    private final long[] gridVertexBufferRing = new long[RING_SLOTS];
    private final long[] gridMemoryBufferRing = new long[RING_SLOTS];
    private final int[] gridVertexCapacityRing = new int[RING_SLOTS];
    private int gridRingIndex = 0;
    private int gridVertexCount = 0;

    private int totalFrameCount = 0;

    public long getUiVertexBuffer() { return uiVertexBufferRing[uiRingIndex]; }
    public int getUiVertexCount() { return uiVertexCount; }

    public long getGridVertexBuffer() { return gridVertexBufferRing[gridRingIndex]; }
    public int getGridVertexCount() { return gridVertexCount; }

    public long getGroundQuadBuffer(VulkanDevice device) {
        if (groundQuadBuffer != null) return groundQuadBuffer.bufferHandle;
        if (device == null || device.getLogicalDevice() == null) return 0L;

        // 22-float stride (88 bytes) ground quad VBO for dynamic shadow blob projection
        float[] groundVerts = new float[] {
            // X, Y, Z,  NX, NY, NZ,  R, G, B, A,  U1, V1,  U2, V2,  TX, TY, TZ,  BX, BY, BZ,  LM_S, LM_B
            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            -1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 1.0f,  0.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,

            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 0.0f,  1.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f
        };

        groundQuadBuffer = createHostVisibleBuffer(device, groundVerts);
        return (groundQuadBuffer != null) ? groundQuadBuffer.bufferHandle : 0L;
    }

    public void updateUiMesh(VulkanDevice device, float[] vertices, int count) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || count <= 0) {
            this.uiVertexCount = 0;
            return;
        }

        totalFrameCount++;
        processDeferredDisposals(device);

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = (long) count * 6 * Float.BYTES;

        this.uiRingIndex = (this.uiRingIndex + 1) % RING_SLOTS;

        long currentBuffer = uiVertexBufferRing[uiRingIndex];
        long currentMemory = uiMemoryBufferRing[uiRingIndex];
        int currentCap = uiVertexCapacityRing[uiRingIndex];

        try (MemoryStack stack = stackPush()) {
            if (currentBuffer == 0L || count > currentCap) {
                if (currentBuffer != 0L) {
                    enqueueDeferredDisposal(currentBuffer, currentMemory);
                }

                int newCap = Math.max(count * 2, 16384);
                long newSize = (long) newCap * 6 * Float.BYTES;

                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(newSize)
                        .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

                LongBuffer pBuffer = stack.mallocLong(1);
                if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return;
                currentBuffer = pBuffer.get(0);

                VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(vkDevice, currentBuffer, memReqs);

                int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(memReqs.size())
                        .memoryTypeIndex(memTypeIndex);

                LongBuffer pMemory = stack.mallocLong(1);
                if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) return;
                currentMemory = pMemory.get(0);

                vkBindBufferMemory(vkDevice, currentBuffer, currentMemory, 0);

                uiVertexBufferRing[uiRingIndex] = currentBuffer;
                uiMemoryBufferRing[uiRingIndex] = currentMemory;
                uiVertexCapacityRing[uiRingIndex] = newCap;
            }

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, currentMemory, 0, bufferSize, 0, pData) == VK_SUCCESS) {
                FloatBuffer floatBuf = pData.getFloatBuffer(0, count * 6);
                floatBuf.put(vertices, 0, count * 6);
                vkUnmapMemory(vkDevice, currentMemory);
                this.uiVertexCount = count;
            }
        }
    }

    public void updateGridMesh(VulkanDevice device, float[] vertices, int count) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || count <= 0) {
            this.gridVertexCount = 0;
            return;
        }

        totalFrameCount++;
        processDeferredDisposals(device);

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = (long) vertices.length * Float.BYTES;

        this.gridRingIndex = (this.gridRingIndex + 1) % RING_SLOTS;

        long currentBuffer = gridVertexBufferRing[gridRingIndex];
        long currentMemory = gridMemoryBufferRing[gridRingIndex];
        int currentCap = gridVertexCapacityRing[gridRingIndex];

        try (MemoryStack stack = stackPush()) {
            if (currentBuffer == 0L || vertices.length > currentCap) {
                if (currentBuffer != 0L) {
                    enqueueDeferredDisposal(currentBuffer, currentMemory);
                }

                int newCap = Math.max(vertices.length, 2048 * 9);
                long newSize = (long) newCap * Float.BYTES;

                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(newSize)
                        .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

                LongBuffer pBuffer = stack.mallocLong(1);
                if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return;
                currentBuffer = pBuffer.get(0);

                VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(vkDevice, currentBuffer, memReqs);

                int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(memReqs.size())
                        .memoryTypeIndex(memTypeIndex);

                LongBuffer pMemory = stack.mallocLong(1);
                if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) return;
                currentMemory = pMemory.get(0);

                vkBindBufferMemory(vkDevice, currentBuffer, currentMemory, 0);

                gridVertexBufferRing[gridRingIndex] = currentBuffer;
                gridMemoryBufferRing[gridRingIndex] = currentMemory;
                gridVertexCapacityRing[gridRingIndex] = newCap;
            }

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, currentMemory, 0, bufferSize, 0, pData) == VK_SUCCESS) {
                FloatBuffer floatBuf = pData.getFloatBuffer(0, vertices.length);
                floatBuf.put(vertices, 0, vertices.length);
                vkUnmapMemory(vkDevice, currentMemory);
                this.gridVertexCount = count;
            }
        }
    }

    private void enqueueDeferredDisposal(long bufferHandle, long memoryHandle) {
        if (bufferHandle == 0L) return;
        synchronized (deferredDisposalQueue) {
            deferredDisposalQueue.add(new DeferredVulkanResource(bufferHandle, memoryHandle, totalFrameCount));
        }
    }

    private void processDeferredDisposals(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        synchronized (deferredDisposalQueue) {
            while (!deferredDisposalQueue.isEmpty()) {
                DeferredVulkanResource res = deferredDisposalQueue.peek();
                if (totalFrameCount - res.frameEnqueued >= RING_SLOTS) {
                    deferredDisposalQueue.poll();
                    if (res.bufferHandle != 0L) vkDestroyBuffer(vkDevice, res.bufferHandle, null);
                    if (res.memoryHandle != 0L) vkFreeMemory(vkDevice, res.memoryHandle, null);
                } else {
                    break;
                }
            }
        }
    }

    public VulkanBuffer getOrCreateMeshBuffer(VulkanDevice device, String meshPath, float[] vertices) {
        if (meshPath == null || vertices == null || vertices.length == 0) return null;

        VulkanBuffer existing = meshCache.get(meshPath);
        if (existing != null) return existing;

        VulkanBuffer created = createHostVisibleBuffer(device, vertices);
        if (created != null) {
            meshCache.put(meshPath, created);
        }
        return created;
    }

    public VulkanTexture getOrCreateTexture(VulkanDevice device, String texturePath, byte[] pixels, int width, int height) {
        if (texturePath == null || pixels == null || pixels.length == 0 || width <= 0 || height <= 0) return null;

        VulkanTexture existing = textureCache.get(texturePath);
        if (existing != null) return existing;

        VulkanTexture created = createTextureFromPixels(device, pixels, width, height);
        if (created != null) {
            textureCache.put(texturePath, created);
        }
        return created;
    }

    public VulkanBuffer createHostVisibleBuffer(VulkanDevice device, float[] vertices) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || vertices.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = (long) vertices.length * Float.BYTES;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                System.err.println("[VulkanGpuCache] Failed to create vertex buffer!");
                return null;
            }
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
                System.err.println("[VulkanGpuCache] Failed to allocate vertex buffer memory!");
                vkDestroyBuffer(vkDevice, buffer, null);
                return null;
            }
            long bufferMemory = pBufferMemory.get(0);

            vkBindBufferMemory(vkDevice, buffer, bufferMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, bufferMemory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, vertices.length);
            floatBuf.put(vertices);
            vkUnmapMemory(vkDevice, bufferMemory);

            int floatStride = (vertices.length % 22 == 0) ? 22 : ((vertices.length % 14 == 0) ? 14 : 9);
            return new VulkanBuffer(buffer, bufferMemory, vertices.length / floatStride);
        }
    }

    public VulkanTexture createTextureFromPixels(VulkanDevice device, byte[] pixels, int width, int height) {
        if (device == null || device.getLogicalDevice() == null || pixels == null || pixels.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long imageSize = (long) width * height * 4;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo stagingBufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(imageSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, stagingBufferInfo, null, pStagingBuffer) != VK_SUCCESS) return null;
            long stagingBuffer = pStagingBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, stagingBuffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pStagingMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pStagingMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                return null;
            }
            long stagingBufferMemory = pStagingMemory.get(0);

            vkBindBufferMemory(vkDevice, stagingBuffer, stagingBufferMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, stagingBufferMemory, 0, imageSize, 0, pData);
            ByteBuffer byteBuf = pData.getByteBuffer(0, (int) imageSize);
            byteBuf.put(pixels);
            vkUnmapMemory(vkDevice, stagingBufferMemory);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .extent(extent -> extent.set(width, height, 1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .format(VK_FORMAT_B8G8R8A8_UNORM)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                vkFreeMemory(vkDevice, stagingBufferMemory, null);
                return null;
            }
            long textureImage = pImage.get(0);

            VkMemoryRequirements imgMemReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, textureImage, imgMemReqs);

            int imgMemTypeIndex = findMemoryType(device.getPhysicalDevice(), imgMemReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VkMemoryAllocateInfo imgAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(imgMemReqs.size())
                    .memoryTypeIndex(imgMemTypeIndex);

            LongBuffer pImageMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, imgAllocInfo, null, pImageMemory) != VK_SUCCESS) {
                vkDestroyImage(vkDevice, textureImage, null);
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                vkFreeMemory(vkDevice, stagingBufferMemory, null);
                return null;
            }
            long textureImageMemory = pImageMemory.get(0);

            vkBindImageMemory(vkDevice, textureImage, textureImageMemory, 0);

            transitionImageLayout(device, textureImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyBufferToImage(device, stagingBuffer, textureImage, width, height);
            transitionImageLayout(device, textureImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            vkDestroyBuffer(vkDevice, stagingBuffer, null);
            vkFreeMemory(vkDevice, stagingBufferMemory, null);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(textureImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_B8G8R8A8_UNORM);

            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pImageView) != VK_SUCCESS) return null;
            long textureImageView = pImageView.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(vkDevice, samplerInfo, null, pSampler) != VK_SUCCESS) return null;
            long textureSampler = pSampler.get(0);

            return new VulkanTexture(textureImage, textureImageMemory, textureImageView, textureSampler);
        }
    }

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        if (defaultWhiteTexture != null) return defaultWhiteTexture;
        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        defaultWhiteTexture = createTextureFromPixels(device, whitePixel, 1, 1);
        return defaultWhiteTexture;
    }

    public VulkanBuffer getOrCreateLightUniformBuffer(VulkanDevice device) {
        if (lightUniformBuffer != null) return lightUniformBuffer;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = 512L; // 512 bytes

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
                                        float camX, float camY, float camZ, float groundY) {
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

                floatBuf.put(36, numDir); floatBuf.put(37, numPoint); floatBuf.put(38, numSpot); floatBuf.put(39, 0.005f);

                floatBuf.put(40, camX); floatBuf.put(41, camY); floatBuf.put(42, camZ); floatBuf.put(43, groundY);

                vkUnmapMemory(vkDevice, buf.memoryHandle);
            }
        }
    }

    private void transitionImageLayout(VulkanDevice device, long image, int oldLayout, int newLayout) {
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = beginSingleTimeCommands(device);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            int sourceStage;
            int destinationStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            } else {
                return;
            }

            vkCmdPipelineBarrier(cmd, sourceStage, destinationStage, 0, null, null, barrier);
            endSingleTimeCommands(device, cmd);
        }
    }

    private void copyBufferToImage(VulkanDevice device, long buffer, long image, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = beginSingleTimeCommands(device);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);

            region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);

            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            endSingleTimeCommands(device, cmd);
        }
    }

    private VkCommandBuffer beginSingleTimeCommands(VulkanDevice device) {
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
            return cmd;
        }
    }

    private void endSingleTimeCommands(VulkanDevice device, VkCommandBuffer cmd) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);

            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(vkDevice, fenceInfo, null, pFence) == VK_SUCCESS) {
                long fence = pFence.get(0);

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(cmd));

                vkQueueSubmit(device.getGraphicsQueue(), submitInfo, fence);
                vkWaitForFences(vkDevice, fence, true, 1000000000L); // 1 sec timeout
                vkDestroyFence(vkDevice, fence, null);
            } else {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(cmd));

                vkQueueSubmit(device.getGraphicsQueue(), submitInfo, VK_NULL_HANDLE);
                vkQueueWaitIdle(device.getGraphicsQueue());
            }
        }
    }

    private int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
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

        for (int i = 0; i < RING_SLOTS; i++) {
            if (uiVertexBufferRing[i] != 0L) {
                vkDestroyBuffer(vkDevice, uiVertexBufferRing[i], null);
                vkFreeMemory(vkDevice, uiMemoryBufferRing[i], null);
                uiVertexBufferRing[i] = 0L;
                uiMemoryBufferRing[i] = 0L;
            }
            if (gridVertexBufferRing[i] != 0L) {
                vkDestroyBuffer(vkDevice, gridVertexBufferRing[i], null);
                vkFreeMemory(vkDevice, gridMemoryBufferRing[i], null);
                gridVertexBufferRing[i] = 0L;
                gridMemoryBufferRing[i] = 0L;
            }
        }

        if (lightUniformBuffer != null) {
            if (lightUniformBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, lightUniformBuffer.bufferHandle, null);
            if (lightUniformBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, lightUniformBuffer.memoryHandle, null);
            lightUniformBuffer = null;
        }

        if (groundQuadBuffer != null) {
            if (groundQuadBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, groundQuadBuffer.bufferHandle, null);
            if (groundQuadBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, groundQuadBuffer.memoryHandle, null);
            groundQuadBuffer = null;
        }
    }
}
