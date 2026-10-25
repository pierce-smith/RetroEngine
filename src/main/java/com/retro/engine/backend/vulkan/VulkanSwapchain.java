package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages double-buffered VkSwapchainKHR allocation, surface format selection, image views, and present modes.
 */
public class VulkanSwapchain {

    private long swapchainHandle = 0L;
    private long[] swapchainImages;
    private long[] swapchainImageViews;

    private int width;
    private int height;
    private int imageFormat;

    public boolean createSwapchain(VulkanDevice device, VulkanSurface surface, int width, int height) {
        return createSwapchain(device, surface != null ? surface.getSurfaceHandle() : 0L, width, height);
    }

    public boolean createSwapchain(VulkanDevice device, long surfaceHandle, int width, int height) {
        if (device == null || device.getLogicalDevice() == null || surfaceHandle == 0L) return false;

        this.width = width;
        this.height = height;

        VkDevice vkDevice = device.getLogicalDevice();
        VkPhysicalDevice physicalDevice = device.getPhysicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surfaceHandle, capabilities);

            IntBuffer pFormatCount = stack.mallocInt(1);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceHandle, pFormatCount, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(pFormatCount.get(0), stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceHandle, pFormatCount, formats);

            imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
            int colorSpace = KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            for (int i = 0; i < formats.capacity(); i++) {
                if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM || formats.get(i).format() == VK_FORMAT_R8G8B8A8_UNORM) {
                    imageFormat = formats.get(i).format();
                    colorSpace = formats.get(i).colorSpace();
                    break;
                }
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
                imageCount = capabilities.maxImageCount();
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surfaceHandle)
                    .minImageCount(imageCount)
                    .imageFormat(imageFormat)
                    .imageColorSpace(colorSpace)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true);

            createInfo.imageExtent().set(width, height);

            LongBuffer pSwapchain = stack.mallocLong(1);
            if (KHRSwapchain.vkCreateSwapchainKHR(vkDevice, createInfo, null, pSwapchain) != VK_SUCCESS) {
                System.err.println("[VulkanSwapchain] ERROR: Failed to create Vulkan swapchain!");
                return false;
            }
            swapchainHandle = pSwapchain.get(0);

            IntBuffer pActualCount = stack.mallocInt(1);
            KHRSwapchain.vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, pActualCount, null);
            LongBuffer pImages = stack.mallocLong(pActualCount.get(0));
            KHRSwapchain.vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, pActualCount, pImages);

            swapchainImages = new long[pImages.capacity()];
            swapchainImageViews = new long[pImages.capacity()];

            for (int i = 0; i < pImages.capacity(); i++) {
                swapchainImages[i] = pImages.get(i);

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(swapchainImages[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(imageFormat);
                viewInfo.components().r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY).b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                LongBuffer pView = stack.mallocLong(1);
                if (vkCreateImageView(vkDevice, viewInfo, null, pView) != VK_SUCCESS) {
                    System.err.println("[VulkanSwapchain] ERROR: Failed to create image view for swapchain index " + i);
                    return false;
                }
                swapchainImageViews[i] = pView.get(0);
            }

            System.out.println("[VulkanSwapchain] Created swapchain with " + swapchainImages.length + " images (" + width + "x" + height + ").");
            return true;
        }
    }

    public int acquireNextImage(VulkanDevice device, long semaphore) {
        if (device == null || device.getLogicalDevice() == null || swapchainHandle == 0L) return -1;
        try (MemoryStack stack = stackPush()) {
            IntBuffer pIndex = stack.mallocInt(1);
            int res = KHRSwapchain.vkAcquireNextImageKHR(device.getLogicalDevice(), swapchainHandle, Long.MAX_VALUE, semaphore, VK_NULL_HANDLE, pIndex);
            if (res == VK_SUCCESS || res == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                return pIndex.get(0);
            }
        }
        return -1;
    }

    public boolean presentImage(VulkanDevice device, int imageIndex, long waitSemaphore) {
        if (device == null || device.getLogicalDevice() == null || swapchainHandle == 0L || imageIndex < 0) return false;
        try (MemoryStack stack = stackPush()) {
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchainHandle))
                    .pImageIndices(stack.ints(imageIndex));

            int res;
            synchronized (device.getQueueLock()) {
                res = KHRSwapchain.vkQueuePresentKHR(device.getPresentQueue(), presentInfo);
            }
            return (res == VK_SUCCESS);
        }
    }

    public void cleanup(VulkanDevice device) {
        shutdown(device);
    }

    public void shutdown(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (swapchainImageViews != null) {
            for (long view : swapchainImageViews) {
                if (view != 0L) vkDestroyImageView(vkDevice, view, null);
            }
            swapchainImageViews = null;
        }
        if (swapchainHandle != 0L) {
            KHRSwapchain.vkDestroySwapchainKHR(vkDevice, swapchainHandle, null);
            swapchainHandle = 0L;
        }
        System.out.println("[VulkanSwapchain] Swapchain resources destroyed.");
    }

    public long getSwapchainHandle() { return swapchainHandle; }
    public long[] getSwapchainImageViews() { return swapchainImageViews; }
    public long getImageView(int index) {
        if (swapchainImageViews != null && index >= 0 && index < swapchainImageViews.length) {
            return swapchainImageViews[index];
        }
        return 0L;
    }
    public long getSwapchainImage(int index) {
        if (swapchainImages != null && index >= 0 && index < swapchainImages.length) {
            return swapchainImages[index];
        }
        return 0L;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getExtentWidth() { return width; }
    public int getExtentHeight() { return height; }
    public int getImageFormat() { return imageFormat; }
}
