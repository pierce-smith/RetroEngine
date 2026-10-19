package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Encodes offscreen 3D passes and window quad composition, streaming MVP matrices
 * and multi-light type attributes (Directional, Point, Spot) into GPU uniform slots.
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

        // 1A. Grid Mesh Encoding
        if (resourceCache.getGridVertexBuffer() != 0L && resourceCache.getGridVertexCount() > 0) {
            float[] mvpGrid = streamer.createGridMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw);
            long pBufferContents = invokePPP(streamingUniformBuffer, sel_registerName("contents"), send);
            if (pBufferContents != 0L) {
                FloatBuffer fb = MemoryUtil.memFloatBuffer(pBufferContents + currentSlotOffset, 16);
                fb.clear();
                fb.put(mvpGrid);
                MemoryUtil.memPutInt(pBufferContents + currentSlotOffset + 192L, 0); // hasTexture = 0
                MemoryUtil.memPutInt(pBufferContents + currentSlotOffset + 196L, 1); // isUnlit = 1
                MemoryUtil.memPutInt(pBufferContents + currentSlotOffset + 200L, 9); // stride = 9
            }

            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilState3D(), send);

            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getGridVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 1L, 0L, (long) resourceCache.getGridVertexCount(), send);
        }

        // 1B. Instanced & Batched 3D Models
        if (sceneQueue != null && !sceneQueue.isEmpty()) {
            sceneQueue.sort(BATCH_COMPARATOR);

            int entityIndex = 0;
            long pBufferContents = invokePPP(streamingUniformBuffer, sel_registerName("contents"), send);
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

                long entityUniformOffset = currentSlotOffset + MetalUniformStream.UNIFORM_STRIDE * (1 + entityIndex);
                float[] mvpModel = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);
                float[] modelMat = streamer.getModelMatrix();

                if (pBufferContents != 0L) {
                    long baseAddr = pBufferContents + entityUniformOffset;

                    // MVP Matrix (64 bytes: 0..63)
                    FloatBuffer fbMVP = MemoryUtil.memFloatBuffer(baseAddr, 16);
                    fbMVP.clear(); fbMVP.put(mvpModel);

                    // Model Matrix (64 bytes: 64..127)
                    FloatBuffer fbModel = MemoryUtil.memFloatBuffer(baseAddr + 64L, 16);
                    fbModel.clear(); fbModel.put(modelMat);

                    // Light Dir & Intensity (16 bytes: 128..143)
                    MemoryUtil.memPutFloat(baseAddr + 128L, lightDirX);
                    MemoryUtil.memPutFloat(baseAddr + 132L, lightDirY);
                    MemoryUtil.memPutFloat(baseAddr + 136L, lightDirZ);
                    MemoryUtil.memPutFloat(baseAddr + 140L, lightIntensity);

                    // Light Pos & Type (16 bytes: 144..159)
                    MemoryUtil.memPutFloat(baseAddr + 144L, lightPosX);
                    MemoryUtil.memPutFloat(baseAddr + 148L, lightPosY);
                    MemoryUtil.memPutFloat(baseAddr + 152L, lightPosZ);
                    MemoryUtil.memPutFloat(baseAddr + 156L, (float) lightType);

                    // Light Color (16 bytes: 160..175)
                    MemoryUtil.memPutFloat(baseAddr + 160L, lightR);
                    MemoryUtil.memPutFloat(baseAddr + 164L, lightG);
                    MemoryUtil.memPutFloat(baseAddr + 168L, lightB);
                    MemoryUtil.memPutFloat(baseAddr + 172L, 1.0f);

                    // Ambient Color (16 bytes: 176..191)
                    MemoryUtil.memPutFloat(baseAddr + 176L, 0.35f);
                    MemoryUtil.memPutFloat(baseAddr + 180L, 0.35f);
                    MemoryUtil.memPutFloat(baseAddr + 184L, 0.38f);
                    MemoryUtil.memPutFloat(baseAddr + 188L, 1.0f);

                    // Flags & Stride (12 bytes: 192..203)
                    MemoryUtil.memPutInt(baseAddr + 192L, item.hasTexture ? 1 : 0);
                    MemoryUtil.memPutInt(baseAddr + 196L, 0);  // isUnlit = 0
                    MemoryUtil.memPutInt(baseAddr + 200L, 14); // stride = 14
                }

                invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, entityUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, entityUniformOffset, 1L, send);
                invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) item.vertexCount, send);

                entityIndex++;
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

        // Draw Offscreen Viewport Quad
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

        // Draw 2D Editor UI Overlay & Viewport Gizmos
        if (resourceCache.getUiVertexBuffer() != 0L && resourceCache.getUiVertexCount() > 0 && pipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineStateUI(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getUiVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) resourceCache.getUiVertexCount(), send);
        }

        invokePPP(encoderWin, sel_registerName("endEncoding"), send);
    }
}
