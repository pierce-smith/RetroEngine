package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * SRP Domain Manager: Manages the default white texture fallback and sampler state lifecycle.
 * Registered scene textures are owned by MetalGpuCache's opaque handle table.
 */
public class MetalTextureRegistry {

    private long defaultTextureHandle = 0L;
    private long samplerStateHandle = 0L;

    public void initDefaultTextureAndSampler(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getDevice() == 0L) return;

        long device = metalDevice.getDevice();
        long objc_msgSend = metalDevice.getObjcMsgSend();

        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
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
        invokePPPP(samplerDesc, sel_registerName("setMinFilter:"), 1L, objc_msgSend);
        invokePPPP(samplerDesc, sel_registerName("setMagFilter:"), 1L, objc_msgSend);
        samplerStateHandle = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, objc_msgSend);
    }

    public long getDefaultTextureHandle(MetalDevice metalDevice) {
        if (defaultTextureHandle == 0L) {
            initDefaultTextureAndSampler(metalDevice);
        }
        return defaultTextureHandle;
    }

    public long getSamplerStateHandle(MetalDevice metalDevice) {
        if (samplerStateHandle == 0L) {
            initDefaultTextureAndSampler(metalDevice);
        }
        return samplerStateHandle;
    }

    public void cleanup(MetalDevice metalDevice) {
        if (metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
            long send = metalDevice.getObjcMsgSend();
            long selRelease = sel_registerName("release");

            if (defaultTextureHandle != 0L) {
                invokePPP(defaultTextureHandle, selRelease, send);
                defaultTextureHandle = 0L;
            }
            if (samplerStateHandle != 0L) {
                invokePPP(samplerStateHandle, selRelease, send);
                samplerStateHandle = 0L;
            }
        }
    }
}
