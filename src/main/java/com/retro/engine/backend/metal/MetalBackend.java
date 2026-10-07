package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * MetalBackend implements IRenderBackend by coordinating MetalDevice, MetalBuffer, MetalTexture, and MetalPipeline.
 * Features 3 rendering passes:
 *   Pass 1: 3D Perspective Ground Grid (Lines)
 *   Pass 2: 3D Perspective Model Mesh (Triangles)
 *   Pass 3: 2D Editor UI Overlay & Viewport Transform Gizmos
 */
public class MetalBackend implements IRenderBackend {

    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    private long depthTexture = 0L;

    private long gridVertexBuffer = 0L;
    private int gridVertexCount = 0;

    private long meshVertexBuffer = 0L;
    private int meshVertexCount = 0;

    private long uiVertexBuffer = 0L;
    private int uiVertexCount = 0;

    private long uniformBufferGrid = 0L;
    private long uniformBufferModel = 0L;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    // Camera Transform State
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    // Model Transform State
    private float modelPx = 0.0f;
    private float modelPy = 0.0f;
    private float modelPz = 0.0f;
    private float modelRx = 0.0f;
    private float modelRy = 0.0f;
    private float modelRz = 0.0f;
    private float modelSx = 1.0f;
    private float modelSy = 1.0f;
    private float modelSz = 1.0f;

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        if (!metalDevice.initialize(windowInfo)) return false;
        if (!metalPipeline.initialize(metalDevice)) return false;

        initDepthBuffer(1280, 720);
        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        uniformBufferGrid = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), 80L);
        uniformBufferModel = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), 80L);

        System.out.println("[MetalBackend] Modular Metal graphics backend active.");
        return true;
    }

    private void initDepthBuffer(int width, int height) {
        long d = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();

        long descClass = objc_getClass("MTLTextureDescriptor");
        long desc = invokePPP(descClass, sel_registerName("new"), send);
        invokePPPP(desc, sel_registerName("setTextureType:"), 2L, send);
        invokePPPP(desc, sel_registerName("setPixelFormat:"), 252L, send);
        invokePPPP(desc, sel_registerName("setWidth:"), (long) width, send);
        invokePPPP(desc, sel_registerName("setHeight:"), (long) height, send);
        invokePPPP(desc, sel_registerName("setUsage:"), 4L, send);
        invokePPPP(desc, sel_registerName("setStorageMode:"), 2L, send);

        depthTexture = invokePPPP(d, sel_registerName("newTextureWithDescriptor:"), desc, send);
    }

    @Override
    public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    @Override
    public void setModelTransform(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        this.modelPx = px;
        this.modelPy = py;
        this.modelPz = pz;
        this.modelRx = rx;
        this.modelRy = ry;
        this.modelRz = rz;
        this.modelSx = sx;
        this.modelSy = sy;
        this.modelSz = sz;
    }

    @Override
    public void updateGridMesh(float[] vertices, int count) {
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
        this.gridVertexBuffer = buf.getHandle();
        this.gridVertexCount = count;
    }

    @Override
    public void updateMesh(float[] vertices, int count) {
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 9);
        this.meshVertexBuffer = buf.getHandle();
        this.meshVertexCount = count;
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices, 6);
        this.uiVertexBuffer = buf.getHandle();
        this.uiVertexCount = count;
    }

    @Override
    public void updateTexture(byte[] pixels, int width, int height) {
        metalTexture.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, width, height);
    }

    private float[] createCameraViewMatrix() {
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        // V Column-major: Rotation * Translation
        return new float[] {
            cy, sy * sp, sy * cp, 0.0f,
            0.0f, cp, -sp, 0.0f,
            -sy, cy * sp, cy * cp, 0.0f,
            camPanX, camPanY, camZoom, 1.0f
        };
    }

    private float[] createModelMatrix() {
        float radRx = (float) Math.toRadians(modelRx);
        float radRy = (float) Math.toRadians(modelRy);
        float radRz = (float) Math.toRadians(modelRz);

        float cx = (float) Math.cos(radRx), sx = (float) Math.sin(radRx);
        float cy = (float) Math.cos(radRy), sy = (float) Math.sin(radRy);
        float cz = (float) Math.cos(radRz), sz = (float) Math.sin(radRz);

        float m00 = (cy * cz + sy * sx * sz) * modelSx;
        float m10 = (cx * sz) * modelSx;
        float m20 = (-sy * cz + cy * sx * sz) * modelSx;

        float m01 = (-cy * sz + sy * sx * cz) * modelSy;
        float m11 = (cx * cz) * modelSy;
        float m21 = (sy * sz + cy * sx * cz) * modelSy;

        float m02 = (sy * cx) * modelSz;
        float m12 = (-sx) * modelSz;
        float m22 = (cy * cx) * modelSz;

        return new float[] {
            m00, m10, m20, 0.0f,
            m01, m11, m21, 0.0f,
            m02, m12, m22, 0.0f,
            modelPx, modelPy, modelPz, 1.0f
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

    private float[] create3DMVPMatrix(float aspect) {
        float[] P = createPerspectiveMatrix(aspect);
        float[] V = createCameraViewMatrix();
        float[] M = createModelMatrix();
        float[] VM = multiplyMat4(V, M);
        return multiplyMat4(P, VM);
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        float aspect = 1280.0f / 720.0f;
        float[] mvpGrid = createGridMVPMatrix(aspect);
        float[] mvpModel = create3DMVPMatrix(aspect);

        long pGridContents = invokePPP(uniformBufferGrid, sel_registerName("contents"), send);
        if (pGridContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pGridContents, 16);
            fb.clear();
            fb.put(mvpGrid);
            MemoryUtil.memPutInt(pGridContents + 64L, 0); // Grid has no texture
        }

        long pModelContents = invokePPP(uniformBufferModel, sel_registerName("contents"), send);
        if (pModelContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pModelContents, 16);
            fb.clear();
            fb.put(mvpModel);
            MemoryUtil.memPutInt(pModelContents + 64L, metalTexture.getHasActiveTexture());
        }

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), send);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), send);
        long texture = invokePPP(currentDrawable, sel_registerName("texture"), send);

        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long renderPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), send);

        long colorAttachments = invokePPP(renderPassDesc, sel_registerName("colorAttachments"), send);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, send);

        invokePPPP(colorAttach0, sel_registerName("setTexture:"), texture, send);
        invokePPPP(colorAttach0, sel_registerName("setLoadAction:"), 2L, send);
        invokePPPP(colorAttach0, sel_registerName("setStoreAction:"), 1L, send);

        if (depthTexture != 0L) {
            long depthAttachment = invokePPP(renderPassDesc, sel_registerName("depthAttachment"), send);
            invokePPPP(depthAttachment, sel_registerName("setTexture:"), depthTexture, send);
            invokePPPP(depthAttachment, sel_registerName("setLoadAction:"), 2L, send);
            invokePPPP(depthAttachment, sel_registerName("setStoreAction:"), 0L, send);
        }

        long encoder = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), renderPassDesc, send);

        // PASS 1: 3D Perspective Ground Grid (Lines = 1L)
        if (gridVertexBuffer != 0L && gridVertexCount > 0) {
            invokePPPP(encoder, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineState3D(), send);
            invokePPPP(encoder, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilState3D(), send);

            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), gridVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBufferGrid, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("setFragmentBuffer:offset:atIndex:"), uniformBufferGrid, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 1L, 0L, (long) gridVertexCount, send); // 1L = MTLPrimitiveTypeLine
        }

        // PASS 2: 3D Perspective Model Mesh (Triangles = 3L)
        if (meshVertexBuffer != 0L && meshVertexCount > 0) {
            invokePPPP(encoder, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineState3D(), send);
            invokePPPP(encoder, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilState3D(), send);

            long texToBind = metalTexture.getBindableTexture();
            if (texToBind != 0L) {
                invokePPPPP(encoder, sel_registerName("setFragmentTexture:atIndex:"), texToBind, 0L, send);
            }
            if (metalTexture.getSamplerStateHandle() != 0L) {
                invokePPPPP(encoder, sel_registerName("setFragmentSamplerState:atIndex:"), metalTexture.getSamplerStateHandle(), 0L, send);
            }

            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), meshVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBufferModel, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("setFragmentBuffer:offset:atIndex:"), uniformBufferModel, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) meshVertexCount, send); // 3L = MTLPrimitiveTypeTriangle
        }

        // PASS 3: 2D UI Overlay & Viewport Transform Gizmos
        if (uiVertexBuffer != 0L && uiVertexCount > 0 && metalPipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoder, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineStateUI(), send);
            invokePPPP(encoder, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uiVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBufferModel, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) uiVertexCount, send);
        }

        invokePPP(encoder, sel_registerName("endEncoding"), send);
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
        System.out.println("[MetalBackend] Releasing Metal resources...");
    }

    @Override
    public long createBuffer(BufferCreateInfo createInfo) { return 102L; }
    @Override
    public void destroyBuffer(long handle) {}
    @Override
    public long createTexture(TextureCreateInfo createInfo) { return 202L; }
    @Override
    public void destroyTexture(long handle) {}
    @Override
    public GraphicsAPI getAPI() { return GraphicsAPI.METAL; }
    @Override
    public String getDeviceName() { return "Native Apple Metal Driver (macOS)"; }
}
