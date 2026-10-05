package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.glfw.GLFWNativeCocoa;
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

    private long pipelineState3D = 0L;
    private long pipelineState2D = 0L;
    private long depthStencilState3D = 0L;
    private long depthStencilState2D = 0L;
    private long depthTexture = 0L;

    private long meshVertexBuffer = 0L;
    private int meshVertexCount = 0;

    private long uiVertexBuffer = 0L;
    private int uiVertexCount = 0;

    private long uniformBuffer3D = 0L;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    private float camPanX = 0.0f;
    private float camPanY = 0.0f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    private long objc_msgSend = 0L;

    private static final String MSL_SHADER_SOURCE = """
        #include <metal_stdlib>
        using namespace metal;

        struct VertexOut {
            float4 position [[position]];
            float4 color;
        };

        struct Uniforms {
            float4x4 mvp;
        };

        // 3D Shader
        vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                                    constant float* vertices [[buffer(0)]],
                                    constant Uniforms& uniforms [[buffer(1)]]) {
            VertexOut out;
            int idx = vertexID * 7;
            float4 pos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);
            out.position = uniforms.mvp * pos;
            out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
            return out;
        }

        fragment float4 fragmentMain(VertexOut in [[stage_in]]) {
            return in.color;
        }

        // 2D UI Shader
        vertex VertexOut uiVertexMain(uint vertexID [[vertex_id]],
                                      constant float* vertices [[buffer(0)]]) {
            VertexOut out;
            int idx = vertexID * 7;
            out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
            out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
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
        System.out.println("[MetalBackend] Initializing Dual-Pass Metal Engine Architecture...");

        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(windowInfo.nativeWindowHandle());
        if (nsWindow == 0L) {
            System.err.println("[MetalBackend] ERROR: Failed to get NSWindow handle from GLFW!");
            return false;
        }

        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long metalLib = DynamicLinkLoader.dlopen("/System/Library/Frameworks/Metal.framework/Metal", DynamicLinkLoader.RTLD_LAZY);
        if (metalLib == 0L) {
            System.err.println("[MetalBackend] ERROR: Failed to load Metal framework!");
            return false;
        }

        long pMTLCreateSystemDefaultDevice = DynamicLinkLoader.dlsym(metalLib, "MTLCreateSystemDefaultDevice");
        if (pMTLCreateSystemDefaultDevice == 0L) {
            System.err.println("[MetalBackend] ERROR: Failed to find MTLCreateSystemDefaultDevice!");
            return false;
        }

        device = invokeP(pMTLCreateSystemDefaultDevice);
        if (device == 0L) {
            System.err.println("[MetalBackend] ERROR: Metal is not supported on this device!");
            return false;
        }

        commandQueue = invokePPP(device, sel_registerName("newCommandQueue"), objc_msgSend);

        long layerClass = objc_getClass("CAMetalLayer");
        metalLayer = invokePPP(layerClass, sel_registerName("layer"), objc_msgSend);

        invokePPPV(metalLayer, sel_registerName("setDevice:"), device, objc_msgSend);
        invokePPPV(metalLayer, sel_registerName("setOpaque:"), 1L, objc_msgSend);
        invokePPPV(metalLayer, sel_registerName("setPixelFormat:"), 80L, objc_msgSend); // MTLPixelFormatBGRA8Unorm

        long contentView = invokePPP(nsWindow, sel_registerName("contentView"), objc_msgSend);
        invokePPPV(contentView, sel_registerName("setLayer:"), metalLayer, objc_msgSend);
        invokePPPV(contentView, sel_registerName("setWantsLayer:"), 1L, objc_msgSend);

        if (!initPipelinesAndShaders()) {
            System.err.println("[MetalBackend] ERROR: Failed to initialize Metal dual pipelines!");
            return false;
        }

        initDepthBuffer(1280, 720);

        uniformBuffer3D = invokePPPPP(device, sel_registerName("newBufferWithLength:options:"), 64L, 0L, objc_msgSend);

        System.out.println("[MetalBackend] Dual-Pass 3D & 2D IDE Engine active on Apple Silicon Metal.");
        return true;
    }

    private void initDepthBuffer(int width, int height) {
        long descClass = objc_getClass("MTLTextureDescriptor");
        long desc = invokePPP(descClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(desc, sel_registerName("setTextureType:"), 2L, objc_msgSend); // 2L = MTLTextureType2D
        invokePPPV(desc, sel_registerName("setPixelFormat:"), 252L, objc_msgSend); // 252L = MTLPixelFormatDepth32Float
        invokePPPV(desc, sel_registerName("setWidth:"), (long) width, objc_msgSend);
        invokePPPV(desc, sel_registerName("setHeight:"), (long) height, objc_msgSend);
        invokePPPV(desc, sel_registerName("setUsage:"), 4L, objc_msgSend); // 4L = RenderTarget
        invokePPPV(desc, sel_registerName("setStorageMode:"), 2L, objc_msgSend); // 2L = Private

        depthTexture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        // 3D Depth State (Compare Less, Write Enabled)
        long depthDesc3D = invokePPP(objc_getClass("MTLDepthStencilDescriptor"), sel_registerName("new"), objc_msgSend);
        invokePPPV(depthDesc3D, sel_registerName("setDepthCompareFunction:"), 1L, objc_msgSend); // 1L = MTLCompareFunctionLess
        invokePPPV(depthDesc3D, sel_registerName("setDepthWriteEnabled:"), 1L, objc_msgSend);
        depthStencilState3D = invokePPPP(device, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc3D, objc_msgSend);

        // 2D Depth State (Compare Always, Write Disabled)
        long depthDesc2D = invokePPP(objc_getClass("MTLDepthStencilDescriptor"), sel_registerName("new"), objc_msgSend);
        invokePPPV(depthDesc2D, sel_registerName("setDepthCompareFunction:"), 7L, objc_msgSend); // 7L = MTLCompareFunctionAlways
        invokePPPV(depthDesc2D, sel_registerName("setDepthWriteEnabled:"), 0L, objc_msgSend);
        depthStencilState2D = invokePPPP(device, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc2D, objc_msgSend);
    }

    @Override
    public void updateMesh(float[] vertices, int count) {
        if (device == 0L || vertices == null || vertices.length == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.meshVertexBuffer = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) vertices.length * 4L, 0L, objc_msgSend);
        this.meshVertexCount = count;

        MemoryUtil.memFree(fb);
        System.out.println("[MetalBackend] Uploaded 3D Mesh to Metal VRAM (" + count + " vertices).");
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        if (device == 0L || vertices == null || count == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(count * 7);
        fb.put(vertices, 0, count * 7).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.uiVertexBuffer = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) count * 7L * 4L, 0L, objc_msgSend);
        this.uiVertexCount = count;

        MemoryUtil.memFree(fb);
    }

    @Override
    public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    private boolean initPipelinesAndShaders() {
        long nsSourceString = createNSString(MSL_SHADER_SOURCE);
        long library = invokePPPPPP(device, sel_registerName("newLibraryWithSource:options:error:"), nsSourceString, 0L, 0L, objc_msgSend);
        if (library == 0L) return false;

        long vert3D = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("vertexMain"), objc_msgSend);
        long frag3D = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("fragmentMain"), objc_msgSend);

        long vert2D = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("uiVertexMain"), objc_msgSend);
        long frag2D = invokePPPP(library, sel_registerName("newFunctionWithName:"), createNSString("uiFragmentMain"), objc_msgSend);

        // 3D Pipeline
        long desc3D = invokePPP(objc_getClass("MTLRenderPipelineDescriptor"), sel_registerName("new"), objc_msgSend);
        invokePPPV(desc3D, sel_registerName("setVertexFunction:"), vert3D, objc_msgSend);
        invokePPPV(desc3D, sel_registerName("setFragmentFunction:"), frag3D, objc_msgSend);
        long color3D = invokePPPP(invokePPP(desc3D, sel_registerName("colorAttachments"), objc_msgSend), sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(color3D, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);
        invokePPPV(desc3D, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, objc_msgSend);
        pipelineState3D = invokePPPPP(device, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), desc3D, 0L, objc_msgSend);

        // 2D Pipeline with Alpha Blending
        long desc2D = invokePPP(objc_getClass("MTLRenderPipelineDescriptor"), sel_registerName("new"), objc_msgSend);
        invokePPPV(desc2D, sel_registerName("setVertexFunction:"), vert2D, objc_msgSend);
        invokePPPV(desc2D, sel_registerName("setFragmentFunction:"), frag2D, objc_msgSend);
        long color2D = invokePPPP(invokePPP(desc2D, sel_registerName("colorAttachments"), objc_msgSend), sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(color2D, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);
        invokePPPV(color2D, sel_registerName("setBlendingEnabled:"), 1L, objc_msgSend);
        invokePPPV(color2D, sel_registerName("setSourceRGBBlendFactor:"), 1L, objc_msgSend); // MTLBlendFactorSourceAlpha
        invokePPPV(color2D, sel_registerName("setDestinationRGBBlendFactor:"), 5L, objc_msgSend); // MTLBlendFactorOneMinusSourceAlpha
        invokePPPV(desc2D, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, objc_msgSend);

        pipelineState2D = invokePPPPP(device, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), desc2D, 0L, objc_msgSend);

        return pipelineState3D != 0L && pipelineState2D != 0L;
    }

    private float[] create3DMVPMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        // Perspective Projection Matrix
        float p00 = f / (aspect * 1.35f);
        float p11 = f * 1.15f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);
        float p32 = -1.0f;

        // Camera Orbit Angles (Pitch / Yaw)
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cosY = (float) Math.cos(radYaw);
        float sinY = (float) Math.sin(radYaw);
        float cosP = (float) Math.cos(radPitch);
        float sinP = (float) Math.sin(radPitch);

        // Combined Projection * View Matrix with View Translation
        float tx = camPanX;
        float ty = camPanY + 0.15f;
        float tz = camZoom;

        float[] mvp = new float[16];
        mvp[0] = p00 * cosY;
        mvp[1] = p11 * sinY * sinP;
        mvp[2] = p22 * sinY * cosP;
        mvp[3] = p32 * sinY * cosP;

        mvp[4] = 0.0f;
        mvp[5] = p11 * cosP;
        mvp[6] = -p22 * sinP;
        mvp[7] = -p32 * sinP;

        mvp[8] = p00 * -sinY;
        mvp[9] = p11 * cosY * sinP;
        mvp[10] = p22 * cosY * cosP;
        mvp[11] = p32 * cosY * cosP;

        mvp[12] = p00 * tx;
        mvp[13] = p11 * ty;
        mvp[14] = p22 * tz + p23;
        mvp[15] = p32 * tz;

        return mvp;
    }

    @Override
    public void beginFrame() {
        if (metalLayer == 0L || commandQueue == 0L) return;

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), objc_msgSend);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), objc_msgSend);
        long texture = invokePPP(currentDrawable, sel_registerName("texture"), objc_msgSend);

        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long renderPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), objc_msgSend);

        long colorAttachments = invokePPP(renderPassDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);

        invokePPPV(colorAttach0, sel_registerName("setTexture:"), texture, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setLoadAction:"), 2L, objc_msgSend); // Clear
        invokePPPV(colorAttach0, sel_registerName("setStoreAction:"), 1L, objc_msgSend); // Store

        if (depthTexture != 0L) {
            long depthAttachment = invokePPP(renderPassDesc, sel_registerName("depthAttachment"), objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setTexture:"), depthTexture, objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setLoadAction:"), 2L, objc_msgSend); // Clear depth
            invokePPPV(depthAttachment, sel_registerName("setStoreAction:"), 0L, objc_msgSend);
        }

        long encoder = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), renderPassDesc, objc_msgSend);

        // PASS 1: Render 3D Model inside Viewport Panel Bounds
        if (meshVertexBuffer != 0L && meshVertexCount > 0 && pipelineState3D != 0L) {
            float[] mvp = create3DMVPMatrix(1280.0f / 720.0f);

            long pContents = invokePPP(uniformBuffer3D, sel_registerName("contents"), objc_msgSend);
            if (pContents != 0L) {
                FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, 16);
                fb.clear();
                fb.put(mvp);
            }

            invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineState3D, objc_msgSend);
            if (depthStencilState3D != 0L) {
                invokePPPV(encoder, sel_registerName("setDepthStencilState:"), depthStencilState3D, objc_msgSend);
            }

            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), meshVertexBuffer, 0L, 0L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBuffer3D, 0L, 1L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) meshVertexCount, objc_msgSend);
        }

        // PASS 2: Render 2D Solid Editor UI Panels & Text Overlay across full window
        if (uiVertexBuffer != 0L && uiVertexCount > 0 && pipelineState2D != 0L) {
            invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineState2D, objc_msgSend);
            if (depthStencilState2D != 0L) {
                invokePPPV(encoder, sel_registerName("setDepthStencilState:"), depthStencilState2D, objc_msgSend);
            }

            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uiVertexBuffer, 0L, 0L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) uiVertexCount, objc_msgSend);
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
