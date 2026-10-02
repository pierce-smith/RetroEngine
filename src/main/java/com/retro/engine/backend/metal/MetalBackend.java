package com.retro.engine.backend.metal;

import com.retro.engine.rhi.*;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

public class MetalBackend implements IRenderBackend {

    private long device = 0L;
    private long commandQueue = 0L;
    private long metalLayer = 0L;

    private long currentDrawable = 0L;
    private long currentCommandBuffer = 0L;

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        System.out.println("[MetalBackend] Initializing Native Metal Device...");

        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(windowInfo.nativeWindowHandle());
        if (nsWindow == 0L) {
            System.err.println("[MetalBackend] ERROR: Failed to get NSWindow handle from GLFW!");
            return false;
        }

        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

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

        System.out.println("[MetalBackend] CAMetalLayer successfully attached and configured.");
        return true;
    }

    @Override
    public void beginFrame() {
        if (metalLayer == 0L || commandQueue == 0L) return;

        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long sel_nextDrawable = sel_registerName("nextDrawable");
        currentDrawable = invokePPP(metalLayer, sel_nextDrawable, objc_msgSend);
        if (currentDrawable == 0L) return;

        long sel_commandBuffer = sel_registerName("commandBuffer");
        currentCommandBuffer = invokePPP(commandQueue, sel_commandBuffer, objc_msgSend);

        long sel_texture = sel_registerName("texture");
        long texture = invokePPP(currentDrawable, sel_texture, objc_msgSend);

        long passDescClass = objc_getClass("MTLRenderPassDescriptor");
        long sel_passDesc = sel_registerName("renderPassDescriptor");
        long renderPassDesc = invokePPP(passDescClass, sel_passDesc, objc_msgSend);

        long sel_colorAttachments = sel_registerName("colorAttachments");
        long colorAttachments = invokePPP(renderPassDesc, sel_colorAttachments, objc_msgSend);

        long sel_objectAtIndexedSubscript = sel_registerName("objectAtIndexedSubscript:");
        long colorAttachment0 = invokePPPP(colorAttachments, sel_objectAtIndexedSubscript, 0L, objc_msgSend);

        long sel_setTexture = sel_registerName("setTexture:");
        invokePPPV(colorAttachment0, sel_setTexture, texture, objc_msgSend);

        long sel_setLoadAction = sel_registerName("setLoadAction:");
        invokePPPV(colorAttachment0, sel_setLoadAction, 1L, objc_msgSend);

        long sel_setStoreAction = sel_registerName("setStoreAction:");
        invokePPPV(colorAttachment0, sel_setStoreAction, 1L, objc_msgSend);

        long sel_encoder = sel_registerName("renderCommandEncoderWithDescriptor:");
        long encoder = invokePPPP(currentCommandBuffer, sel_encoder, renderPassDesc, objc_msgSend);

        long sel_endEncoding = sel_registerName("endEncoding");
        invokePPV(encoder, sel_endEncoding, objc_msgSend);
    }

    @Override
    public void endFrame() {
        if (currentCommandBuffer == 0L || currentDrawable == 0L) return;

        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long sel_presentDrawable = sel_registerName("presentDrawable:");
        invokePPPV(currentCommandBuffer, sel_presentDrawable, currentDrawable, objc_msgSend);
    }

    @Override
    public void present() {
        if (currentCommandBuffer == 0L) return;

        long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long sel_commit = sel_registerName("commit");
        invokePPV(currentCommandBuffer, sel_commit, objc_msgSend);

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