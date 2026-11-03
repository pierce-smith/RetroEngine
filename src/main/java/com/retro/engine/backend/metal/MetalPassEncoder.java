package com.retro.engine.backend.metal;

import com.retro.engine.rhi.DirectionalShadowFrustum;
import com.retro.engine.rhi.RenderCommand;
import com.retro.engine.rhi.SceneLights;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.libffi.LibFFI.FFI_DEFAULT_ABI;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;
import static org.lwjgl.system.libffi.LibFFI.nffi_call;
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

    // Current frame's draw list: transforms and material scalars per command, raw Metal objects per draw (key order)
    private RenderCommand[] commands;
    private MetalDrawBuffers draws;

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
    private static final long SEL_BLIT_COMMAND_ENCODER = sel_registerName("blitCommandEncoder");
    private static final long SEL_COPY_TEXTURE_TO_BUFFER = sel_registerName(
            "copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:sourceSize:toBuffer:destinationOffset:destinationBytesPerRow:destinationBytesPerImage:");

    private static final long MTL_PRIMITIVE_LINE = 1L;
    private static final long MTL_PRIMITIVE_TRIANGLE = 3L;
    private static final long MTL_RESOURCE_USAGE_READ = 1L;
    private static final long MTL_RENDER_STAGES_VERTEX_FRAGMENT = 3L;

    private static final int MAX_SHADOW_BLOBS = 64;

    // Every pass binds buffers at slot 0 only: the DrawArgs table for 3D passes, raw vertices for window passes
    private static final int MAX_BUFFER_SLOTS = 1;
    private static final long UNBOUND = -1L;

    private float sceneAspect = 0.0f; // 0 = fall back to framebuffer aspect

    // Per-frame light and editor camera state shared by the editor and camera preview scene passes
    private SceneLights lights;
    private final float[] editorViewProj = new float[16];
    private final float[] editorCamPos = new float[3];

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

    // Color-ID pick pass for the frame being encoded: rendered only when armed, then the probed texel is blitted to the readback buffer
    private MetalTargetPass pickTarget = null;
    private long pickReadbackBuffer = 0L;
    private long pickReadbackOffset = 0L;
    private int pickTexelX, pickTexelY;
    // MTLOrigin + MTLSize (3 NSUInteger each), passed by pointer like the MTLRegion uploads in MetalTexture
    private LongBuffer pickCopyRegion = null;
    // The texture-to-buffer copy takes 11 pointer-sized arguments, more than LWJGL's JNI invokers cover, so objc_msgSend
    // is called through libffi with a prepared call interface and argument slots
    private static final int PICK_COPY_ARG_COUNT = 11;
    private FFICIF pickCopyCif = null;
    private LongBuffer pickCopyArgs = null;
    private PointerBuffer pickCopyArgPointers = null;

    /** Arms the pick pass for the next encoded frame; a null target or 0L buffer disarms it. */
    public void setPickPass(MetalTargetPass target, long readbackBuffer, long readbackOffset, int texelX, int texelY) {
        this.pickTarget = target;
        this.pickReadbackBuffer = readbackBuffer;
        this.pickReadbackOffset = readbackOffset;
        this.pickTexelX = texelX;
        this.pickTexelY = texelY;
    }

    public void setSceneAspect(float aspect) {
        this.sceneAspect = aspect;
    }

    /**
     * Encodes the shadow map pass, the editor viewport pass and, when {@code previewViewProj} is non-null, the selected
     * Camera entity's picture-in-picture pass into {@code previewTarget}. Draws arrive key-sorted from the RenderQueue;
     * both scene passes share their skinning slots, residency list and shadow map, and each writes its own uniform slot range.
     */
    public void encodeOffscreen3DPass(
            long commandBuffer, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalTargetPass previewTarget, MetalGpuCache resourceCache, MetalUniformStream streamer,
            RenderCommand[] commands, MetalDrawBuffers draws, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw,
            float[] explicitView, float[] explicitEye,
            float[] previewViewProj, float[] previewCamPos,
            SceneLights lights) {

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

        // Stream bone palettes once; the shadow and main passes share slot assignments
        this.commands = commands;
        this.draws = draws;
        int entityCount = Math.min(draws.count, MetalUniformStream.MAX_ENTITIES_PER_FRAME);
        assignSkinningSlots(streamer, pBoneContents, entityCount);

        long gridBuffer = resourceCache.getGridVertexBuffer();
        boolean drawGrid = gridBuffer != 0L && resourceCache.getGridVertexCount() > 0;
        long groundQuadBuf = resourceCache.getGroundQuadBuffer();

        collectResidency(entityCount, streamingUniformBuffer, bonePaletteBuffer, defaultTexture,
                drawGrid ? gridBuffer : 0L, groundQuadBuf);

        // PASS 0: Directional shadow map depth pass, encoded before the main 3D pass samples it
        encodeShadowMapPass(commandBuffer, pipeline, texture, resourceCache, streamer, entityCount,
                pBufferContents, currentSlotOffset, defaultResourceId);

        // Point and spot arrays are shared by every lit draw in both scene passes, so they are written once per frame
        this.lights = lights;
        streamer.writeLightBlock(pBufferContents, lights);

        // The offscreen image is stretched over the docked Scene tile, so project with that tile's aspect
        float aspect = sceneAspect > 0.0f ? sceneAspect
                : (framebuffer.getWidth() > 0 && framebuffer.getHeight() > 0) ? ((float) framebuffer.getWidth() / (float) framebuffer.getHeight()) : (712.0f / 488.0f);
        if (explicitView != null) {
            System.arraycopy(streamer.createViewProjMatrix(aspect, explicitView), 0, editorViewProj, 0, 16);
            System.arraycopy(explicitEye, 0, editorCamPos, 0, 3);
        } else {
            System.arraycopy(streamer.createGridMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw), 0, editorViewProj, 0, 16);
            System.arraycopy(streamer.computeCameraPosition(camPanX, camPanY, camZoom, camPitch, camYaw), 0, editorCamPos, 0, 3);
        }

        // PASS 1: Editor viewport (grid, lit models, ground shadow blobs)
        encodeScenePass(commandBuffer, framebuffer.getOffscreenColorTexture(), framebuffer.getOffscreenDepthTexture(),
                pipeline, texture, resourceCache, streamer, entityCount, pBufferContents, currentSlotOffset,
                defaultResourceId, editorViewProj, editorCamPos, drawGrid ? gridBuffer : 0L, groundQuadBuf,
                MetalUniformStream.SLOT_BASE_ENTITIES, MetalUniformStream.SLOT_BASE_SHADOW_BLOBS);

        // PASS 1B: Color-ID picking from the editor camera, with the probed texel blitted back for the host
        if (pickTarget != null && pickTarget.getOffscreenColorTexture() != 0L && pickReadbackBuffer != 0L && pipeline.getPipelineStatePick() != 0L) {
            encodePickPass(commandBuffer, pipeline, texture, resourceCache, streamer, entityCount, pBufferContents, currentSlotOffset, defaultResourceId);
        }

        // PASS 2: Selected Camera entity's view for the picture-in-picture preview (game view: no editor grid)
        if (previewViewProj != null && previewCamPos != null && previewTarget != null && previewTarget.getOffscreenColorTexture() != 0L) {
            encodeScenePass(commandBuffer, previewTarget.getOffscreenColorTexture(), previewTarget.getOffscreenDepthTexture(),
                    pipeline, texture, resourceCache, streamer, entityCount, pBufferContents, currentSlotOffset,
                    defaultResourceId, previewViewProj, previewCamPos, 0L, groundQuadBuf,
                    MetalUniformStream.SLOT_BASE_PREVIEW_ENTITIES, MetalUniformStream.SLOT_BASE_PREVIEW_SHADOW_BLOBS);
        }
    }

    /**
     * One lit scene render into {@code colorTarget} from {@code viewProj}: the grid (only when {@code gridBuffer} is
     * non-zero, always uniform slot 0), the queued models and their ground shadow blobs.
     */
    private void encodeScenePass(long commandBuffer, long colorTarget, long depthTarget,
                                 MetalPipeline pipeline, MetalTexture texture, MetalGpuCache resourceCache, MetalUniformStream streamer,
                                 int entityCount, long pBufferContents, long currentSlotOffset,
                                 long defaultResourceId, float[] viewProj, float[] camPos, long gridBuffer, long groundQuadBuf,
                                 int entitySlotBase, int blobSlotBase) {
        long offscreenPassDesc = invokePPP(renderPassDescClass(), SEL_RENDER_PASS_DESCRIPTOR, send);

        long colorAttachOff = invokePPP(offscreenPassDesc, SEL_COLOR_ATTACHMENTS, send);
        long colorAttachOff0 = invokePPPP(colorAttachOff, SEL_OBJECT_AT_INDEXED_SUBSCRIPT, 0L, send);

        invokePPPP(colorAttachOff0, SEL_SET_TEXTURE, colorTarget, send);
        invokePPPP(colorAttachOff0, SEL_SET_LOAD_ACTION, 2L, send);
        invokePPPP(colorAttachOff0, SEL_SET_STORE_ACTION, 1L, send);

        if (depthTarget != 0L) {
            long depthAttachOff = invokePPP(offscreenPassDesc, SEL_DEPTH_ATTACHMENT, send);
            invokePPPP(depthAttachOff, SEL_SET_TEXTURE, depthTarget, send);
            invokePPPP(depthAttachOff, SEL_SET_LOAD_ACTION, 2L, send);
            invokePPPP(depthAttachOff, SEL_SET_STORE_ACTION, 0L, send);
        }

        long encoder3D = invokePPPP(commandBuffer, SEL_RENDER_COMMAND_ENCODER, offscreenPassDesc, send);
        if (encoder3D == 0L) return;
        beginEncoder(encoder3D);
        invokePPPP(encoder3D, SEL_SET_TRIANGLE_FILL_MODE, 0L, send); // MTLTriangleFillModeFill

        // Per-encoder setup: argument table, material sampler, shadow map at texture(1) / sampler(1), and residency
        bindArgumentTable(texture);
        bindShadowMap(resourceCache);
        useResidentResources();

        // 1A. Procedural Ray-Plane Infinite Grid Encoding
        if (gridBuffer != 0L) {
            int slot = 0;
            long gridAddr = pBufferContents + currentSlotOffset;
            MemoryUtil.memFloatBuffer(gridAddr + MetalUniformStream.OFFSET_MVP, 16).put(viewProj);
            float[] identityModel = streamer.createModelMatrix(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            MemoryUtil.memFloatBuffer(gridAddr + MetalUniformStream.OFFSET_MODEL, 16).put(identityModel);

            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, 0);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_IS_UNLIT, 1);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_STRIDE, 9);
            MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 0);

            writeDrawArgs(slot, MetalBuffer.gpuAddress(gridBuffer, send), streamer.getUniformSlotAddress(slot),
                    streamer.getBonePaletteAddress(0), -1, defaultResourceId);

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
                RenderCommand item = commands[draws.commandIndex[i]];
                boolean skinned = draws.skinnedSlot[i] >= 0;
                int slot = entitySlotBase + i;

                float[] mvpModel = streamer.createMVPFromViewProj(viewProj, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                float[] modelMat = streamer.getModelMatrix();

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;

                FloatBuffer fbMVP = MemoryUtil.memFloatBuffer(baseAddr, 16);
                fbMVP.clear(); fbMVP.put(mvpModel);

                FloatBuffer fbModel = MemoryUtil.memFloatBuffer(baseAddr + 64L, 16);
                fbModel.clear(); fbModel.put(modelMat);

                streamer.writeDrawLights(baseAddr, lights);
                streamer.writeDrawEnvironment(baseAddr, lights.environment());

                MemoryUtil.memPutFloat(baseAddr + 208L, item.reflect);
                MemoryUtil.memPutFloat(baseAddr + 216L, DirectionalShadowFrustum.SHADOW_BIAS);
                streamer.writeLightSpaceMatrix(baseAddr);

                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE, item.specularStrength);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 4L, item.roughness);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 8L, item.normalStrength);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_SURFACE + 12L, item.emissiveIntensity);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS, camPos[0]);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS + 4L, camPos[1]);
                MemoryUtil.memPutFloat(baseAddr + MetalUniformStream.OFFSET_VIEW_POS + 8L, camPos[2]);

                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, draws.materialMask[i]);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, draws.floatStride[i]);

                int passFlags = 0;
                if (item.reflect > 0.0f) {
                    passFlags |= 2;
                }
                if (skinned) {
                    passFlags |= MetalUniformStream.PASS_FLAG_SKINNED;
                }
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, passFlags);

                writeDrawArgs(slot, draws.vertexAddress[i], streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(draws.skinnedSlot[i]), i, defaultResourceId);
                drawEntity(i, slot);
            }
        }

        // 1C. Dynamic Downward-Raycast Surface Shadow Blobs Pass (ProjectSettings.shadowBlobsEnabled)
        if (entityCount > 0 && groundQuadBuf != 0L && lights.environment().shadowBlobsEnabled) {
            int shadowIndex = 0;
            bindPipeline(pipeline.getPipelineState3D());
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            bindDepthStencil(shadowDepthState);

            for (int i = 0; i < draws.count; i++) {
                if (shadowIndex >= MAX_SHADOW_BLOBS) break;
                RenderCommand item = commands[draws.commandIndex[i]];

                float clearance = item.py - item.groundY;
                if (clearance < -0.01f) continue;

                float heightFade = Math.max(0.0f, 1.0f - (clearance / 10.0f));
                if (heightFade < 0.05f) continue;

                float shadowScale = Math.max(item.sx, item.sz) * 1.25f;
                int slot = blobSlotBase + shadowIndex;
                float shadowY = item.groundY + 0.005f;
                float[] mvpShadow = streamer.createMVPFromViewProj(viewProj, item.px, shadowY, item.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale);
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
                        streamer.getBonePaletteAddress(0), -1, defaultResourceId);
                drawInstanced(MTL_PRIMITIVE_TRIANGLE, 0L, 6L, slot);

                shadowIndex++;
            }
        }

        invokePPP(encoder3D, SEL_END_ENCODING, send);
        encoder = 0L;
    }

    /**
     * Renders every pickable queued mesh's ID into the pick target with the editor view-projection, then the screen-space
     * pick overlay (gizmo handles) on top, then blits the single probed texel into the shared readback buffer.
     * The cleared background decodes to PICK_ID_NONE (see entity_id.metal).
     */
    private void encodePickPass(long commandBuffer, MetalPipeline pipeline, MetalTexture texture, MetalGpuCache resourceCache, MetalUniformStream streamer,
                                int entityCount, long pBufferContents, long currentSlotOffset,
                                long defaultResourceId) {
        long pickPassDesc = invokePPP(renderPassDescClass(), SEL_RENDER_PASS_DESCRIPTOR, send);
        long colorAttach = invokePPPP(invokePPP(pickPassDesc, SEL_COLOR_ATTACHMENTS, send), SEL_OBJECT_AT_INDEXED_SUBSCRIPT, 0L, send);
        invokePPPP(colorAttach, SEL_SET_TEXTURE, pickTarget.getOffscreenColorTexture(), send);
        invokePPPP(colorAttach, SEL_SET_LOAD_ACTION, 2L, send);  // Clear
        invokePPPP(colorAttach, SEL_SET_STORE_ACTION, 1L, send); // Store for the texel blit
        long depthAttach = invokePPP(pickPassDesc, SEL_DEPTH_ATTACHMENT, send);
        invokePPPP(depthAttach, SEL_SET_TEXTURE, pickTarget.getOffscreenDepthTexture(), send);
        invokePPPP(depthAttach, SEL_SET_LOAD_ACTION, 2L, send);
        invokePPPP(depthAttach, SEL_SET_STORE_ACTION, 0L, send);

        long encoderPick = invokePPPP(commandBuffer, SEL_RENDER_COMMAND_ENCODER, pickPassDesc, send);
        if (encoderPick == 0L) return;
        beginEncoder(encoderPick);

        if (entityCount > 0) {
            bindPipeline(pipeline.getPipelineStatePick());
            bindDepthStencil(pipeline.getDepthStencilState3D());
            bindArgumentTable(texture);
            useResidentResources();

            for (int i = 0; i < entityCount; i++) {
                RenderCommand item = commands[draws.commandIndex[i]];
                if (item.pickId == 0) continue;
                int slot = MetalUniformStream.SLOT_BASE_PICK_ENTITIES + i;

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;
                float[] mvp = streamer.createMVPFromViewProj(editorViewProj, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MVP, 16).put(mvp);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MODEL, 16).put(streamer.getModelMatrix());
                // Only the albedo alpha cutout runs in the pick pass
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, draws.materialMask[i] & MetalUniformStream.MATERIAL_ALBEDO);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, draws.floatStride[i]);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, draws.skinnedSlot[i] >= 0 ? MetalUniformStream.PASS_FLAG_SKINNED : 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PICK_ID, item.pickId);

                writeDrawArgs(slot, draws.vertexAddress[i], streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(draws.skinnedSlot[i]), i, defaultResourceId);
                drawEntity(i, slot);
            }
        }

        // Overlay handles ignore depth so a gizmo drawn over a mesh owns those pixels
        long overlayBuffer = resourceCache.getPickOverlayVertexBuffer();
        int overlayCount = resourceCache.getPickOverlayVertexCount();
        if (overlayBuffer != 0L && overlayCount > 0 && pipeline.getPipelineStatePickOverlay() != 0L) {
            bindPipeline(pipeline.getPipelineStatePickOverlay());
            bindDepthStencil(pipeline.getDepthStencilStateUI());
            bindVertexBuffer(overlayBuffer, 0L, 0);
            invokePPPPPP(encoderPick, SEL_DRAW_PRIMITIVES, MTL_PRIMITIVE_TRIANGLE, 0L, (long) overlayCount, send);
        }

        invokePPP(encoderPick, SEL_END_ENCODING, send);
        encoder = 0L;

        long blit = invokePPP(commandBuffer, SEL_BLIT_COMMAND_ENCODER, send);
        if (blit == 0L) return;
        if (pickCopyRegion == null) pickCopyRegion = MemoryUtil.memAllocLong(6);
        pickCopyRegion.put(0, pickTexelX).put(1, pickTexelY).put(2, 0L)  // sourceOrigin
                      .put(3, 1L).put(4, 1L).put(5, 1L);                  // sourceSize
        long region = MemoryUtil.memAddress(pickCopyRegion);
        msgSendPickCopy(blit, SEL_COPY_TEXTURE_TO_BUFFER, pickTarget.getOffscreenColorTexture(), 0L, 0L,
                region, region + 24L, pickReadbackBuffer, pickReadbackOffset, 4L, 4L);
        invokePPP(blit, SEL_END_ENCODING, send);
    }

    /** objc_msgSend with 11 pointer-sized arguments (void return) through libffi. */
    private void msgSendPickCopy(long... args) {
        if (pickCopyCif == null) {
            FFIType[] argTypes = new FFIType[PICK_COPY_ARG_COUNT];
            java.util.Arrays.fill(argTypes, ffi_type_pointer);
            pickCopyCif = apiCreateCIF(FFI_DEFAULT_ABI, ffi_type_void, argTypes);
            pickCopyArgs = MemoryUtil.memAllocLong(PICK_COPY_ARG_COUNT);
            pickCopyArgPointers = MemoryUtil.memAllocPointer(PICK_COPY_ARG_COUNT);
            long base = MemoryUtil.memAddress(pickCopyArgs);
            for (int i = 0; i < PICK_COPY_ARG_COUNT; i++) pickCopyArgPointers.put(i, base + 8L * i);
        }
        for (int i = 0; i < PICK_COPY_ARG_COUNT; i++) pickCopyArgs.put(i, args[i]);
        nffi_call(pickCopyCif.address(), send, 0L, MemoryUtil.memAddress(pickCopyArgPointers));
    }

    public void encodeWindowCompositionPass(
            long commandBuffer, long drawableTexture, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalGpuCache resourceCache, MetalUniformStream streamer, long cameraPreviewTexture) {

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

            // SDF font atlas with the linear sampler; solid geometry ignores it, so the default texture stands in when absent
            long fontAtlas = resourceCache.getUiFontTexture();
            bindFragmentTexture(fontAtlas != 0L ? fontAtlas : texture.getBindableTexture());
            if (texture.getSamplerStateHandle() != 0L) {
                bindFragmentSampler(texture.getSamplerStateHandle());
            }
            // Camera preview target for picture-in-picture quads (uv offset by UIGeometryBatch.PREVIEW_UV_OFFSET)
            invokePPPPP(encoderWin, SEL_SET_FRAGMENT_TEXTURE,
                    cameraPreviewTexture != 0L ? cameraPreviewTexture : texture.getBindableTexture(), 1L, send);

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
        if (pickCopyRegion != null) {
            MemoryUtil.memFree(pickCopyRegion);
            pickCopyRegion = null;
        }
        if (pickCopyArgs != null) {
            MemoryUtil.memFree(pickCopyArgs);
            MemoryUtil.memFree(pickCopyArgPointers);
            pickCopyArgs = null;
            pickCopyArgPointers = null;
        }
    }

    /**
     * Assigns each skinned draw a bone palette slot (in key order) and streams its palette once,
     * so the shadow map pass and the main pass bind identical skinning data.
     */
    private void assignSkinningSlots(MetalUniformStream streamer, long pBoneContents, int count) {
        int skinnedIndex = 0;
        for (int i = 0; i < count; i++) {
            float[][] palette = commands[draws.commandIndex[i]].bonePalette;
            draws.skinnedSlot[i] = -1;
            if (draws.floatStride[i] >= 30 && palette != null && pBoneContents != 0L
                    && skinnedIndex < MetalUniformStream.MAX_SKINNED_ENTITIES_PER_FRAME) {
                streamer.writeBonePalette(pBoneContents, skinnedIndex, palette);
                draws.skinnedSlot[i] = skinnedIndex++;
            }
        }
    }

    /**
     * Renders every queued mesh into the 2048x2048 Depth32Float shadow map with mvp = lightSpace * model.
     * Always encoded (an empty queue still clears the map) because the main pass samples it unconditionally.
     */
    private void encodeShadowMapPass(long commandBuffer, MetalPipeline pipeline, MetalTexture texture,
                                     MetalGpuCache resourceCache, MetalUniformStream streamer,
                                     int entityCount, long pBufferContents,
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
                RenderCommand item = commands[draws.commandIndex[i]];
                // Same skinning rule as the main pass: unskinned 30-float meshes draw in bind pose there too
                boolean skinned = draws.skinnedSlot[i] >= 0;
                int slot = MetalUniformStream.SLOT_BASE_SHADOW_CASTERS + i;

                long baseAddr = pBufferContents + currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * slot;
                float[] lightMvp = streamer.createLightSpaceMVPMatrix(item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MVP, 16).put(lightMvp);
                MemoryUtil.memFloatBuffer(baseAddr + MetalUniformStream.OFFSET_MODEL, 16).put(streamer.getModelMatrix());
                streamer.writeLightSpaceMatrix(baseAddr);
                // Only the albedo alpha cutout runs in the depth pass
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_MATERIAL_MASK, draws.materialMask[i] & MetalUniformStream.MATERIAL_ALBEDO);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, draws.floatStride[i]);
                MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, skinned ? MetalUniformStream.PASS_FLAG_SKINNED : 0);

                writeDrawArgs(slot, draws.vertexAddress[i], streamer.getUniformSlotAddress(slot),
                        streamer.getBonePaletteAddress(draws.skinnedSlot[i]), i, defaultResourceId);
                drawEntity(i, slot);
            }
        }

        invokePPP(encoderShadow, SEL_END_ENCODING, send);
        encoder = 0L;
    }

    /**
     * Fills DrawArgs[slot] of the current frame with draw {@code draw}'s material channels (-1 = no material); absent
     * channels (resource ID 0) point at the default texture.
     */
    private void writeDrawArgs(int slot, long vertexAddress, long uniformAddress, long boneAddress,
                               int draw, long defaultResourceId) {
        long entry = pDrawArgs + MetalUniformStream.DRAW_ARGS_STRIDE * slot;
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_VERTICES, vertexAddress);
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_UNIFORMS, uniformAddress);
        MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_BONES, boneAddress);
        long[] resourceIds = draws.resourceIds;
        int base = draw * MetalDrawBuffers.CHANNELS;
        for (int c = 0; c < MetalUniformStream.MATERIAL_CHANNELS; c++) {
            long id = (draw >= 0 && resourceIds[base + c] != 0L) ? resourceIds[base + c] : defaultResourceId;
            MemoryUtil.memPutLong(entry + MetalUniformStream.DRAW_ARGS_TEXTURES + 8L * c, id);
        }
    }

    private void drawEntity(int draw, int slot) {
        if (draws.indexBuffer[draw] != 0L && draws.indexCount[draw] > 0) {
            invokePPPPPPPPPPP(encoder, SEL_DRAW_INDEXED_PRIMITIVES_INSTANCED,
                    MTL_PRIMITIVE_TRIANGLE, (long) draws.indexCount[draw], draws.indexType[draw], draws.indexBuffer[draw], 0L,
                    1L, 0L, (long) slot, send);
        } else {
            drawInstanced(MTL_PRIMITIVE_TRIANGLE, 0L, (long) draws.vertexCount[draw], slot);
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
    private void collectResidency(int entityCount, long uniformBuffer, long boneBuffer,
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
            scratch[n++] = draws.vertexBuffer[i];
            int base = i * MetalDrawBuffers.CHANNELS;
            for (int c = 0; c < MetalDrawBuffers.CHANNELS; c++) scratch[n++] = draws.textures[base + c];
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
        long materialSampler = texture.getMaterialSamplerStateHandle() != 0L ? texture.getMaterialSamplerStateHandle() : texture.getSamplerStateHandle();
        if (materialSampler != 0L) {
            bindFragmentSampler(materialSampler);
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
