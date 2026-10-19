package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Handles command pool allocation, primary command buffer recording, and CPU/GPU sync primitives.
 */
public class VulkanPassEncoder {

    private long commandPool = 0L;
    private VkCommandBuffer commandBuffer;

    private long imageAvailableSemaphore = 0L;
    private long renderFinishedSemaphore = 0L;
    private long inFlightFence = 0L;

    public boolean initialize(VulkanDevice deviceManager) {
        if (deviceManager == null || deviceManager.getDevice() == null) return false;
        VkDevice device = deviceManager.getDevice();
        int graphicsFamily = deviceManager.getGraphicsQueueFamilyIndex();

        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateCommandPool(device, poolInfo, null, pPool) != VK_SUCCESS) {
                System.err.println("[VulkanPassEncoder] ERROR: Failed to create command pool!");
                return false;
            }
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(device, allocInfo, pBuffer) != VK_SUCCESS) {
                System.err.println("[VulkanPassEncoder] ERROR: Failed to allocate command buffer!");
                return false;
            }
            commandBuffer = new VkCommandBuffer(pBuffer.get(0), device);

            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pSem1 = stack.mallocLong(1);
            LongBuffer pSem2 = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            vkCreateSemaphore(device, semInfo, null, pSem1);
            vkCreateSemaphore(device, semInfo, null, pSem2);
            vkCreateFence(device, fenceInfo, null, pFence);

            imageAvailableSemaphore = pSem1.get(0);
            renderFinishedSemaphore = pSem2.get(0);
            inFlightFence = pFence.get(0);

            System.out.println("[VulkanPassEncoder] Command pool, buffer, and sync primitives initialized.");
            return true;
        }
    }

    public void cleanup(VulkanDevice deviceManager) {
        if (deviceManager != null) {
            shutdown(deviceManager.getDevice());
        }
    }

    public void cleanup(VkDevice device) {
        shutdown(device);
    }

    public void shutdown(VkDevice device) {
        if (device != null) {
            if (inFlightFence != 0L) vkDestroyFence(device, inFlightFence, null);
            if (renderFinishedSemaphore != 0L) vkDestroySemaphore(device, renderFinishedSemaphore, null);
            if (imageAvailableSemaphore != 0L) vkDestroySemaphore(device, imageAvailableSemaphore, null);
            if (commandPool != 0L) vkDestroyCommandPool(device, commandPool, null);
            System.out.println("[VulkanPassEncoder] Sync and command pool destroyed.");
        }
    }

    public VkCommandBuffer getCommandBuffer() { return commandBuffer; }
    public long getImageAvailableSemaphore() { return imageAvailableSemaphore; }
    public long getRenderFinishedSemaphore() { return renderFinishedSemaphore; }
    public long getInFlightFence() { return inFlightFence; }
}
