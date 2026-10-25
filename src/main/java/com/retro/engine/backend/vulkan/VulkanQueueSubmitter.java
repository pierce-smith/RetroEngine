package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SRP Domain Manager: Encapsulates Command Pool creation, primary Command Buffer allocation,
 * frame synchronization primitives (VkSemaphore, VkFence), and integrated VkDescriptorPool management.
 */
public class VulkanQueueSubmitter {

    private long commandPool = 0L;
    private VkCommandBuffer commandBuffer = null;

    private long imageAvailableSemaphore = 0L;
    private long renderFinishedSemaphore = 0L;
    private long inFlightFence = 0L;

    private long descriptorPool = 0L;

    public VkCommandBuffer getCommandBuffer() { return commandBuffer; }
    public long getImageAvailableSemaphore() { return imageAvailableSemaphore; }
    public long getRenderFinishedSemaphore() { return renderFinishedSemaphore; }
    public long getInFlightFence() { return inFlightFence; }
    public long getDescriptorPoolHandle() { return descriptorPool; }

    public boolean initialize(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return false;

        VkDevice vkDevice = device.getLogicalDevice();
        int queueFamily = device.getGraphicsQueueFamilyIndex();

        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);

            LongBuffer pCmdPool = stack.mallocLong(1);
            if (vkCreateCommandPool(vkDevice, poolInfo, null, pCmdPool) != VK_SUCCESS) return false;
            commandPool = pCmdPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCmdBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(vkDevice, allocInfo, pCmdBuffer) != VK_SUCCESS) return false;
            commandBuffer = new VkCommandBuffer(pCmdBuffer.get(0), vkDevice);

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer pSem1 = stack.mallocLong(1);
            LongBuffer pSem2 = stack.mallocLong(1);
            if (vkCreateSemaphore(vkDevice, semaphoreInfo, null, pSem1) != VK_SUCCESS ||
                vkCreateSemaphore(vkDevice, semaphoreInfo, null, pSem2) != VK_SUCCESS) return false;

            imageAvailableSemaphore = pSem1.get(0);
            renderFinishedSemaphore = pSem2.get(0);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(vkDevice, fenceInfo, null, pFence) != VK_SUCCESS) return false;
            inFlightFence = pFence.get(0);

            // Integrated VkDescriptorPool Allocation
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1024);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2048);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);

            VkDescriptorPoolCreateInfo descPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1024)
                    .pPoolSizes(poolSizes);

            LongBuffer pDescPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(vkDevice, descPoolInfo, null, pDescPool) != VK_SUCCESS) return false;
            descriptorPool = pDescPool.get(0);

            return true;
        }
    }

    public void resetDescriptorPool(VkDevice device) {
        if (device != null && descriptorPool != 0L) {
            vkResetDescriptorPool(device, descriptorPool, 0);
        }
    }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        vkDeviceWaitIdle(vkDevice);

        if (descriptorPool != 0L) {
            vkDestroyDescriptorPool(vkDevice, descriptorPool, null);
            descriptorPool = 0L;
        }
        if (inFlightFence != 0L) {
            vkDestroyFence(vkDevice, inFlightFence, null);
            inFlightFence = 0L;
        }
        if (imageAvailableSemaphore != 0L) {
            vkDestroySemaphore(vkDevice, imageAvailableSemaphore, null);
            imageAvailableSemaphore = 0L;
        }
        if (renderFinishedSemaphore != 0L) {
            vkDestroySemaphore(vkDevice, renderFinishedSemaphore, null);
            renderFinishedSemaphore = 0L;
        }
        if (commandPool != 0L) {
            if (commandBuffer != null) {
                vkFreeCommandBuffers(vkDevice, commandPool, commandBuffer);
                commandBuffer = null;
            }
            vkResetCommandPool(vkDevice, commandPool, VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT);
            vkDestroyCommandPool(vkDevice, commandPool, null);
            commandPool = 0L;
        }
    }
}
