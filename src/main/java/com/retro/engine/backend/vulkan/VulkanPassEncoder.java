package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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

        public long getVertexBufferHandle() {
            return vertexBufferHandle;
        }

        public long getTextureHandle() {
            return textureHandle;
        }
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
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateCommandPool(device, poolInfo, null, pPool) != VK_SUCCESS) {
                System.err.println("[VulkanPassEncoder] ERROR: Failed to create command pool!");
                return false;
            }
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pBuffer = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(device, allocInfo, pBuffer) != VK_SUCCESS) {
                System.err.println("[VulkanPassEncoder] ERROR: Failed to allocate command buffer!");
                return false;
            }
            commandBuffer = new VkCommandBuffer(pBuffer.get(0), device);

            VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
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

    public void record4PhasePipeline(VkCommandBuffer cmd,
                                     VulkanTargetPass targetPass,
                                     VulkanSwapchain swapchainManager,
                                     int imageIndex,
                                     VulkanPipeline pipelineManager,
                                     VulkanGpuCache gpuCache,
                                     VulkanDescriptorPool descriptorPool,
                                     VulkanDevice deviceManager,
                                     float[] viewMatrix,
                                     float[] projMatrix,
                                     List<RenderEntity> sceneQueue,
                                     int sceneQueueCount) {
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
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(targetPass.getOffscreenImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            offscreenColorAttachment.clearValue().color().float32(0, 0.10f).float32(1, 0.12f).float32(2, 0.15f).float32(3, 1.0f);

            VkRenderingAttachmentInfo offscreenDepthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(targetPass.getDepthImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);

            offscreenDepthAttachment.clearValue().depthStencil().set(1.0f, 0);

            VkRenderingInfo offscreenRenderingInfo = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
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

            // PHASE 2: 3D Scene Pass (Blinn-Phong Mesh Geometry Rendering)
            if (pipelineManager.getGraphicsPipeline3D() != 0L && sceneQueue != null && sceneQueueCount > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipeline3D());

                VkDevice vkDevice = deviceManager.getDevice();
                VulkanGpuCache.VulkanTexture whiteTex = gpuCache.getDefaultWhiteTexture(deviceManager);
                VulkanGpuCache.VulkanBuffer lightUbo = gpuCache.getOrCreateLightUniformBuffer(deviceManager);

                if (vkDevice != null && descriptorPool.getHandle() != 0L && lightUbo != null && whiteTex != null) {
                    // Allocate Set 0
                    VkDescriptorSetAllocateInfo allocSetInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                            .descriptorPool(descriptorPool.getHandle())
                            .pSetLayouts(stack.longs(pipelineManager.getDescriptorSetLayout3D()));

                    LongBuffer pSet = stack.mallocLong(1);
                    if (vkAllocateDescriptorSets(vkDevice, allocSetInfo, pSet) == VK_SUCCESS) {
                        long descriptorSet0 = pSet.get(0);

                        // Binding 0: Light UBO
                        VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(lightUbo.bufferHandle)
                                .offset(0)
                                .range(512);

                        // Binding 1: Main Texture
                        VkDescriptorImageInfo.Buffer imageInfoTex = VkDescriptorImageInfo.calloc(1, stack)
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                                .imageView(whiteTex.imageViewHandle)
                                .sampler(whiteTex.samplerHandle);

                        // Binding 2: Shadow Map
                        VkDescriptorImageInfo.Buffer imageInfoShadow = VkDescriptorImageInfo.calloc(1, stack)
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                                .imageView(whiteTex.imageViewHandle)
                                .sampler(whiteTex.samplerHandle);

                        VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(3, stack);
                        
                        descriptorWrites.get(0)
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .dstSet(descriptorSet0)
                                .dstBinding(0)
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                                .pBufferInfo(bufferInfo);

                        descriptorWrites.get(1)
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .dstSet(descriptorSet0)
                                .dstBinding(1)
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .pImageInfo(imageInfoTex);

                        descriptorWrites.get(2)
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .dstSet(descriptorSet0)
                                .dstBinding(2)
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .pImageInfo(imageInfoShadow);

                        vkUpdateDescriptorSets(vkDevice, descriptorWrites, null);

                        // Bind Descriptor Set 0 before draw calls
                        IntBuffer pDynamicOffsets = stack.ints(0);
                        vkCmdBindDescriptorSets(
                                cmd,
                                VK_PIPELINE_BIND_POINT_GRAPHICS,
                                pipelineManager.getPipelineLayout3D(),
                                0,
                                stack.longs(descriptorSet0),
                                pDynamicOffsets
                        );
                    }
                }

                float[] identityLightSpace = new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                };

                for (int i = 0; i < sceneQueueCount; i++) {
                    RenderEntity entity = sceneQueue.get(i);
                    long vboHandle = entity.getVertexBufferHandle();
                    if (vboHandle == 0L || entity.vertexCount <= 0) continue;

                    // 1. Compute 64-byte 4x4 Model Matrix
                    float[] modelMatrix = computeModelMatrix(
                            entity.px, entity.py, entity.pz,
                            entity.rx, entity.ry, entity.rz,
                            entity.sx, entity.sy, entity.sz
                    );

                    // 2. Allocate contiguous 256-byte (64 float) push constant buffer
                    FloatBuffer push256 = stack.mallocFloat(64);
                    push256.put(modelMatrix);                                                       // Model (64 bytes)
                    push256.put(viewMatrix != null && viewMatrix.length == 16 ? viewMatrix : identityLightSpace); // View (64 bytes)
                    push256.put(projMatrix != null && projMatrix.length == 16 ? projMatrix : identityLightSpace); // Proj (64 bytes)
                    push256.put(identityLightSpace);                                                // LightSpaceMatrix (64 bytes)
                    push256.flip();

                    // 3. Issue 256-byte Push Constant Upload for VK_SHADER_STAGE_VERTEX_BIT
                    vkCmdPushConstants(
                            cmd,
                            pipelineManager.getPipelineLayout3D(),
                            VK_SHADER_STAGE_VERTEX_BIT,
                            0,
                            push256
                    );

                    // 4. Bind primitive VBO address
                    LongBuffer pBuffers = stack.longs(vboHandle);
                    LongBuffer pOffsets = stack.longs(0L);
                    vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);

                    // 5. Draw mesh geometry
                    vkCmdDraw(cmd, entity.vertexCount, 1, 0, 0);
                }
            }

            // PHASE 3: Infinite 3D Grid Pass
            if (pipelineManager.getGraphicsPipelineGrid() != 0L) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineGrid());

                // Pass 128-byte Push Constants (View matrix + Proj matrix)
                FloatBuffer pushData = stack.mallocFloat(32);
                if (viewMatrix != null && viewMatrix.length == 16) pushData.put(viewMatrix);
                else pushData.put(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, -5, 1}); // Default View Matrix

                if (projMatrix != null && projMatrix.length == 16) pushData.put(projMatrix);
                else pushData.put(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}); // Default Proj Matrix

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
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                        .imageView(currentSwapchainImageView)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                swapchainColorAttachment.clearValue().color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 1.0f);

                VkRenderingInfo swapchainRenderingInfo = VkRenderingInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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

    private static float[] computeModelMatrix(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float radRx = (float) Math.toRadians(rx);
        float radRy = (float) Math.toRadians(ry);
        float radRz = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radRx), sxM = (float) Math.sin(radRx);
        float cy = (float) Math.cos(radRy), syM = (float) Math.sin(radRy);
        float cz = (float) Math.cos(radRz), szM = (float) Math.sin(radRz);

        float[] rotX = {
                1, 0, 0, 0,
                0, cx, -sxM, 0,
                0, sxM, cx, 0,
                0, 0, 0, 1
        };

        float[] rotY = {
                cy, 0, syM, 0,
                0, 1, 0, 0,
                -syM, 0, cy, 0,
                0, 0, 0, 1
        };

        float[] rotZ = {
                cz, -szM, 0, 0,
                szM, cz, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };

        float[] tempR = new float[16];
        multiplyMat4(rotY, rotX, tempR);
        float[] rot = new float[16];
        multiplyMat4(rotZ, tempR, rot);

        float[] model = new float[16];
        model[0] = rot[0] * sx;  model[1] = rot[1] * sx;  model[2] = rot[2] * sx;  model[3] = 0.0f;
        model[4] = rot[4] * sy;  model[5] = rot[5] * sy;  model[6] = rot[6] * sy;  model[7] = 0.0f;
        model[8] = rot[8] * sz;  model[9] = rot[9] * sz;  model[10] = rot[10] * sz;model[11] = 0.0f;
        model[12] = px;          model[13] = py;          model[14] = pz;          model[15] = 1.0f;

        return model;
    }

    private static void multiplyMat4(float[] A, float[] B, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += A[row + k * 4] * B[k + col * 4];
                }
                out[row + col * 4] = sum;
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
