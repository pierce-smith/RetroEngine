package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Encodes offscreen 3D passes (grid, batched 3D models, and dynamic downward-raycast shadow blobs)
 * and window quad composition into Metal command buffers.
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
        public long textureHandle;
        public boolean hasTexture;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY = 0.0f;
        public float reflectionStrength = 0.0f;
    }

    public void encodeOffscreen3DPass(
            long commandBuffer, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalGpuCache resourceCache, MetalUniformStream streamer,
            List<RenderEntity> sceneQueue, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw,
            int lightType, float lightPosX, float lightPosY, float lightPosZ,
            float lightDirX, float lightDirY, float lightDirZ,
            float lightR, float lightG, float lightB, float lightIntensity) {

        long send = device.getObjcMsgSend();
        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long offscreenPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachOff = invokePPP(offscreenPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachOff0 = invokePPPP(colorAttachOff, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachOff0, sel_registerName("setTexture:"), framebuffer.getOffscreenColorTexture(), send);
        invokePPPP(colorAttachOff0, sel_registerName("setLoadAction:"), 2L, send);
        invokePPPP(colorAttachOff0, sel_registerName("setStoreAction:"), 1L, send);

        if (framebuffer.getOffscreenDepthTexture() != 0L) {
            long depthAttachOff = invokePPP(offscreenPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachOff, sel_registerName("setTexture:"), framebuffer.getOffscreenDepthTexture(), send);
            invokePPPP(depthAttachOff, sel_registerName("setLoadAction:"), 2L, send);
            invokePPPP(depthAttachOff, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoder3D = invokePPPP(commandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), offscreenPassDesc, send);

        float aspect = 712.0f / 488.0f;
        long currentSlotOffset = streamer.getCurrentSlotOffset();
        long streamingUniformBuffer = streamer.getStreamingUniformBuffer();
        long pBufferContents = invokePPP(streamingUniformBuffer, sel_registerName("contents"), send);

        // 1A. Grid Mesh Encoding
        if (resourceCache.getGridVertexBuffer() != 0L && resourceCache.getGridVertexCount() > 0) {
            float[] mvpGrid = streamer.createGridMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw);
            if (pBufferContents != 0L) {
                long gridAddr = pBufferContents + currentSlotOffset;
                FloatBuffer fb = MemoryUtil.memFloatBuffer(gridAddr, 16);
                fb.clear();
                fb.put(mvpGrid);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_HAS_TEXTURE, 0);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_IS_UNLIT, 1);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_STRIDE, 9);
                MemoryUtil.memPutInt(gridAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 0);
            }

            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilState3D(), send);

            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getGridVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 1L, 0L, (long) resourceCache.getGridVertexCount(), send);
        }

        // 1B. Instanced & Batched Opaque 3D Models (Drawn FIRST to populate color & depth buffers)
        if (sceneQueue != null && !sceneQueue.isEmpty()) {
            sceneQueue.sort(BATCH_COMPARATOR);

            int entityIndex = 0;
            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilState3D(), send);

            long lastVbo = -1L;
            long lastTexture = -1L;

            for (RenderEntity item : sceneQueue) {
                if (entityIndex >= MetalUniformStream.MAX_ENTITIES_PER_FRAME) break;

                long currentVbo = item.vertexBufferHandle;
                long currentTex = (item.hasTexture && item.textureHandle != 0L) ? item.textureHandle : texture.getBindableTexture();

                if (currentVbo != lastVbo) {
                    invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), currentVbo, 0L, 0L, send);
                    lastVbo = currentVbo;
                }

                if (currentTex != lastTexture) {
                    invokePPPPP(encoder3D, sel_registerName("setFragmentTexture:atIndex:"), currentTex, 0L, send);
                    if (texture.getSamplerStateHandle() != 0L) {
                        invokePPPPP(encoder3D, sel_registerName("setFragmentSamplerState:atIndex:"), texture.getSamplerStateHandle(), 0L, send);
                    }
                    lastTexture = currentTex;
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

                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_HAS_TEXTURE, item.hasTexture ? 1 : 0);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_IS_UNLIT, 0);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_STRIDE, 14);
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 0);
                }

                invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, entityUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, entityUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) item.vertexCount, send);

                entityIndex++;
            }
        }

        // 1C. Dynamic Downward-Raycast Surface Shadow Blobs Pass (Pass Flag Bit 0)
        // Drawn AFTER 3D models with Depth Write DISABLED so shadow quads blend smoothly without punching holes in geometry!
        long groundQuadBuf = resourceCache.getGroundQuadBuffer();
        if (sceneQueue != null && !sceneQueue.isEmpty() && groundQuadBuf != 0L) {
            int shadowIndex = 0;
            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            // Use depthStencilStateShadow (Depth Test LessEqual, Depth Write OFF)
            long shadowDepthState = pipeline.getDepthStencilStateShadow() != 0L ? pipeline.getDepthStencilStateShadow() : pipeline.getDepthStencilState3D();
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), shadowDepthState, send);
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), groundQuadBuf, 0L, 0L, send);

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
                    MemoryUtil.memPutInt(baseAddr + MetalUniformStream.OFFSET_PASS_FLAGS, 1); // SHADOW_BLOB
                }

                invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, shadowUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, shadowUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, 6L, send);

                shadowIndex++;
            }
        }

        invokePPP(encoder3D, sel_registerName("endEncoding"), send);
    }

    public void encodeWindowCompositionPass(
            long commandBuffer, long drawableTexture, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalTargetPass framebuffer, MetalGpuCache resourceCache, MetalUniformStream streamer) {

        long send = device.getObjcMsgSend();
        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long windowPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachWin = invokePPP(windowPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachWin0 = invokePPPP(colorAttachWin, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachWin0, sel_registerName("setTexture:"), drawableTexture, send);
        invokePPPP(colorAttachWin0, sel_registerName("setLoadAction:"), 2L, send);
        invokePPPP(colorAttachWin0, sel_registerName("setStoreAction:"), 1L, send);

        if (framebuffer.getWindowDepthTexture() != 0L) {
            long depthAttachWin = invokePPP(windowPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachWin, sel_registerName("setTexture:"), framebuffer.getWindowDepthTexture(), send);
            invokePPPP(depthAttachWin, sel_registerName("setLoadAction:"), 2L, send);
            invokePPPP(depthAttachWin, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoderWin = invokePPPP(commandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), windowPassDesc, send);

        if (resourceCache.getViewportQuadBuffer() != 0L && framebuffer.getOffscreenColorTexture() != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilStateUI(), send);

            invokePPPPP(encoderWin, sel_registerName("setFragmentTexture:atIndex:"), framebuffer.getOffscreenColorTexture(), 0L, send);
            if (texture.getSamplerStateHandle() != 0L) {
                invokePPPPP(encoderWin, sel_registerName("setFragmentSamplerState:atIndex:"), texture.getSamplerStateHandle(), 0L, send);
            }

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getViewportQuadBuffer(), 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), streamer.getUniformBufferIdentity(), 0L, 1L, send);
            invokePPPPPP(encoderWin, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamer.getUniformBufferIdentity(), 0L, 1L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, 6L, send);
        }

        if (resourceCache.getUiVertexBuffer() != 0L && resourceCache.getUiVertexCount() > 0 && pipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineStateUI(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getUiVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) resourceCache.getUiVertexCount(), send);
        }

        invokePPP(encoderWin, sel_registerName("endEncoding"), send);
    }
}
