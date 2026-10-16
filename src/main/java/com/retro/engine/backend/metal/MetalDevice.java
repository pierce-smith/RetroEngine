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

    public long createNSString(String text) {
        if (text == null || objc_msgSend == 0L) return 0L;
        ByteBuffer utf8 = MemoryUtil.memUTF8(text, true);
        long nsStringClass = objc_getClass("NSString");
        long sel_stringWithUTF8 = sel_registerName("stringWithUTF8String:");
        long nsStr = invokePPPP(nsStringClass, sel_stringWithUTF8, MemoryUtil.memAddress(utf8), objc_msgSend);
        MemoryUtil.memFree(utf8);
        return nsStr;
    }

    public long getDevice() { return device; }
    public long getCommandQueue() { return commandQueue; }
    public long getMetalLayer() { return metalLayer; }
    public long getObjcMsgSend() { return objc_msgSend; }
}
