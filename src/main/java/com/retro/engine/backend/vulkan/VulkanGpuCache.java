package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles VRAM allocations for 3D Mesh Vertex Buffers (VBOs), 2D Textures, and Light UBOs
 * using Vulkan 1.3 memory management with optimal image tiling and staging buffers.
 */
public class VulkanGpuCache {

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

    private final Map<String, VulkanBuffer> meshCache = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, VulkanTexture> textureCache = Collections.synchronizedMap(new HashMap<>());

    private VulkanBuffer lightUniformBuffer = null;
    private VulkanTexture defaultWhiteTexture = null;

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

            int floatStride = (vertices.length % 14 == 0) ? 14 : 9;
            return new VulkanBuffer(buffer, bufferMemory, vertices.length / floatStride);
        }
    }

    public VulkanTexture createTextureFromPixels(VulkanDevice device, byte[] pixels, int width, int height) {
        if (device == null || device.getLogicalDevice() == null || pixels == null || pixels.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long imageSize = (long) width * height * 4;

        try (MemoryStack stack = stackPush()) {
            // 1. Create Staging Buffer in Host-Visible Memory
            VkBufferCreateInfo stagingBufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(imageSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, stagingBufferInfo, null, pStagingBuffer) != VK_SUCCESS) return null;
            long stagingBuffer = pStagingBuffer.get(0);

            VkMemoryRequirements stagingMemReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, stagingBuffer, stagingMemReqs);

            VkMemoryAllocateInfo stagingAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(stagingMemReqs.size())
                    .memoryTypeIndex(findMemoryType(device.getPhysicalDevice(), stagingMemReqs.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

            LongBuffer pStagingMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, stagingAllocInfo, null, pStagingMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                return null;
            }
            long stagingMemory = pStagingMemory.get(0);
            vkBindBufferMemory(vkDevice, stagingBuffer, stagingMemory, 0);

            // Copy pixel data to staging memory
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, stagingMemory, 0, imageSize, 0, pData) == VK_SUCCESS) {
                ByteBuffer byteBuf = pData.getByteBuffer(0, (int) imageSize);
                int copyLen = Math.min(pixels.length, byteBuf.remaining());
                byteBuf.put(pixels, 0, copyLen);
                vkUnmapMemory(vkDevice, stagingMemory);
            }

            // 2. Create Target Image with OPTIMAL Tiling
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) {
                vkFreeMemory(vkDevice, stagingMemory, null);
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                return null;
            }
            long image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, image, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) != VK_SUCCESS) {
                vkDestroyImage(vkDevice, image, null);
                vkFreeMemory(vkDevice, stagingMemory, null);
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                return null;
            }
            long memory = pMem.get(0);
            vkBindImageMemory(vkDevice, image, memory, 0);

            // 3. Execute Staging Copy & Layout Transition to SHADER_READ_ONLY_OPTIMAL
            copyBufferToImage(device, stagingBuffer, image, width, height);

            // Clean up staging buffer
            vkDestroyBuffer(vkDevice, stagingBuffer, null);
            vkFreeMemory(vkDevice, stagingMemory, null);

            // 4. Create ImageView
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pView) != VK_SUCCESS) {
                vkFreeMemory(vkDevice, memory, null);
                vkDestroyImage(vkDevice, image, null);
                return null;
            }
            long imageView = pView.get(0);

            // 5. Create Sampler
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(vkDevice, samplerInfo, null, pSampler) != VK_SUCCESS) {
                vkDestroyImageView(vkDevice, imageView, null);
                vkFreeMemory(vkDevice, memory, null);
                vkDestroyImage(vkDevice, image, null);
                return null;
            }
            long sampler = pSampler.get(0);

            return new VulkanTexture(image, memory, imageView, sampler);
        }
    }

    private void copyBufferToImage(VulkanDevice device, long buffer, long image, int width, int height) {
        VkDevice vkDevice = device.getLogicalDevice();
        int queueFamily = device.getGraphicsQueueFamilyIndex();

        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(queueFamily);

            LongBuffer pCmdPool = stack.mallocLong(1);
            if (vkCreateCommandPool(vkDevice, poolInfo, null, pCmdPool) != VK_SUCCESS) return;
            long cmdPool = pCmdPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(vkDevice, allocInfo, pCmd) != VK_SUCCESS) {
                vkDestroyCommandPool(vkDevice, cmdPool, null);
                return;
            }
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vkDevice);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmd, beginInfo);

            // Barrier 1: UNDEFINED -> TRANSFER_DST_OPTIMAL
            VkImageMemoryBarrier.Buffer barrier1 = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);

            barrier1.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier1);

            // Buffer to Image Copy Region
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(0);
            region.bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            // Barrier 2: TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL
            VkImageMemoryBarrier.Buffer barrier2 = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            barrier2.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier2);

            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));

            vkQueueSubmit(device.getGraphicsQueue(), submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(device.getGraphicsQueue());

            vkDestroyCommandPool(vkDevice, cmdPool, null);
        }
    }

    public VulkanBuffer getOrCreateLightUniformBuffer(VulkanDevice device) {
        if (lightUniformBuffer != null) return lightUniformBuffer;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = 512L; // 512-byte LightBuffer UBO

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

            // Populate with basic light defaults
            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, memory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, (int) (bufferSize / 4));
            // Directional Light: dir=(-0.5, -1.0, -0.5, 0), color=(1, 0.95, 0.85, 1.2)
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

                // Directional Light (floats 0..7)
                floatBuf.put(0, dirX); floatBuf.put(1, dirY); floatBuf.put(2, dirZ); floatBuf.put(3, 0.0f);
                floatBuf.put(4, r);    floatBuf.put(5, g);    floatBuf.put(6, b);    floatBuf.put(7, intensity);

                // Point Light (floats 8..19)
                floatBuf.put(8, posX); floatBuf.put(9, posY); floatBuf.put(10, posZ); floatBuf.put(11, 0.0f);
                floatBuf.put(12, r);   floatBuf.put(13, g);   floatBuf.put(14, b);    floatBuf.put(15, intensity);
                floatBuf.put(16, 1.0f);floatBuf.put(17, 0.09f);floatBuf.put(18, 0.032f);floatBuf.put(19, 0.0f);

                // Spot Light (floats 20..35)
                floatBuf.put(20, posX); floatBuf.put(21, posY); floatBuf.put(22, posZ); floatBuf.put(23, 0.93f); // innerCutoff
                floatBuf.put(24, dirX); floatBuf.put(25, dirY); floatBuf.put(26, dirZ); floatBuf.put(27, 0.82f); // outerCutoff
                floatBuf.put(28, r);    floatBuf.put(29, g);    floatBuf.put(30, b);    floatBuf.put(31, intensity);
                floatBuf.put(32, 1.0f); floatBuf.put(33, 0.09f);floatBuf.put(34, 0.032f);floatBuf.put(35, 0.0f);

                // numLights (floats 36..39)
                floatBuf.put(36, numDir); floatBuf.put(37, numPoint); floatBuf.put(38, numSpot); floatBuf.put(39, 0.005f);

                // viewPos (floats 40..43)
                floatBuf.put(40, camX); floatBuf.put(41, camY); floatBuf.put(42, camZ); floatBuf.put(43, groundY);

                vkUnmapMemory(vkDevice, buf.memoryHandle);
            }
        }
    }

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        if (defaultWhiteTexture != null) return defaultWhiteTexture;
        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        defaultWhiteTexture = createTextureFromPixels(device, whitePixel, 1, 1);
        return defaultWhiteTexture;
    }

    private int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0 &&
                        (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        throw new RuntimeException("[VulkanGpuCache] Failed to find suitable memory type!");
    }

    public void cleanup(VulkanDevice device) {
        shutdown(device);
    }

    public void shutdown(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (lightUniformBuffer != null) {
            if (lightUniformBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, lightUniformBuffer.bufferHandle, null);
            if (lightUniformBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, lightUniformBuffer.memoryHandle, null);
            lightUniformBuffer = null;
        }

        if (defaultWhiteTexture != null) {
            if (defaultWhiteTexture.imageViewHandle != 0L) vkDestroyImageView(vkDevice, defaultWhiteTexture.imageViewHandle, null);
            if (defaultWhiteTexture.samplerHandle != 0L) vkDestroySampler(vkDevice, defaultWhiteTexture.samplerHandle, null);
            if (defaultWhiteTexture.imageHandle != 0L) vkDestroyImage(vkDevice, defaultWhiteTexture.imageHandle, null);
            if (defaultWhiteTexture.memoryHandle != 0L) vkFreeMemory(vkDevice, defaultWhiteTexture.memoryHandle, null);
            defaultWhiteTexture = null;
        }

        for (VulkanBuffer buf : meshCache.values()) {
            if (buf.bufferHandle != 0L) vkDestroyBuffer(vkDevice, buf.bufferHandle, null);
            if (buf.memoryHandle != 0L) vkFreeMemory(vkDevice, buf.memoryHandle, null);
        }
        meshCache.clear();

        for (VulkanTexture tex : textureCache.values()) {
            if (tex.imageViewHandle != 0L) vkDestroyImageView(vkDevice, tex.imageViewHandle, null);
            if (tex.samplerHandle != 0L) vkDestroySampler(vkDevice, tex.samplerHandle, null);
            if (tex.imageHandle != 0L) vkDestroyImage(vkDevice, tex.imageHandle, null);
            if (tex.memoryHandle != 0L) vkFreeMemory(vkDevice, tex.memoryHandle, null);
        }
        textureCache.clear();
    }
}
