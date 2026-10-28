package com.retro.engine.backend.metal;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Delegate managing offscreen BGRA8 color textures and Depth32Float depth textures
 * for offscreen viewport rendering and swapchain depth testing.
 * Hardened with zero-leak native Objective-C ARC release semantics and NSAutoreleasePool scope management during viewport resizes.
 * Targets replaced by a resize are retired through MetalGpuCache's fenced disposal queue, since in-flight frames and the
 * editor's viewport panel may still reference the previous offscreen color texture.
 */
public class MetalTargetPass {

    private long offscreenColorTexture = 0L;
    private long offscreenDepthTexture = 0L;
    private long windowDepthTexture = 0L;
    private int width = 712;
    private int height = 488;
    private final long colorPixelFormat;

    public MetalTargetPass() { this(80L); } // 80L = BGRA8Unorm

    /** Target with a non-default color format (e.g. the RGBA8Uint Color-ID picking target). */
    public MetalTargetPass(long colorPixelFormat) { this.colorPixelFormat = colorPixelFormat; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

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
        initFramebuffer(metalDevice, width, height, null);
    }

    /** Allocates the targets; previous targets go to {@code retireQueue} when given, otherwise they are released at once. */
    private void initFramebuffer(MetalDevice metalDevice, int width, int height, MetalGpuCache retireQueue) {
        if (metalDevice == null) return;
        this.width = width;
        this.height = height;
        long d = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (d == 0L || send == 0L) return;

        long autoPool = metalDevice.pushAutoreleasePool();
        try {
            long selRelease = sel_registerName("release");

            retire(metalDevice, retireQueue, offscreenColorTexture);
            retire(metalDevice, retireQueue, offscreenDepthTexture);
            retire(metalDevice, retireQueue, windowDepthTexture);
            offscreenColorTexture = 0L;
            offscreenDepthTexture = 0L;
            windowDepthTexture = 0L;

            long descClass = objc_getClass("MTLTextureDescriptor");
            long selNew = sel_registerName("new");
            long selNewTexture = sel_registerName("newTextureWithDescriptor:");

            // 1. Offscreen Color Texture (RenderTarget + ShaderRead)
            long desc = invokePPP(descClass, selNew, send);
            invokePPPP(desc, sel_registerName("setTextureType:"), 2L, send); // 2L = MTLTextureType2D
            invokePPPP(desc, sel_registerName("setPixelFormat:"), colorPixelFormat, send);
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
            invokePPPP(winDepthDesc, sel_registerName("setWidth:"), (long) width, send);
            invokePPPP(winDepthDesc, sel_registerName("setHeight:"), (long) height, send);
            invokePPPP(winDepthDesc, sel_registerName("setUsage:"), 4L, send);
            invokePPPP(winDepthDesc, sel_registerName("setStorageMode:"), 2L, send);

            windowDepthTexture = invokePPPP(d, selNewTexture, winDepthDesc, send);
            invokePPP(winDepthDesc, selRelease, send); // Release descriptor retain count
        } finally {
            metalDevice.drainAutoreleasePool(autoPool);
        }
    }

    private static void retire(MetalDevice metalDevice, MetalGpuCache retireQueue, long texture) {
        if (texture == 0L) return;
        if (retireQueue != null) {
            retireQueue.enqueueDeferredDisposal(texture);
        } else {
            metalDevice.release(texture);
        }
    }

    /**
     * Reallocates the targets at the new size inside an explicit autorelease pool, so descriptor and texture temporaries
     * are drained per resize instead of accumulating in a frame pool. Same-size calls are no-ops.
     */
    public void resizeViewport(MetalDevice metalDevice, int width, int height, MetalGpuCache retireQueue) {
        if (metalDevice == null || width <= 0 || height <= 0) return;
        if (width == this.width && height == this.height && offscreenColorTexture != 0L) return;

        long pool = metalDevice.pushAutoreleasePool();
        try {
            initFramebuffer(metalDevice, width, height, retireQueue);
        } finally {
            metalDevice.drainAutoreleasePool(pool);
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
