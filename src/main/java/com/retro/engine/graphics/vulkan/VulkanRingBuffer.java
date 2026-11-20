package com.retro.engine.graphics.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

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

        this.ringIndex = (this.ringIndex + 1) % RING_SLOTS;

        long currentBuffer = bufferRing[ringIndex];
        long currentMemory = memoryRing[ringIndex];
        int currentCap = capacityRing[ringIndex];

        try (MemoryStack stack = stackPush()) {
            if (currentBuffer == 0L || neededCap > currentCap) {
                int newCap = Math.max(neededCap * 2, 16384);
                long newSize = (long) newCap * Float.BYTES;

                VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(newSize)
                        .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

                // Build the replacement before retiring the old buffer: on failure the slot keeps a live buffer
                // instead of naming one already queued for destruction (which a later grow would queue again)
                LongBuffer pBuffer = stack.mallocLong(1);
                if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                    this.vertexCount = 0;
                    return;
                }
                long newBuffer = pBuffer.get(0);

                long newMemory = device.getAllocator().bindBuffer(newBuffer,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                if (newMemory == 0L) {
                    vkDestroyBuffer(vkDevice, newBuffer, null);
                    this.vertexCount = 0;
                    return;
                }

                if (currentBuffer != 0L && disposalQueue != null) {
                    synchronized (disposalQueue) {
                        disposalQueue.add(new VulkanGpuCache.DeferredVulkanResource(currentBuffer, currentMemory, currentFrame));
                    }
                }

                currentBuffer = newBuffer;
                currentMemory = newMemory;
                bufferRing[ringIndex] = currentBuffer;
                memoryRing[ringIndex] = currentMemory;
                capacityRing[ringIndex] = newCap;
            }

            long mapped = device.getAllocator().mappedAddress(currentMemory);
            if (mapped != 0L) {
                MemoryUtil.memFloatBuffer(mapped, neededCap).put(vertices, 0, neededCap);
                this.vertexCount = count;
            } else {
                this.vertexCount = 0;
            }
        }
    }

    public void cleanup(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();
        for (int i = 0; i < RING_SLOTS; i++) {
            if (bufferRing[i] != 0L) {
                vkDestroyBuffer(vkDevice, bufferRing[i], null);
                device.getAllocator().free(memoryRing[i]);
                bufferRing[i] = 0L;
                memoryRing[i] = 0L;
                capacityRing[i] = 0;
            }
        }
    }
}
