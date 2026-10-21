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

import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles VRAM allocations for 3D Mesh Vertex Buffers (VBOs), 2D Textures, and Light UBOs
 * using Vulkan 1.3 host-visible memory blocks.
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

        try (MemoryStack stack = MemoryStack.stackPush()) {
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

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_LINEAR)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) return null;
            long image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, image, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) != VK_SUCCESS) {
                vkDestroyImage(vkDevice, image, null);
                return null;
            }
            long memory = pMem.get(0);

            vkBindImageMemory(vkDevice, image, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, memory, 0, memReqs.size(), 0, pData) == VK_SUCCESS) {
                ByteBuffer byteBuf = pData.getByteBuffer(0, (int) memReqs.size());
                int copyLen = Math.min(pixels.length, byteBuf.remaining());
                byteBuf.put(pixels, 0, copyLen);
                vkUnmapMemory(vkDevice, memory);
            }

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

    public VulkanBuffer getOrCreateLightUniformBuffer(VulkanDevice device) {
        if (lightUniformBuffer != null) return lightUniformBuffer;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = 512L; // 512-byte LightBuffer UBO

        try (MemoryStack stack = MemoryStack.stackPush()) {
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

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        if (defaultWhiteTexture != null) return defaultWhiteTexture;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create 1x1 2D VkImage
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(e -> e.width(1).height(1).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_LINEAR)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) return null;
            long image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, image, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) != VK_SUCCESS) return null;
            long memory = pMem.get(0);

            vkBindImageMemory(vkDevice, image, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, memory, 0, memReqs.size(), 0, pData) == VK_SUCCESS) {
                ByteBuffer byteBuf = pData.getByteBuffer(0, (int) memReqs.size());
                if (byteBuf.remaining() >= 4) {
                    byteBuf.put((byte) 255);
                    byteBuf.put((byte) 255);
                    byteBuf.put((byte) 255);
                    byteBuf.put((byte) 255);
                }
                vkUnmapMemory(vkDevice, memory);
            }

            // 2. Create VkImageView
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pView) != VK_SUCCESS) return null;
            long imageView = pView.get(0);

            // 3. Create VkSampler
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(vkDevice, samplerInfo, null, pSampler) != VK_SUCCESS) return null;
            long sampler = pSampler.get(0);

            defaultWhiteTexture = new VulkanTexture(image, memory, imageView, sampler);
            return defaultWhiteTexture;
        }
    }

    private int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
