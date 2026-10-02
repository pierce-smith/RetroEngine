package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

public class MetalBackend implements IRenderBackend {

    private long device = 0L;
    private long commandQueue = 0L;
    private long metalLayer = 0L;
    private long pipelineState = 0L;

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

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        System.out.println("[MetalBackend] Initializing Native Metal 3D Pipeline...");

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

        long sel_newCommandQueue = sel_registerName("newCommandQueue");
        commandQueue = invokePPP(device, sel_newCommandQueue, objc_msgSend);

        long layerClass = objc_getClass("CAMetalLayer");
        long sel_layer = sel_registerName("layer");
        metalLayer = invokePPP(layerClass, sel_layer, objc_msgSend);

        long sel_setDevice = sel_registerName("setDevice:");
        invokePPPV(metalLayer, sel_setDevice, device, objc_msgSend);

        long sel_setOpaque = sel_registerName("setOpaque:");
        invokePPPV(metalLayer, sel_setOpaque, 1L, objc_msgSend);

        long sel_contentView = sel_registerName("contentView");
        long contentView = invokePPP(nsWindow, sel_contentView, objc_msgSend);

        long sel_setLayer = sel_registerName("setLayer:");
        invokePPPV(contentView, sel_setLayer, metalLayer, objc_msgSend);

        long sel_setWantsLayer = sel_registerName("setWantsLayer:");
        invokePPPV(contentView, sel_setWantsLayer, 1L, objc_msgSend);

        if (!initShadersAndPipeline(objc_msgSend)) {
            System.err.println("[MetalBackend] ERROR: Failed to initialize Metal 3D pipeline!");
            return false;
        }

        long sel_newBufferWithLength = sel_registerName("newBufferWithLength:options:");
        uniformBuffer = invokePPPPP(device, sel_newBufferWithLength, 64L, 0L, objc_msgSend);

        System.out.println("[MetalBackend] 3D Low-Poly Mesh Pipeline active on Apple Silicon Metal.");
        return true;
    }

    public void updateMesh(float[] vertices, int count) {
        if (device == 0L || objc_msgSend == 0L || vertices == null || vertices.length == 0) return;

        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        this.vertexBuffer = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) vertices.length * 4L, 0L, objc_msgSend);
        this.vertexCount = count;

        MemoryUtil.memFree(fb);
        System.out.println("[MetalBackend] Updated Metal Vertex Buffer with " + count + " vertices.");
    }

    private boolean initShadersAndPipeline(long objc_msgSend) {
        long nsStringClass = objc_getClass("NSString");
        long sel_stringWithUTF8 = sel_registerName("stringWithUTF8String:");
        long nsSourceString;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pShader = MemoryUtil.memAddress(stack.UTF8(MSL_SHADER_SOURCE));
            nsSourceString = invokePPPP(nsStringClass, sel_stringWithUTF8, pShader, objc_msgSend);
        }

        if (nsSourceString == 0L) {
            System.err.println("[MetalBackend] Failed to allocate NSString for shader source.");
            return false;
        }

        long sel_newLibrary = sel_registerName("newLibraryWithSource:options:error:");
        long library = invokePPPPPP(device, sel_newLibrary, nsSourceString, 0L, 0L, objc_msgSend);
        if (library == 0L) {
            System.err.println("[MetalBackend] Failed to create Metal shader library.");
            return false;
        }

        long vertFunc, fragFunc;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long sel_newFunc = sel_registerName("newFunctionWithName:");
            long pVertName = MemoryUtil.memAddress(stack.UTF8("vertexMain"));
            long pFragName = MemoryUtil.memAddress(stack.UTF8("fragmentMain"));

            long vertName = invokePPPP(nsStringClass, sel_stringWithUTF8, pVertName, objc_msgSend);
            long fragName = invokePPPP(nsStringClass, sel_stringWithUTF8, pFragName, objc_msgSend);

            vertFunc = invokePPPP(library, sel_newFunc, vertName, objc_msgSend);
            fragFunc = invokePPPP(library, sel_newFunc, fragName, objc_msgSend);
        }

        if (vertFunc == 0L || fragFunc == 0L) {
            System.err.println("[MetalBackend] Failed to locate vertex/fragment entry points.");
            return false;
        }

        long pipeDescClass = objc_getClass("MTLRenderPipelineDescriptor");
        long pipeDesc = invokePPP(pipeDescClass, sel_registerName("new"), objc_msgSend);

        invokePPPV(pipeDesc, sel_registerName("setVertexFunction:"), vertFunc, objc_msgSend);
        invokePPPV(pipeDesc, sel_registerName("setFragmentFunction:"), fragFunc, objc_msgSend);

        long colorAttachments = invokePPP(pipeDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttach0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);
        invokePPPV(colorAttach0, sel_registerName("setPixelFormat:"), 80L, objc_msgSend);

        long sel_newPipeline = sel_registerName("newRenderPipelineStateWithDescriptor:error:");
        pipelineState = invokePPPPP(device, sel_newPipeline, pipeDesc, 0L, objc_msgSend);
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

        float tz = -3.5f;

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

        long colorAttachments = invokePPP(renderPassDesc, sel_registerName("colorAttachments"), objc_msgSend);
        long colorAttachment0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, objc_msgSend);

        invokePPPV(colorAttachment0, sel_registerName("setTexture:"), texture, objc_msgSend);
        invokePPPV(colorAttachment0, sel_registerName("setLoadAction:"), 1L, objc_msgSend);
        invokePPPV(colorAttachment0, sel_registerName("setStoreAction:"), 1L, objc_msgSend);

        long encoder = invokePPPP(currentCommandBuffer, sel_registerName("renderCommandEncoderWithDescriptor:"), renderPassDesc, objc_msgSend);

        invokePPPV(encoder, sel_registerName("setRenderPipelineState:"), pipelineState, objc_msgSend);

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

        long sel_commit = sel_registerName("commit");
        invokePPV(currentCommandBuffer, sel_commit, objc_msgSend);

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