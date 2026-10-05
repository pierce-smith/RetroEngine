package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

public class MetalBackend implements IRenderBackend {

    private long device = 0L;
    private long commandQueue = 0L;
    private long metalLayer = 0L;

    // 3D Scene Pipeline
    private long pipelineState3D = 0L;
    private long depthStencilState3D = 0L;
    private long depthTexture = 0L;
    private long vertexBuffer3D = 0L;
    private int vertexCount3D = 0;
    private long uniformBuffer3D = 0L;

    // Texture & Sampler State
    private long modelTexture = 0L;
    private long defaultTexture = 0L;
    private long samplerState = 0L;
    private int hasTexture = 0;

    // 2D UI Overlay Pipeline
    private long pipelineStateUI = 0L;
    private long depthStencilStateUI = 0L;
    private long vertexBufferUI = 0L;
    private int vertexCountUI = 0;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;
    private long objc_msgSend = 0L;

    // Camera Transform State
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    private static final String MSL_3D_SHADER = """
        #include <metal_stdlib>
        using namespace metal;

        struct VertexOut {
            float4 position [[position]];
            float4 color;
            float2 uv;
        };

        struct Uniforms {
            float4x4 mvp;
            uint hasTexture;
        };

        vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                                    constant float* vertices [[buffer(0)]],
                                    constant Uniforms& uniforms [[buffer(1)]]) {
            VertexOut out;
            int idx = vertexID * 9;
            float4 pos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);
            out.position = uniforms.mvp * pos;
            out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
            out.uv = float2(vertices[idx+7], vertices[idx+8]);
            return out;
        }

        fragment float4 fragmentMain(VertexOut in [[stage_in]],
                                      texture2d<float> tex [[texture(0)]],
                                      sampler smp [[sampler(0)]],
                                      constant Uniforms& uniforms [[buffer(1)]]) {
            if (uniforms.hasTexture == 1) {
                float4 texColor = tex.sample(smp, in.uv);
                return texColor * in.color;
            }
            return in.color;
        }
        """;

    private static final String MSL_UI_SHADER = """
        #include <metal_stdlib>
        using namespace metal;

        struct VertexOut {
            float4 position [[position]];
            float4 color;
        };

        vertex VertexOut uiVertexMain(uint vertexID [[vertex_id]],
                                      constant float* vertices [[buffer(0)]]) {
            VertexOut out;
            int idx = vertexID * 6;
            out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
            out.color = float4(vertices[idx+2], vertices[idx+3], vertices[idx+4], vertices[idx+5]);
            return out;
        }

        fragment float4 uiFragmentMain(VertexOut in [[stage_in]]) {
            return in.color;
        }
        """;

    private long createNSString(String text) {
        ByteBuffer utf8 = MemoryUtil.memUTF8(text, true);
        long nsStringClass = objc_getClass("NSString");
        long sel_stringWithUTF8 = sel_registerName("stringWithUTF8String:");
        long nsStr = invokePPPP(nsStringClass, sel_stringWithUTF8, MemoryUtil.memAddress(utf8), objc_msgSend);
        MemoryUtil.memFree(utf8);
        return nsStr;
    }

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        System.out.println("[MetalBackend] Initializing Metal Pipeline with Camera & Texture Sampling...");

        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(windowInfo.nativeWindowHandle());
        if (nsWindow == 0L) return false;

        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long metalLib = DynamicLinkLoader.dlopen("/System/Library/Frameworks/Metal.framework/Metal", DynamicLinkLoader.RTLD_LAZY);
        if (metalLib == 0L) return false;

        long pMTLCreateSystemDefaultDevice = DynamicLinkLoader.dlsym(metalLib, "MTLCreateSystemDefaultDevice");
        if (pMTLCreateSystemDefaultDevice == 0L) return false;

        device = invokeP(pMTLCreateSystemDefaultDevice);
        if (device == 0L) return false;

        commandQueue = invokePPP(device, sel_registerName("newCommandQueue"), objc_msgSend);

        long layerClass = objc_getClass("CAMetalLayer");
        metalLayer = invokePPP(layerClass, sel_registerName("layer"), objc_msgSend);

        invokePPPV(metalLayer, sel_registerName("setDevice:"), device, objc_msgSend);
        invokePPPV(metalLayer, sel_registerName("setOpaque:"), 1L, objc_msgSend);
        invokePPPV(metalLayer, sel_registerName("setPixelFormat:"), 80L, objc_msgSend); // MTLPixelFormatBGRA8Unorm

        long contentView = invokePPP(nsWindow, sel_registerName("contentView"), objc_msgSend);
        invokePPPV(contentView, sel_registerName("setLayer:"), metalLayer, objc_msgSend);
        invokePPPV(contentView, sel_registerName("setWantsLayer:"), 1L, objc_msgSend);

        if (!init3DPipeline() || !initUIPipeline()) {
            System.err.println("[MetalBackend] ERROR: Failed to initialize Pipelines!");
            return false;
        }

        initDepthBuffer(1280, 720);
        initDefaultTextureAndSampler();

        uniformBuffer3D = invokePPPPP(device, sel_registerName("newBufferWithLength:options:"), 80L, 0L, objc_msgSend);

        System.out.println("[MetalBackend] Dual-Pass Shader Pipeline active on Metal.");
        return true;
    }

    private void initDefaultTextureAndSampler() {
        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        long desc = invokePPPPPPP(descClass, selTex2D, 80L, 1L, 1L, 0L, objc_msgSend);
        defaultTexture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        if (defaultTexture != 0L) {
            ByteBuffer buf = MemoryUtil.memAlloc(4);
            buf.put(whitePixel).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer region = stack.malloc(8 * 6);
                region.putLong(0).putLong(0).putLong(0);
                region.putLong(1).putLong(1).putLong(1);
                region.flip();

                long sel_replaceRegion = sel_registerName("replaceRegion:mipmapLevel:withBytes:bytesPerRow:");
                invokePPPPPPV(defaultTexture, sel_replaceRegion, MemoryUtil.memAddress(region), 0L, MemoryUtil.memAddress(buf), 4L, objc_msgSend);
            }
            MemoryUtil.memFree(buf);
        }

        long samplerDescClass = objc_getClass("MTLSamplerDescriptor");
        long samplerDesc = invokePPP(samplerDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(samplerDesc, sel_registerName("setMinFilter:"), 1L, objc_msgSend);
        invokePPPV(samplerDesc, sel_registerName("setMagFilter:"), 1L, objc_msgSend);
        samplerState = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, objc_msgSend);
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
    public void updateTexture(byte[] pixels, int width, int height) {
        if (device == 0L || pixels == null || pixels.length == 0) return;

        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        long desc = invokePPPPPPP(descClass, selTex2D, 80L, (long) width, (long) height, 0L, objc_msgSend);
        long texture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);
        if (texture == 0L) return;

        ByteBuffer buf = MemoryUtil.memAlloc(pixels.length);
        buf.put(pixels).flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer region = stack.malloc(8 * 6);
            region.putLong(0).putLong(0).putLong(0);
            region.putLong(width).putLong(height).putLong(1);
            region.flip();

            long sel_replaceRegion = sel_registerName("replaceRegion:mipmapLevel:withBytes:bytesPerRow:");
            invokePPPPPPV(texture, sel_replaceRegion, MemoryUtil.memAddress(region), 0L, MemoryUtil.memAddress(buf), (long) width * 4L, objc_msgSend);
        }

        MemoryUtil.memFree(buf);
        this.modelTexture = texture;
        this.hasTexture = 1;
        System.out.println("[MetalBackend] Successfully bound " + width + "x" + height + " texture to Metal 3D model.");
    }

    private void initDepthBuffer(int width, int height) {
        long descClass = objc_getClass("MTLTextureDescriptor");
        long desc = invokePPP(descClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(desc, sel_registerName("setTextureType:"), 2L, objc_msgSend);
        invokePPPV(desc, sel_registerName("setPixelFormat:"), 252L, objc_msgSend);
        invokePPPV(desc, sel_registerName("setWidth:"), (long) width, objc_msgSend);
        invokePPPV(desc, sel_registerName("setHeight:"), (long) height, objc_msgSend);
        invokePPPV(desc, sel_registerName("setUsage:"), 4L, objc_msgSend);
        invokePPPV(desc, sel_registerName("setStorageMode:"), 2L, objc_msgSend);

        depthTexture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        long depthDescClass = objc_getClass("MTLDepthStencilDescriptor");
        long depthDesc3D = invokePPP(depthDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(depthDesc3D, sel_registerName("setDepthCompareFunction:"), 1L, objc_msgSend);
        invokePPPV(depthDesc3D, sel_registerName("setDepthWriteEnabled:"), 1L, objc_msgSend);
        depthStencilState3D = invokePPPP(device, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc3D, objc_msgSend);

        long depthDescUI = invokePPP(depthDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(depthDescUI, sel_registerName("setDepthCompareFunction:"), 7L, objc_msgSend);
        invokePPPV(depthDescUI, sel_registerName("setDepthWriteEnabled:"), 0L, objc_msgSend);
        depthStencilStateUI = invokePPPP(device, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDescUI, objc_msgSend);
    }

    private boolean init3DPipeline() {
        long nsSourceString = createNSString(MSL_3D_SHADER);
        long library = invokePPPPPP(device, sel_registerName("newLibraryWithSource:options:error:"), nsSourceString, 0L, 0L, objc_msgSend);
        if (library == 0L) return false;

        long vertFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("vertexMain"), objc_msgSend);
        long fragFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("fragmentMain"), objc_msgSend);

        long pipeDescClass = objc_getClass("MTLRenderPipelineDescriptor");
        long pipeDesc = invokePPP(pipeDescClass, sel_registerName("new"), objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setVertexFunction:"), vertFunc, objc_msgSend);
        invokePPPV(pipeDesc, sel_registerName("setFragmentFunction:"), fragFunc, objc_msgSend);

        long colorAttachments = invokePPP(pipeDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);
        invokePPPV(pipeDesc, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, objc_msgSend);

        pipelineState3D = invokePPPPP(device, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDesc, 0L, objc_msgSend);
        return pipelineState3D != 0L;
    }

    private boolean initUIPipeline() {
        long nsSourceString = createNSString(MSL_UI_SHADER);
        long library = invokePPPPPP(device, sel_registerName("newLibraryWithSource:options:error:"), nsSourceString, 0L, 0L, objc_msgSend);
        if (library == 0L) return false;

        long vertFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("uiVertexMain"), objc_msgSend);
        long fragFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("uiFragmentMain"), objc_msgSend);

        long pipeDescClass = objc_getClass("MTLRenderPipelineDescriptor");
        long pipeDesc = invokePPP(pipeDescClass, sel_registerName("new"), objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setVertexFunction:"), vertFunc, objc_msgSend);
        invokePPPV(pipeDesc, sel_registerName("setFragmentFunction:"), fragFunc, objc_msgSend);

        long colorAttachments = invokePPP(pipeDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);

        invokePPPV(colorAttach0, sel_registerName("setBlendingEnabled:"), 1L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setSourceRGBBlendFactor:"), 4L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setDestinationRGBBlendFactor:"), 5L, objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, objc_msgSend);

        pipelineStateUI = invokePPPPP(device, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDesc, 0L, objc_msgSend);
        return pipelineStateUI != 0L;
    }

    @Override
    public void updateMesh(float[] vertices, int count) {
        if (device == 0L || objc_msgSend == 0L || vertices == null || vertices.length == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.vertexBuffer3D = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) vertices.length * 4L, 0L, objc_msgSend);
        this.vertexCount3D = count;

        MemoryUtil.memFree(fb);
        System.out.println("[MetalBackend] Uploaded 3D Mesh to Metal VRAM (" + count + " vertices).");
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        if (device == 0L || objc_msgSend == 0L || vertices == null || vertices.length == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.vertexBufferUI = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) vertices.length * 4L, 0L, objc_msgSend);
        this.vertexCountUI = count;

        MemoryUtil.memFree(fb);
    }

    private float[] create3DMVPMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        float radPitch = (float) Math.toRadians(camPitch);
        float radYaw = (float) Math.toRadians(camYaw);

        float cosP = (float) Math.cos(radPitch);
        float sinP = (float) Math.sin(radPitch);
        float cosY = (float) Math.cos(radYaw);
        float sinY = (float) Math.sin(radYaw);

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);

        float m00 = cosY,       m01 = 0.0f,     m02 = sinY,        m03 = -camPanX;
        float m10 = sinP*sinY,  m11 = cosP,     m12 = -sinP*cosY,  m13 = -camPanY;
        float m20 = -cosP*sinY, m21 = sinP,     m22 = cosP*cosY,   m23 = camZoom;

        float[] mvp = new float[16];
        mvp[0] = p00 * m00;
        mvp[1] = p11 * m10;
        mvp[2] = p22 * m20;
        mvp[3] = -m20;

        mvp[4] = p00 * m01;
        mvp[5] = p11 * m11;
        mvp[6] = p22 * m21;
        mvp[7] = -m21;

        mvp[8] = p00 * m02;
        mvp[9] = p11 * m12;
        mvp[10] = p22 * m22;
        mvp[11] = -m22;

        mvp[12] = p00 * m03;
        mvp[13] = p11 * m13;
        mvp[14] = p22 * m23 + p23;
        mvp[15] = -m23;

        return mvp;
    }

    @Override
    public void beginFrame() {
        if (metalLayer == 0L || commandQueue == 0L || pipelineState3D == 0L) return;

        float[] mvp = create3DMVPMatrix(1280.0f / 720.0f);

        long pContents = invokePPP(uniformBuffer3D, sel_registerName("contents"), objc_msgSend);
        if (pContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, 16);
            fb.clear();
            fb.put(mvp);
            MemoryUtil.memPutInt(pContents + 64L, hasTexture);
        }

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), objc_msgSend);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), objc_msgSend);
        long texture = invokePPP(currentDrawable, sel_registerName("texture"), objc_msgSend);

        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long renderPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), objc_msgSend);

        long colorAttachments = invokePPP(renderPassDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttachment0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);

        invokePPPV(colorAttachment0, sel_registerName("setTexture:"), texture, objc_msgSend);
        invokePPPV(colorAttachment0, sel_registerName("setLoadAction:"), 2L, objc_msgSend);
        invokePPPV(colorAttachment0, sel_registerName("setStoreAction:"), 1L, objc_msgSend);

        if (depthTexture != 0L) {
            long depthAttachment = invokePPP(renderPassDesc, sel_registerName("depthAttachment"), objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setTexture:"), depthTexture, objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setLoadAction:"), 2L, objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setStoreAction:"), 0L, objc_msgSend);
        }

        long encoder = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), renderPassDesc, objc_msgSend);

        // Pass 1: 3D Viewport Mesh
        if (vertexBuffer3D != 0L && vertexCount3D > 0) {
            invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineState3D, objc_msgSend);
            if (depthStencilState3D != 0L) {
                invokePPPV(encoder, sel_registerName("setDepthStencilState:"), depthStencilState3D, objc_msgSend);
            }

            long texToBind = (hasTexture == 1 && modelTexture != 0L) ? modelTexture : defaultTexture;
            if (texToBind != 0L) {
                invokePPPPV(encoder, sel_registerName("setFragmentTexture:atIndex:"), texToBind, 0L, objc_msgSend);
            }
            if (samplerState != 0L) {
                invokePPPPV(encoder, sel_registerName("setFragmentSamplerState:atIndex:"), samplerState, 0L, objc_msgSend);
            }

            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), vertexBuffer3D, 0L, 0L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("setFragmentBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) vertexCount3D, objc_msgSend);
        }

        // Pass 2: 2D Editor UI Overlay
        if (vertexBufferUI != 0L && vertexCountUI > 0) {
            invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineStateUI, objc_msgSend);
            if (depthStencilStateUI != 0L) {
                invokePPPV(encoder, sel_registerName("setDepthStencilState:"), depthStencilStateUI, objc_msgSend);
            }
            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), vertexBufferUI, 0L, 0L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) vertexCountUI, objc_msgSend);
        }

        invokePPV(encoder, sel_registerName("endEncoding"), objc_msgSend);
    }

    @Override
    public void endFrame() {
        if (currentCommandBuffer == 0L || currentDrawable == 0L) return;
        invokePPPV(currentCommandBuffer, sel_registerName("presentDrawable:"), currentDrawable, objc_msgSend);
    }

    @Override
    public void present() {
        if (currentCommandBuffer == 0L) return;

        invokePPV(currentCommandBuffer, sel_registerName("commit"), objc_msgSend);

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
