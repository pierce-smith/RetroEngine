package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * MetalBackend implements IRenderBackend by coordinating MetalDevice, MetalBuffer, MetalTexture, and MetalPipeline.
 */
public class MetalBackend implements IRenderBackend {

    private final MetalDevice metalDevice = new MetalDevice();
    private final MetalTexture metalTexture = new MetalTexture();
    private final MetalPipeline metalPipeline = new MetalPipeline();

    private long depthTexture = 0L;

    private long meshVertexBuffer = 0L;
    private int meshVertexCount = 0;

    private long uiVertexBuffer = 0L;
    private int uiVertexCount = 0;

    private long uniformBuffer3D = 0L;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    // Camera Transform State
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        if (!metalDevice.initialize(windowInfo)) return false;
        if (!metalPipeline.initialize(metalDevice)) return false;

        initDepthBuffer(1280, 720);
        metalTexture.initDefaultTextureAndSampler(metalDevice.getDevice(), metalDevice.getObjcMsgSend());

        uniformBuffer3D = MetalBuffer.createUniformBuffer(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), 80L);

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
    public void updateMesh(float[] vertices, int count) {
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices);
        this.meshVertexBuffer = buf.getHandle();
        this.meshVertexCount = count;
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        MetalBuffer buf = MetalBuffer.createWithFloatArray(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), vertices);
        this.uiVertexBuffer = buf.getHandle();
        this.uiVertexCount = count;
    }

    @Override
    public void updateTexture(byte[] pixels, int width, int height) {
        metalTexture.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, width, height);
    }

    private float[] create3DMVPMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);
        float p32 = -1.0f;

        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cosY = (float) Math.cos(radYaw);
        float sinY = (float) Math.sin(radYaw);
        float cosP = (float) Math.cos(radPitch);
        float sinP = (float) Math.sin(radPitch);

        float[] mvp = new float[16];

        mvp[0]  = p00 * cosY;
        mvp[1]  = p11 * (sinY * sinP);
        mvp[2]  = p22 * (sinY * cosP);
        mvp[3]  = p32 * (sinY * cosP);

        mvp[4]  = 0.0f;
        mvp[5]  = p11 * cosP;
        mvp[6]  = p22 * -sinP;
        mvp[7]  = p32 * -sinP;

        mvp[8]  = p00 * -sinY;
        mvp[9]  = p11 * (cosY * sinP);
        mvp[10] = p22 * (cosY * cosP);
        mvp[11] = p32 * (cosY * cosP);

        mvp[12] = p00 * camPanX;
        mvp[13] = p11 * camPanY;
        mvp[14] = p22 * camZoom + p23;
        mvp[15] = p32 * camZoom;

        return mvp;
    }

    @Override
    public void beginFrame() {
        long metalLayer = metalDevice.getMetalLayer();
        long commandQueue = metalDevice.getCommandQueue();
        long send = metalDevice.getObjcMsgSend();

        if (metalLayer == 0L || commandQueue == 0L || metalPipeline.getPipelineState3D() == 0L) return;

        float[] mvp = create3DMVPMatrix(1280.0f / 720.0f);

        long pContents = invokePPP(uniformBuffer3D, sel_registerName("contents"), send);
        if (pContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, 16);
            fb.clear();
            fb.put(mvp);
            MemoryUtil.memPutInt(pContents + 64L, metalTexture.getHasActiveTexture());
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

        // PASS 1: 3D Viewport Pass
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
            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("setFragmentBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, send);
            invokePPPPPP(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) meshVertexCount, send);
        }

        // PASS 2: 2D UI Overlay Pass
        if (uiVertexBuffer != 0L && uiVertexCount > 0 && metalPipeline.getPipelineStateUI() != 0L) {
            invokePPPP(encoder, sel_registerName("setRenderPipelineState:"), metalPipeline.getPipelineStateUI(), send);
            invokePPPP(encoder, sel_registerName("setDepthStencilState:"), metalPipeline.getDepthStencilStateUI(), send);

            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uiVertexBuffer, 0L, 0L, send);
            invokePPPPPP(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, send);
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
