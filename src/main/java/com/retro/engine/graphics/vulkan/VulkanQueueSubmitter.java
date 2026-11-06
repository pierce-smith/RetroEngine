package com.retro.engine.graphics.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SRP Domain Manager: Encapsulates Command Pool creation, primary Command Buffer allocation and
 * frame synchronization primitives (VkSemaphore, VkFence). 3D descriptors live in VulkanGpuCache's persistent
 * bindless set, so no per-frame descriptor pool is needed.
 */
public class VulkanQueueSubmitter {

    private long commandPool = 0L;
    private VkCommandBuffer commandBuffer = null;

    private long imageAvailableSemaphore = 0L;
    // One per swapchain image: a present may still be holding the semaphore of an earlier frame, and the image index
    // is what proves that present has finished
    private long[] renderFinishedSemaphores = new long[0];
    private long inFlightFence = 0L;

    public VkCommandBuffer getCommandBuffer() { return commandBuffer; }
    public long getImageAvailableSemaphore() { return imageAvailableSemaphore; }
    public long getRenderFinishedSemaphore(int imageIndex) { return renderFinishedSemaphores[imageIndex]; }

    /** Grows the render-finished set to cover {@code imageCount} swapchain images. Spares are kept for a later resize. */
    public boolean ensureRenderFinishedSemaphores(VulkanDevice deviceManager, int imageCount) {
        if (renderFinishedSemaphores.length < imageCount) {
            renderFinishedSemaphores = java.util.Arrays.copyOf(renderFinishedSemaphores, imageCount);
        }
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < imageCount; i++) {
                if (renderFinishedSemaphores[i] != 0L) continue;
                if (vkCreateSemaphore(deviceManager.getDevice(), semaphoreInfo, null, handle) != VK_SUCCESS) return false;
                renderFinishedSemaphores[i] = handle.get(0);
            }
        }
        return true;
    }
    public long getInFlightFence() { return inFlightFence; }

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
            if (vkCreateSemaphore(vkDevice, semaphoreInfo, null, pSem1) != VK_SUCCESS) return false;

            imageAvailableSemaphore = pSem1.get(0);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(vkDevice, fenceInfo, null, pFence) != VK_SUCCESS) return false;
            inFlightFence = pFence.get(0);

            return true;
        }
    }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        vkDeviceWaitIdle(vkDevice);

        if (inFlightFence != 0L) {
            vkDestroyFence(vkDevice, inFlightFence, null);
            inFlightFence = 0L;
        }
        if (imageAvailableSemaphore != 0L) {
            vkDestroySemaphore(vkDevice, imageAvailableSemaphore, null);
            imageAvailableSemaphore = 0L;
        }
        for (long semaphore : renderFinishedSemaphores) {
            if (semaphore != 0L) vkDestroySemaphore(vkDevice, semaphore, null);
        }
        renderFinishedSemaphores = new long[0];
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
