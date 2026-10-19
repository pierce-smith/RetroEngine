package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages offscreen 3D render target color & depth textures for ViewportPanel rendering.
 */
public class VulkanTargetPass {

    private long offscreenImage = 0L;
    private long offscreenMemory = 0L;
    private long offscreenImageView = 0L;

    private long depthImage = 0L;
    private long depthMemory = 0L;
    private long depthImageView = 0L;

    private int width = 712;
    private int height = 488;

    public void initFramebuffer(VulkanDevice device, int width, int height) {
        if (device == null || device.getLogicalDevice() == null) return;
        this.width = Math.max(16, width);
        this.height = Math.max(16, height);

        dispose(device);

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Offscreen Color Texture Attachment (BGRA8 / RGBA8)
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_B8G8R8A8_UNORM)
                    .extent(e -> e.width(this.width).height(this.height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) == VK_SUCCESS) {
                offscreenImage = pImage.get(0);

                VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(vkDevice, offscreenImage, memReqs);

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(memReqs.size())
                        .memoryTypeIndex(findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

                LongBuffer pMem = stack.mallocLong(1);
                if (vkAllocateMemory(vkDevice, allocInfo, null, pMem) == VK_SUCCESS) {
                    offscreenMemory = pMem.get(0);
                    vkBindImageMemory(vkDevice, offscreenImage, offscreenMemory, 0);
                }

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(offscreenImage)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK_FORMAT_B8G8R8A8_UNORM)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

                LongBuffer pView = stack.mallocLong(1);
                if (vkCreateImageView(vkDevice, viewInfo, null, pView) == VK_SUCCESS) {
                    offscreenImageView = pView.get(0);
                }
            }

            // 2. Offscreen Depth Texture Attachment (D32_SFLOAT)
            VkImageCreateInfo depthInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_D32_SFLOAT)
                    .extent(e -> e.width(this.width).height(this.height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pDepthImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, depthInfo, null, pDepthImage) == VK_SUCCESS) {
                depthImage = pDepthImage.get(0);

                VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(vkDevice, depthImage, memReqs);

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(memReqs.size())
                        .memoryTypeIndex(findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

                LongBuffer pDepthMem = stack.mallocLong(1);
                if (vkAllocateMemory(vkDevice, allocInfo, null, pDepthMem) == VK_SUCCESS) {
                    depthMemory = pDepthMem.get(0);
                    vkBindImageMemory(vkDevice, depthImage, depthMemory, 0);
                }

                VkImageViewCreateInfo depthViewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(depthImage)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK_FORMAT_D32_SFLOAT)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

                LongBuffer pDepthView = stack.mallocLong(1);
                if (vkCreateImageView(vkDevice, depthViewInfo, null, pDepthView) == VK_SUCCESS) {
                    depthImageView = pDepthView.get(0);
                }
            }
        }
        System.out.println("[VulkanTargetPass] Initialized Offscreen 3D Viewport Target (" + width + "x" + height + ").");
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
        return 0;
    }

    public void resizeViewport(VulkanDevice device, int width, int height) {
        if (width > 0 && height > 0) {
            initFramebuffer(device, width, height);
        }
    }

    public long getOffscreenColorTexture() {
        return offscreenImageView != 0L ? offscreenImageView : offscreenImage;
    }

    public long getOffscreenImageView() { return offscreenImageView; }
    public long getDepthImageView() { return depthImageView; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void cleanup(VulkanDevice device) {
        dispose(device);
    }

    public void shutdown(VulkanDevice device) {
        dispose(device);
    }

    public void dispose(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (offscreenImageView != 0L) { vkDestroyImageView(vkDevice, offscreenImageView, null); offscreenImageView = 0L; }
        if (offscreenImage != 0L) { vkDestroyImage(vkDevice, offscreenImage, null); offscreenImage = 0L; }
        if (offscreenMemory != 0L) { vkFreeMemory(vkDevice, offscreenMemory, null); offscreenMemory = 0L; }

        if (depthImageView != 0L) { vkDestroyImageView(vkDevice, depthImageView, null); depthImageView = 0L; }
        if (depthImage != 0L) { vkDestroyImage(vkDevice, depthImage, null); depthImage = 0L; }
        if (depthMemory != 0L) { vkFreeMemory(vkDevice, depthMemory, null); depthMemory = 0L; }
    }
}
