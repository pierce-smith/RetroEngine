package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * SRP Domain Manager: Owns VkImage, VkImageView, VkSampler allocations,
 * texture path caching, layout transitions, and default fallback texture generation.
 */
public class VulkanTextureRegistry {

    private final Map<String, VulkanGpuCache.VulkanTexture> textureCache = Collections.synchronizedMap(new HashMap<>());
    private VulkanGpuCache.VulkanTexture defaultWhiteTexture = null;

    public VulkanGpuCache.VulkanTexture getOrCreateTexture(VulkanDevice device, String texturePath, byte[] pixels, int width, int height) {
        if (texturePath == null || pixels == null || pixels.length == 0 || width <= 0 || height <= 0) return null;

        VulkanGpuCache.VulkanTexture existing = textureCache.get(texturePath);
        if (existing != null) return existing;

        VulkanGpuCache.VulkanTexture created = createTextureFromPixels(device, pixels, width, height);
        if (created != null) {
            textureCache.put(texturePath, created);
        }
        return created;
    }

    public VulkanGpuCache.VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        if (defaultWhiteTexture != null) return defaultWhiteTexture;
        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        defaultWhiteTexture = createTextureFromPixels(device, whitePixel, 1, 1);
        return defaultWhiteTexture;
    }

    public VulkanGpuCache.VulkanTexture createTextureFromPixels(VulkanDevice device, byte[] pixels, int width, int height) {
        if (device == null || device.getLogicalDevice() == null || pixels == null || pixels.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long imageSize = (long) width * height * 4;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo stagingBufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(imageSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, stagingBufferInfo, null, pStagingBuffer) != VK_SUCCESS) return null;
            long stagingBuffer = pStagingBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, stagingBuffer, memReqs);

            int memTypeIndex = VulkanGpuCache.findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pStagingMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pStagingMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                return null;
            }
            long stagingBufferMemory = pStagingMemory.get(0);

            vkBindBufferMemory(vkDevice, stagingBuffer, stagingBufferMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, stagingBufferMemory, 0, imageSize, 0, pData);
            ByteBuffer byteBuf = pData.getByteBuffer(0, (int) imageSize);
            byteBuf.put(pixels);
            vkUnmapMemory(vkDevice, stagingBufferMemory);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .extent(extent -> extent.set(width, height, 1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            if (vkCreateImage(vkDevice, imageInfo, null, pImage) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                vkFreeMemory(vkDevice, stagingBufferMemory, null);
                return null;
            }
            long textureImage = pImage.get(0);

            VkMemoryRequirements imgMemReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, textureImage, imgMemReqs);

            int imgMemTypeIndex = VulkanGpuCache.findMemoryType(device.getPhysicalDevice(), imgMemReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

            VkMemoryAllocateInfo imgAllocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(imgMemReqs.size())
                    .memoryTypeIndex(imgMemTypeIndex);

            LongBuffer pImageMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, imgAllocInfo, null, pImageMemory) != VK_SUCCESS) {
                vkDestroyImage(vkDevice, textureImage, null);
                vkDestroyBuffer(vkDevice, stagingBuffer, null);
                vkFreeMemory(vkDevice, stagingBufferMemory, null);
                return null;
            }
            long textureImageMemory = pImageMemory.get(0);

            vkBindImageMemory(vkDevice, textureImage, textureImageMemory, 0);

            transitionImageLayout(device, textureImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyBufferToImage(device, stagingBuffer, textureImage, width, height);
            transitionImageLayout(device, textureImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            vkDestroyBuffer(vkDevice, stagingBuffer, null);
            vkFreeMemory(vkDevice, stagingBufferMemory, null);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(textureImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);

            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);
            if (vkCreateImageView(vkDevice, viewInfo, null, pImageView) != VK_SUCCESS) return null;
            long textureImageView = pImageView.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(vkDevice, samplerInfo, null, pSampler) != VK_SUCCESS) return null;
            long textureSampler = pSampler.get(0);

            return new VulkanGpuCache.VulkanTexture(textureImage, textureImageMemory, textureImageView, textureSampler);
        }
    }

    private void transitionImageLayout(VulkanDevice device, long image, int oldLayout, int newLayout) {
        try (MemoryStack stack = stackPush()) {
            VulkanGpuCache.SingleTimeCmdContext stc = VulkanGpuCache.beginSingleTimeCommands(device);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);

            barrier.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            int sourceStage;
            int destinationStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.get(0).srcAccessMask(0);
                barrier.get(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.get(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            } else {
                VulkanGpuCache.endSingleTimeCommands(device, stc);
                return;
            }

            vkCmdPipelineBarrier(stc.cmd, sourceStage, destinationStage, 0, null, null, barrier);
            VulkanGpuCache.endSingleTimeCommands(device, stc);
        }
    }

    private void copyBufferToImage(VulkanDevice device, long buffer, long image, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VulkanGpuCache.SingleTimeCmdContext stc = VulkanGpuCache.beginSingleTimeCommands(device);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);

            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);

            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(width, height, 1);

            vkCmdCopyBufferToImage(stc.cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            VulkanGpuCache.endSingleTimeCommands(device, stc);
        }
    }

    public void cleanup(VkDevice vkDevice) {
        if (vkDevice == null) return;
        for (VulkanGpuCache.VulkanTexture tex : textureCache.values()) {
            if (tex != null) {
                if (tex.imageViewHandle != 0L) vkDestroyImageView(vkDevice, tex.imageViewHandle, null);
                if (tex.samplerHandle != 0L) vkDestroySampler(vkDevice, tex.samplerHandle, null);
                if (tex.imageHandle != 0L) vkDestroyImage(vkDevice, tex.imageHandle, null);
                if (tex.memoryHandle != 0L) vkFreeMemory(vkDevice, tex.memoryHandle, null);
            }
        }
        textureCache.clear();

        if (defaultWhiteTexture != null) {
            if (defaultWhiteTexture.imageViewHandle != 0L) vkDestroyImageView(vkDevice, defaultWhiteTexture.imageViewHandle, null);
            if (defaultWhiteTexture.samplerHandle != 0L) vkDestroySampler(vkDevice, defaultWhiteTexture.samplerHandle, null);
            if (defaultWhiteTexture.imageHandle != 0L) vkDestroyImage(vkDevice, defaultWhiteTexture.imageHandle, null);
            if (defaultWhiteTexture.memoryHandle != 0L) vkFreeMemory(vkDevice, defaultWhiteTexture.memoryHandle, null);
            defaultWhiteTexture = null;
        }
    }
}
