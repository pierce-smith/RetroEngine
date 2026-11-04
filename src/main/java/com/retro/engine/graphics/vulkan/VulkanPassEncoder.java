package com.retro.engine.graphics.vulkan;

import com.retro.engine.graphics.RenderCommand;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Encodes the directional shadow map depth pass, offscreen 3D passes, dynamic ground shadow blobs, and final swapchain composition pass
 * (3D viewport blit + 2D UI overlay pass).
 * All image layout transitions use VK_KHR_synchronization2 (vkCmdPipelineBarrier2). Every 3D pass binds the bindless
 * set 0 exactly once; per-draw material channels and bone palette slots are pushed as constants.
 */
public class VulkanPassEncoder {

    // Current frame's draw list: transforms and material scalars per command, raw handles and slots per draw (key order)
    private RenderCommand[] commands;
    private VulkanDrawBuffers draws;

    // Pass flags read by blinn_phong.frag (params.x)
    private static final float PASS_SCENE = 0.0f;
    private static final float PASS_SHADOW_BLOB = 1.0f;
    private static final float PASS_SHADOW_DEPTH = 2.0f;

    // Camera view selector pushed in texIndices2.z: the editor camera or the selected Camera entity's preview
    private static final int CAMERA_VIEW_EDITOR = 0;
    private static final int CAMERA_VIEW_PREVIEW = 1;

    // Reusable scratchpad arrays for zero-allocation frame loops
    private final float[] modelMatrixScratch = new float[16];
    private final float[] identityLightSpace = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private final float[] defaultGridView = new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,-5,1};

    // Screen NDC rect of the docked Scene tile the offscreen image is blitted into
    private float sceneMinX = -0.55f, sceneMinY = -0.50f, sceneMaxX = 0.55f, sceneMaxY = 0.88f;

    // Color-ID pick pass for the current frame: rendered only when armed, then the probed texel is copied to the readback buffer
    private VulkanTargetPass pickTarget = null;
    private long pickReadbackBuffer = 0L;
    private int pickTexelX, pickTexelY;

    /** Arms the pick pass for the next recorded frame; a null target or 0L buffer disarms it. */
    public void setPickPass(VulkanTargetPass target, long readbackBuffer, int texelX, int texelY) {
        this.pickTarget = target;
        this.pickReadbackBuffer = readbackBuffer;
        this.pickTexelX = texelX;
        this.pickTexelY = texelY;
    }

    // ProjectSettings.shadowBlobsEnabled for the frame being recorded; set by VulkanBackend before recording
    private boolean shadowBlobsEnabled = true;

    public void setShadowBlobsEnabled(boolean enabled) {
        this.shadowBlobsEnabled = enabled;
    }

    // Scene color target clear (opaque black, or the fog color while fog is on: fog doubles as the draw distance, so
    // culled space reads as fog); set by VulkanBackend before recording
    private float sceneClearR, sceneClearG, sceneClearB;

    public void setSceneClearColor(float r, float g, float b) {
        this.sceneClearR = r;
        this.sceneClearG = g;
        this.sceneClearB = b;
    }

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
                                     RenderCommand[] commands,
                                     VulkanDrawBuffers draws,
                                     VulkanTargetPass previewTarget,
                                     boolean previewActive,
                                     int previewSlot) {
        this.commands = commands;
        this.draws = draws;
        try (MemoryStack stack = stackPush()) {
            // Reused for every 3D/shadow draw: vkCmdPushConstants copies the data at record time
            ByteBuffer meshPush = stack.malloc(VulkanPipeline.MESH_PUSH_CONSTANT_SIZE);
            long bindlessSet = gpuCache.getBindlessDescriptorSet();

            // PASS 0: DIRECTIONAL SHADOW MAP DEPTH PASS (leaves the shadow map in SHADER_READ_ONLY_OPTIMAL)
            encodeShadowDepthPass(cmd, stack, meshPush, bindlessSet, pipelineManager, gpuCache, deviceManager);

            // Offscreen color and depth are fully cleared each frame, so their previous contents (last frame's blit
            // source / depth) are discarded; the source scopes only order this frame's writes after last frame's use.
            imageBarrier(cmd, stack, targetPass.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            imageBarrier(cmd, stack, targetPass.getDepthImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

            // PASS 1, 2, 3: OFFSCREEN VIEWPORT RENDERING
            beginOffscreenRendering(cmd, stack, targetPass);

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

            recordSceneDraws(cmd, stack, meshPush, bindlessSet, pipelineManager, gpuCache, deviceManager,
                    CAMERA_VIEW_EDITOR);

            vkCmdEndRendering(cmd);

            // PASS 3A: Color-ID picking pass from the editor camera, with the probed texel copied back for the host
            if (pickTarget != null && pickTarget.getOffscreenImageView() != 0L && pickReadbackBuffer != 0L) {
                recordPickPass(cmd, stack, meshPush, bindlessSet, pipelineManager, gpuCache, deviceManager);
            }

            // PASS 3B: Selected Camera entity's view into the picture-in-picture target (game view: no editor grid)
            if (previewActive && previewTarget != null && previewTarget.getOffscreenImageView() != 0L) {
                recordCameraPreviewPass(cmd, stack, meshPush, bindlessSet, previewTarget, pipelineManager, gpuCache,
                        deviceManager);
            }

            // ---------------------------------------------------------------------------------
            // PHASE 4: FINAL SWAPCHAIN COMPOSITION PASS (3D Viewport Blit + 2D UI Overlay Pass)
            // ---------------------------------------------------------------------------------
            long currentSwapchainImageView = swapchainManager.getImageView(imageIndex);
            long currentSwapchainImage = swapchainManager.getSwapchainImage(imageIndex);

            // 1. Offscreen color goes straight to the blit source; the swapchain image is discarded into TRANSFER_DST.
            // The swapchain transition's source stage matches the acquire semaphore's wait stage in VulkanBackend.endFrame,
            // which chains the layout change after presentation has released the image.
            VkImageMemoryBarrier2.Buffer compositionBarriers = VkImageMemoryBarrier2.calloc(2, stack);
            fillImageBarrier(compositionBarriers.get(0), targetPass.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
            fillImageBarrier(compositionBarriers.get(1), currentSwapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_NONE,
                    VK_PIPELINE_STAGE_2_CLEAR_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
            pipelineBarrier(cmd, stack, compositionBarriers);

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

            // Clear and blit both write the swapchain image: order them (write-after-write) within TRANSFER_DST
            imageBarrier(cmd, stack, currentSwapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_2_CLEAR_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);

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

            // 2. Transition Swapchain Image TRANSFER_DST_OPTIMAL -> COLOR_ATTACHMENT_OPTIMAL (UI pass loads, then blends)
            imageBarrier(cmd, stack, currentSwapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_CLEAR_BIT | VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);

            // 3. Render 2D UI Overlay quads directly over Swapchain Framebuffer
            long uiVbo = gpuCache.getUiVertexBuffer();
            int uiCount = gpuCache.getUiVertexCount();

            long uiBindlessSet = gpuCache.getBindlessDescriptorSet();

            if (pipelineManager.getGraphicsPipelineUI() != 0L && uiVbo != 0L && uiCount > 0 && uiBindlessSet != 0L) {
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

                // SDF font atlas from the bindless table; -1 until its table write lands (glyphs draw transparent meanwhile)
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayoutUI(), 0, stack.longs(uiBindlessSet), null);
                ByteBuffer uiPush = stack.calloc(VulkanPipeline.UI_PUSH_CONSTANT_SIZE);
                uiPush.putInt(0, gpuCache.resolveTextureSlot(gpuCache.getUiFontTexture()));
                // Camera preview target: sampled only when it was rendered (and left in SHADER_READ_ONLY) this frame
                boolean previewRendered = previewActive && previewTarget != null && previewTarget.getOffscreenImageView() != 0L;
                uiPush.putInt(4, previewRendered ? previewSlot : VulkanGpuCache.NO_TEXTURE);
                vkCmdPushConstants(cmd, pipelineManager.getPipelineLayoutUI(), VK_SHADER_STAGE_FRAGMENT_BIT, 0, uiPush);

                LongBuffer pBuffers = stack.longs(uiVbo);
                LongBuffer pOffsets = stack.longs(0L);
                vkCmdBindVertexBuffers(cmd, 0, pBuffers, pOffsets);

                vkCmdDraw(cmd, uiCount, 1, 0, 0);

                vkCmdEndRendering(cmd);
            }

            // 4. Transition Swapchain Image COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR (presentation waits on the
            // render-finished semaphore, so no destination stage is needed)
            imageBarrier(cmd, stack, currentSwapchainImage, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_NONE, VK_ACCESS_2_NONE);
        }
    }

    /**
     * Records the lit mesh draws and ground shadow blobs for one camera; {@code cameraView} selects the view-projection
     * the vertex stage reads from the light UBO (texIndices2.z).
     */
    private void recordSceneDraws(VkCommandBuffer cmd, MemoryStack stack, ByteBuffer meshPush, long bindlessSet,
                                  VulkanPipeline pipelineManager, VulkanGpuCache gpuCache, VulkanDevice deviceManager,
                                  int cameraView) {
        // ---------------------------------------------------------------------------------
        // STEP 2: 3D Scene Mesh Pass
        // ---------------------------------------------------------------------------------
        if (pipelineManager.getGraphicsPipeline3D() != 0L && bindlessSet != 0L && draws.count > 0) {
            VulkanGpuCache.VulkanBuffer bonePaletteBuf = gpuCache.getOrCreateBonePaletteBuffer(deviceManager);
            long skinnedPipeline = pipelineManager.getGraphicsPipelineSkinned();

            // Set 0 is bound once for the whole pass; every draw selects its textures and palette via push constants
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(bindlessSet), null);
            LongBuffer pBuffers = stack.mallocLong(1);
            LongBuffer pOffsets = stack.longs(0L);
            long boundPipeline = 0L;

            for (int i = 0; i < draws.count; i++) {
                RenderCommand entity = commands[draws.commandIndex[i]];

                // 30-float buffers must go through the 120-byte skinned pipeline; without a palette or a free
                // slot they still draw there against an identity-seeded slot rather than misreading the stride.
                boolean skinnedLayout = draws.skinnedLayout[i];
                if (skinnedLayout && (skinnedPipeline == 0L || bonePaletteBuf == null)) continue;

                long wantedPipeline = skinnedLayout ? skinnedPipeline : pipelineManager.getGraphicsPipeline3D();
                if (wantedPipeline != boundPipeline) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wantedPipeline);
                    boundPipeline = wantedPipeline;
                }

                computeModelMatrixInPlace(entity.px, entity.py, entity.pz, entity.rx, entity.ry, entity.rz, entity.sx, entity.sy, entity.sz, modelMatrixScratch);
                writeEntityPush(meshPush, modelMatrixScratch, PASS_SCENE, entity, draws, i, cameraView);
                vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);

                drawMesh(cmd, pBuffers, pOffsets, i);
            }
        }

        // ---------------------------------------------------------------------------------
        // STEP 3: Dynamic Ground Entity Shadow Blobs Pass
        // ---------------------------------------------------------------------------------
        long groundQuadBuf = gpuCache.getGroundQuadBuffer(deviceManager);
        long shadowPipeline = pipelineManager.getGraphicsPipelineShadow();
        if (shadowPipeline == 0L) shadowPipeline = pipelineManager.getGraphicsPipeline3D();

        if (shadowBlobsEnabled && shadowPipeline != 0L && bindlessSet != 0L && draws.count > 0 && groundQuadBuf != 0L) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, shadowPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(bindlessSet), null);

            // Every blob draws the same quad, so it is bound once for the pass
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(groundQuadBuf), stack.longs(0L));

            int shadowIndex = 0;
            for (int i = 0; i < draws.count; i++) {
                if (shadowIndex >= 64) break;
                RenderCommand entity = commands[draws.commandIndex[i]];
                float clearance = entity.py - entity.groundY;
                if (clearance < -0.01f) continue;

                float heightFade = Math.max(0.0f, 1.0f - (clearance / 10.0f));
                if (heightFade < 0.05f) continue;

                float shadowScale = Math.max(entity.sx, entity.sz) * 1.25f;
                float shadowY = entity.groundY + 0.005f;

                computeModelMatrixInPlace(entity.px, shadowY, entity.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale, modelMatrixScratch);

                writeBlobPush(meshPush, modelMatrixScratch, heightFade, cameraView);
                vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);
                vkCmdDraw(cmd, 6, 1, 0, 0);

                shadowIndex++;
            }
        }
    }

    /**
     * Renders every pickable queued mesh's ID into the pick target (cleared to PICK_ID_NONE) with the same viewport and
     * editor view-projection as the scene pass, then copies the single probed texel into the host-visible readback buffer.
     */
    private void recordPickPass(VkCommandBuffer cmd, MemoryStack stack, ByteBuffer meshPush, long bindlessSet,
                                VulkanPipeline pipelineManager, VulkanGpuCache gpuCache, VulkanDevice deviceManager) {
        VulkanTargetPass target = pickTarget;

        // Fully cleared each frame; the source scope orders this frame's writes after last frame's texel copy
        imageBarrier(cmd, stack, target.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        imageBarrier(cmd, stack, target.getDepthImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        beginOffscreenRendering(cmd, stack, target, true, 0.0f, 0.0f, 0.0f);

        long staticPipeline = pipelineManager.getGraphicsPipelinePick();
        long skinnedPipeline = pipelineManager.getGraphicsPipelinePickSkinned();
        VulkanGpuCache.VulkanBuffer bonePaletteBuf = gpuCache.getOrCreateBonePaletteBuffer(deviceManager);

        if (staticPipeline != 0L && bindlessSet != 0L && draws.count > 0) {
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(bindlessSet), null);
            LongBuffer pBuffers = stack.mallocLong(1);
            LongBuffer pOffsets = stack.longs(0L);
            long boundPipeline = 0L;

            for (int i = 0; i < draws.count; i++) {
                RenderCommand entity = commands[draws.commandIndex[i]];
                if (entity.pickId == 0) continue;

                // Same pipeline-selection rules as the scene pass so picks match what is drawn
                boolean skinnedLayout = draws.skinnedLayout[i];
                if (skinnedLayout && (skinnedPipeline == 0L || bonePaletteBuf == null)) continue;

                long wantedPipeline = skinnedLayout ? skinnedPipeline : staticPipeline;
                if (wantedPipeline != boundPipeline) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wantedPipeline);
                    boundPipeline = wantedPipeline;
                }

                computeModelMatrixInPlace(entity.px, entity.py, entity.pz, entity.rx, entity.ry, entity.rz, entity.sx, entity.sy, entity.sz, modelMatrixScratch);
                writeEntityPush(meshPush, modelMatrixScratch, PASS_SCENE, entity, draws, i, CAMERA_VIEW_EDITOR);
                vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);

                drawMesh(cmd, pBuffers, pOffsets, i);
            }
        }

        // Screen-space overlay (gizmo handles) last with depth testing off, so a handle drawn over a mesh owns those pixels
        long overlayPipeline = pipelineManager.getGraphicsPipelinePickOverlay();
        long overlayVbo = gpuCache.getPickOverlayVertexBuffer();
        int overlayCount = gpuCache.getPickOverlayVertexCount();
        if (overlayPipeline != 0L && overlayVbo != 0L && overlayCount > 0) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, overlayPipeline);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(overlayVbo), stack.longs(0L));
            vkCmdDraw(cmd, overlayCount, 1, 0, 0);
        }

        vkCmdEndRendering(cmd);

        imageBarrier(cmd, stack, target.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).imageOffset().set(pickTexelX, pickTexelY, 0);
        region.get(0).imageExtent().set(1, 1, 1);
        vkCmdCopyImageToBuffer(cmd, target.getOffscreenImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pickReadbackBuffer, region);

        // Make the copied texel visible to the host read that follows the frame fence
        VkBufferMemoryBarrier2.Buffer hostBarrier = VkBufferMemoryBarrier2.calloc(1, stack);
        hostBarrier.get(0).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(pickReadbackBuffer).offset(0L).size(VK_WHOLE_SIZE);
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(hostBarrier));
    }

    private void beginOffscreenRendering(VkCommandBuffer cmd, MemoryStack stack, VulkanTargetPass target) {
        beginOffscreenRendering(cmd, stack, target, false, sceneClearR, sceneClearG, sceneClearB);
    }

    /**
     * Begins dynamic rendering into {@code target}'s color + depth (both cleared) with its viewport and scissor bound.
     * Float targets clear to opaque (clearR, clearG, clearB); integer targets clear to all-zero (PICK_ID_NONE).
     */
    private static void beginOffscreenRendering(VkCommandBuffer cmd, MemoryStack stack, VulkanTargetPass target, boolean integerColor,
                                                float clearR, float clearG, float clearB) {
        // Dynamic Viewport & Scissor Setup for Offscreen Viewport (Negative height for Vulkan Y-Inversion)
        VkViewport.Buffer offscreenViewport = VkViewport.calloc(1, stack);
        offscreenViewport.get(0)
                .x(0.0f).y((float) target.getHeight())
                .width((float) target.getWidth()).height(-(float) target.getHeight())
                .minDepth(0.0f).maxDepth(1.0f);

        VkRect2D.Buffer offscreenScissor = VkRect2D.calloc(1, stack);
        offscreenScissor.get(0).offset().set(0, 0);
        offscreenScissor.get(0).extent().set(target.getWidth(), target.getHeight());

        VkRenderingAttachmentInfo.Buffer offscreenColorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
        offscreenColorAttachment.get(0)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(target.getOffscreenImageView())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

        if (integerColor) {
            offscreenColorAttachment.get(0).clearValue().color().uint32(0, 0).uint32(1, 0).uint32(2, 0).uint32(3, 0);
        } else {
            offscreenColorAttachment.get(0).clearValue().color().float32(0, clearR).float32(1, clearG).float32(2, clearB).float32(3, 1.0f);
        }

        VkRenderingAttachmentInfo offscreenDepthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(target.getDepthImageView())
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);

        offscreenDepthAttachment.clearValue().depthStencil().set(1.0f, 0);

        VkRenderingInfo offscreenRenderingInfo = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pColorAttachments(offscreenColorAttachment)
                .pDepthAttachment(offscreenDepthAttachment);

        offscreenRenderingInfo.renderArea().extent().set(target.getWidth(), target.getHeight());

        vkCmdBeginRendering(cmd, offscreenRenderingInfo);

        vkCmdSetViewport(cmd, 0, offscreenViewport);
        vkCmdSetScissor(cmd, 0, offscreenScissor);
    }

    /**
     * Renders the queue from the selected Camera entity into the picture-in-picture target, then leaves it in
     * SHADER_READ_ONLY_OPTIMAL for the UI pass. The shadow map is already read-only from PASS 0.
     */
    private void recordCameraPreviewPass(VkCommandBuffer cmd, MemoryStack stack, ByteBuffer meshPush, long bindlessSet,
                                         VulkanTargetPass previewTarget, VulkanPipeline pipelineManager, VulkanGpuCache gpuCache,
                                         VulkanDevice deviceManager) {
        // Fully cleared each frame, so previous contents are discarded; the source scope orders this frame's writes
        // after last frame's UI sampling
        imageBarrier(cmd, stack, previewTarget.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        imageBarrier(cmd, stack, previewTarget.getDepthImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        beginOffscreenRendering(cmd, stack, previewTarget);
        recordSceneDraws(cmd, stack, meshPush, bindlessSet, pipelineManager, gpuCache, deviceManager,
                CAMERA_VIEW_PREVIEW);
        vkCmdEndRendering(cmd);

        imageBarrier(cmd, stack, previewTarget.getOffscreenImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    /** Writes the 128-byte push block for a scene or shadow-depth draw {@code d} of command {@code entity}. */
    private static void writeEntityPush(ByteBuffer push, float[] model, float passFlag, RenderCommand entity, VulkanDrawBuffers draws, int d, int cameraView) {
        push.clear();
        for (int m = 0; m < 16; m++) push.putFloat(model[m]);
        // params: passFlags, heightFade, reflection, emissiveIntensity
        push.putFloat(passFlag).putFloat(1.0f).putFloat(entity.reflect).putFloat(entity.emissiveIntensity);
        // surface: specularStrength, roughness, normalStrength, unused
        push.putFloat(entity.specularStrength).putFloat(entity.roughness).putFloat(entity.normalStrength).putFloat(0.0f);
        // texIndices: albedo, normal, specular/roughness, lightmap/AO
        int base = d * VulkanDrawBuffers.CHANNELS;
        int[] slots = draws.textureSlots;
        push.putInt(slots[base + VulkanGpuCache.CHANNEL_ALBEDO])
            .putInt(slots[base + VulkanGpuCache.CHANNEL_NORMAL])
            .putInt(slots[base + VulkanGpuCache.CHANNEL_SPECULAR])
            .putInt(slots[base + VulkanGpuCache.CHANNEL_LIGHTMAP]);
        // texIndices2: emissive, bone palette slot, camera view, pick ID (read only by entity_id.frag)
        push.putInt(slots[base + VulkanGpuCache.CHANNEL_EMISSIVE])
            .putInt(draws.paletteSlot[d])
            .putInt(cameraView).putInt(entity.pickId);
        push.flip();
    }

    /** Binds draw {@code d}'s vertex (and index) buffer and issues its draw. */
    private void drawMesh(VkCommandBuffer cmd, LongBuffer pBuffers, LongBuffer pOffsets, int d) {
        vkCmdBindVertexBuffers(cmd, 0, pBuffers.put(0, draws.vertexBuffer[d]), pOffsets);
        if (draws.indexBuffer[d] != 0L && draws.indexCount[d] > 0) {
            vkCmdBindIndexBuffer(cmd, draws.indexBuffer[d], 0, draws.indexType[d]);
            vkCmdDrawIndexed(cmd, draws.indexCount[d], 1, 0, 0, 0);
        } else {
            vkCmdDraw(cmd, draws.vertexCount[d], 1, 0, 0);
        }
    }

    /** Push block for a ground shadow blob: procedural alpha in the fragment stage, so every channel is absent. */
    private static void writeBlobPush(ByteBuffer push, float[] model, float heightFade, int cameraView) {
        push.clear();
        for (int m = 0; m < 16; m++) push.putFloat(model[m]);
        push.putFloat(PASS_SHADOW_BLOB).putFloat(heightFade).putFloat(0.0f).putFloat(0.0f);
        push.putFloat(0.0f).putFloat(1.0f).putFloat(1.0f).putFloat(0.0f);
        push.putInt(VulkanGpuCache.NO_TEXTURE).putInt(VulkanGpuCache.NO_TEXTURE).putInt(VulkanGpuCache.NO_TEXTURE).putInt(VulkanGpuCache.NO_TEXTURE);
        push.putInt(VulkanGpuCache.NO_TEXTURE).putInt(0).putInt(cameraView).putInt(0);
        push.flip();
    }

    /** Fills one synchronization2 image barrier covering mip 0 / layer 0 of {@code image}. */
    static void fillImageBarrier(VkImageMemoryBarrier2 barrier, long image, int aspectMask, int oldLayout, int newLayout,
                                 long srcStage, long srcAccess, long dstStage, long dstAccess) {
        barrier.sType$Default()
                .srcStageMask(srcStage)
                .srcAccessMask(srcAccess)
                .dstStageMask(dstStage)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(aspectMask).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
    }

    static void pipelineBarrier(VkCommandBuffer cmd, MemoryStack stack, VkImageMemoryBarrier2.Buffer barriers) {
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pImageMemoryBarriers(barriers);
        vkCmdPipelineBarrier2(cmd, dependency);
    }

    /** Records a single synchronization2 image layout transition / memory dependency. */
    static void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, long image, int aspectMask, int oldLayout, int newLayout,
                             long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack);
        fillImageBarrier(barrier.get(0), image, aspectMask, oldLayout, newLayout, srcStage, srcAccess, dstStage, dstAccess);
        pipelineBarrier(cmd, stack, barrier);
    }

    /**
     * Renders every queued mesh into the 2048x2048 shadow map from the light (passFlags 2.0 selects lightSpaceMatrix in
     * the vertex stage), then transitions it to SHADER_READ_ONLY_OPTIMAL for binding 2. Runs every frame, even with an
     * empty queue, because the main pass descriptors always expect the shadow map in the read-only layout.
     */
    private void encodeShadowDepthPass(VkCommandBuffer cmd, MemoryStack stack, ByteBuffer meshPush, long bindlessSet,
                                       VulkanPipeline pipelineManager, VulkanGpuCache gpuCache, VulkanDevice deviceManager) {
        if (!gpuCache.hasShadowMap()) return;

        int size = VulkanGpuCache.SHADOW_MAP_SIZE;
        int depthLayout = deviceManager.supportsSeparateDepthStencilLayouts()
                ? VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        // Previous contents are cleared, so discard them; wait on last frame's fragment reads before writing depth
        imageBarrier(cmd, stack, gpuCache.getShadowMapImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, depthLayout,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_NONE,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

        VkRenderingAttachmentInfo shadowDepthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(gpuCache.getShadowMapImageView())
                .imageLayout(depthLayout)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        shadowDepthAttachment.clearValue().depthStencil().set(1.0f, 0);

        VkRenderingInfo shadowRenderingInfo = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pDepthAttachment(shadowDepthAttachment);
        shadowRenderingInfo.renderArea().extent().set(size, size);

        vkCmdBeginRendering(cmd, shadowRenderingInfo);

        // Positive-height viewport: blinn_phong.frag maps light NDC to shadow UVs as ndc * 0.5 + 0.5
        VkViewport.Buffer shadowViewport = VkViewport.calloc(1, stack);
        shadowViewport.get(0).x(0.0f).y(0.0f).width((float) size).height((float) size).minDepth(0.0f).maxDepth(1.0f);
        VkRect2D.Buffer shadowScissor = VkRect2D.calloc(1, stack);
        shadowScissor.get(0).offset().set(0, 0);
        shadowScissor.get(0).extent().set(size, size);
        vkCmdSetViewport(cmd, 0, shadowViewport);
        vkCmdSetScissor(cmd, 0, shadowScissor);

        long staticPipeline = pipelineManager.getGraphicsPipelineShadowDepth();
        long skinnedPipeline = pipelineManager.getGraphicsPipelineShadowDepthSkinned();
        VulkanGpuCache.VulkanBuffer bonePaletteBuf = gpuCache.getOrCreateBonePaletteBuffer(deviceManager);

        if (staticPipeline != 0L && bindlessSet != 0L && draws.count > 0) {
            // Bound once for the pass: shadow_depth.frag reads only the albedo slot for alpha cutout
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineManager.getPipelineLayout3D(), 0, stack.longs(bindlessSet), null);
            LongBuffer pBuffers = stack.mallocLong(1);
            LongBuffer pOffsets = stack.longs(0L);
            long boundPipeline = 0L;

            for (int i = 0; i < draws.count; i++) {
                RenderCommand entity = commands[draws.commandIndex[i]];

                // Same pipeline-selection rules as the main 3D pass so casters and receivers always match
                boolean skinnedLayout = draws.skinnedLayout[i];
                if (skinnedLayout && (skinnedPipeline == 0L || bonePaletteBuf == null)) continue;

                long wantedPipeline = skinnedLayout ? skinnedPipeline : staticPipeline;
                if (wantedPipeline != boundPipeline) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wantedPipeline);
                    boundPipeline = wantedPipeline;
                }

                computeModelMatrixInPlace(entity.px, entity.py, entity.pz, entity.rx, entity.ry, entity.rz, entity.sx, entity.sy, entity.sz, modelMatrixScratch);
                writeEntityPush(meshPush, modelMatrixScratch, PASS_SHADOW_DEPTH, entity, draws, i, CAMERA_VIEW_EDITOR);
                vkCmdPushConstants(cmd, pipelineManager.getPipelineLayout3D(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, meshPush);

                drawMesh(cmd, pBuffers, pOffsets, i);
            }
        }

        vkCmdEndRendering(cmd);

        // Depth writes (including the load-op clear) can land in either fragment test stage
        imageBarrier(cmd, stack, gpuCache.getShadowMapImage(), VK_IMAGE_ASPECT_DEPTH_BIT,
                depthLayout, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    private void computeModelMatrixInPlace(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float[] outMatrix) {
        float radX = (float) Math.toRadians(rx);
        float radY = (float) Math.toRadians(ry);
        float radZ = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radX), sx_s = (float) Math.sin(radX);
        float cy = (float) Math.cos(radY), sy_s = (float) Math.sin(radY);
        float cz = (float) Math.cos(radZ), sz_s = (float) Math.sin(radZ);

        // Column-major R * S, matching MetalUniformStream.createModelMatrix: each column (a local axis) takes its own scale
        float r00 = cy * cz,                         r01 = cx * sz_s + sx_s * sy_s * cz,    r02 = sx_s * sz_s - cx * sy_s * cz;
        float r10 = -cy * sz_s,                      r11 = cx * cz - sx_s * sy_s * sz_s,    r12 = sx_s * cz + cx * sy_s * sz_s;
        float r20 = sy_s,                            r21 = -sx_s * cy,                       r22 = cx * cy;

        outMatrix[0] = r00 * sx;  outMatrix[1] = r10 * sx;  outMatrix[2] = r20 * sx;  outMatrix[3] = 0.0f;
        outMatrix[4] = r01 * sy;  outMatrix[5] = r11 * sy;  outMatrix[6] = r21 * sy;  outMatrix[7] = 0.0f;
        outMatrix[8] = r02 * sz;  outMatrix[9] = r12 * sz;  outMatrix[10] = r22 * sz; outMatrix[11] = 0.0f;
        outMatrix[12] = px;  outMatrix[13] = py;  outMatrix[14] = pz;  outMatrix[15] = 1.0f;
    }

}
