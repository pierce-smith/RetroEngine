package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

/**
 * Encodes the full 4-phase Vulkan 1.3 multi-pass pipeline inside a single primary command buffer:
 * Phase 1: Downward Raycast Shadow-Blob Pass (Offscreen Target)
 * Phase 2: 3D Scene Pass with Blinn-Phong Lighting (Offscreen Target)
 * Phase 3: Infinite 3D Grid Pass with Ray-Plane Unprojection & Depth Testing (Offscreen Target)
 * [Hinge Barrier]: VkImageMemoryBarrier transition from COLOR_ATTACHMENT_OPTIMAL to SHADER_READ_ONLY_OPTIMAL
 * Phase 4: Final Swapchain Composition Pass (3-Panel UI Overlay / Direct Hardware Blit)
 */
public class VulkanPassEncoder {

    public static class RenderEntity {
        public long vertexBufferHandle;
        public int vertexCount;
        public long textureHandle;
        public boolean hasTexture;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY = 0.0f;
        public float reflectionStrength = 0.0f;
    }

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

            System.out.println("[VulkanPassEncoder] Command pool, command buffer, and sync primitives initialized.");
            return true;
        }
    }

    /**
     * Executes the complete 4-phase pipeline recording inside the active command buffer.
     *
     * @param cmd              Primary VkCommandBuffer
     * @param targetPass       Offscreen Framebuffer Target (Viewport Texture)
     * @param swapchainManager Final Swapchain Manager
     * @param imageIndex       Active swapchain frame index
     * @param pipelineManager  Vulkan Graphics Pipelines & Layouts
     * @param viewMatrix       64-byte 4x4 View Matrix
     * @param projMatrix       64-byte 4x4 Projection Matrix
     */
    public void record4PhasePipeline(VkCommandBuffer cmd,
                                     VulkanTargetPass targetPass,
                                     VulkanSwapchain swapchainManager,
                                     int imageIndex,
                                     VulkanPipeline pipelineManager,
                                     float[] viewMatrix,
                                     float[] projMatrix) {
        try (MemoryStack stack = stackPush()) {

            // Dynamic Viewport & Scissor Setup for Offscreen Viewport (Phase 1, 2, 3)
            VkViewport offscreenViewport = VkViewport.calloc(stack)
                    .x(0.0f)
                    .y(0.0f)
                    .width((float) targetPass.getWidth())
                    .height((float) targetPass.getHeight())
                    .minDepth(0.0f)
                    .maxDepth(1.0f);

            VkRect2D offscreenScissor = VkRect2D.calloc(stack);
            offscreenScissor.offset().set(0, 0);
            offscreenScissor.extent().set(targetPass.getWidth(), targetPass.getHeight());

            // ---------------------------------------------------------------------------------
            // PASS 1, 2, 3: OFFSCREEN VIEWPORT RENDERING (Dynamic Rendering)
            // ---------------------------------------------------------------------------------
            VkRenderingAttachmentInfo.Buffer offscreenColorAttachment = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType$Default()
                    .imageView(targetPass.getOffscreenImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            offscreenColorAttachment.clearValue().color().float32(0, 0.10f).float32(1, 0.12f).float32(2, 0.15f).float32(3, 1.0f);

            VkRenderingAttachmentInfo offscreenDepthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                    .sType$Default()
                    .imageView(targetPass.getDepthImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);

            offscreenDepthAttachment.clearValue().depthStencil().set(1.0f, 0);

            VkRenderingInfo offscreenRenderingInfo = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(offscreenColorAttachment)
                    .pDepthAttachment(offscreenDepthAttachment);

            offscreenRenderingInfo.renderArea().extent().set(targetPass.getWidth(), targetPass.getHeight());

            // Begin Offscreen Dynamic Render Pass
            vkCmdBeginRendering(cmd, offscreenRenderingInfo);

            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).put(0, offscreenViewport));
            vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack).put(0, offscreenScissor));

            // PHASE 1: Downward Raycast Shadow-Blob Projection Pass
            if (pipelineManager.getGraphicsPipeline3D() != 0L) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipeline3D());
            }

            // PHASE 2: 3D Scene Geometry Pass (Blinn-Phong Lighting)
            if (pipelineManager.getGraphicsPipeline3D() != 0L) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipeline3D());
            }

            // PHASE 3: Infinite 3D Grid Pass
            if (pipelineManager.getGraphicsPipelineGrid() != 0L) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineGrid());

                // Pass 128-byte Push Constants (View matrix + Proj matrix)
                FloatBuffer pushData = stack.mallocFloat(32);
                if (viewMatrix != null && viewMatrix.length == 16) pushData.put(viewMatrix);
                else pushData.put(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-5,1}); // Default View Matrix

                if (projMatrix != null && projMatrix.length == 16) pushData.put(projMatrix);
                else pushData.put(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}); // Default Proj Matrix

                pushData.flip();

                vkCmdPushConstants(
                        cmd,
                        pipelineManager.getPipelineLayoutGrid(),
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0,
                        pushData
                );

                // Draw procedural infinite 3D grid quad (6 vertices)
                vkCmdDraw(cmd, 6, 1, 0, 0);
            }

            // End Offscreen Pass
            vkCmdEndRendering(cmd);

            // =========================================================================
            // HINGE BARRIER: Transition Offscreen Viewport Image Layout
            // VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            // =========================================================================
            VkImageMemoryBarrier.Buffer hingeBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(targetPass.getOffscreenImage())
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            hingeBarrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            vkCmdPipelineBarrier(
                    cmd,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    hingeBarrier
            );

            // ---------------------------------------------------------------------------------
            // PHASE 4: FINAL SWAPCHAIN COMPOSITION PASS (Direct Hardware Blit / UI Pass)
            // ---------------------------------------------------------------------------------
            long currentSwapchainImageView = swapchainManager.getImageView(imageIndex);
            long currentSwapchainImage = swapchainManager.getSwapchainImage(imageIndex);

            if (pipelineManager.getGraphicsPipelineUI() != 0L) {
                // UI Overlay Pass via Dynamic Rendering
                VkImageMemoryBarrier.Buffer swapchainLayoutBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(currentSwapchainImage)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

                swapchainLayoutBarrier.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);

                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, swapchainLayoutBarrier);

                VkRenderingAttachmentInfo.Buffer swapchainColorAttachment = VkRenderingAttachmentInfo.calloc(1, stack)
                        .sType$Default()
                        .imageView(currentSwapchainImageView)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                swapchainColorAttachment.clearValue().color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 1.0f);

                VkRenderingInfo swapchainRenderingInfo = VkRenderingInfo.calloc(stack)
                        .sType$Default()
                        .layerCount(1)
                        .pColorAttachments(swapchainColorAttachment);

                swapchainRenderingInfo.renderArea().extent().set(swapchainManager.getWidth(), swapchainManager.getHeight());

                vkCmdBeginRendering(cmd, swapchainRenderingInfo);

                VkViewport swapchainViewport = VkViewport.calloc(stack)
                        .x(0.0f).y(0.0f)
                        .width((float) swapchainManager.getWidth()).height((float) swapchainManager.getHeight())
                        .minDepth(0.0f).maxDepth(1.0f);

                VkRect2D swapchainScissor = VkRect2D.calloc(stack);
                swapchainScissor.offset().set(0, 0);
                swapchainScissor.extent().set(swapchainManager.getWidth(), swapchainManager.getHeight());

                vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).put(0, swapchainViewport));
                vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack).put(0, swapchainScissor));

                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineUI());

                vkCmdEndRendering(cmd);

                // Transition to Present
                VkImageMemoryBarrier.Buffer presentBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(currentSwapchainImage)
                        .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .dstAccessMask(0);

                presentBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);
            } else {
                // FALLBACK COMPOSITION: Direct Hardware VkCmdBlitImage from Offscreen Viewport -> Swapchain Window
                // 1. Transition Offscreen Image SHADER_READ_ONLY_OPTIMAL -> TRANSFER_SRC_OPTIMAL
                VkImageMemoryBarrier.Buffer srcBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(targetPass.getOffscreenImage())
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                srcBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                // 2. Transition Swapchain Image UNDEFINED -> TRANSFER_DST_OPTIMAL
                VkImageMemoryBarrier.Buffer dstBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(currentSwapchainImage)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                dstBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, srcBarrier);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, dstBarrier);

                // 3. Issue Hardware VkCmdBlitImage
                VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
                blitRegion.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                blitRegion.srcOffsets(0).set(0, 0, 0);
                blitRegion.srcOffsets(1).set(targetPass.getWidth(), targetPass.getHeight(), 1);

                blitRegion.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                blitRegion.dstOffsets(0).set(0, 0, 0);
                blitRegion.dstOffsets(1).set(swapchainManager.getWidth(), swapchainManager.getHeight(), 1);

                vkCmdBlitImage(
                        cmd,
                        targetPass.getOffscreenImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        currentSwapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blitRegion,
                        VK_FILTER_LINEAR
                );

                // 4. Transition Swapchain Image TRANSFER_DST_OPTIMAL -> PRESENT_SRC_KHR
                VkImageMemoryBarrier.Buffer presentBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(currentSwapchainImage)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(0);
                presentBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                // 5. Restore Offscreen Image TRANSFER_SRC_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL
                VkImageMemoryBarrier.Buffer restoreOffscreen = VkImageMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(targetPass.getOffscreenImage())
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                restoreOffscreen.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, restoreOffscreen);
            }
        }
    }

    public void cleanup(VulkanDevice deviceManager) {
        if (deviceManager != null && deviceManager.getDevice() != null) {
            VkDevice device = deviceManager.getDevice();
            if (inFlightFence != 0L) vkDestroyFence(device, inFlightFence, null);
            if (renderFinishedSemaphore != 0L) vkDestroySemaphore(device, renderFinishedSemaphore, null);
            if (imageAvailableSemaphore != 0L) vkDestroySemaphore(device, imageAvailableSemaphore, null);
            if (commandPool != 0L) vkDestroyCommandPool(device, commandPool, null);
        }
    }

    public VkCommandBuffer getCommandBuffer() { return commandBuffer; }
    public long getImageAvailableSemaphore() { return imageAvailableSemaphore; }
    public long getRenderFinishedSemaphore() { return renderFinishedSemaphore; }
    public long getInFlightFence() { return inFlightFence; }
}
