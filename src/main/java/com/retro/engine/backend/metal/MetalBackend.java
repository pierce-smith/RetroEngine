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
    private long pipelineState = 0L;
    private long depthStencilState = 0L;
    private long depthTexture = 0L;

    private long vertexBuffer = 0L;
    private int vertexCount = 0;
    private long uniformBuffer = 0L;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    private float rotationAngle = 0.0f;
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
        System.out.println("[MetalBackend] Initializing Native Metal 3D Pipeline with Z-Buffer Depth Testing...");

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

        if (!initShadersAndPipeline()) {
            System.err.println("[MetalBackend] ERROR: Failed to initialize Metal 3D pipeline!");
            return false;
        }

        initDepthBuffer(1280, 720);

        uniformBuffer = invokePPPPP(device, sel_registerName("newBufferWithLength:options:"), 64L, 0L, objc_msgSend);

        System.out.println("[MetalBackend] 3D Solid Rendering Pipeline with Z-Buffer active on Apple Silicon Metal.");
        return true;
    }

    private void initDepthBuffer(int width, int height) {
        long descClass = objc_getClass("MTLTextureDescriptor");
        long desc = invokePPP(descClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(desc, sel_registerName("setTextureType:"), 2L, objc_msgSend); // 2L = MTLTextureType2D
        invokePPPV(desc, sel_registerName("setPixelFormat:"), 252L, objc_msgSend); // 252L = MTLPixelFormatDepth32Float
        invokePPPV(desc, sel_registerName("setWidth:"), (long) width, objc_msgSend);
        invokePPPV(desc, sel_registerName("setHeight:"), (long) height, objc_msgSend);
        invokePPPV(desc, sel_registerName("setUsage:"), 4L, objc_msgSend); // 4L = MTLTextureUsageRenderTarget
        invokePPPV(desc, sel_registerName("setStorageMode:"), 2L, objc_msgSend); // 2L = MTLStorageModePrivate

        depthTexture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        long depthDescClass = objc_getClass("MTLDepthStencilDescriptor");
        long depthDesc = invokePPP(depthDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPV(depthDesc, sel_registerName("setDepthCompareFunction:"), 1L, objc_msgSend); // 1L = MTLCompareFunctionLess
        invokePPPV(depthDesc, sel_registerName("setDepthWriteEnabled:"), 1L, objc_msgSend);

        depthStencilState = invokePPPP(device, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc, objc_msgSend);
    }

    public void updateMesh(float[] vertices, int count) {
        if (device == 0L || objc_msgSend == 0L || vertices == null || vertices.length == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.vertexBuffer = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) vertices.length * 4L, 0L, objc_msgSend);
        this.vertexCount = count;

        MemoryUtil.memFree(fb);
        System.out.println("[MetalBackend] Uploaded new 3D Mesh to Metal VRAM (" + count + " vertices).");
    }

    private boolean initShadersAndPipeline() {
        long nsSourceString = createNSString(MSL_SHADER_SOURCE);
        long library = invokePPPPPP(device, sel_registerName("newLibraryWithSource:options:error:"), nsSourceString, 0L, 0L, objc_msgSend);
        if (library == 0L) return false;

        long vertName = createNSString("vertexMain");
        long fragName = createNSString("fragmentMain");

        long vertFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), vertName, objc_msgSend);
        long fragFunc = invokePPPP(library, sel_registerName("newFunctionWithName:"), fragName, objc_msgSend);

        long pipeDescClass = objc_getClass("MTLRenderPipelineDescriptor");
        long pipeDesc = invokePPP(pipeDescClass, sel_registerName("new"), objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setVertexFunction:"), vertFunc, objc_msgSend);
        invokePPPV(pipeDesc, sel_registerName("setFragmentFunction:"), fragFunc, objc_msgSend);

        long colorAttachments = invokePPP(pipeDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, objc_msgSend); // MTLPixelFormatDepth32Float

        pipelineState = invokePPPPP(device, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDesc, 0L, objc_msgSend);
        return pipelineState != 0L;
    }

    private float[] createMVPMatrix(float aspect, float angle) {
        float fov = (float) Math.toRadians(60.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        float cosY = (float) Math.cos(angle);
        float sinY = (float) Math.sin(angle);

        float m00 = cosY;
        float m02 = sinY;
        float m11 = 1.0f;
        float m20 = -sinY;
        float m22 = cosY;

        float tz = -4.5f; // Pulled camera back so model is centered and fully visible

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);
        float p32 = -1.0f;

        float[] mvp = new float[16];
        mvp[0] = p00 * m00;
        mvp[2] = p22 * m02;
        mvp[3] = p32 * m02;
        mvp[5] = p11 * m11;
        mvp[8] = p00 * m20;
        mvp[10] = p22 * m22;
        mvp[11] = p32 * m22;
        mvp[14] = p22 * tz + p23;
        mvp[15] = p32 * tz;

        return mvp;
    }

    @Override
    public void beginFrame() {
        if (metalLayer == 0L || commandQueue == 0L || pipelineState == 0L) return;

        rotationAngle += 0.015f;
        float[] mvp = createMVPMatrix(1280.0f / 720.0f, rotationAngle);

        long pContents = invokePPP(uniformBuffer, sel_registerName("contents"), objc_msgSend);
        if (pContents != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, 16);
            fb.clear();
            fb.put(mvp);
        }

        currentDrawable = invokePPP(metalLayer, sel_registerName("nextDrawable"), objc_msgSend);
        if (currentDrawable == 0L) return;

        currentCommandBuffer = invokePPP(commandQueue, sel_registerName("commandBuffer"), objc_msgSend);
        long texture = invokePPP(currentDrawable, sel_registerName("texture"), objc_msgSend);

        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long renderPassDesc = invokePPP(passDescClass, sel_registerName("renderPassDescriptor"), objc_msgSend);

        // Color attachment setup with clear screen
        long colorAttachments = invokePPP(renderPassDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttachment0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);

        invokePPPV(colorAttachment0, sel_registerName("setTexture:"), texture, objc_msgSend);
        invokePPPV(colorAttachment0, sel_registerName("setLoadAction:"), 2L, objc_msgSend); // 2L = MTLLoadActionClear
        invokePPPV(colorAttachment0, sel_registerName("setStoreAction:"), 1L, objc_msgSend); // 1L = MTLStoreActionStore

        // Depth attachment setup for Z-Buffer
        if (depthTexture != 0L) {
            long depthAttachment = invokePPP(renderPassDesc, sel_registerName("depthAttachment"), objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setTexture:"), depthTexture, objc_msgSend);
            invokePPPV(depthAttachment, sel_registerName("setLoadAction:"), 2L, objc_msgSend); // 2L = MTLLoadActionClear
            invokePPPV(depthAttachment, sel_registerName("setStoreAction:"), 0L, objc_msgSend); // 0L = MTLStoreActionDontCare
        }

        long encoder = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), renderPassDesc, objc_msgSend);

        invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineState, objc_msgSend);
        if (depthStencilState != 0L) {
            invokePPPV(encoder, sel_registerName("setDepthStencilState:"), depthStencilState, objc_msgSend);
        }

        if (vertexBuffer != 0L && vertexCount > 0) {
            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), vertexBuffer, 0L, 0L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("setVertexBuffer:offset:atIndex:"), uniformBuffer, 0L, 1L, objc_msgSend);
            invokePPPPPV(encoder, sel_registerName("drawPrimitives:vertexStart:vertexCount:"), 3L, 0L, (long) vertexCount, objc_msgSend);
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
        System.out.println("[MetalBackend] Releasing 3D Metal resources...");
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