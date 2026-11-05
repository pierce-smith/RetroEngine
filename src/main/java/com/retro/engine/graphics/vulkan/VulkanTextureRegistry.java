package com.retro.engine.graphics.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SRP Domain Manager: Creates device-local VkImage + VkImageView allocations whose texels stream in asynchronously
 * through VulkanUploadRing, plus default fallback texture generation. Sampling uses the shared material sampler in
 * the bindless descriptor set, so textures carry no per-image VkSampler. Registered textures are owned by
 * VulkanGpuCache's handle table.
 */
public class VulkanTextureRegistry {

    private VulkanGpuCache.VulkanTexture defaultWhiteTexture = null;

    public VulkanGpuCache.VulkanTexture getDefaultWhiteTexture(VulkanDevice device, VulkanUploadRing uploadRing) {
        if (defaultWhiteTexture != null) return defaultWhiteTexture;
        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        defaultWhiteTexture = createTextureFromPixels(device, uploadRing, whitePixel, 1, 1);
        return defaultWhiteTexture;
    }

    /** Returns the white texture if it already exists, without creating it. */
    public VulkanGpuCache.VulkanTexture peekDefaultWhiteTexture() { return defaultWhiteTexture; }

    /**
     * Allocates the image and view, then queues the texel upload without waiting for it. The returned texture records
     * the upload's timeline value so disposal can be deferred until the GPU has finished writing it.
     */
    public VulkanGpuCache.VulkanTexture createTextureFromPixels(VulkanDevice device, VulkanUploadRing uploadRing, byte[] pixels, int width, int height) {
        if (device == null || device.getLogicalDevice() == null || uploadRing == null || pixels == null || pixels.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        com.retro.engine.graphics.MipChain mips = com.retro.engine.graphics.MipChain.generate(pixels, width, height);

        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .extent(extent -> extent.set(width, height, 1))
                    .mipLevels(mips.levels())
                    .arrayLayers(1)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) return null;
            long textureImage = pImage.get(0);

            VkMemoryRequirements imgMemReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, textureImage, imgMemReqs);

            VkMemoryAllocateInfo imgAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(imgMemReqs.size())
                    .memoryTypeIndex(VulkanGpuCache.findMemoryType(device.getPhysicalDevice(), imgMemReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

            LongBuffer pImageMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, imgAllocInfo, null, pImageMemory) != VK_SUCCESS) {
                vkDestroyImage(vkDevice, textureImage, null);
                return null;
            }
            long textureImageMemory = pImageMemory.get(0);
            vkBindImageMemory(vkDevice, textureImage, textureImageMemory, 0);

            // Nothing has been submitted yet on failure, so the image can be destroyed immediately
            long uploadValue = uploadRing.uploadImage(textureImage, mips);
            if (uploadValue < 0L) {
                vkDestroyImage(vkDevice, textureImage, null);
                vkFreeMemory(vkDevice, textureImageMemory, null);
                return null;
            }

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(textureImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(mips.levels())
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);
            long textureImageView = (vkCreateImageView(vkDevice, viewInfo, null, pImageView) == VK_SUCCESS) ? pImageView.get(0) : 0L;

            VulkanGpuCache.VulkanTexture texture = new VulkanGpuCache.VulkanTexture(textureImage, textureImageMemory, textureImageView, 0L);
            texture.uploadValue = uploadValue;
            // A failed view still owns an image the GPU is writing; hand it back so the caller defers its destruction
            return texture;
        }
    }

    public void cleanup(VkDevice vkDevice) {
        if (vkDevice == null) return;

        if (defaultWhiteTexture != null) {
            if (defaultWhiteTexture.imageViewHandle != 0L) vkDestroyImageView(vkDevice, defaultWhiteTexture.imageViewHandle, null);
            if (defaultWhiteTexture.imageHandle != 0L) vkDestroyImage(vkDevice, defaultWhiteTexture.imageHandle, null);
            if (defaultWhiteTexture.memoryHandle != 0L) vkFreeMemory(vkDevice, defaultWhiteTexture.memoryHandle, null);
            defaultWhiteTexture = null;
        }
    }
}
