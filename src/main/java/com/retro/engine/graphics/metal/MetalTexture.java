package com.retro.engine.graphics.metal;

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
    // Scene material sampler: linear filtering with Repeat addressing, matching VulkanGpuCache.createMaterialSampler.
    // The UI sampler above keeps Metal's default ClampToEdge so font atlas glyphs never bleed across edges.
    private long materialSamplerStateHandle = 0L;
    private int hasActiveTexture = 0;
    // MTLTexture.gpuResourceID values (Metal 3), resolved once so argument buffer writes need no FFI call
    private long activeResourceId = 0L;
    private long defaultResourceId = 0L;

    private static final long SEL_GPU_RESOURCE_ID = sel_registerName("gpuResourceID");
    private static final long MTL_SAMPLER_ADDRESS_REPEAT = 2L;
    private static final long MTL_SAMPLER_MIP_FILTER_LINEAR = 2L;

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
            defaultResourceId = invokePPP(defaultTextureHandle, SEL_GPU_RESOURCE_ID, objc_msgSend);
        }

        long samplerDescClass = objc_getClass("MTLSamplerDescriptor");
        long samplerDesc = invokePPP(samplerDescClass, sel_registerName("new"), objc_msgSend);
        // 0L = MTLSamplerMinMagFilterNearest for sharp, crisp UI quads, text, and vector lines
        invokePPPP(samplerDesc, sel_registerName("setMinFilter:"), 1L, objc_msgSend);
        invokePPPP(samplerDesc, sel_registerName("setMagFilter:"), 1L, objc_msgSend);
        samplerStateHandle = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, objc_msgSend);

        // Imported models routinely carry tiled UVs outside [0, 1]; clamping them smears the texture's edge texels
        // across whole faces, so scene materials repeat exactly like the Vulkan backend's material sampler
        long materialDesc = invokePPP(samplerDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPP(materialDesc, sel_registerName("setMinFilter:"), 1L, objc_msgSend);                    // Linear
        invokePPPP(materialDesc, sel_registerName("setMagFilter:"), 1L, objc_msgSend);
        invokePPPP(materialDesc, sel_registerName("setMipFilter:"), MTL_SAMPLER_MIP_FILTER_LINEAR, objc_msgSend);
        invokePPPP(materialDesc, sel_registerName("setSAddressMode:"), MTL_SAMPLER_ADDRESS_REPEAT, objc_msgSend);
        invokePPPP(materialDesc, sel_registerName("setTAddressMode:"), MTL_SAMPLER_ADDRESS_REPEAT, objc_msgSend);
        materialSamplerStateHandle = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), materialDesc, objc_msgSend);
        long selRelease = sel_registerName("release");
        invokePPP(samplerDesc, selRelease, objc_msgSend);
        invokePPP(materialDesc, selRelease, objc_msgSend);
    }

    public void uploadTexture(long device, long objc_msgSend, byte[] pixels, int width, int height) {
        if (device == 0L || pixels == null || pixels.length == 0) return;

        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        // 70L = MTLPixelFormatRGBA8Unorm (prevents Red/Blue channel swizzling)
        long desc = invokePPPPPPP(descClass, selTex2D, 70L, (long) width, (long) height, 1L, objc_msgSend);
        long texture = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);
        if (texture == 0L) return;

        com.retro.engine.graphics.MipChain mips = com.retro.engine.graphics.MipChain.generate(pixels, width, height);
        ByteBuffer buf = MemoryUtil.memAlloc(mips.pixels.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            buf.put(mips.pixels).flip();
            long sel_replaceRegion = sel_registerName("replaceRegion:mipmapLevel:withBytes:bytesPerRow:");
            for (int level = 0; level < mips.levels(); level++) {
                ByteBuffer region = stack.malloc(8 * 6);
                region.putLong(0).putLong(0).putLong(0);
                region.putLong(mips.widths[level]).putLong(mips.heights[level]).putLong(1);
                region.flip();
                invokePPPPPPP(texture, sel_replaceRegion, MemoryUtil.memAddress(region), (long) level,
                        MemoryUtil.memAddress(buf) + mips.offsets[level], (long) mips.widths[level] * 4L, objc_msgSend);
            }
        } finally {
            MemoryUtil.memFree(buf);
        }
        this.activeTextureHandle = texture;
        this.activeResourceId = invokePPP(texture, SEL_GPU_RESOURCE_ID, objc_msgSend);
        this.hasActiveTexture = 1;
        System.out.println("[MetalTexture] Bound " + width + "x" + height + " RGBA texture to Metal GPU pipeline.");
    }

    public long getBindableTexture() {
        return (hasActiveTexture == 1 && activeTextureHandle != 0L) ? activeTextureHandle : defaultTextureHandle;
    }

    /** gpuResourceID of {@link #getBindableTexture()}; an MTLResourceID is a single 64-bit value returned in a register. */
    public long getBindableResourceId() {
        return (hasActiveTexture == 1 && activeTextureHandle != 0L) ? activeResourceId : defaultResourceId;
    }

    public long getSamplerStateHandle() { return samplerStateHandle; }
    /** Repeat-addressed linear sampler for lit scene materials (sampler(0) of the scene passes). */
    public long getMaterialSamplerStateHandle() { return materialSamplerStateHandle; }
    public int getHasActiveTexture() { return hasActiveTexture; }
}
