package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages VkDescriptorPool allocations for Vulkan 1.3 frame-based descriptor set allocations.
 * Allows resetting the pool every frame via vkResetDescriptorPool to prevent VK_ERROR_OUT_OF_POOL_MEMORY_KHR.
 */
public class VulkanDescriptorPool {

    private long descriptorPool = 0L;

    public boolean initialize(VkDevice device) {
        if (device == null) return false;

        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);

            // Pool Size 0: Uniform Buffers
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(1024);

            // Pool Size 1: Combined Image Samplers
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(2048);

            // Pool Size 2: Storage Buffers
            poolSizes.get(2)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(512);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1024)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK_SUCCESS) {
                System.err.println("[VulkanDescriptorPool] ERROR: Failed to create VkDescriptorPool!");
                return false;
            }
            descriptorPool = pPool.get(0);
            System.out.println("[VulkanDescriptorPool] Created VkDescriptorPool [0x" + Long.toHexString(descriptorPool) + "] capacity 1024 sets.");
            return true;
        }
    }

    public void resetPool(VkDevice device) {
        if (device != null && descriptorPool != 0L) {
            vkResetDescriptorPool(device, descriptorPool, 0);
        }
    }

    public long getHandle() {
        return descriptorPool;
    }

    public void cleanup(VkDevice device) {
        if (device != null && descriptorPool != 0L) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0L;
        }
    }
}
