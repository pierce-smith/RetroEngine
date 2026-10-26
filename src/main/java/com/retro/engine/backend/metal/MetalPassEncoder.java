package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Encodes offscreen 3D passes (procedural grid, batched 3D models with environment reflections,
 * and dynamic downward-raycast shadow blobs) and window quad composition into Metal command buffers.
 */
public class MetalPassEncoder {

    private static final java.util.Comparator<RenderEntity> BATCH_COMPARATOR = (a, b) -> {
        int cmp = Long.compare(a.vertexBufferHandle, b.vertexBufferHandle);
        if (cmp != 0) return cmp;
        return Long.compare(a.textureHandle, b.textureHandle);
    };

    public static class RenderEntity {
        public long vertexBufferHandle;
        public int vertexCount;
        public long indexBufferHandle;   // 0L = non-indexed draw
        public int indexCount;
        public long indexType;           // MTLIndexType (0 = UInt16, 1 = UInt32)
        public long textureHandle;
        public boolean hasTexture;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY = 0.0f;
        public float reflectionStrength = 0.0f;
        public int stride = 22;
        public float[][] bonePalette = null;
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
    private static final long SEL_DRAW_INDEXED_PRIMITIVES = sel_registerName("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:");
    private static final long SEL_END_ENCODING = sel_registerName("endEncoding");

    // Highest buffer slot bound by any pass: 0 = vertices, 1 = uniforms, 2 = bone palette
    private static final int MAX_BUFFER_SLOTS = 3;
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
        beginEncoder(encoder3D);
        invokePPPP(encoder3D, SEL_SET_TRIANGLE_FILL_MODE, 0L, send); // MTLTriangleFillModeFill

        // The offscreen image is stretched over the docked Scene tile, so project with that tile's aspect
        float aspect = sceneAspect > 0.0f ? sceneAspect
                : (framebuffer.getWidth() > 0 && framebuffer.getHeight() > 0) ? ((float) framebuffer.getWidth() / (float) framebuffer.getHeight()) : (712.0f / 488.0f);
        long currentSlotOffset = streamer.getCurrentSlotOffset();
        long streamingUniformBuffer = streamer.getStreamingUniformBuffer();
        long pBufferContents = invokePPP(streamingUniformBuffer, SEL_CONTENTS, send);

        // vertexMain declares the bone palette at buffer(2); keep it bound for every draw in this pass
        long bonePaletteBuffer = streamer.getBonePaletteBuffer();
        long pBoneContents = bonePaletteBuffer != 0L ? invokePPP(bonePaletteBuffer, SEL_CONTENTS, send) : 0L;
        bindVertexBuffer(bonePaletteBuffer, streamer.getBonePaletteOffset(0), 2);

        // 1A. Procedural Ray-Plane Infinite Grid Encoding
        if (resourceCache.getGridVertexBuffer() != 0L && resourceCache.getGridVertexCount() > 0) {
            float[] mvpGrid = streamer.createGridMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw);
            if (pBufferContents != 0L) {
                long gridAddr = pBufferContents + currentSlotOffset;
                FloatBuffer fb = MemoryUtil.memFloatBuffer(gridAddr, 16);
                fb.clear();
                fb.put(mvpGrid);

                float[] identityModel = streamer.createModelMatrix(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);
                FloatBuffer fbModel = MemoryUtil.memFloatBuffer(gridAddr + MetalUniformStream.OFFSET_MODEL, 16);
                fbModel.clear();
                fbModel.put(identityModel);

                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_HAS_TEXTURE, 0);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_IS_UNLIT, 1);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_STRIDE, 9);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 0);
            }

            bindPipeline(pipeline.getPipelineState3D());
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            bindDepthStencil(shadowDepthState);

            bindVertexBuffer(resourceCache.getGridVertexBuffer(), 0L, 0);
            bindVertexBuffer(streamingUniformBuffer, currentSlotOffset, 1);
            bindFragmentBuffer(streamingUniformBuffer, currentSlotOffset, 1);

            // Draw 6 vertices as Triangles (3L = MTLPrimitiveTypeTriangle) for Procedural Infinite Grid Quad
            invokePPPPPP(encoder3D, SEL_DRAW_PRIMITIVES, 3L, 0L, 6L, send);

            // Draw remaining 2 vertices as Lines (1L = MTLPrimitiveTypeLine) for Vertical Green Y-Axis Pillar
            if (resourceCache.getGridVertexCount() >= 8) {
                invokePPPPPP(encoder3D, SEL_DRAW_PRIMITIVES, 1L, 6L, 2L, send);
            }
        }

        // 1B. Instanced & Batched Opaque 3D Models
        if (sceneQueue != null && !sceneQueue.isEmpty()) {
            sceneQueue.sort(BATCH_COMPARATOR);

            int entityIndex = 0;
            bindPipeline(pipeline.getPipelineState3D());
            bindDepthStencil(pipeline.getDepthStencilState3D());

            int skinnedIndex = 0;

            for (RenderEntity item : sceneQueue) {
                if (entityIndex >= MetalUniformStream.MAX_ENTITIES_PER_FRAME) break;

                long currentVbo = item.vertexBufferHandle;
                long currentTex = (item.hasTexture && item.textureHandle != 0L) ? item.textureHandle : texture.getBindableTexture();

                bindVertexBuffer(currentVbo, 0L, 0);
                bindFragmentTexture(currentTex);
                if (texture.getSamplerStateHandle() != 0L) {
                    bindFragmentSampler(texture.getSamplerStateHandle());
                }

                boolean skinned = item.stride >= 30 && item.bonePalette != null && pBoneContents != 0L
                        && skinnedIndex < MetalUniformStream.MAX_SKINNED_ENTITIES_PER_FRAME;
                if (skinned) {
                    streamer.writeBonePalette(pBoneContents, skinnedIndex, item.bonePalette);
                    bindVertexBuffer(bonePaletteBuffer, streamer.getBonePaletteOffset(skinnedIndex), 2);
                    skinnedIndex++;
                }

                long entityUniformOffset = currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * (1 + 64 + entityIndex);
                float[] mvpModel = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                float[] modelMat = streamer.getModelMatrix();

                if (pBufferContents != 0L) {
                    long baseAddr = pBufferContents + entityUniformOffset;

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

                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_HAS_TEXTURE, item.hasTexture ? 1 : 0);
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
                }

                bindVertexBuffer(streamingUniformBuffer, entityUniformOffset, 1);
                bindFragmentBuffer(streamingUniformBuffer, entityUniformOffset, 1);
                if (item.indexBufferHandle != 0L && item.indexCount > 0) {
                    invokePPPPPPPV(encoder3D, SEL_DRAW_INDEXED_PRIMITIVES,
                            3L, (long) item.indexCount, item.indexType, item.indexBufferHandle, 0L, send);
                } else {
                    invokePPPPPP(encoder3D, SEL_DRAW_PRIMITIVES, 3L, 0L, (long) item.vertexCount, send);
                }

                entityIndex++;
            }
        }

        // 1C. Dynamic Downward-Raycast Surface Shadow Blobs Pass
        long groundQuadBuf = resourceCache.getGroundQuadBuffer();
        if (sceneQueue != null && !sceneQueue.isEmpty() && groundQuadBuf != 0L) {
            int shadowIndex = 0;
            bindPipeline(pipeline.getPipelineState3D());
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            bindDepthStencil(shadowDepthState);
            bindVertexBuffer(groundQuadBuf, 0L, 0);

            for (RenderEntity item : sceneQueue) {
                if (shadowIndex >= 64) break;

                float clearance = item.py - item.groundY;
                if (clearance < -0.01f) continue;

                float heightFade = Math.max(0.0f, 1.0f - (clearance / 10.0f));
                if (heightFade < 0.05f) continue;

                float shadowScale = Math.max(item.sx, item.sz) * 1.25f;
                long shadowUniformOffset = currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * (1 + shadowIndex);
                float shadowY = item.groundY + 0.005f;
                float[] mvpShadow = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, shadowY, item.pz, 0.0f, 0.0f, 0.0f, shadowScale, 1.0f, shadowScale);
                float[] modelMat = streamer.getModelMatrix();

                if (pBufferContents != 0L) {
                    long baseAddr = pBufferContents + shadowUniformOffset;
                    FloatBuffer fbMVP = MemoryUtil.memFloatBuffer(baseAddr, 16);
                    fbMVP.clear(); fbMVP.put(mvpShadow);

                    FloatBuffer fbModel = MemoryUtil.memFloatBuffer(baseAddr + 64L, 16);
                    fbModel.clear(); fbModel.put(modelMat);

                    MemoryUtil.memPutFloat(baseAddr + 212L, heightFade);

                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_HAS_TEXTURE, 0);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, 14);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 1);
                }

                bindVertexBuffer(streamingUniformBuffer, shadowUniformOffset, 1);
                bindFragmentBuffer(streamingUniformBuffer, shadowUniformOffset, 1);
                invokePPPPPP(encoder3D, SEL_DRAW_PRIMITIVES, 3L, 0L, 6L, send);

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
        if (pipelineComp == 0L) pipelineComp = pipeline.getPipelineState3D();

        if (resourceCache.isViewportQuadVisible() && resourceCache.getViewportQuadBuffer() != 0L && framebuffer.getOffscreenColorTexture() != 0L && pipelineComp != 0L) {
            bindPipeline(pipelineComp);

            bindFragmentTexture(framebuffer.getOffscreenColorTexture());
            if (texture.getSamplerStateHandle() != 0L) {
                bindFragmentSampler(texture.getSamplerStateHandle());
            }

            bindVertexBuffer(resourceCache.getViewportQuadBuffer(), 0L, 0);
            bindVertexBuffer(streamer.getUniformBufferIdentity(), 0L, 1);
            bindVertexBuffer(streamer.getBonePaletteBuffer(), 0L, 2);
            bindFragmentBuffer(streamer.getUniformBufferIdentity(), 0L, 1);
            invokePPPPPP(encoderWin, SEL_DRAW_PRIMITIVES, 3L, 0L, 6L, send);
        }

        // 2. 2D Editor UI Geometry Batch Pass
        if (resourceCache.getUiVertexBuffer() != 0L && resourceCache.getUiVertexCount() > 0 && pipeline.getPipelineStateUI() != 0L) {
            bindPipeline(pipeline.getPipelineStateUI());

            bindVertexBuffer(resourceCache.getUiVertexBuffer(), 0L, 0);
            invokePPPPPP(encoderWin, SEL_DRAW_PRIMITIVES, 3L, 0L, (long) resourceCache.getUiVertexCount(), send);
        }

        invokePPP(encoderWin, SEL_END_ENCODING, send);
        encoder = 0L;
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
