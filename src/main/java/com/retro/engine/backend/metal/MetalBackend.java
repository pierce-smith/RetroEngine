package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * MetalBackend implements IRenderBackend by coordinating MetalDevice, MetalBuffer, MetalTexture, and MetalPipeline.
 * Step 1: Dedicated Offscreen Viewport Render Target (712x488 canvas aspect ratio).
 * Step 2: Off-Heap Ring-Buffered Uniform Streaming (Triple Buffering).
 * Step 3: Instanced Mesh & State Batching (Grouped by VBO + Texture handle).
 */
public class MetalBackend implements IRenderBackend {
    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    private long offscreenColorTexture = 0L;
    private long offscreenDepthTexture = 0L;
    private long windowDepthTexture = 0L;

    private long gridVertexBuffer = 0L;
    private int gridVertexCount = 0;

    private long uiVertexBuffer = 0L;
    private int uiVertexCount = 0;

    private long viewportQuadBuffer = 0L;

    // Ring-Buffered Uniform Streaming (Triple Buffering)
    private static final int RING_SLOTS = 3;
    private static final long UNIFORM_STRIDE = 256L; // 256-byte alignment required by Metal
    private static final int MAX_ENTITIES_PER_FRAME = 256;
    private static final long FRAME_SLOT_SIZE = UNIFORM_STRIDE * (1 + MAX_ENTITIES_PER_FRAME);
    private static final long TOTAL_RING_BUFFER_SIZE = FRAME_SLOT_SIZE * RING_SLOTS;

    private long streamingUniformBuffer = 0L;
    private long uniformBufferIdentity = 0L;
    private int frameIndex = 0;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    // Camera Transform State
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    // Multi-entity Batch Queue & VRAM Caches
    private final List<RenderEntity> sceneQueue = new ArrayList<>();
    private final Map<String, Long> gpuMeshCache = new HashMap<>();
    private final Map<String, Long> gpuTextureCache = new HashMap<>();

    private static class RenderEntity {
        long vertexBufferHandle;
        int vertexCount;
        long textureHandle;
        boolean hasTexture;
        float px, py, pz, rx, ry, rz, sx, sy, sz;
    }

    private static class BatchGroupKey {
        final long vbo;
        final long texture;

        BatchGroupKey(long vbo, long texture) {
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
            return Long.hashCode(vbo) * 31 + Long.hashCode(texture);
        }
    }

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        if (!metalDevice.initialize(windowInfo)) return false;
        if (!metalPipeline.initialize(metalDevice)) return false;

        initOffscreenFramebuffer(712, 488);
        initViewportQuad();

        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        // Allocate dynamic ring-buffered VRAM allocation for uniform streaming
        streamingUniformBuffer = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), TOTAL_RING_BUFFER_SIZE);
        initIdentityUniform();

        System.out.println("[MetalBackend] Step 3 Active: Off-Heap Uniform Streaming + Instanced Mesh Batching Initialized.");
        return true;
    }

    private void initOffscreenFramebuffer(int width, int height) {
        long d = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (d == 0L || send == 0L) return;

        long descClass = objc_getClass("MTLTextureDescriptor");

        // 1. Offscreen Color Texture (RenderTarget + ShaderRead)
        long desc = invokePPP(descClass, sel_registerName("new"), send);
        invokePPPP(desc, sel_registerName("setTextureType:"), 2L, send); // 2L = MTLTextureType2D
        invokePPPP(desc, sel_registerName("setPixelFormat:"), 80L, send); // 80L = BGRA8Unorm
        invokePPPP(desc, sel_registerName("setWidth:"), (long) width, send);
        invokePPPP(desc, sel_registerName("setHeight:"), (long) height, send);
        invokePPPP(desc, sel_registerName("setUsage:"), 5L, send); // RenderTarget (4) | ShaderRead (1)
        invokePPPP(desc, sel_registerName("setStorageMode:"), 2L, send); // Private

        offscreenColorTexture = invokePPPP(d, sel_registerName("newTextureWithDescriptor:"), desc, send);

        // 2. Offscreen Depth Texture (Depth32Float)
        long depthDesc = invokePPP(descClass, sel_registerName("new"), send);
        invokePPPP(depthDesc, sel_registerName("setTextureType:"), 2L, send);
        invokePPPP(depthDesc, sel_registerName("setPixelFormat:"), 252L, send); // 252L = Depth32Float
        invokePPPP(depthDesc, sel_registerName("setWidth:"), (long) width, send);
        invokePPPP(depthDesc, sel_registerName("setHeight:"), (long) height, send);
        invokePPPP(depthDesc, sel_registerName("setUsage:"), 4L, send);
        invokePPPP(depthDesc, sel_registerName("setStorageMode:"), 2L, send);

        offscreenDepthTexture = invokePPPP(d, sel_registerName("newTextureWithDescriptor:"), depthDesc, send);

        // 3. Main Window Depth Texture
        long winDepthDesc = invokePPP(descClass, sel_registerName("new"), send);
        invokePPPP(winDepthDesc, sel_registerName("setTextureType:"), 2L, send);
        invokePPPP(winDepthDesc, sel_registerName("setPixelFormat:"), 252L, send);
        invokePPPP(winDepthDesc, sel_registerName("setWidth:"), 1280L, send);
        invokePPPP(winDepthDesc, sel_registerName("setHeight:"), 720L, send);
        invokePPPP(winDepthDesc, sel_registerName("setUsage:"), 4L, send);
        invokePPPP(winDepthDesc, sel_registerName("setStorageMode:"), 2L, send);

        windowDepthTexture = invokePPPP(d, sel_registerName("newTextureWithDescriptor:"), winDepthDesc, send);
    }

    private void initViewportQuad() {
        // Viewport canvas bounds in NDC: X: -0.55f to 0.55f, Y: -0.50f to 0.88f
        float[] quadVerts = new float[] {
            // X,       Y,      Z,    R,    G,    B,    A,    U,    V
            -0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
            -0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f,
             0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,

            -0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f,
             0.55f, -0.50f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
             0.55f,  0.88f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f
        };
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), quadVerts, 9);
        viewportQuadBuffer = buf.getHandle();
    }

    private void initIdentityUniform() {
        float[] identityMVP = new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        long send = metalDevice.getObjcMsgSend();
        uniformBufferIdentity = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), send, 256L);
        long pIdentity = invokePPP(uniformBufferIdentity, sel_registerName("contents"), send);
        if (pIdentity != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pIdentity, 16);
            fb.clear();
            fb.put(identityMVP);
            MemoryUtil.memPutInt(pIdentity + 64L, 1); // hasTexture = 1
        }
    }

    @Override public long getOffscreenViewportTexture() { return offscreenColorTexture; }
    @Override public void resizeViewport(int width, int height) { if (width > 0 && height > 0) { initOffscreenFramebuffer(width, height); } }
    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) { this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw; }

    @Override
    public void updateGridMesh(float[] vertices, int count) {
        if (vertices == null || count <= 0) {
            this.gridVertexCount = 0;
            return;
        }
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
        this.gridVertexBuffer = buf.getHandle();
        this.gridVertexCount = count;
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        if (vertices == null || count <= 0) {
            this.uiVertexCount = 0;
            return;
        }
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 6);
        this.uiVertexBuffer = buf.getHandle();
        this.uiVertexCount = count;
    }

    @Override public void clearSceneQueue() { sceneQueue.clear(); }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        if (meshPath == null || vertices == null || vertexCount <= 0) return;

        long vbo = gpuMeshCache.computeIfAbsent(meshPath, k -> {
            MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
            return buf.getHandle();
        });

        long texHandle = 0L;
        boolean hasTex = false;
        if (texturePath != null && pixels != null && pixels.length > 0) {
            texHandle = gpuTextureCache.computeIfAbsent(texturePath, k -> {
                MetalTexture tex = new MetalTexture();
                tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, texW, texH);
                return tex.getBindableTexture();
            });
            hasTex = (texHandle != 0L);
        }

        RenderEntity item = new RenderEntity();
        item.vertexBufferHandle = vbo;
        item.vertexCount = vertexCount;
        item.textureHandle = texHandle;
        item.hasTexture = hasTex;
        item.px = px; item.py = py; item.pz = pz;
        item.rx = rx; item.ry = ry; item.rz = rz;
        item.sx = sx; item.sy = sy; item.sz = sz;

        sceneQueue.add(item);
    }

    // Camera View & Projection Matrix Calculations
    private float[] createCameraViewMatrix() {
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        return new float[] {
            cy, sy * sp, sy * cp, 0.0f,
            0.0f, cp, -sp, 0.0f,
            -sy, cy * sp, cy * cp, 0.0f,
            camPanX, camPanY, camZoom, 1.0f
        };
    }

    private float[] createModelMatrix(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float radRx = (float) Math.toRadians(rx);
        float radRy = (float) Math.toRadians(ry);
        float radRz = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radRx), sx1 = (float) Math.sin(radRx);
        float cy = (float) Math.cos(radRy), sy1 = (float) Math.sin(radRy);
        float cz = (float) Math.cos(radRz), sz1 = (float) Math.sin(radRz);

        float m00 = cz * cy;
        float m01 = cz * sy1 * sx1 - sz1 * cx;
        float m02 = cz * sy1 * cx + sz1 * sx1;

        float m10 = sz1 * cy;
        float m11 = sz1 * sy1 * sx1 + cz * cx;
        float m12 = sz1 * sy1 * cx - cz * sx1;

        float m20 = -sy1;
        float m21 = cy * sx1;
        float m22 = cy * cx;

        return new float[] {
            m00 * sx, m10 * sx, m20 * sx, 0.0f,
            m01 * sy, m11 * sy, m21 * sy, 0.0f,
            m02 * sz, m12 * sz, m22 * sz, 0.0f,
            px, py, pz, 1.0f
        };
    }

    private float[] multiplyMat4(float[] A, float[] B) {
        float[] C = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += A[row + k * 4] * B[k + col * 4];
                }
                C[row + col * 4] = sum;
            }
        }
        return C;
    }

    private float[] createPerspectiveMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);

        return new float[] {
            p00, 0.0f, 0.0f, 0.0f,
            0.0f, p11, 0.0f, 0.0f,
            0.0f, 0.0f, p22, -1.0f,
            0.0f, 0.0f, p23, 0.0f
        };
    }

    private float[] createGridMVPMatrix(float aspect) {
        float[] P = createPerspectiveMatrix(aspect);
        float[] V = createCameraViewMatrix();
        return multiplyMat4(P, V);
    }

    private float[] create3DMVPMatrix(float aspect, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float[] P = createPerspectiveMatrix(aspect);
        float[] V = createCameraViewMatrix();
        float[] M = createModelMatrix(px, py, pz, rx, ry, rz, sx, sy, sz);
        float[] VM = multiplyMat4(V, M);
        return multiplyMat4(P, VM);
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        // Ring Buffer Slot Advance
        frameIndex = (frameIndex + 1) % RING_SLOTS;
        long currentSlotOffset = frameIndex * FRAME_SLOT_SIZE;

        float aspect = 712.0f / 488.0f; // Viewport Canvas Aspect Ratio
        float[] mvpGrid = createGridMVPMatrix(aspect);

        // Stream Grid/Camera Uniform directly to off-heap VRAM pointer at slot index 0
        long pBufferContents = invokePPP(streamingUniformBuffer, sel_registerName("contents"), send);
        long gridUniformOffset = currentSlotOffset;

        if (pBufferContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pBufferContents + gridUniformOffset, 16);
            fb.clear();
            fb.put(mvpGrid);
            MemoryUtil.memPutInt(pBufferContents + gridUniformOffset + 64L, 0); // Grid has no texture
        }

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), send);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), send);

        // =========================================================================
        // PASS 1: OFFSCREEN 3D SCENE PASS (Grid + Models -> offscreenColorTexture)
        // =========================================================================
        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long offscreenPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachOff = invokePPP(offscreenPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachOff0 = invokePPPP(colorAttachOff, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachOff0, sel_registerName("setTexture:"), offscreenColorTexture, send);
        invokePPPP(colorAttachOff0, sel_registerName("setLoadAction:"), 2L, send); // Clear
        invokePPPP(colorAttachOff0, sel_registerName("setStoreAction:"), 1L, send); // Store

        if (offscreenDepthTexture != 0L) {
            long depthAttachOff = invokePPP(offscreenPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachOff, sel_registerName("setTexture:"), offscreenDepthTexture, send);
            invokePPPP(depthAttachOff, sel_registerName("setLoadAction:"), 2L, send); // Clear
            invokePPPP(depthAttachOff, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoder3D = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), offscreenPassDesc, send);

        // Subpass 1A: 3D Ground Grid (Lines = 1L)
        if (gridVertexBuffer != 0L && gridVertexCount > 0) {
            invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineState3D(), send);
            invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilState3D(), send);

            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), gridVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), streamingUniformBuffer, gridUniformOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("setFragmentBuffer:offset:atIndex:"), streamingUniformBuffer, gridUniformOffset, 1L, send);
            invokePPPPPP(encoder3D, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 1L, 0L, (long) gridVertexCount, send);
        }

        // Subpass 1B: Instanced & Batched 3D Model Meshes
        // Group scene entities by (vbo, textureHandle) signature to eliminate GPU re-binding overhead
        Map<BatchGroupKey, List<RenderEntity>> batches = new LinkedHashMap<>();
        for (RenderEntity entity : sceneQueue) {
            BatchGroupKey key = new BatchGroupKey(entity.vertexBufferHandle, entity.textureHandle);
            batches.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }

        int entityIndex = 0;
        invokePPPP(encoder3D, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineState3D(), send);
        invokePPPP(encoder3D, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilState3D(), send);

        for (Map.Entry<BatchGroupKey, List<RenderEntity>> entry : batches.entrySet()) {
            BatchGroupKey key = entry.getKey();
            List<RenderEntity> groupEntities = entry.getValue();
            if (groupEntities.isEmpty()) continue;

            RenderEntity firstEntity = groupEntities.get(0);

            // Bind Texture & Sampler ONCE per batch group
            long texToBind = (firstEntity.hasTexture && key.texture != 0L) ? key.texture : metalTexture.getBindableTexture();
            invokePPPPP(encoder3D, sel_registerName("setFragmentTexture:atIndex:"), texToBind, 0L, send);
            if (metalTexture.getSamplerStateHandle() != 0L) {
                invokePPPPP(encoder3D, sel_registerName("setFragmentSamplerState:atIndex:"), metalTexture.getSamplerStateHandle(), 0L, send);
            }

            // Bind Vertex Buffer ONCE per batch group
            invokePPPPPP(encoder3D, sel_registerName("setVertexBuffer:offset:atIndex:"), key.vbo, 0L, 0L, send);

            // Dispatch entities in batch
            for (RenderEntity item : groupEntities) {
                if (entityIndex >= MAX_ENTITIES_PER_FRAME) break;

                long entityUniformOffset = currentSlotOffset + UNIFORM_STRIDE * (1 + entityIndex);
                float[] mvpModel = create3DMVPMatrix(aspect, item.px, item.py, item.pz, item.rx, item.ry, item.rz, item.sx, item.sy, item.sz);

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

        // =========================================================================
        // PASS 2: WINDOW COMPOSITION PASS (Offscreen Viewport Quad + 2D UI Overlay)
        // =========================================================================
        long drawableTexture = invokePPP(currentDrawable, sel_registerName("texture"), send);

        long windowPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);
        long colorAttachWin = invokePPP(windowPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttachWin0 = invokePPPP(colorAttachWin, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttachWin0, sel_registerName("setTexture:"), drawableTexture, send);
        invokePPPP(colorAttachWin0, sel_registerName("setLoadAction:"), 2L, send); // Clear
        invokePPPP(colorAttachWin0, sel_registerName("setStoreAction:"), 1L, send); // Store

        if (windowDepthTexture != 0L) {
            long depthAttachWin = invokePPP(windowPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachWin, sel_registerName("setTexture:"), windowDepthTexture, send);
            invokePPPP(depthAttachWin, sel_registerName("setLoadAction:"), 2L, send); // Clear
            invokePPPP(depthAttachWin, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoderWin = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), windowPassDesc, send);

        // Subpass 2A: Draw Offscreen 3D Viewport Quad into Viewport Canvas Region
        if (viewportQuadBuffer != 0L && offscreenColorTexture != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineState3D(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilStateUI(), send); // Depth disabled for UI quad

            invokePPPPP(encoderWin, sel_registerName("setFragmentTexture:atIndex:"), offscreenColorTexture, 0L, send);
            if (metalTexture.getSamplerStateHandle() != 0L) {
                invokePPPPP(encoderWin, sel_registerName("setFragmentSamplerState:atIndex:"), metalTexture.getSamplerStateHandle(), 0L, send);
            }

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), viewportQuadBuffer, 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBufferIdentity, 0L, 1L, send);
            invokePPPPPP(encoderWin, sel_registerName("setFragmentBuffer:offset:atIndex:"), uniformBufferIdentity, 0L, 1L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, 6L, send);
        }

        // Subpass 2B: Draw 2D Editor UI Overlay & Viewport Transform Gizmos
        if (uiVertexBuffer != 0L && uiVertexCount > 0 && metalPipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoderWin, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineStateUI(), send);
            invokePPPP(encoderWin, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoderWin, sel_registerName("setVertexBuffer:offset:atIndex:"), uiVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoderWin, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) uiVertexCount, send);
        }

        invokePPP(encoderWin, sel_registerName("endEncoding"), send);
    }

    @Override
    public void endFrame() {
        if (currentCommandBuffer == 0L || currentDrawable == 0L) return;
        invokePPPP(currentCommandBuffer, sel_registerName("presentDrawable:"), currentDrawable, metalDevice.getObjcMsgSend());
    }

    @Override
    public void present() {
        if (currentCommandBuffer == 0L) return;
        invokePPP(currentCommandBuffer, sel_registerName("commit"), metalDevice.getObjcMsgSend());
        currentCommandBuffer = 0L;
        currentDrawable = 0L;
    }

    @Override
    public void shutdown() {
        System.out.println("[MetalBackend] Releasing Metal offscreen and pipeline resources...");
    }

    @Override public long createBuffer(BufferCreateInfo createInfo) { return 102L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureCreateInfo createInfo) { return 202L; }
    @Override public void destroyTexture(long handle) {}
    @Override public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override public String getDeviceName() { return "Native Apple Metal Driver (Step 3 Instanced Batching Active)"; }
}
