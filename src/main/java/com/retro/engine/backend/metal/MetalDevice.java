package com.retro.engine.backend.metal;

import com.retro.engine.rhi.ViewportSpec;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

public class MetalDevice {

    private static final long SEL_ALLOC = sel_registerName("alloc");
    private static final long SEL_INIT = sel_registerName("init");
    private static final long SEL_INIT_WITH_UTF8_STRING = sel_registerName("initWithUTF8String:");
    private static final long SEL_RELEASE = sel_registerName("release");
    private static final long SEL_DRAIN = sel_registerName("drain");
    private static final long MTL_GPU_FAMILY_METAL3 = 5001L;

    private long device = 0L;
    private long commandQueue = 0L;
    private long metalLayer = 0L;
    private long objc_msgSend = 0L;

    public boolean initialize(ViewportSpec windowInfo) {
        System.out.println("[MetalDevice] Initializing Native Apple Metal Device & Command Queue...");

        long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(windowInfo.nativeWindowHandle());
        if (nsWindow == 0L) {
            System.err.println("[MetalDevice] ERROR: Failed to obtain NSWindow handle from GLFW!");
            return false;
        }

        objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

        long metalLib = DynamicLinkLoader.dlopen("/System/Library/Frameworks/Metal.framework/Metal", DynamicLinkLoader.RTLD_LAZY);
        if (metalLib == 0L) {
            System.err.println("[MetalDevice] ERROR: Failed to load Metal.framework!");
            return false;
        }

        long pMTLCreateSystemDefaultDevice = DynamicLinkLoader.dlsym(metalLib, "MTLCreateSystemDefaultDevice");
        if (pMTLCreateSystemDefaultDevice == 0L) {
            System.err.println("[MetalDevice] ERROR: Failed to locate MTLCreateSystemDefaultDevice symbol!");
            return false;
        }

        device = invokeP(pMTLCreateSystemDefaultDevice);
        if (device == 0L) {
            System.err.println("[MetalDevice] ERROR: Metal is not supported on this device!");
            return false;
        }

        commandQueue = invokePPP(device, sel_registerName("newCommandQueue"), objc_msgSend);

        long layerClass = objc_getClass("CAMetalLayer");
        metalLayer = invokePPP(layerClass, sel_registerName("layer"), objc_msgSend);

        invokePPPP(metalLayer, sel_registerName("setDevice:"), device, objc_msgSend);
        invokePPPP(metalLayer, sel_registerName("setOpaque:"), 1L, objc_msgSend);
        invokePPPP(metalLayer, sel_registerName("setPixelFormat:"), 80L, objc_msgSend); // MTLPixelFormatBGRA8Unorm

        long contentView = invokePPP(nsWindow, sel_registerName("contentView"), objc_msgSend);
        invokePPPP(contentView, sel_registerName("setLayer:"), metalLayer, objc_msgSend);
        invokePPPP(contentView, sel_registerName("setWantsLayer:"), 1L, objc_msgSend);

        System.out.println("[MetalDevice] Native MTLDevice successfully initialized.");
        return true;
    }

    /**
     * Returns an owned (+1) NSString; the caller must {@link #release} it. An autoreleased string would either leak
     * into whatever pool happens to be active (none during startup) or be freed by a pool drained around this call.
     */
    public long createNSString(String text) {
        if (text == null || objc_msgSend == 0L) return 0L;
        ByteBuffer utf8 = MemoryUtil.memUTF8(text, true);
        try {
            long alloc = invokePPP(objc_getClass("NSString"), SEL_ALLOC, objc_msgSend);
            return invokePPPP(alloc, SEL_INIT_WITH_UTF8_STRING, MemoryUtil.memAddress(utf8), objc_msgSend);
        } finally {
            MemoryUtil.memFree(utf8);
        }
    }

    public void release(long object) {
        if (object != 0L && objc_msgSend != 0L) invokePPP(object, SEL_RELEASE, objc_msgSend);
    }

    /** Opens an NSAutoreleasePool; pair with {@link #drainAutoreleasePool} in a finally block. */
    public long pushAutoreleasePool() {
        if (objc_msgSend == 0L) return 0L;
        long poolClass = objc_getClass("NSAutoreleasePool");
        if (poolClass == 0L) return 0L;
        return invokePPP(invokePPP(poolClass, SEL_ALLOC, objc_msgSend), SEL_INIT, objc_msgSend);
    }

    public void drainAutoreleasePool(long pool) {
        if (pool != 0L && objc_msgSend != 0L) invokePPP(pool, SEL_DRAIN, objc_msgSend);
    }

    /** True when the GPU supports MTLGPUFamilyMetal3 (gpuAddress / gpuResourceID argument buffers). */
    public boolean supportsMetal3() {
        if (device == 0L || objc_msgSend == 0L) return false;
        return (invokePPPP(device, sel_registerName("supportsFamily:"), MTL_GPU_FAMILY_METAL3, objc_msgSend) & 0xFFL) != 0L;
    }

    public long getDevice() { return device; }
    public long getCommandQueue() { return commandQueue; }
    public long getMetalLayer() { return metalLayer; }
    public long getObjcMsgSend() { return objc_msgSend; }
}
