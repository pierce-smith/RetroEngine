package com.retro.engine.backend.metal;

import com.retro.engine.rhi.DirectionalShadowFrustum;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Encodes the directional shadow map depth pass, offscreen 3D passes (procedural grid, batched 3D models with environment reflections,
 * and dynamic downward-raycast shadow blobs) and window quad composition into Metal command buffers.
 * <p>
 * 3D draws use Metal 3 argument buffers: each frame writes one 64-byte DrawArgs entry per draw (vertex buffer, uniform slot
 * and bone palette gpuAddresses plus the albedo / normal / specular / lightmap / emissive gpuResourceIDs). The table is bound
 * once per encoder and every indirectly referenced resource is made resident with a single useResources call, so a draw
 * costs exactly one objc_msgSend: the instanced draw whose baseInstance selects its DrawArgs entry.
 */
public class MetalPassEncoder {

    private static final java.util.Comparator<RenderEntity> BATCH_COMPARATOR = (a, b) -> {
        int cmp = Long.compare(a.vertexBufferHandle, b.vertexBufferHandle);
        if (cmp != 0) return cmp;
        return Long.compare(a.materialTextures[0], b.materialTextures[0]);
    };

    public static class RenderEntity {
        public long vertexBufferHandle;
        public long vertexBufferAddress; // gpuAddress written into DrawArgs.vertices
        public int vertexCount;
        public long indexBufferHandle;   // 0L = non-indexed draw
        public int indexCount;
        public long indexType;           // MTLIndexType (0 = UInt16, 1 = UInt32)
        // Per channel (albedo, normal, specular, lightmap, emissive): id<MTLTexture> for residency, 0L = absent
        public final long[] materialTextures = new long[MetalUniformStream.MATERIAL_CHANNELS];
        public final long[] materialResourceIds = new long[MetalUniformStream.MATERIAL_CHANNELS];
        public int materialMask;         // MetalUniformStream.MATERIAL_* bits for the present channels
        public float specularStrength = 0.0f;
        public float roughness = 0.6f;
        public float normalStrength = 1.0f;
        public float emissiveIntensity = 1.0f;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY = 0.0f;
        public float reflectionStrength = 0.0f;
        public int stride = 22;
        public float[][] bonePalette = null;
        public float boundsRadius = 1.0f; // local bounding sphere radius, fits the shadow frustum
        int skinnedSlot = -1;             // bone palette slot assigned for this frame, -1 = not skinned
    }

    // Selectors are interned once at class load; nothing on the encoding path performs a string lookup
    private static final long SEL_RENDER_PASS_DESCRIPTOR = sel_registerName("renderPassDescriptor");
    private static final long SEL_COLOR_ATTACHMENTS = sel_registerName("colorAttachments");
    private static final long SEL_OBJECT_AT_INDEXED_SUBSCRIPT = sel_registerName("objectAtIndexedSubscript:");
    private static final long SEL_DEPTH_ATTACHMENT = sel_registerName("depthAttachment");
    private static final long SEL_SET_TEXTURE = sel_registerName("setTexture:");
    private static final long SEL_SET_LOAD_ACTION = sel_registerName("setLoadAction:");
    private static final long SEL_SET_STORE_ACTION = sel_registerName("setStoreAction:");
    private static final long SEL_RENDER_COMMAND_ENCODER = sel_registerName("renderCommandEncoderWithDescriptor:");
    private static final long SEL_SET_TRIANGLE_FILL_MODE = sel_registerName("setTriangleFillMode:");
    private static final long SEL_CONTENTS = sel_registerName("contents");
    private static final long SEL_SET_RENDER_PIPELINE_STATE = sel_registerName("setRenderPipelineState:");
    private static final long SEL_SET_DEPTH_STENCIL_STATE = sel_registerName("setDepthStencilState:");
    private static final long SEL_SET_VERTEX_BUFFER = sel_registerName("setVertexBuffer:offset:atIndex:");
    private static final long SEL_SET_VERTEX_BUFFER_OFFSET = sel_registerName("setVertexBufferOffset:atIndex:");
    private static final long SEL_SET_FRAGMENT_BUFFER = sel_registerName("setFragmentBuffer:offset:atIndex:");
    private static final long SEL_SET_FRAGMENT_BUFFER_OFFSET = sel_registerName("setFragmentBufferOffset:atIndex:");
    private static final long SEL_SET_FRAGMENT_TEXTURE = sel_registerName("setFragmentTexture:atIndex:");
    private static final long SEL_SET_FRAGMENT_SAMPLER_STATE = sel_registerName("setFragmentSamplerState:atIndex:");
    private static final long SEL_DRAW_PRIMITIVES = sel_registerName("drawPrimitives:vertexStart:vertexCount:");
    private static final long SEL_DRAW_PRIMITIVES_INSTANCED = sel_registerName("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:");
    private static final long SEL_DRAW_INDEXED_PRIMITIVES_INSTANCED = sel_registerName(
            "drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:");
    private static final long SEL_USE_RESOURCES = sel_registerName("useResources:count:usage:stages:");
    private static final long SEL_END_ENCODING = sel_registerName("endEncoding");

    private static final long MTL_PRIMITIVE_LINE = 1L;
    private static final long MTL_PRIMITIVE_TRIANGLE = 3L;
    private static final long MTL_RESOURCE_USAGE_READ = 1L;
    private static final long MTL_RENDER_STAGES_VERTEX_FRAGMENT = 3L;

    private static final int MAX_SHADOW_BLOBS = 64;

    // Every pass binds buffers at slot 0 only: the DrawArgs table for 3D passes, raw vertices for window passes
    private static final int MAX_BUFFER_SLOTS = 1;
    private static final long UNBOUND = -1L;

    private float sceneAspect = 0.0f; // 0 = fall back to framebuffer aspect

    // Resolved lazily: the Metal framework is not loaded until MetalDevice initializes, after this object is built
    private long renderPassDescClass = 0L;

    // Encoder-local binding state; reset whenever a new render command encoder is created
    private long encoder = 0L;
    private long send = 0L;
    private long boundPipeline;
    private long boundDepthStencil;
    private long boundFragmentTexture;
    private long boundFragmentSampler;
    private final long[] boundVertexBuffers = new long[MAX_BUFFER_SLOTS];
    private final long[] boundVertexOffsets = new long[MAX_BUFFER_SLOTS];
    private final long[] boundFragmentBuffers = new long[MAX_BUFFER_SLOTS];
    private final long[] boundFragmentOffsets = new long[MAX_BUFFER_SLOTS];

    // Deduplicated per-frame residency set for the argument-buffer passes, mirrored into native memory for useResources
    private long[] residencyScratch = new long[256];
    private LongBuffer residencyList = null;
    private int residencyCount = 0;

    // Per-frame argument table state shared by the shadow map and main 3D encoders
    private long drawArgsBuffer;
    private long drawArgsOffset;
    private long pDrawArgs;

    public void setSceneAspect(float aspect) {
        this.sceneAspect = aspect;
    }

    public void encodeOffscreen3DPass(
            long commandBuffer, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalGpuCache resourceCache, MetalUniformStream streamer,
            List<RenderEntity> sceneQueue, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw,
            int lightType, float lightPosX, float lightPosY, float lightPosZ,
            float lightDirX, float lightDirY, float lightDirZ,
            float lightR, float lightG, float lightB, float lightIntensity) {

        send = device.getObjcMsgSend();

        long currentSlotOffset = streamer.getCurrentSlotOffset();
        long streamingUniformBuffer = streamer.getStreamingUniformBuffer();
        long pBufferContents = invokePPP(streamingUniformBuffer, SEL_CONTENTS, send);
        long bonePaletteBuffer = streamer.getBonePaletteBuffer();
        long pBoneContents = bonePaletteBuffer != 0L ? invokePPP(bonePaletteBuffer, SEL_CONTENTS, send) : 0L;

        drawArgsBuffer = streamer.getDrawArgsBuffer();
        drawArgsOffset = streamer.getCurrentDrawArgsOffset();
        long pArgsContents = drawArgsBuffer != 0L ? invokePPP(drawArgsBuffer, SEL_CONTENTS, send) : 0L;
        pDrawArgs = pArgsContents != 0L ? pArgsContents + drawArgsOffset : 0L;
        if (pBufferContents == 0L || pDrawArgs == 0L) return;

        long defaultTexture = texture.getBindableTexture();
        long defaultResourceId = texture.getBindableResourceId();

        // Sort once and stream bone palettes once; the shadow and main passes share slot assignments
        int entityCount = 0;
        if (sceneQueue != null && !sceneQueue.isEmpty()) {
            sceneQueue.sort(BATCH_COMPARATOR);
            assignSkinningSlots(sceneQueue, streamer, pBoneContents);
            entityCount = Math.min(sceneQueue.size(), MetalUniformStream.MAX_ENTITIES_PER_FRAME);
        }

        long gridBuffer = resourceCache.getGridVertexBuffer();
        boolean drawGrid = gridBuffer != 0L && resourceCache.getGridVertexCount() > 0;
        long groundQuadBuf = resourceCache.getGroundQuadBuffer();

        collectResidency(sceneQueue, entityCount, streamingUniformBuffer, bonePaletteBuffer, defaultTexture,
                drawGrid ? gridBuffer : 0L, groundQuadBuf);

        // PASS 0: Directional shadow map depth pass, encoded before the main 3D pass samples it
        encodeShadowMapPass(commandBuffer, pipeline, texture, resourceCache, streamer, sceneQueue, entityCount,
                pBufferContents, currentSlotOffset, defaultResourceId);

        long offscreenPassDesc = invokePPP(renderPassDescClass(), SEL_RENDER_PASS_DESCRIPTOR, send);

        long colorAttachOff = invokePPP(offscreenPassDesc, SEL_COLOR_ATTACHMENTS, send);
        long colorAttachOff0 = invokePPPP(colorAttachOff, SEL_OBJECT_AT_INDEXED_SUBSCRIPT, 0L, send);

        invokePPPP(colorAttachOff0, SEL_SET_TEXTURE, framebuffer.getOffscreenColorTexture(), send);
        invokePPPP(colorAttachOff0, SEL_SET_LOAD_ACTION, 2L, send);
        invokePPPP(colorAttachOff0, SEL_SET_STORE_ACTION, 1L, send);

        if (framebuffer.getOffscreenDepthTexture() != 0L) {
            long depthAttachOff = invokePPP(offscreenPassDesc, SEL_DEPTH_ATTACHMENT, send);
            invokePPPP(depthAttachOff, SEL_SET_TEXTURE, framebuffer.getOffscreenDepthTexture(), send);
            invokePPPP(depthAttachOff, SEL_SET_LOAD_ACTION, 2L, send);
            invokePPPP(depthAttachOff, SEL_SET_STORE_ACTION, 0L, send);
        }

        long encoder3D = invokePPPP(commandBuffer, SEL_RENDER_COMMAND_ENCODER, offscreenPassDesc, send);
        if (encoder3D == 0L) return;
        beginEncoder(encoder3D);
        invokePPPP(encoder3D, SEL_SET_TRIANGLE_FILL_MODE, 0L, send); // MTLTriangleFillModeFill

        // The offscreen image is stretched over the docked Scene tile, so project with that tile's aspect
        float aspect = sceneAspect > 0.0f ? sceneAspect
                : (framebuffer.getWidth() > 0 && framebuffer.getHeight() > 0) ? ((float) framebuffer.getWidth() / (float) framebuffer.getHeight()) : (712.0f / 488.0f);
        float[] camPos = streamer.computeCameraPosition(camPanX, camPanY, camZoom, camPitch, camYaw);

        // Per-encoder setup: argument table, material sampler, shadow map at texture(1) / sampler(1), and residency
        bindArgumentTable(texture);
        bindShadowMap(resourceCache);
        useResidentResources();

        // 1A. Procedural Ray-Plane Infinite Grid Encoding
        if (drawGrid) {
            int slot = 0;
            float[] mvpGrid = streamer.createGridMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw);
            long gridAddr = pBufferContents + currentSlotOffset;
            MemoryUtil.memFloatBuffer(gridAddr + MetalUniformStream.OFFSET_MVP, 16).put(mvpGrid);
            float[] identityModel = streamer.createModelMatrix(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            MemoryUtil.memFloatBuffer(gridAddr + MetalUniformStream.OFFSET_MODEL, 16).put(identityModel);

            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, 0);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_IS_UNLIT, 1);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_STRIDE, 9);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 0);

            writeDrawArgs(slot, MetalBuffer.gpuAddress(gridBuffer, send), streamer.getUniformSlotAddress(slot),
                    streamer.getBonePaletteAddress(0), null, defaultResourceId);

            bindPipeline(pipeline.getPipelineState3D());
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            bindDepthStencil(shadowDepthState);

            // 6 vertices as triangles for the procedural grid quad
            drawInstanced(MTL_PRIMITIVE_TRIANGLE, 0L, 6L, slot);
            // Remaining 2 vertices as lines for the vertical green Y-axis pillar
            if (resourceCache.getGridVertexCount() >= 8) {
                drawInstanced(MTL_PRIMITIVE_LINE, 6L, 2L, slot);
            }
        }

        // 1B. Batched Opaque 3D Models
        if (entityCount > 0) {
            bindPipeline(pipeline.getPipelineState3D());
            bindDepthStencil(pipeline.getDepthStencilState3D());

            for (int i = 0; i < entityCount; i++) {
                RenderEntity item = sceneQueue.get(i);
                boolean skinned = item.skinnedSlot >= 0;
                int slot = MetalUniformStream.SLOT_BASE_ENTITIES + i;

                float[] mvpModel = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                float[] modelMat = streamer.getModelMatrix();

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;

                FloatBuffer fbMVP = MemoryUtil.memFloatBuffer(baseAddr, 16);
                fbMVP.clear(); fbMVP.put(mvpModel);

                FloatBuffer fbModel = MemoryUtil.memFloatBuffer(baseAddr + 64L, 16);
                fbModel.clear(); fbModel.put(modelMat);

                MemoryUtil.memPutFloat(baseAddr + 128L, lightDirX);
                MemoryUtil.memPutFloat(baseAddr + 132L, lightDirY);
                MemoryUtil.memPutFloat(baseAddr + 136L, lightDirZ);
                MemoryUtil.memPutFloat(baseAddr + 140L, lightIntensity);

                MemoryUtil.memPutFloat(baseAddr + 144L, lightPosX);
                MemoryUtil.memPutFloat(baseAddr + 148L, lightPosY);
                MemoryUtil.memPutFloat(baseAddr + 152L, lightPosZ);
                MemoryUtil.memPutFloat(baseAddr + 156L, (float) lightType);

                MemoryUtil.memPutFloat(baseAddr + 160L, lightR);
                MemoryUtil.memPutFloat(baseAddr + 164L, lightG);
                MemoryUtil.memPutFloat(baseAddr + 168L, lightB);
                MemoryUtil.memPutFloat(baseAddr + 172L, 1.0f);

                MemoryUtil.memPutFloat(baseAddr + 176L, 0.35f);
                MemoryUtil.memPutFloat(baseAddr + 180L, 0.35f);
                MemoryUtil.memPutFloat(baseAddr + 184L, 0.38f);
                MemoryUtil.memPutFloat(baseAddr + 188L, 1.0f);

                MemoryUtil.memPutFloat(baseAddr + 208L, item.reflectionStrength);
                MemoryUtil.memPutFloat(baseAddr + 216L, DirectionalShadowFrustum.SHADOW_BIAS);
                streamer.writeLightSpaceMatrix(baseAddr);

                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE, item.specularStrength);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 4L, item.roughness);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 8L, item.normalStrength);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 12L, item.emissiveIntensity);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS, camPos[0]);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS + 4L, camPos[1]);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS + 8L, camPos[2]);

                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, item.materialMask);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, item.stride);

                int passFlags = 0;
                if (item.reflectionStrength > 0.0f) {
                    passFlags |= 2;
                }
                if (skinned) {
                    passFlags |= MetalUniformStream.PASS_FLAG_SKINNED;
                }
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, passFlags);

                writeDrawArgs(slot, item.vertexBufferAddress, streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(item.skinnedSlot), item.materialResourceIds, defaultResourceId);
                drawEntity(item, slot);
            }
        }

        // 1C. Dynamic Downward-Raycast Surface Shadow Blobs Pass
        if (entityCount > 0 && groundQuadBuf != 0L) {
            int shadowIndex = 0;
            bindPipeline(pipeline.getPipelineState3D());
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            bindDepthStencil(shadowDepthState);

            for (RenderEntity item : sceneQueue) {
                if (shadowIndex >= MAX_SHADOW_BLOBS) break;

                float clearance = item.py - item.groundY;
                if (clearance < -0.01f) continue;

                float heightFade = Math.max(0.0f, 1.0f - (clearance / 10.0f));
                if (heightFade < 0.05f) continue;

                float shadowScale = Math.max(item.sx, item.sz) * 1.25f;
                int slot = MetalUniformStream.SLOT_BASE_SHADOW_BLOBS + shadowIndex;
                float shadowY = item.groundY + 0.005f;
                float[] mvpShadow = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, shadowY, item.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale);
                float[] modelMat = streamer.getModelMatrix();

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;
                FloatBuffer fbMVP = MemoryUtil.memFloatBuffer(baseAddr, 16);
                fbMVP.clear(); fbMVP.put(mvpShadow);

                FloatBuffer fbModel = MemoryUtil.memFloatBuffer(baseAddr + 64L, 16);
                fbModel.clear(); fbModel.put(modelMat);

                MemoryUtil.memPutFloat(baseAddr + 212L, heightFade);

                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, 14);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 1);

                writeDrawArgs(slot, resourceCache.getGroundQuadAddress(), streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(0), null, defaultResourceId);
                drawInstanced(MTL_PRIMITIVE_TRIANGLE, 0L, 6L, slot);

                shadowIndex++;
            }
        }

        invokePPP(encoder3D, SEL_END_ENCODING, send);
        encoder = 0L;
    }

    public void encodeWindowCompositionPass(
            long commandBuffer, long drawableTexture, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalGpuCache resourceCache, MetalUniformStream streamer) {

        send = device.getObjcMsgSend();
        long windowPassDesc = invokePPP(renderPassDescClass(), SEL_RENDER_PASS_DESCRIPTOR, send);

        long colorAttachWin = invokePPP(windowPassDesc, SEL_COLOR_ATTACHMENTS, send);
        long colorAttachWin0 = invokePPPP(colorAttachWin, SEL_OBJECT_AT_INDEXED_SUBSCRIPT, 0L, send);

        invokePPPP(colorAttachWin0, SEL_SET_TEXTURE, drawableTexture, send);
        invokePPPP(colorAttachWin0, SEL_SET_LOAD_ACTION, 2L, send); // Clear
        invokePPPP(colorAttachWin0, SEL_SET_STORE_ACTION, 1L, send); // Store

        // NO DEPTH ATTACHMENT FOR WINDOW COMPOSITION PASS!
        // drawableTexture handles color output at native window/Retina resolution.

        long encoderWin = invokePPPP(commandBuffer, SEL_RENDER_COMMAND_ENCODER, windowPassDesc, send);
        if (encoderWin == 0L) return;
        beginEncoder(encoderWin);

        // 1. Viewport Quad Pass-Through Composition
        long pipelineComp = pipeline.getPipelineStateComposition();
        if (resourceCache.isViewportQuadVisible() && resourceCache.getViewportQuadBuffer() != 0L && framebuffer.getOffscreenColorTexture() != 0L && pipelineComp != 0L) {
            bindPipeline(pipelineComp);

            bindFragmentTexture(framebuffer.getOffscreenColorTexture());
            if (texture.getSamplerStateHandle() != 0L) {
                bindFragmentSampler(texture.getSamplerStateHandle());
            }

            bindVertexBuffer(resourceCache.getViewportQuadBuffer(), 0L, 0);
            invokePPPPPP(encoderWin, SEL_DRAW_PRIMITIVES, MTL_PRIMITIVE_TRIANGLE, 0L, 6L, send);
        }

        // 2. 2D Editor UI Geometry Batch Pass
        if (resourceCache.getUiVertexBuffer() != 0L && resourceCache.getUiVertexCount() > 0 && pipeline.getPipelineStateUI() != 0L) {
            bindPipeline(pipeline.getPipelineStateUI());

            bindVertexBuffer(resourceCache.getUiVertexBuffer(), 0L, 0);
            invokePPPPPP(encoderWin, SEL_DRAW_PRIMITIVES, MTL_PRIMITIVE_TRIANGLE, 0L, (long) resourceCache.getUiVertexCount(), send);
        }

        invokePPP(encoderWin, SEL_END_ENCODING, send);
        encoder = 0L;
    }

    /** Frees the native residency list; call once the GPU no longer executes frames encoded by this object. */
    public void dispose() {
        if (residencyList != null) {
            MemoryUtil.memFree(residencyList);
            residencyList = null;
        }
    }

    /**
     * Assigns each skinned entity a bone palette slot (in sorted queue order) and streams its palette once,
     * so the shadow map pass and the main pass bind identical skinning data.
     */
    private void assignSkinningSlots(List<RenderEntity> sceneQueue, MetalUniformStream streamer, long pBoneContents) {
        int skinnedIndex = 0;
        int count = Math.min(sceneQueue.size(), MetalUniformStream.MAX_ENTITIES_PER_FRAME);
        for (int i = 0; i < count; i++) {
            RenderEntity item = sceneQueue.get(i);
            item.skinnedSlot = -1;
            if (item.stride >= 30 && item.bonePalette != null && pBoneContents != 0L
                    && skinnedIndex < MetalUniformStream.MAX_SKINNED_ENTITIES_PER_FRAME) {
                streamer.writeBonePalette(pBoneContents, skinnedIndex, item.bonePalette);
                item.skinnedSlot = skinnedIndex++;
            }
        }
    }

    /**
     * Renders every queued mesh into the 2048x2048 Depth32Float shadow map with mvp = lightSpace * model.
     * Always encoded (an empty queue still clears the map) because the main pass samples it unconditionally.
     */
    private void encodeShadowMapPass(long commandBuffer, MetalPipeline pipeline, MetalTexture texture,
                                     MetalGpuCache resourceCache, MetalUniformStream streamer,
                                     List<RenderEntity> sceneQueue, int entityCount, long pBufferContents,
                                     long currentSlotOffset, long defaultResourceId) {
        long shadowMap = resourceCache.getShadowMapTexture();
        if (shadowMap == 0L) return;

        long shadowPassDesc = invokePPP(renderPassDescClass(), SEL_RENDER_PASS_DESCRIPTOR, send);
        long depthAttach = invokePPP(shadowPassDesc, SEL_DEPTH_ATTACHMENT, send);
        invokePPPP(depthAttach, SEL_SET_TEXTURE, shadowMap, send);
        invokePPPP(depthAttach, SEL_SET_LOAD_ACTION, 2L, send);  // Clear (clearDepth defaults to 1.0)
        invokePPPP(depthAttach, SEL_SET_STORE_ACTION, 1L, send); // Store for sampling in the main pass

        long encoderShadow = invokePPPP(commandBuffer, SEL_RENDER_COMMAND_ENCODER, shadowPassDesc, send);
        if (encoderShadow == 0L) return;
        beginEncoder(encoderShadow);

        long shadowPipeline = pipeline.getPipelineStateShadowMap();
        if (shadowPipeline != 0L && entityCount > 0) {
            bindPipeline(shadowPipeline);
            bindDepthStencil(pipeline.getDepthStencilStateShadowMap());
            bindArgumentTable(texture);
            useResidentResources();

            for (int i = 0; i < entityCount; i++) {
                RenderEntity item = sceneQueue.get(i);
                // Same skinning rule as the main pass: unskinned 30-float meshes draw in bind pose there too
                boolean skinned = item.skinnedSlot >= 0;
                int slot = MetalUniformStream.SLOT_BASE_SHADOW_CASTERS + i;

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;
                float[] lightMvp = streamer.createLightSpaceMVPMatrix(item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MVP, 16).put(lightMvp);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MODEL, 16).put(streamer.getModelMatrix());
                streamer.writeLightSpaceMatrix(baseAddr);
                // Only the albedo alpha cutout runs in the depth pass
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, item.materialMask & MetalUniformStream.MATERIAL_ALBEDO);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, item.stride);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, skinned ? MetalUniformStream.PASS_FLAG_SKINNED : 0);

                writeDrawArgs(slot, item.vertexBufferAddress, streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(item.skinnedSlot), item.materialResourceIds, defaultResourceId);
                drawEntity(item, slot);
            }
        }

        invokePPP(encoderShadow, SEL_END_ENCODING, send);
        encoder = 0L;
    }

    /** Fills DrawArgs[slot] of the current frame; absent material channels (resource ID 0) point at the default texture. */
    private void writeDrawArgs(int slot, long vertexAddress, long uniformAddress, long boneAddress,
                               long[] resourceIds, long defaultResourceId) {
        long entry = pDrawArgs + MetalUniformStream.DRAW_ARGS_STRIDE * slot;
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_VERTICES, vertexAddress);
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_UNIFORMS, uniformAddress);
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_BONES, boneAddress);
        for (int c = 0; c < MetalUniformStream.MATERIAL_CHANNELS; c++) {
            long id = (resourceIds != null && resourceIds[c] != 0L) ? resourceIds[c] : defaultResourceId;
            MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_TEXTURES + 8L * c, id);
        }
    }

    private void drawEntity(RenderEntity item, int slot) {
        if (item.indexBufferHandle != 0L && item.indexCount > 0) {
            invokePPPPPPPPPPP(encoder, SEL_DRAW_INDEXED_PRIMITIVES_INSTANCED,
                    MTL_PRIMITIVE_TRIANGLE, (long) item.indexCount, item.indexType, item.indexBufferHandle, 0L,
                    1L, 0L, (long) slot, send);
        } else {
            drawInstanced(MTL_PRIMITIVE_TRIANGLE, 0L, (long) item.vertexCount, slot);
        }
    }

    // A single instance whose baseInstance is the DrawArgs slot; the shaders read it back as [[base_instance]]
    private void drawInstanced(long primitive, long vertexStart, long vertexCount, int slot) {
        invokePPPPPPPV(encoder, SEL_DRAW_PRIMITIVES_INSTANCED, primitive, vertexStart, vertexCount, 1L, (long) slot, send);
    }

    /**
     * Gathers every resource the DrawArgs table references indirectly (vertex buffers, the uniform and bone palette rings,
     * and material textures) into a sorted, deduplicated native list. Index buffers are passed straight to the draw call.
     */
    private void collectResidency(List<RenderEntity> sceneQueue, int entityCount, long uniformBuffer, long boneBuffer,
                                  long defaultTexture, long gridBuffer, long groundQuadBuffer) {
        int n = 0;
        int capacity = 5 + entityCount * (1 + MetalUniformStream.MATERIAL_CHANNELS);
        if (residencyScratch.length < capacity) {
            residencyScratch = new long[Math.max(capacity, residencyScratch.length * 2)];
        }
        long[] scratch = residencyScratch;
        scratch[n++] = uniformBuffer;
        scratch[n++] = boneBuffer;
        scratch[n++] = defaultTexture;
        scratch[n++] = gridBuffer;
        scratch[n++] = groundQuadBuffer;
        for (int i = 0; i < entityCount; i++) {
            RenderEntity item = sceneQueue.get(i);
            scratch[n++] = item.vertexBufferHandle;
            for (long tex : item.materialTextures) scratch[n++] = tex;
        }

        Arrays.sort(scratch, 0, n);
        int unique = 0;
        for (int i = 0; i < n; i++) {
            long handle = scratch[i];
            if (handle != 0L && (unique == 0 || scratch[unique - 1] != handle)) {
                scratch[unique++] = handle;
            }
        }

        if (residencyList == null || residencyList.capacity() < unique) {
            if (residencyList != null) MemoryUtil.memFree(residencyList);
            residencyList = MemoryUtil.memAllocLong(Math.max(unique, 256));
        }
        // Flip so memAddress (which is position-relative) points at the first handle
        residencyList.clear();
        residencyList.put(scratch, 0, unique).flip();
        residencyCount = unique;
    }

    /** One FFI call makes every indirectly referenced resource resident for the current encoder. */
    private void useResidentResources() {
        if (residencyCount == 0) return;
        invokePPPPPPP(encoder, SEL_USE_RESOURCES, MemoryUtil.memAddress(residencyList), (long) residencyCount,
                MTL_RESOURCE_USAGE_READ, MTL_RENDER_STAGES_VERTEX_FRAGMENT, send);
    }

    /** Binds the current frame's DrawArgs table at buffer(0) for both stages, plus the material sampler at sampler(0). */
    private void bindArgumentTable(MetalTexture texture) {
        bindVertexBuffer(drawArgsBuffer, drawArgsOffset, 0);
        bindFragmentBuffer(drawArgsBuffer, drawArgsOffset, 0);
        if (texture.getSamplerStateHandle() != 0L) {
            bindFragmentSampler(texture.getSamplerStateHandle());
        }
    }

    /** Binds the shadow map and its comparison sampler at fragment slot 1; never call inside the shadow map pass. */
    private void bindShadowMap(MetalGpuCache resourceCache) {
        if (!resourceCache.hasShadowMap()) return;
        invokePPPPP(encoder, SEL_SET_FRAGMENT_TEXTURE, resourceCache.getShadowMapTexture(), 1L, send);
        invokePPPPP(encoder, SEL_SET_FRAGMENT_SAMPLER_STATE, resourceCache.getShadowSamplerState(), 1L, send);
    }

    private long renderPassDescClass() {
        if (renderPassDescClass == 0L) {
            renderPassDescClass = objc_getClass("MTLRenderPassDescriptor");
        }
        return renderPassDescClass;
    }

    private void beginEncoder(long newEncoder) {
        encoder = newEncoder;
        boundPipeline = UNBOUND;
        boundDepthStencil = UNBOUND;
        boundFragmentTexture = UNBOUND;
        boundFragmentSampler = UNBOUND;
        Arrays.fill(boundVertexBuffers, UNBOUND);
        Arrays.fill(boundVertexOffsets, UNBOUND);
        Arrays.fill(boundFragmentBuffers, UNBOUND);
        Arrays.fill(boundFragmentOffsets, UNBOUND);
    }

    private void bindPipeline(long pipelineState) {
        if (pipelineState == boundPipeline) return;
        invokePPPP(encoder, SEL_SET_RENDER_PIPELINE_STATE, pipelineState, send);
        boundPipeline = pipelineState;
    }

    private void bindDepthStencil(long depthStencilState) {
        if (depthStencilState == boundDepthStencil) return;
        invokePPPP(encoder, SEL_SET_DEPTH_STENCIL_STATE, depthStencilState, send);
        boundDepthStencil = depthStencilState;
    }

    // Rebinding the same buffer at a new offset only needs the lighter offset-update entry point
    private void bindVertexBuffer(long buffer, long offset, int index) {
        if (buffer == boundVertexBuffers[index]) {
            if (offset == boundVertexOffsets[index]) return;
            if (buffer != 0L) {
                invokePPPPP(encoder, SEL_SET_VERTEX_BUFFER_OFFSET, offset, (long) index, send);
                boundVertexOffsets[index] = offset;
                return;
            }
        }
        invokePPPPPP(encoder, SEL_SET_VERTEX_BUFFER, buffer, offset, (long) index, send);
        boundVertexBuffers[index] = buffer;
        boundVertexOffsets[index] = offset;
    }

    private void bindFragmentBuffer(long buffer, long offset, int index) {
        if (buffer == boundFragmentBuffers[index]) {
            if (offset == boundFragmentOffsets[index]) return;
            if (buffer != 0L) {
                invokePPPPP(encoder, SEL_SET_FRAGMENT_BUFFER_OFFSET, offset, (long) index, send);
                boundFragmentOffsets[index] = offset;
                return;
            }
        }
        invokePPPPPP(encoder, SEL_SET_FRAGMENT_BUFFER, buffer, offset, (long) index, send);
        boundFragmentBuffers[index] = buffer;
        boundFragmentOffsets[index] = offset;
    }

    private void bindFragmentTexture(long textureHandle) {
        if (textureHandle == boundFragmentTexture) return;
        invokePPPPP(encoder, SEL_SET_FRAGMENT_TEXTURE, textureHandle, 0L, send);
        boundFragmentTexture = textureHandle;
    }

    private void bindFragmentSampler(long samplerState) {
        if (samplerState == boundFragmentSampler) return;
        invokePPPPP(encoder, SEL_SET_FRAGMENT_SAMPLER_STATE, samplerState, 0L, send);
        boundFragmentSampler = samplerState;
    }
}
