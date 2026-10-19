package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles VRAM allocations for 3D Mesh Vertex Buffers (VBOs) and 2D Textures using Vulkan 1.3 host-visible memory blocks.
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
