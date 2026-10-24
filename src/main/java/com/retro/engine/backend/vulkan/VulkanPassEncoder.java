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
 * Encodes offscreen 3D passes, dynamic ground shadow blobs, and final swapchain composition pass
 * (3D viewport blit + 2D UI overlay pass).
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

        public long getVertexBufferHandle() { return vertexBufferHandle; }
        public long getTextureHandle() { return textureHandle; }
    }

    // Reusable scratchpad arrays for zero-allocation frame loops
    private final float[] modelMatrixScratch = new float[16];
    private final float[] identityLightSpace = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private final float[] defaultGridView = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-5,1};

    public void record4PhasePipeline(VkCommandBuffer cmd,
                                     VulkanTargetPass targetPass,
                                     VulkanSwapchain swapchainManager,
                                     int imageIndex,
                                     VulkanPipeline pipelineManager,
                                     VulkanGpuCache gpuCache,
                                     long descriptorPoolHandle,
                                     VulkanDevice deviceManager,
                                     float[] viewMatrix,
                                     float[] projMatrix,
                                     List<RenderEntity> sceneQueue,
                                     int sceneQueueCount) {
        try (MemoryStack stack = stackPush()) {

            // Dynamic Viewport & Scissor Setup for Offscreen Viewport (Negative height for Vulkan Y-Inversion)
            VkViewport.Buffer offscreenViewport = VkViewport.calloc(1, stack);
            offscreenViewport.get(0)
                    .x(0.0f).y((float) targetPass.getHeight())
                    .width((float) targetPass.getWidth()).height(-(float) targetPass.getHeight())
                    .minDepth(0.0f).maxDepth(1.0f);

            VkRect2D.Buffer offscreenScissor = VkRect2D.calloc(1, stack);
            offscreenScissor.get(0).offset().set(0, 0);
            offscreenScissor.get(0).extent().set(targetPass.getWidth(), targetPass.getHeight());

            // PASS 1, 2, 3: OFFSCREEN VIEWPORT RENDERING
            VkRenderingAttachmentInfo.Buffer offscreenColorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            offscreenColorAttachment.get(0)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(targetPass.getOffscreenImageView())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            offscreenColorAttachment.get(0).clearValue().color().float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);

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

            vkCmdBeginRendering(cmd, offscreenRenderingInfo);

            vkCmdSetViewport(cmd, 0, offscreenViewport);
            vkCmdSetScissor(cmd, 0, offscreenScissor);

            // ---------------------------------------------------------------------------------
            // STEP 1: Infinite 3D Grid Pass & Vertical Green Y-Axis Pillar Line (Background Floor)
            // ---------------------------------------------------------------------------------
            long gridVbo = gpuCache.getGridVertexBuffer();
            int gridCount = gpuCache.getGridVertexCount();

            if (gridVbo != 0L && gridCount > 0) {
                FloatBuffer pushData = stack.mallocFloat(32);
                if (viewMatrix != null && viewMatrix.length == 16) pushData.put(viewMatrix);
                else pushData.put(defaultGridView);

                if (projMatrix != null && projMatrix.length == 16) pushData.put(projMatrix);
                else pushData.put(identityLightSpace);

                pushData.flip();

                LongBuffer pGridBuffers = stack.longs(gridVbo);
                LongBuffer pGridOffsets = stack.longs(0L);

                // 1A. Draw 6 vertices for infinite grid floor quad (TRIANGLE_LIST)
                if (pipelineManager.getGraphicsPipelineGrid() != 0L) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineGrid());
                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayoutGrid(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushData);
                    vkCmdBindVertexBuffers(cmd, 0, pGridBuffers, pGridOffsets);
                    vkCmdDraw(cmd, 6, 1, 0, 0);
                }

                // 1B. Draw 2 vertices for vertical green Y-axis line (LINE_LIST)
                if (gridCount >= 8 && pipelineManager.getGraphicsPipelineGridLines() != 0L) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineGridLines());
                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayoutGrid(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushData);
                    vkCmdBindVertexBuffers(cmd, 0, pGridBuffers, pGridOffsets);
                    vkCmdDraw(cmd, 2, 1, 6, 0);
                }
            }

            // ---------------------------------------------------------------------------------
            // STEP 2: 3D Scene Mesh Pass
            // ---------------------------------------------------------------------------------
            if (pipelineManager.getGraphicsPipeline3D() != 0L && sceneQueue != null && sceneQueueCount > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipeline3D());

                VkDevice vkDevice = deviceManager.getDevice();
                VulkanGpuCache.VulkanTexture whiteTex = gpuCache.getDefaultWhiteTexture(deviceManager);
                VulkanGpuCache.VulkanBuffer lightUbo = gpuCache.getOrCreateLightUniformBuffer(deviceManager);

                for (int i = 0; i < sceneQueueCount; i++) {
                    RenderEntity entity = sceneQueue.get(i);
                    long vboHandle = entity.getVertexBufferHandle();
                    if (vboHandle == 0L || entity.vertexCount <= 0) continue;

                    if (vkDevice != null && descriptorPoolHandle != 0L && lightUbo != null && whiteTex != null) {
                        VkDescriptorSetAllocateInfo allocSetInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                                .descriptorPool(descriptorPoolHandle)
                                .pSetLayouts(stack.longs(pipelineManager.getDescriptorSetLayout3D()));

                        LongBuffer pSet = stack.mallocLong(1);
                        if (vkAllocateDescriptorSets(vkDevice, allocSetInfo, pSet) == VK_SUCCESS) {
                            long descriptorSetEntity = pSet.get(0);

                            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(lightUbo.bufferHandle).offset(0).range(512);

                            long activeTexView = (entity.hasTexture && entity.getTextureHandle() != 0L) ? entity.getTextureHandle() : whiteTex.imageViewHandle;

                            VkDescriptorImageInfo.Buffer imageInfoTex = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(activeTexView).sampler(whiteTex.samplerHandle);
                            VkDescriptorImageInfo.Buffer imageInfoShadow = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(whiteTex.imageViewHandle).sampler(whiteTex.samplerHandle);

                            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(3, stack);
                            descriptorWrites.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(bufferInfo);
                            descriptorWrites.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoTex);
                            descriptorWrites.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoShadow);

                            vkUpdateDescriptorSets(vkDevice, descriptorWrites, null);

                            IntBuffer pDynamicOffsets = stack.ints(0);
                            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(descriptorSetEntity), pDynamicOffsets);
                        }
                    }

                    computeModelMatrixInPlace(entity.px, entity.py, entity.pz, entity.rx, entity.ry, entity.rz, entity.sx, entity.sy, entity.sz, modelMatrixScratch);

                    FloatBuffer push68 = stack.mallocFloat(68);
                    push68.put(modelMatrixScratch);
                    push68.put(viewMatrix != null && viewMatrix.length == 16 ? viewMatrix : identityLightSpace);
                    push68.put(projMatrix != null && projMatrix.length == 16 ? projMatrix : identityLightSpace);
                    push68.put(0.0f);                      // shadowParams.x = passFlags (0.0 = Standard 3D Mesh)
                    push68.put(1.0f);                      // shadowParams.y = heightFade
                    push68.put(entity.reflectionStrength); // shadowParams.z = reflection
                    push68.put(0.0f);                      // shadowParams.w = unused
                    push68.put(identityLightSpace);
                    push68.flip();

                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, push68);

                    LongBuffer pBuffers = stack.longs(vboHandle);
                    LongBuffer pOffsets = stack.longs(0L);
                    vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);
                    vkCmdDraw(cmd, entity.vertexCount, 1, 0, 0);
                }
            }

            // ---------------------------------------------------------------------------------
            // STEP 3: Dynamic Ground Entity Shadow Blobs Pass
            // ---------------------------------------------------------------------------------
            long groundQuadBuf = gpuCache.getGroundQuadBuffer(deviceManager);
            long shadowPipeline = pipelineManager.getGraphicsPipelineShadow();
            if (shadowPipeline == 0L) shadowPipeline = pipelineManager.getGraphicsPipeline3D();

            if (shadowPipeline != 0L && sceneQueue != null && sceneQueueCount > 0 && groundQuadBuf != 0L) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, shadowPipeline);

                VkDevice vkDevice = deviceManager.getDevice();
                VulkanGpuCache.VulkanTexture whiteTex = gpuCache.getDefaultWhiteTexture(deviceManager);
                VulkanGpuCache.VulkanBuffer lightUbo = gpuCache.getOrCreateLightUniformBuffer(deviceManager);

                int shadowIndex = 0;
                for (int i = 0; i < sceneQueueCount; i++) {
                    if (shadowIndex >= 64) break;
                    RenderEntity entity = sceneQueue.get(i);
                    float clearance = entity.py - entity.groundY;
                    if (clearance < -0.01f) continue;

                    float heightFade = Math.max(0.0f, 1.0f - (clearance / 10.0f));
                    if (heightFade < 0.05f) continue;

                    float shadowScale = Math.max(entity.sx, entity.sz) * 1.25f;
                    float shadowY = entity.groundY + 0.005f;

                    if (vkDevice != null && descriptorPoolHandle != 0L && lightUbo != null && whiteTex != null) {
                        VkDescriptorSetAllocateInfo allocSetInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                                .descriptorPool(descriptorPoolHandle)
                                .pSetLayouts(stack.longs(pipelineManager.getDescriptorSetLayout3D()));

                        LongBuffer pSet = stack.mallocLong(1);
                        if (vkAllocateDescriptorSets(vkDevice, allocSetInfo, pSet) == VK_SUCCESS) {
                            long descriptorSetEntity = pSet.get(0);

                            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(lightUbo.bufferHandle).offset(0).range(512);
                            VkDescriptorImageInfo.Buffer imageInfoTex = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(whiteTex.imageViewHandle).sampler(whiteTex.samplerHandle);
                            VkDescriptorImageInfo.Buffer imageInfoShadow = VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(whiteTex.imageViewHandle).sampler(whiteTex.samplerHandle);

                            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(3, stack);
                            descriptorWrites.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(bufferInfo);
                            descriptorWrites.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoTex);
                            descriptorWrites.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSetEntity).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(imageInfoShadow);

                            vkUpdateDescriptorSets(vkDevice, descriptorWrites, null);

                            IntBuffer pDynamicOffsets = stack.ints(0);
                            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(descriptorSetEntity), pDynamicOffsets);
                        }
                    }

                    computeModelMatrixInPlace(entity.px, shadowY, entity.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale, modelMatrixScratch);

                    FloatBuffer pushShadow = stack.mallocFloat(68);
                    pushShadow.put(modelMatrixScratch);
                    pushShadow.put(viewMatrix != null && viewMatrix.length == 16 ? viewMatrix : identityLightSpace);
                    pushShadow.put(projMatrix != null && projMatrix.length == 16 ? projMatrix : identityLightSpace);
                    pushShadow.put(1.0f);       // shadowParams.x = passFlags (1.0 = Shadow Blob Pass!)
                    pushShadow.put(heightFade); // shadowParams.y = heightFade
                    pushShadow.put(0.0f);       // shadowParams.z = unused
                    pushShadow.put(0.0f);       // shadowParams.w = unused
                    pushShadow.put(identityLightSpace);
                    pushShadow.flip();

                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushShadow);

                    LongBuffer pBuffers = stack.longs(groundQuadBuf);
                    LongBuffer pOffsets = stack.longs(0L);
                    vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);
                    vkCmdDraw(cmd, 6, 1, 0, 0);

                    shadowIndex++;
                }
            }

            vkCmdEndRendering(cmd);

            // Transition Offscreen Viewport Image layout to SHADER_READ_ONLY_OPTIMAL
            VkImageMemoryBarrier.Buffer hingeBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(targetPass.getOffscreenImage())
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            hingeBarrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, hingeBarrier);

            // ---------------------------------------------------------------------------------
            // PHASE 4: FINAL SWAPCHAIN COMPOSITION PASS (3D Viewport Blit + 2D UI Overlay Pass)
            // ---------------------------------------------------------------------------------
            long currentSwapchainImageView = swapchainManager.getImageView(imageIndex);
            long currentSwapchainImage = swapchainManager.getSwapchainImage(imageIndex);

            // 1. Blit Offscreen 3D Viewport to Swapchain Image inside Viewport Sub-Window Bounds
            VkImageMemoryBarrier.Buffer srcBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(targetPass.getOffscreenImage())
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            srcBarrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            VkImageMemoryBarrier.Buffer dstBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(currentSwapchainImage)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            dstBarrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, srcBarrier);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, dstBarrier);

            int dstWidth = swapchainManager.getWidth();
            int dstHeight = swapchainManager.getHeight();

            // Clear swapchain background VRAM to pitch black
            VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
            clearColor.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);

            VkImageSubresourceRange clearRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdClearColorImage(cmd, currentSwapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, clearRange);

            int dstX0 = (int) ((1.0f - 0.55f) * 0.5f * dstWidth);
            int dstX1 = (int) ((1.0f + 0.55f) * 0.5f * dstWidth);
            int dstY0 = (int) ((1.0f - 0.88f) * 0.5f * dstHeight);
            int dstY1 = (int) ((1.0f - (-0.50f)) * 0.5f * dstHeight);

            VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
            blitRegion.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blitRegion.get(0).srcOffsets(0).set(0, 0, 0);
            blitRegion.get(0).srcOffsets(1).set(targetPass.getWidth(), targetPass.getHeight(), 1);

            blitRegion.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blitRegion.get(0).dstOffsets(0).set(dstX0, dstY0, 0);
            blitRegion.get(0).dstOffsets(1).set(dstX1, dstY1, 1);

            vkCmdBlitImage(cmd, targetPass.getOffscreenImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, currentSwapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_LINEAR);

            // 2. Transition Swapchain Image TRANSFER_DST_OPTIMAL -> COLOR_ATTACHMENT_OPTIMAL
            VkImageMemoryBarrier.Buffer uiBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(currentSwapchainImage)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            uiBarrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, uiBarrier);

            // 3. Render 2D UI Overlay quads directly over Swapchain Framebuffer
            long uiVbo = gpuCache.getUiVertexBuffer();
            int uiCount = gpuCache.getUiVertexCount();

            if (pipelineManager.getGraphicsPipelineUI() != 0L && uiVbo != 0L && uiCount > 0) {
                VkRenderingAttachmentInfo.Buffer swapchainColorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
                swapchainColorAttachment.get(0)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                        .imageView(currentSwapchainImageView)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

                VkRenderingInfo swapchainRenderingInfo = VkRenderingInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                        .layerCount(1)
                        .pColorAttachments(swapchainColorAttachment);

                swapchainRenderingInfo.renderArea().extent().set(swapchainManager.getWidth(), swapchainManager.getHeight());

                vkCmdBeginRendering(cmd, swapchainRenderingInfo);

                // Swapchain UI Viewport (Negative height for Y-Inversion)
                VkViewport.Buffer swapchainViewport = VkViewport.calloc(1, stack);
                swapchainViewport.get(0)
                        .x(0.0f).y((float) swapchainManager.getHeight())
                        .width((float) swapchainManager.getWidth()).height(-(float) swapchainManager.getHeight())
                        .minDepth(0.0f).maxDepth(1.0f);

                VkRect2D.Buffer swapchainScissor = VkRect2D.calloc(1, stack);
                swapchainScissor.get(0).offset().set(0, 0);
                swapchainScissor.get(0).extent().set(swapchainManager.getWidth(), swapchainManager.getHeight());

                vkCmdSetViewport(cmd, 0, swapchainViewport);
                vkCmdSetScissor(cmd, 0, swapchainScissor);

                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getGraphicsPipelineUI());

                LongBuffer pBuffers = stack.longs(uiVbo);
                LongBuffer pOffsets = stack.longs(0L);
                vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);

                vkCmdDraw(cmd, uiCount, 1, 0, 0);

                vkCmdEndRendering(cmd);
            }

            // 4. Transition Swapchain Image COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR
            VkImageMemoryBarrier.Buffer presentBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(currentSwapchainImage)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(0);

            presentBarrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);
        }
    }

    private void computeModelMatrixInPlace(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float[] outMatrix) {
        float radX = (float) Math.toRadians(rx);
        float radY = (float) Math.toRadians(ry);
        float radZ = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radX), sx_s = (float) Math.sin(radX);
        float cy = (float) Math.cos(radY), sy_s = (float) Math.sin(radY);
        float cz = (float) Math.cos(radZ), sz_s = (float) Math.sin(radZ);

        float m00 = cy * cz * sx;
        float m01 = (cx * sz_s + sx_s * sy_s * cz) * sx;
        float m02 = (sx_s * sz_s - cx * sy_s * cz) * sx;

        float m10 = -cy * sz_s * sy;
        float m11 = (cx * cz - sx_s * sy_s * sz_s) * sy;
        float m12 = (sx_s * cz + cx * sy_s * sz_s) * sy;

        float m20 = sy_s * sz;
        float m21 = -sx_s * cy * sz;
        float m22 = cx * cy * sz;

        outMatrix[0] = m00;  outMatrix[1] = m10;  outMatrix[2] = m20;  outMatrix[3] = 0.0f;
        outMatrix[4] = m01;  outMatrix[5] = m11;  outMatrix[6] = m21;  outMatrix[7] = 0.0f;
        outMatrix[8] = m02;  outMatrix[9] = m12;  outMatrix[10] = m22; outMatrix[11] = 0.0f;
        outMatrix[12] = px;  outMatrix[13] = py;  outMatrix[14] = pz;  outMatrix[15] = 1.0f;
    }

}
