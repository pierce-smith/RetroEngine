package com.retro.engine.backend.metal.delegates;

import com.retro.engine.backend.metal.MetalDevice;
import com.retro.engine.backend.metal.MetalPipeline;
import com.retro.engine.backend.metal.MetalTexture;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Delegate managing multi-pass Metal render command encoding:
 * Pass 1: Offscreen 3D Scene Pass & Instanced Mesh Batch Encoding.
 * Pass 2: Window Composition Pass (Offscreen Viewport Quad + 2D Editor UI Overlay).
 */
public class MetalRenderPassEncoder {

    public static class RenderEntity {
        public long vertexBufferHandle;
        public int vertexCount;
        public long textureHandle;
        public boolean hasTexture;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
    }

    public static class BatchGroupKey {
        public final long vbo;
        public final long texture;

        public BatchGroupKey(long vbo, long texture) {
            this.vbo = vbo;
            this.texture = texture;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BatchGroupKey that = (BatchGroupKey) o;
            return vbo == that.vbo && texture == that.texture;
        }

        @Override
        public int hashCode() {
            return Objects.hash(vbo, texture);
        }
    }

    public void encodeOffscreen3DPass(
            long commandBuffer, MetalDevice device, MetalPipeline pipeline, MetalTexture texture,
            MetalFramebuffer framebuffer, MetalResourceCache resourceCache, MetalUniformStreamer streamer,
            List<RenderEntity> sceneQueue, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {

        long send = device.getObjcMsgSend();
        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long offscreenPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachOff = invokePPP(offscreenPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachOff0 = invokePPPP(colorAttachOff, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachOff0, sel_registerName("setTexture:"), framebuffer.getOffscreenColorTexture(), send);
        invokePPPP(colorAttachOff0, sel_registerName("setLoadAction:"), 2L, send); // Clear
        invokePPPP(colorAttachOff0, sel_registerName("setStoreAction:"), 1L, send); // Store

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
                MemoryUtil.memPutInt(pBufferContents + currentSlotOffset + 64L, 0);
            }

            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilState3D(), send);

            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getGridVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, currentSlotOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 1L, 0L, (long) resourceCache.getGridVertexCount(), send);
        }

        // 1B. Instanced & Batched 3D Models
        Map<BatchGroupKey, List<RenderEntity>> batches = new LinkedHashMap<>();
        for (RenderEntity entity : sceneQueue) {
            BatchGroupKey key = new BatchGroupKey(entity.vertexBufferHandle, entity.textureHandle);
            batches.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }

        int entityIndex = 0;
        long pBufferContents = invokePPP(streamingUniformBuffer, sel_registerName("contents"), send);
        invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineState3D(), send);
        invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilState3D(), send);

        for (Map.Entry<BatchGroupKey, List<RenderEntity>> entry : batches.entrySet()) {
            BatchGroupKey key = entry.getKey();
            List<RenderEntity> groupEntities = entry.getValue();
            if (groupEntities.isEmpty()) continue;

            RenderEntity firstEntity = groupEntities.get(0);
            long texToBind = (firstEntity.hasTexture && key.texture != 0L) ? key.texture : texture.getBindableTexture();
            invokePPPPP(encoder3D, sel_registerName("setFragmentTexture:atIndex:"), texToBind, 0L, send);
            if (texture.getSamplerStateHandle() != 0L) {
                invokePPPPP(encoder3D, sel_registerName("setFragmentSamplerState:atIndex:"), texture.getSamplerStateHandle(), 0L, send);
            }

            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), key.vbo, 0L, 0L, send);

            for (RenderEntity item : groupEntities) {
                if (entityIndex >= MetalUniformStreamer.MAX_ENTITIES_PER_FRAME) break;

                long entityUniformOffset = currentSlotOffset + MetalUniformStreamer.UNIFORM_STRIDE * (1 + entityIndex);
                float[] mvpModel = streamer.create3DMVPMatrix(aspect, camPanX, camPanY, camZoom, camPitch, camYaw, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);

                if (pBufferContents != 0L) {
                    FloatBuffer fb = MemoryUtil.memFloatBuffer(pBufferContents + entityUniformOffset, 16);
                    fb.clear();
                    fb.put(mvpModel);
                    MemoryUtil.memPutInt(pBufferContents + entityUniformOffset + 64L, item.hasTexture ? 1 : 0);
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
            MetalFramebuffer framebuffer, MetalResourceCache resourceCache, MetalUniformStreamer streamer) {

        long send = device.getObjcMsgSend();
        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long windowPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachWin = invokePPP(windowPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachWin0 = invokePPPP(colorAttachWin, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachWin0, sel_registerName("setTexture:"), drawableTexture, send);
        invokePPPP(colorAttachWin0, sel_registerName("setLoadAction:"), 2L, send); // Clear
        invokePPPP(colorAttachWin0, sel_registerName("setStoreAction:"), 1L, send); // Store

        if (framebuffer.getWindowDepthTexture() != 0L) {
            long depthAttachWin = invokePPP(windowPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachWin, sel_registerName("setTexture:"), framebuffer.getWindowDepthTexture(), send);
            invokePPPP(depthAttachWin, sel_registerName("setLoadAction:"), 2L, send); // Clear
            invokePPPP(depthAttachWin, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoderWin = invokePPPP(commandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), windowPassDesc, send);

        // 2A. Draw Offscreen Viewport Quad
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

        // 2B. Draw 2D Editor UI Overlay & Viewport Gizmos
        if (resourceCache.getUiVertexBuffer() != 0L && resourceCache.getUiVertexCount() > 0 && pipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), pipeline.getPipelineStateUI(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), pipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), resourceCache.getUiVertexBuffer(), 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) resourceCache.getUiVertexCount(), send);
        }

        invokePPP(encoderWin, sel_registerName("endEncoding"), send);
    }
}
