package com.retro.engine.graphics.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages double-buffered VkSwapchainKHR allocation, surface format selection, image views, and present modes.
 * Acquire/present report VK_ERROR_OUT_OF_DATE_KHR and VK_SUBOPTIMAL_KHR through {@link #needsRecreate()}; the owner
 * then calls {@link #recreateSwapchain} once the device is idle. The extent always follows the surface's pixel size
 * (currentExtent), never the window's point size, so the compositor never has to stretch a mismatched image.
 */
public class VulkanSwapchain {

    /** Surface reports an undefined extent; the swapchain size is chosen by the application (spec sentinel). */
    private static final int EXTENT_UNDEFINED = 0xFFFFFFFF;

    private long swapchainHandle = 0L;
    private long[] swapchainImages;
    private long[] swapchainImageViews;

    private int width;
    private int height;
    private int imageFormat = VK_FORMAT_UNDEFINED;
    private int imageColorSpace;
    private boolean needsRecreate = false;
    /** Requested sync behaviour; the mode actually used depends on what the surface supports. */
    private boolean vsync = true;
    private int presentMode = -1;

    public boolean createSwapchain(VulkanDevice device, VulkanSurface surface, int width, int height) {
        return createSwapchain(device, surface != null ? surface.getSurfaceHandle() : 0L, width, height);
    }

    public boolean createSwapchain(VulkanDevice device, long surfaceHandle, int width, int height) {
        return buildSwapchain(device, surfaceHandle, width, height);
    }

    /**
     * Rebuilds the swapchain and its image views at the surface's current extent, handing the old swapchain to the
     * driver as oldSwapchain. The caller must guarantee the device no longer uses the old images (vkDeviceWaitIdle).
     * Returns false and keeps the previous swapchain when the surface has zero area (minimized window); the
     * recreate request stays pending so the next frame retries.
     */
    public boolean recreateSwapchain(VulkanDevice device, long surfaceHandle, int framebufferWidth, int framebufferHeight) {
        return buildSwapchain(device, surfaceHandle, framebufferWidth, framebufferHeight);
    }

    private boolean buildSwapchain(VulkanDevice device, long surfaceHandle, int fallbackWidth, int fallbackHeight) {
        if (device == null || device.getLogicalDevice() == null || surfaceHandle == 0L) return false;

        VkDevice vkDevice = device.getLogicalDevice();
        VkPhysicalDevice physicalDevice = device.getPhysicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surfaceHandle, capabilities);

            int extentW, extentH;
            if (capabilities.currentExtent().width() != EXTENT_UNDEFINED) {
                extentW = capabilities.currentExtent().width();
                extentH = capabilities.currentExtent().height();
            } else {
                extentW = clamp(fallbackWidth, capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                extentH = clamp(fallbackHeight, capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }
            if (extentW <= 0 || extentH <= 0) {
                needsRecreate = true;
                return false;
            }

            // The UI pipeline is baked against this format, so it is chosen once and kept across recreations
            if (imageFormat == VK_FORMAT_UNDEFINED) {
                IntBuffer pFormatCount = stack.mallocInt(1);
                KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceHandle, pFormatCount, null);
                VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(pFormatCount.get(0), stack);
                KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surfaceHandle, pFormatCount, formats);

                imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
                imageColorSpace = KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                for (int i = 0; i < formats.capacity(); i++) {
                    if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM) {
                        imageColorSpace = formats.get(i).colorSpace();
                        break;
                    }
                }
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
                imageCount = capabilities.maxImageCount();
            }

            int chosenMode = choosePresentMode(vsync, supportedPresentModes(physicalDevice, surfaceHandle, stack));
            if (chosenMode != presentMode) {
                String note = vsync ? "vsync on"
                        : chosenMode == KHRSurface.VK_PRESENT_MODE_FIFO_KHR ? "vsync off requested, but the surface supports only FIFO"
                        : "vsync off";
                System.out.println("[VulkanSwapchain] Present mode " + presentModeName(chosenMode) + " (" + note + ").");
            }

            long oldSwapchain = swapchainHandle;

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surfaceHandle)
                    .minImageCount(imageCount)
                    .imageFormat(imageFormat)
                    .imageColorSpace(imageColorSpace)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(chosenMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);

            createInfo.imageExtent().set(extentW, extentH);

            LongBuffer pSwapchain = stack.mallocLong(1);
            int res = KHRSwapchain.vkCreateSwapchainKHR(vkDevice, createInfo, null, pSwapchain);
            if (res != VK_SUCCESS) {
                System.err.println("[VulkanSwapchain] ERROR: Failed to create Vulkan swapchain! Code: " + res);
                return false;
            }

            // The old swapchain is retired even though it was passed as oldSwapchain: its views and handle go now
            destroyImageViews(vkDevice);
            if (oldSwapchain != 0L) {
                KHRSwapchain.vkDestroySwapchainKHR(vkDevice, oldSwapchain, null);
            }
            swapchainHandle = pSwapchain.get(0);
            presentMode = chosenMode;
            this.width = extentW;
            this.height = extentH;

            IntBuffer pActualCount = stack.mallocInt(1);
            KHRSwapchain.vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, pActualCount, null);
            LongBuffer pImages = stack.mallocLong(pActualCount.get(0));
            KHRSwapchain.vkGetSwapchainImagesKHR(vkDevice, swapchainHandle, pActualCount, pImages);

            if (swapchainImages == null || swapchainImages.length != pImages.capacity()) {
                swapchainImages = new long[pImages.capacity()];
                swapchainImageViews = new long[pImages.capacity()];
            }

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
                    swapchainImageViews[i] = 0L;
                    needsRecreate = true;
                    return false;
                }
                swapchainImageViews[i] = pView.get(0);
            }

            needsRecreate = false;
            System.out.println("[VulkanSwapchain] " + (oldSwapchain != 0L ? "Recreated" : "Created") + " swapchain with "
                    + swapchainImages.length + " images (" + extentW + "x" + extentH + ").");
            return true;
        }
    }

    /**
     * Picks the present mode for a vsync request. FIFO is the only mode the spec guarantees, so it is both the vsync
     * mode and the last resort. Without vsync, MAILBOX is preferred (no tearing, lowest latency), then IMMEDIATE.
     * A driver override can still force vsync on or off; RuntimeLoop's frame limiter covers the forced-off case.
     */
    static int choosePresentMode(boolean vsync, int[] supported) {
        if (vsync || supported == null) return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        boolean immediate = false;
        for (int mode : supported) {
            if (mode == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) return mode;
            if (mode == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) immediate = true;
        }
        return immediate ? KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR : KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
    }

    private static int[] supportedPresentModes(VkPhysicalDevice physicalDevice, long surfaceHandle, MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        if (KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surfaceHandle, pCount, null) != VK_SUCCESS
                || pCount.get(0) <= 0) {
            return null;
        }
        IntBuffer pModes = stack.mallocInt(pCount.get(0));
        if (KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surfaceHandle, pCount, pModes) != VK_SUCCESS) {
            return null;
        }
        int[] modes = new int[pCount.get(0)];
        pModes.get(0, modes);
        return modes;
    }

    static String presentModeName(int mode) {
        return switch (mode) {
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "mode " + mode;
        };
    }

    /** Requests vsync on or off. A change rebuilds the swapchain before the next acquire. */
    public void setVsync(boolean vsync) {
        if (this.vsync == vsync) return;
        this.vsync = vsync;
        if (swapchainHandle != 0L) needsRecreate = true;
    }

    public boolean isVsync() { return vsync; }

    /** The VkPresentModeKHR in use, or -1 before the first swapchain exists. */
    public int getPresentMode() { return presentMode; }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void destroyImageViews(VkDevice vkDevice) {
        if (swapchainImageViews == null) return;
        for (int i = 0; i < swapchainImageViews.length; i++) {
            if (swapchainImageViews[i] != 0L) vkDestroyImageView(vkDevice, swapchainImageViews[i], null);
            swapchainImageViews[i] = 0L;
        }
    }

    /** True once acquire/present reported OUT_OF_DATE or SUBOPTIMAL, or the last rebuild was deferred (zero extent). */
    public boolean needsRecreate() { return needsRecreate; }

    /** Requests a rebuild before the next acquire, e.g. from a framebuffer-size window event. */
    public void markOutOfDate() { needsRecreate = true; }

    public int acquireNextImage(VulkanDevice device, long semaphore) {
        if (device == null || device.getLogicalDevice() == null || swapchainHandle == 0L) return -1;
        try (MemoryStack stack = stackPush()) {
            IntBuffer pIndex = stack.mallocInt(1);
            int res = KHRSwapchain.vkAcquireNextImageKHR(device.getLogicalDevice(), swapchainHandle, Long.MAX_VALUE, semaphore, VK_NULL_HANDLE, pIndex);
            if (res == VK_SUCCESS) {
                return pIndex.get(0);
            }
            if (res == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                // The image is acquired and the semaphore will signal: render and present it, rebuild next frame
                needsRecreate = true;
                return pIndex.get(0);
            }
            if (res == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                // Nothing was acquired and the semaphore stays unsignaled; the frame must be skipped
                needsRecreate = true;
            } else {
                System.err.println("[VulkanSwapchain] ERROR: vkAcquireNextImageKHR failed with code: " + res);
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
            if (res == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || res == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                needsRecreate = true;
                return res == KHRSwapchain.VK_SUBOPTIMAL_KHR;
            }
            if (res != VK_SUCCESS) {
                System.err.println("[VulkanSwapchain] ERROR: vkQueuePresentKHR failed with code: " + res);
            }
            return res == VK_SUCCESS;
        }
    }

    public void cleanup(VulkanDevice device) {
        shutdown(device);
    }

    public void shutdown(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        destroyImageViews(vkDevice);
        swapchainImageViews = null;
        swapchainImages = null;
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
