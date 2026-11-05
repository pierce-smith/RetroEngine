package com.retro.engine.graphics.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * SRP Domain Manager: Asynchronous texture uploads through double-buffered, persistently mapped staging buffers.
 * Each slot owns a staging buffer and a command pool; slots are recycled through a single timeline semaphore whose
 * value increments once per submitted upload. The CPU only ever blocks when it reuses a slot whose previous transfer
 * is still executing (two uploads back), never on the transfer it just submitted, and never with vkQueueWaitIdle.
 * Uploads share the graphics queue, so frame submissions that follow them in queue order observe the uploaded
 * texels through the upload's synchronization2 barriers; VulkanBackend additionally waits on the timeline value.
 */
public class VulkanUploadRing {

    public static final int SLOT_COUNT = 2;
    private static final long WAIT_TIMEOUT_NS = 5_000_000_000L;
    private static final long STAGING_FLOOR_BYTES = 1L << 20; // 1 MiB floor so small textures never thrash reallocation
    // A slot grown past this by one huge texture is shrunk back once uploads are 4x smaller, instead of pinning
    // hundreds of MiB of host-visible memory for the rest of the session
    private static final long STAGING_SHRINK_THRESHOLD_BYTES = 16L << 20;

    private static final class Slot {
        long commandPool;
        VkCommandBuffer cmd;
        long stagingBuffer;
        long stagingMemory;
        long mappedAddress;
        long capacity;
        long retireValue; // timeline value that signals when this slot's last upload finished
    }

    private final Slot[] slots = new Slot[SLOT_COUNT];
    private VulkanDevice device;
    private long timelineSemaphore = 0L;
    private volatile long submittedValue = 0L;
    private int nextSlot = 0;

    public boolean initialize(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return false;
        this.device = device;
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType$Default()
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0L);
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(typeInfo.address());

            LongBuffer pSemaphore = stack.mallocLong(1);
            if (vkCreateSemaphore(vkDevice, semaphoreInfo, null, pSemaphore) != VK_SUCCESS) return false;
            timelineSemaphore = pSemaphore.get(0);

            for (int i = 0; i < SLOT_COUNT; i++) {
                Slot slot = new Slot();
                slots[i] = slot;

                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                        .queueFamilyIndex(device.getGraphicsQueueFamilyIndex());
                LongBuffer pPool = stack.mallocLong(1);
                if (vkCreateCommandPool(vkDevice, poolInfo, null, pPool) != VK_SUCCESS) return false;
                slot.commandPool = pPool.get(0);

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(slot.commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                PointerBuffer pCmd = stack.mallocPointer(1);
                if (vkAllocateCommandBuffers(vkDevice, allocInfo, pCmd) != VK_SUCCESS) return false;
                slot.cmd = new VkCommandBuffer(pCmd.get(0), vkDevice);
            }
        }
        return true;
    }

    /** Timeline semaphore signaled by uploads; frame submissions wait on {@link #getSubmittedValue()}. */
    public long getTimelineSemaphore() { return timelineSemaphore; }

    /** Highest timeline value any submitted upload will signal (0 before the first upload). */
    public long getSubmittedValue() { return submittedValue; }

    /** Non-blocking: true once the GPU has finished the upload that signals {@code value}. */
    public boolean isComplete(long value) {
        if (value <= 0L || timelineSemaphore == 0L) return true;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pValue = stack.mallocLong(1);
            return vkGetSemaphoreCounterValue(device.getLogicalDevice(), timelineSemaphore, pValue) == VK_SUCCESS
                    && pValue.get(0) >= value;
        }
    }

    /**
     * Copies every level of {@code mips} into {@code image} (created in UNDEFINED layout) and leaves it in
     * SHADER_READ_ONLY_OPTIMAL. Returns the timeline value that signals completion, or -1 on failure.
     * Thread-safe; uploads from concurrent streamer threads are serialized through the ring.
     */
    public synchronized long uploadImage(long image, com.retro.engine.graphics.MipChain mips) {
        if (timelineSemaphore == 0L || image == 0L || mips == null) return -1L;
        byte[] pixels = mips.pixels;
        long size = pixels.length;

        VkDevice vkDevice = device.getLogicalDevice();
        Slot slot = slots[nextSlot];
        nextSlot = (nextSlot + 1) % SLOT_COUNT;

        // Double buffering: only wait if this slot's previous transfer (two uploads ago) is still on the GPU
        if (!waitForValue(slot.retireValue)) return -1L;
        if (!ensureCapacity(slot, size)) return -1L;

        // Host-coherent mapping: the write is made visible to the device by the queue submission itself
        MemoryUtil.memByteBuffer(slot.mappedAddress, (int) size).put(pixels, 0, (int) size);

        try (MemoryStack stack = stackPush()) {
            vkResetCommandPool(vkDevice, slot.commandPool, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            if (vkBeginCommandBuffer(slot.cmd, beginInfo) != VK_SUCCESS) return -1L;

            VulkanPassEncoder.imageBarrier(slot.cmd, stack, image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(mips.levels(), stack);
            for (int level = 0; level < mips.levels(); level++) {
                VkBufferImageCopy copy = region.get(level);
                copy.bufferOffset(mips.offsets[level]);
                copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
                copy.imageExtent().set(mips.widths[level], mips.heights[level], 1);
            }
            vkCmdCopyBufferToImage(slot.cmd, slot.stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            // Second scope covers every later submission on this queue, including the frames that sample the texture
            VulkanPassEncoder.imageBarrier(slot.cmd, stack, image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            return submit(slot, stack);
        }
    }

    /**
     * Copies {@code size} bytes, written to the staging memory by {@code fill}, into a device-local vertex or index
     * buffer. Returns the timeline value that signals completion, or -1 on failure. Thread-safe, like {@link #uploadImage}.
     */
    public synchronized long uploadBuffer(long buffer, long size, java.util.function.LongConsumer fill) {
        if (timelineSemaphore == 0L || buffer == 0L || size <= 0L) return -1L;

        VkDevice vkDevice = device.getLogicalDevice();
        Slot slot = slots[nextSlot];
        nextSlot = (nextSlot + 1) % SLOT_COUNT;
        if (!waitForValue(slot.retireValue)) return -1L;
        if (!ensureCapacity(slot, size)) return -1L;
        fill.accept(slot.mappedAddress);

        try (MemoryStack stack = stackPush()) {
            vkResetCommandPool(vkDevice, slot.commandPool, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            if (vkBeginCommandBuffer(slot.cmd, beginInfo) != VK_SUCCESS) return -1L;

            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).size(size);
            vkCmdCopyBuffer(slot.cmd, slot.stagingBuffer, buffer, region);

            // Second scope covers every later submission on this queue, including the frames that draw the mesh
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT | VK_PIPELINE_STAGE_2_INDEX_INPUT_BIT | VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_2_INDEX_READ_BIT | VK_ACCESS_2_SHADER_READ_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(slot.cmd, dependency);

            return submit(slot, stack);
        }
    }

    /** Ends the slot's command buffer and submits it, signalling the next timeline value. Returns it, or -1 on failure. */
    private long submit(Slot slot, MemoryStack stack) {
        if (vkEndCommandBuffer(slot.cmd) != VK_SUCCESS) return -1L;

        long signalValue = submittedValue + 1L;
        VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
                .sType$Default()
                .commandBuffer(slot.cmd);
        VkSemaphoreSubmitInfo.Buffer signalInfo = VkSemaphoreSubmitInfo.calloc(1, stack)
                .sType$Default()
                .semaphore(timelineSemaphore)
                .value(signalValue)
                .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
        VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack)
                .sType$Default()
                .pCommandBufferInfos(cmdInfo)
                .pSignalSemaphoreInfos(signalInfo);

        int result;
        synchronized (device.getQueueLock()) {
            result = vkQueueSubmit2(device.getGraphicsQueue(), submitInfo, VK_NULL_HANDLE);
        }
        if (result != VK_SUCCESS) {
            System.err.println("[VulkanUploadRing] ERROR: vkQueueSubmit2 failed for an upload: " + result);
            return -1L;
        }

        slot.retireValue = signalValue;
        submittedValue = signalValue;
        return signalValue;
    }

    private boolean waitForValue(long value) {
        if (value <= 0L) return true;
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
                    .sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(timelineSemaphore))
                    .pValues(stack.longs(value));
            int result = vkWaitSemaphores(device.getLogicalDevice(), waitInfo, WAIT_TIMEOUT_NS);
            if (result != VK_SUCCESS) {
                System.err.println("[VulkanUploadRing] ERROR: Timed out waiting for staging slot (timeline value " + value + ", result " + result + ").");
                return false;
            }
            return true;
        }
    }

    /**
     * Grows the slot's staging buffer to at least {@code size}, or shrinks an oversized one; only called once the slot's
     * GPU work has retired.
     */
    private boolean ensureCapacity(Slot slot, long size) {
        boolean oversized = slot.capacity > STAGING_SHRINK_THRESHOLD_BYTES && slot.capacity > size * 4L;
        if (slot.capacity >= size && !oversized) return true;
        VkDevice vkDevice = device.getLogicalDevice();
        destroyStaging(vkDevice, slot);

        long capacity = Math.max(size, STAGING_FLOOR_BYTES);
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return false;
            slot.stagingBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, slot.stagingBuffer, memReqs);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(VulkanGpuCache.findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) {
                destroyStaging(vkDevice, slot);
                return false;
            }
            slot.stagingMemory = pMemory.get(0);
            vkBindBufferMemory(vkDevice, slot.stagingBuffer, slot.stagingMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, slot.stagingMemory, 0, capacity, 0, pData) != VK_SUCCESS) {
                destroyStaging(vkDevice, slot);
                return false;
            }
            slot.mappedAddress = pData.get(0);
            slot.capacity = capacity;
            return true;
        }
    }

    private static void destroyStaging(VkDevice vkDevice, Slot slot) {
        if (slot.stagingMemory != 0L && slot.mappedAddress != 0L) vkUnmapMemory(vkDevice, slot.stagingMemory);
        if (slot.stagingBuffer != 0L) vkDestroyBuffer(vkDevice, slot.stagingBuffer, null);
        if (slot.stagingMemory != 0L) vkFreeMemory(vkDevice, slot.stagingMemory, null);
        slot.stagingBuffer = 0L;
        slot.stagingMemory = 0L;
        slot.mappedAddress = 0L;
        slot.capacity = 0L;
    }

    /** Waits for every outstanding upload, then releases all ring resources. */
    public synchronized void cleanup(VkDevice vkDevice) {
        if (vkDevice == null) return;
        waitForValue(submittedValue);
        for (int i = 0; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            destroyStaging(vkDevice, slot);
            if (slot.commandPool != 0L) vkDestroyCommandPool(vkDevice, slot.commandPool, null);
            slots[i] = null;
        }
        if (timelineSemaphore != 0L) {
            vkDestroySemaphore(vkDevice, timelineSemaphore, null);
            timelineSemaphore = 0L;
        }
    }
}
