package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * MetalTexture manages 2D texture allocations and sampler states for Metal GPU pipelines.
 * Configured with MTLPixelFormatRGBA8Unorm (70L) for correct RGBA texture channel alignment
 * and nearest-neighbor sampling for crisp UI and grid rendering.
 */
public class MetalTexture {

    private long activeTextureHandle = 0L;
    private long defaultTextureHandle = 0L;
    private long samplerStateHandle = 0L;
    private int hasActiveTexture = 0;

    public void initDefaultTextureAndSampler(long device, long objc_msgSend) {
        if (device == 0L) return;

        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        // 70L = MTLPixelFormatRGBA8Unorm (matches Vulkan VK_FORMAT_R8G8B8A8_UNORM)
        long desc = invokePPPPPPP(descClass, selTex2D, 70L, 1L, 1L, 0L, objc_msgSend);

        defaultTextureHandle = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        if (defaultTextureHandle != 0L) {
            ByteBuffer buf = MemoryUtil.memAlloc(4);
            buf.put(whitePixel).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer region = stack.malloc(8 * 6);
                region.putLong(0).putLong(0).putLong(0);
                region.putLong(1).putLong(1).putLong(1);
                region.flip();

                long sel_replaceRegion = sel_registerName("replaceRegion:mipmapLevel:withBytes:bytesPerRow:");
                invokePPPPPPP(defaultTextureHandle, sel_replaceRegion, MemoryUtil.memAddress(region), 0L, MemoryUtil.memAddress(buf), 4L, objc_msgSend);
            }
            MemoryUtil.memFree(buf);
        }

        long samplerDescClass = objc_getClass("MTLSamplerDescriptor");
        long samplerDesc = invokePPP(samplerDescClass, sel_registerName("new"), objc_msgSend);
        // 0L = MTLSamplerMinMagFilterNearest for sharp, crisp UI quads, text, and vector lines
        invokePPPP(samplerDesc, sel_registerName("setMinFilter:"), 0L, objc_msgSend);
        invokePPPP(samplerDesc, sel_registerName("setMagFilter:"), 0L, objc_msgSend);
        samplerStateHandle = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, objc_msgSend);
    }

    public void uploadTexture(long device, long objc_msgSend, byte[] pixels, int width, int height) {
        if (device == 0L || pixels == null || pixels.length == 0) return;

        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        // 70L = MTLPixelFormatRGBA8Unorm (prevents Red/Blue channel swizzling)
        long desc = invokePPPPPPP(descClass, selTex2D, 70L, (long) width, (long) height, 0L, objc_msgSend);
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
            invokePPPPPPP(texture, sel_replaceRegion, MemoryUtil.memAddress(region), 0L, MemoryUtil.memAddress(buf), (long) width * 4L, objc_msgSend);
        }

        MemoryUtil.memFree(buf);
        this.activeTextureHandle = texture;
        this.hasActiveTexture = 1;
        System.out.println("[MetalTexture] Bound " + width + "x" + height + " RGBA texture to Metal GPU pipeline.");
    }

    public long getBindableTexture() {
        return (hasActiveTexture == 1 && activeTextureHandle != 0L) ? activeTextureHandle : defaultTextureHandle;
    }

    public long getSamplerStateHandle() { return samplerStateHandle; }
    public int getHasActiveTexture() { return hasActiveTexture; }
}
