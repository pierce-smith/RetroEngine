package com.retro.engine.graphics.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages offscreen 3D Viewport Framebuffer color and depth VkImage attachments.
 * Configures the Viewport Texture Buffer for multi-role usage (Color Attachment + Shader Sampled + Transfer Src)
 * enabling seamless offscreen rendering, shader sampling, and hardware blit operations.
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
    private final int colorFormat;

    public VulkanTargetPass() { this(VK_FORMAT_B8G8R8A8_UNORM); }

    /** Target with a non-default color format (e.g. the R8G8B8A8_UINT Color-ID picking target). */
    public VulkanTargetPass(int colorFormat) { this.colorFormat = colorFormat; }

    public void initFramebuffer(VulkanDevice device, int width, int height) {
        if (device == null || device.getLogicalDevice() == null) return;
        this.width = Math.max(16, width);
        this.height = Math.max(16, height);

        dispose(device);
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Offscreen Viewport Color Attachment (ColorAttachment + Sampled + TransferSrc)
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(this.colorFormat)
                    .extent(e -> e.width(this.width).height(this.height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) {
                throw new RuntimeException("[VulkanTargetPass] Failed to create offscreen color VkImage!");
            }
            offscreenImage = pImage.get(0);

            // Render targets are rebuilt on every resize, so each gets its own allocation instead of fragmenting a block
            offscreenMemory = device.getAllocator().bindImage(offscreenImage, true);
            if (offscreenMemory == 0L) {
                throw new RuntimeException("[VulkanTargetPass] Failed to allocate offscreen color VkDeviceMemory!");
            }

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(offscreenImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.colorFormat)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pView) != VK_SUCCESS) {
                throw new RuntimeException("[VulkanTargetPass] Failed to create offscreen color VkImageView!");
            }
            offscreenImageView = pView.get(0);

            // 2. Offscreen Depth Attachment (D32_SFLOAT)
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
            if (vkCreateImage(vkDevice, depthInfo, null, pDepthImage) != VK_SUCCESS) {
                throw new RuntimeException("[VulkanTargetPass] Failed to create offscreen depth VkImage!");
            }
            depthImage = pDepthImage.get(0);

            depthMemory = device.getAllocator().bindImage(depthImage, true);
            if (depthMemory == 0L) {
                throw new RuntimeException("[VulkanTargetPass] Failed to allocate offscreen depth VkDeviceMemory!");
            }

            VkImageViewCreateInfo depthViewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(depthImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_D32_SFLOAT)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

            LongBuffer pDepthView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, depthViewInfo, null, pDepthView) != VK_SUCCESS) {
                throw new RuntimeException("[VulkanTargetPass] Failed to create offscreen depth VkImageView!");
            }
            depthImageView = pDepthView.get(0);
        }

        System.out.println("[VulkanTargetPass] Offscreen 3D Viewport Target Initialized (" + width + "x" + height + ").");
    }

    public void resizeViewport(VulkanDevice device, int newWidth, int newHeight) {
        if (newWidth > 0 && newHeight > 0 && (newWidth != width || newHeight != height)) {
            initFramebuffer(device, newWidth, newHeight);
        }
    }

    public void dispose(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (offscreenImageView != 0L) { vkDestroyImageView(vkDevice, offscreenImageView, null); offscreenImageView = 0L; }
        if (offscreenImage != 0L) { vkDestroyImage(vkDevice, offscreenImage, null); offscreenImage = 0L; }
        if (offscreenMemory != 0L) { device.getAllocator().free(offscreenMemory); offscreenMemory = 0L; }

        if (depthImageView != 0L) { vkDestroyImageView(vkDevice, depthImageView, null); depthImageView = 0L; }
        if (depthImage != 0L) { vkDestroyImage(vkDevice, depthImage, null); depthImage = 0L; }
        if (depthMemory != 0L) { device.getAllocator().free(depthMemory); depthMemory = 0L; }
    }

    public void cleanup(VulkanDevice device) { dispose(device); }

    public long getOffscreenImage() { return offscreenImage; }
    public long getOffscreenColorTexture() { return offscreenImageView != 0L ? offscreenImageView : offscreenImage; }
    public long getOffscreenImageView() { return offscreenImageView; }
    public long getDepthImage() { return depthImage; }
    public long getDepthImageView() { return depthImageView; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getColorFormat() { return colorFormat; }
}
