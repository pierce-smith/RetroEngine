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

    /** One queued draw. Mesh and texture are opaque VulkanGpuCache handles, resolved during command recording. */
    public static class RenderEntity {
        public long meshHandle;
        public long textureHandle;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY = 0.0f;
        public float reflectionStrength = 0.0f;
        public float[][] bonePalette = null;
    }

    // 3D push constant block: mat4 model (64 B) + vec4 params (16 B) = 80 B, within the 128 B guaranteed minimum
    private static final int MESH_PUSH_FLOATS = 20;

    // Reusable scratchpad arrays for zero-allocation frame loops
    private final float[] modelMatrixScratch = new float[16];
    private final float[] identityLightSpace = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private final float[] defaultGridView = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-5,1};

    // Screen NDC rect of the docked Scene tile the offscreen image is blitted into
    private float sceneMinX = -0.55f, sceneMinY = -0.50f, sceneMaxX = 0.55f, sceneMaxY = 0.88f;

    public void setSceneRect(float minX, float minY, float maxX, float maxY) {
        this.sceneMinX = minX;
        this.sceneMinY = minY;
        this.sceneMaxX = maxX;
        this.sceneMaxY = maxY;
    }

    public void record4PhasePipeline(VkCommandBuffer cmd,
                                     VulkanTargetPass targetPass,
                                     VulkanSwapchain swapchainManager,
                                     int imageIndex,
                                     VulkanPipeline pipelineManager,
                                     VulkanGpuCache gpuCache,
                                     VulkanDevice deviceManager,
                                     float[] viewMatrix,
                                     float[] projMatrix,
                                     List<RenderEntity> sceneQueue,
                                     int sceneQueueCount) {
        try (MemoryStack stack = stackPush()) {
            // Reused for every 3D/shadow draw: vkCmdPushConstants copies the data at record time
            FloatBuffer meshPush = stack.mallocFloat(MESH_PUSH_FLOATS);

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
                VulkanGpuCache.VulkanBuffer bonePaletteBuf = gpuCache.getOrCreateBonePaletteBuffer(deviceManager);
                long skinnedPipeline = pipelineManager.getGraphicsPipelineSkinned();

                // Binding 0 (light UBO) and binding 3 (bone palette) are dynamic: one offset each, in binding order
                IntBuffer dynamicOffsets = stack.mallocInt(2);
                LongBuffer pSet = stack.mallocLong(1);
                long boundPipeline = 0L;
                long boundSet = 0L;
                int boundPaletteOffset = -1;

                for (int i = 0; i < sceneQueueCount; i++) {
                    RenderEntity entity = sceneQueue.get(i);
                    VulkanGpuCache.VulkanBuffer mesh = gpuCache.getMesh(entity.meshHandle);
                    if (mesh == null || mesh.bufferHandle == 0L || mesh.vertexCount <= 0) continue;
                    long vboHandle = mesh.bufferHandle;

                    // 30-float buffers must go through the 120-byte skinned pipeline; without a palette or a free
                    // slot they still draw there against an identity-seeded slot rather than misreading the stride.
                    boolean skinnedLayout = mesh.floatStride >= 30;
                    if (skinnedLayout && (skinnedPipeline == 0L || bonePaletteBuf == null)) continue;

                    long wantedPipeline = skinnedLayout ? skinnedPipeline : pipelineManager.getGraphicsPipeline3D();
                    if (wantedPipeline != boundPipeline) {
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wantedPipeline);
                        boundPipeline = wantedPipeline;
                    }

                    // Sets were written by VulkanGpuCache.prepareSceneDescriptors before recording; only rebind
                    // when the texture set or palette slot actually changes between draws.
                    long entitySet = gpuCache.getEntityDescriptorSet(i);
                    int paletteOffset = gpuCache.getEntityPaletteOffset(i);
                    if (entitySet != 0L && (entitySet != boundSet || paletteOffset != boundPaletteOffset)) {
                        dynamicOffsets.put(0, 0).put(1, paletteOffset);
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, pSet.put(0, entitySet), dynamicOffsets);
                        boundSet = entitySet;
                        boundPaletteOffset = paletteOffset;
                    }

                    computeModelMatrixInPlace(entity.px, entity.py, entity.pz, entity.rx, entity.ry, entity.rz, entity.sx, entity.sy, entity.sz, modelMatrixScratch);

                    // 80-byte block: mat4 model + vec4 params (view/proj are read from the binding 0 UBO)
                    meshPush.clear();
                    meshPush.put(modelMatrixScratch);
                    meshPush.put(0.0f);                      // params.x = passFlags (0.0 = Standard 3D Mesh)
                    meshPush.put(1.0f);                      // params.y = heightFade
                    meshPush.put(entity.reflectionStrength); // params.z = reflection
                    meshPush.put(0.0f);                      // params.w = unused
                    meshPush.flip();

                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);

                    LongBuffer pBuffers = stack.longs(vboHandle);
                    LongBuffer pOffsets = stack.longs(0L);
                    vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);
                    vkCmdDraw(cmd, mesh.vertexCount, 1, 0, 0);
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

                // Every blob samples the same white set prepared before recording, so bind it once for the pass
                long shadowSet = gpuCache.getShadowDescriptorSet();
                if (shadowSet != 0L) {
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(shadowSet), stack.ints(0, 0));
                }

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

                    computeModelMatrixInPlace(entity.px, shadowY, entity.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale, modelMatrixScratch);

                    meshPush.clear();
                    meshPush.put(modelMatrixScratch);
                    meshPush.put(1.0f);       // params.x = passFlags (1.0 = Shadow Blob Pass)
                    meshPush.put(heightFade); // params.y = heightFade
                    meshPush.put(0.0f);       // params.z = unused
                    meshPush.put(0.0f);       // params.w = unused
                    meshPush.flip();

                    vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);

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

            int dstX0 = Math.max(0, Math.min(dstWidth, (int) ((1.0f + sceneMinX) * 0.5f * dstWidth)));
            int dstX1 = Math.max(0, Math.min(dstWidth, (int) ((1.0f + sceneMaxX) * 0.5f * dstWidth)));
            int dstY0 = Math.max(0, Math.min(dstHeight, (int) ((1.0f - sceneMaxY) * 0.5f * dstHeight)));
            int dstY1 = Math.max(0, Math.min(dstHeight, (int) ((1.0f - sceneMinY) * 0.5f * dstHeight)));
            boolean sceneVisible = dstX1 > dstX0 && dstY1 > dstY0;

            VkImageBlit.Buffer blitRegion = VkImageBlit.calloc(1, stack);
            blitRegion.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blitRegion.get(0).srcOffsets(0).set(0, 0, 0);
            blitRegion.get(0).srcOffsets(1).set(targetPass.getWidth(), targetPass.getHeight(), 1);

            blitRegion.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blitRegion.get(0).dstOffsets(0).set(dstX0, dstY0, 0);
            blitRegion.get(0).dstOffsets(1).set(dstX1, dstY1, 1);

            if (sceneVisible) {
                vkCmdBlitImage(cmd, targetPass.getOffscreenImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, currentSwapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blitRegion, VK_FILTER_LINEAR);
            }

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
