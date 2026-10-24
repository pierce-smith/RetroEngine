package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Queue;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SRP Domain Manager: Manages a 3-slot CPU-to-GPU host-visible dynamic vertex buffer ring buffer.
 * Automatically expands capacity when vertex counts exceed the current buffer size.
 */
public class VulkanRingBuffer {

    private static final int RING_SLOTS = 3;

    private final long[] bufferRing = new long[RING_SLOTS];
    private final long[] memoryRing = new long[RING_SLOTS];
    private final int[] capacityRing = new int[RING_SLOTS];

    private int ringIndex = 0;
    private int vertexCount = 0;

    public long getCurrentBufferHandle() { return bufferRing[ringIndex]; }
    public int getVertexCount() { return vertexCount; }

    public void updateBuffer(VulkanDevice device, float[] vertices, int count, int strideFloats,
                             Queue<VulkanGpuCache.DeferredVulkanResource> disposalQueue, int currentFrame) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || count <= 0) {
            this.vertexCount = 0;
            return;
        }

        VkDevice vkDevice = device.getLogicalDevice();
        int neededCap = count * strideFloats;
        long bufferSize = (long) neededCap * Float.BYTES;

        this.ringIndex = (this.ringIndex + 1) % RING_SLOTS;

        long currentBuffer = bufferRing[ringIndex];
        long currentMemory = memoryRing[ringIndex];
        int currentCap = capacityRing[ringIndex];

        try (MemoryStack stack = stackPush()) {
            if (currentBuffer == 0L || neededCap > currentCap) {
                if (currentBuffer != 0L && disposalQueue != null) {
                    synchronized (disposalQueue) {
                        disposalQueue.add(new VulkanGpuCache.DeferredVulkanResource(currentBuffer, currentMemory, currentFrame));
                    }
                }

                int newCap = Math.max(neededCap * 2, 16384);
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

                int memTypeIndex = VulkanGpuCache.findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(memReqs.size())
                        .memoryTypeIndex(memTypeIndex);

                LongBuffer pMemory = stack.mallocLong(1);
                if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) return;
                currentMemory = pMemory.get(0);

                vkBindBufferMemory(vkDevice, currentBuffer, currentMemory, 0);

                bufferRing[ringIndex] = currentBuffer;
                memoryRing[ringIndex] = currentMemory;
                capacityRing[ringIndex] = newCap;
            }

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, currentMemory, 0, bufferSize, 0, pData) == VK_SUCCESS) {
                FloatBuffer floatBuf = pData.getFloatBuffer(0, neededCap);
                floatBuf.put(vertices, 0, neededCap);
                vkUnmapMemory(vkDevice, currentMemory);
                this.vertexCount = count;
            }
        }
    }

    public void cleanup(VkDevice vkDevice) {
        for (int i = 0; i < RING_SLOTS; i++) {
            if (bufferRing[i] != 0L) {
                vkDestroyBuffer(vkDevice, bufferRing[i], null);
                vkFreeMemory(vkDevice, memoryRing[i], null);
                bufferRing[i] = 0L;
                memoryRing[i] = 0L;
                capacityRing[i] = 0;
            }
        }
    }
}
