package com.retro.engine.graphics.metal;

import com.retro.engine.graphics.ViewportSpec;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.macosx.DynamicLinkLoader;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.libffi.LibFFI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

public class MetalDevice {

    private static final long SEL_ALLOC = sel_registerName("alloc");
    private static final long SEL_INIT = sel_registerName("init");
    private static final long SEL_INIT_WITH_UTF8_STRING = sel_registerName("initWithUTF8String:");
    private static final long SEL_RELEASE = sel_registerName("release");
    private static final long SEL_DRAIN = sel_registerName("drain");
    private static final long MTL_GPU_FAMILY_METAL3 = 5001L;
    private static final long SEL_BACKING_SCALE_FACTOR = sel_registerName("backingScaleFactor");
    private static final long SEL_SET_CONTENTS_SCALE = sel_registerName("setContentsScale:");

    private long device = 0L;
    private long commandQueue = 0L;
    private long metalLayer = 0L;
    private long objc_msgSend = 0L;
    private long nsWindow = 0L;
    private double contentsScale = 0.0;

    public boolean initialize(ViewportSpec windowInfo) {
        System.out.println("[MetalDevice] Initializing Native Apple Metal Device & Command Queue...");

        nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(windowInfo.nativeWindowHandle());
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
        syncContentsScale();

        System.out.println("[MetalDevice] Native MTLDevice successfully initialized.");
        return true;
    }

    /**
     * Matches the layer's contentsScale to the window's backing scale so the drawable renders at native (Retina)
     * resolution instead of being upscaled from points; re-applied when the window moves to a different-DPI screen.
     */
    public void syncContentsScale() {
        if (nsWindow == 0L || metalLayer == 0L) return;
        double scale = invokePPD(nsWindow, SEL_BACKING_SCALE_FACTOR, objc_msgSend);
        if (scale <= 0.0 || scale == contentsScale) return;
        // setContentsScale: takes a CGFloat, which the integer-only JNI msgSend helpers cannot pass
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FFICIF cif = FFICIF.malloc(stack);
            PointerBuffer argTypes = stack.pointers(ffi_type_pointer, ffi_type_pointer, ffi_type_double);
            if (ffi_prep_cif(cif, FFI_DEFAULT_ABI, ffi_type_void, argTypes) != FFI_OK) return;
            PointerBuffer args = stack.mallocPointer(3);
            args.put(0, MemoryUtil.memAddress(stack.pointers(metalLayer)));
            args.put(1, MemoryUtil.memAddress(stack.pointers(SEL_SET_CONTENTS_SCALE)));
            args.put(2, MemoryUtil.memAddress(stack.doubles(scale)));
            ffi_call(cif, objc_msgSend, null, args);
        }
        contentsScale = scale;
        System.out.println("[MetalDevice] Layer contentsScale set to " + scale + "x.");
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
