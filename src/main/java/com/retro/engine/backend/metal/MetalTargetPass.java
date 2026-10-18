package com.retro.engine.backend.metal;

import com.retro.engine.backend.metal.MetalDevice;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Delegate managing offscreen BGRA8 color textures and Depth32Float depth textures
 * for offscreen viewport rendering and swapchain depth testing.
 * Hardened with zero-leak native Objective-C ARC release semantics during viewport resizes.
 */
public class MetalTargetPass {

    private long offscreenColorTexture = 0L;
    private long offscreenDepthTexture = 0L;
    private long windowDepthTexture = 0L;

    public long getOffscreenColorTexture() {
        return offscreenColorTexture;
    }

    public long getOffscreenDepthTexture() {
        return offscreenDepthTexture;
    }

    public long getWindowDepthTexture() {
        return windowDepthTexture;
    }

    public void initFramebuffer(MetalDevice metalDevice, int width, int height) {
        if (metalDevice == null) return;
        long d = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (d == 0L || send == 0L) return;

        long selRelease = sel_registerName("release");

        // Release previously allocated native GPU textures if resizing
        if (offscreenColorTexture != 0L) {
            invokePPP(offscreenColorTexture, selRelease, send);
            offscreenColorTexture = 0L;
        }
        if (offscreenDepthTexture != 0L) {
            invokePPP(offscreenDepthTexture, selRelease, send);
            offscreenDepthTexture = 0L;
        }
        if (windowDepthTexture != 0L) {
            invokePPP(windowDepthTexture, selRelease, send);
            windowDepthTexture = 0L;
        }

        long descClass = objc_getClass("MTLTextureDescriptor");
        long selNew = sel_registerName("new");
        long selNewTexture = sel_registerName("newTextureWithDescriptor:");

        // 1. Offscreen Color Texture (RenderTarget + ShaderRead)
        long desc = invokePPP(descClass, selNew, send);
        invokePPPP(desc, sel_registerName("setTextureType:"), 2L, send); // 2L = MTLTextureType2D
        invokePPPP(desc, sel_registerName("setPixelFormat:"), 80L, send); // 80L = BGRA8Unorm
        invokePPPP(desc, sel_registerName("setWidth:"), (long) width, send);
        invokePPPP(desc, sel_registerName("setHeight:"), (long) height, send);
        invokePPPP(desc, sel_registerName("setUsage:"), 5L, send); // RenderTarget (4) | ShaderRead (1)
        invokePPPP(desc, sel_registerName("setStorageMode:"), 2L, send); // Private

        offscreenColorTexture = invokePPPP(d, selNewTexture, desc, send);
        invokePPP(desc, selRelease, send); // Release descriptor retain count

        // 2. Offscreen Depth Texture (Depth32Float)
        long depthDesc = invokePPP(descClass, selNew, send);
        invokePPPP(depthDesc, sel_registerName("setTextureType:"), 2L, send);
        invokePPPP(depthDesc, sel_registerName("setPixelFormat:"), 252L, send); // 252L = Depth32Float
        invokePPPP(depthDesc, sel_registerName("setWidth:"), (long) width, send);
        invokePPPP(depthDesc, sel_registerName("setHeight:"), (long) height, send);
        invokePPPP(depthDesc, sel_registerName("setUsage:"), 4L, send);
        invokePPPP(depthDesc, sel_registerName("setStorageMode:"), 2L, send);

        offscreenDepthTexture = invokePPPP(d, selNewTexture, depthDesc, send);
        invokePPP(depthDesc, selRelease, send); // Release descriptor retain count

        // 3. Main Window Depth Texture
        long winDepthDesc = invokePPP(descClass, selNew, send);
        invokePPPP(winDepthDesc, sel_registerName("setTextureType:"), 2L, send);
        invokePPPP(winDepthDesc, sel_registerName("setPixelFormat:"), 252L, send);
        invokePPPP(winDepthDesc, sel_registerName("setWidth:"), 1280L, send);
        invokePPPP(winDepthDesc, sel_registerName("setHeight:"), 720L, send);
        invokePPPP(winDepthDesc, sel_registerName("setUsage:"), 4L, send);
        invokePPPP(winDepthDesc, sel_registerName("setStorageMode:"), 2L, send);

        windowDepthTexture = invokePPPP(d, selNewTexture, winDepthDesc, send);
        invokePPP(winDepthDesc, selRelease, send); // Release descriptor retain count
    }

    public void resizeViewport(MetalDevice metalDevice, int width, int height) {
        if (width > 0 && height > 0) {
            initFramebuffer(metalDevice, width, height);
        }
    }

    public void dispose(MetalDevice metalDevice) {
        if (metalDevice == null) return;
        long send = metalDevice.getObjcMsgSend();
        if (send == 0L) return;
        long selRelease = sel_registerName("release");

        if (offscreenColorTexture != 0L) { invokePPP(offscreenColorTexture, selRelease, send); offscreenColorTexture = 0L; }
        if (offscreenDepthTexture != 0L) { invokePPP(offscreenDepthTexture, selRelease, send); offscreenDepthTexture = 0L; }
        if (windowDepthTexture != 0L) { invokePPP(windowDepthTexture, selRelease, send); windowDepthTexture = 0L; }
    }
}
