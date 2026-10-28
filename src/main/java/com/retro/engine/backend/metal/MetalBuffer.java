package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

public class MetalBuffer {

    private static final long SEL_GPU_ADDRESS = sel_registerName("gpuAddress");

    private long bufferHandle = 0L;
    private int elementCount = 0;
    private int floatStride = 0;

    public MetalBuffer(long bufferHandle, int elementCount) {
        this.bufferHandle = bufferHandle;
        this.elementCount = elementCount;
    }

    public MetalBuffer(long bufferHandle, int elementCount, int floatStride) {
        this(bufferHandle, elementCount);
        this.floatStride = floatStride;
    }

    public static MetalBuffer createWithFloatArray(long device, long objc_msgSend, float[] data, int floatStride) {
        if (device == 0L || data == null || data.length == 0) return new MetalBuffer(0L, 0);

        FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
        long handle;
        try {
            fb.put(data).flip();
            long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
            handle = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) data.length * 4L, 0L, objc_msgSend);
        } finally {
            MemoryUtil.memFree(fb);
        }
        return new MetalBuffer(handle, data.length / floatStride, floatStride);
    }

    public static MetalBuffer createWithFloatArray(long device, long objc_msgSend, float[] data) {
        return createWithFloatArray(device, objc_msgSend, data, 9);
    }

    public static long createUniformBuffer(long device, long objc_msgSend, long length) {
        if (device == 0L) return 0L;
        return invokePPPPP(device, sel_registerName("newBufferWithLength:options:"), length, 0L, objc_msgSend);
    }

    /** MTLBuffer.gpuAddress (macOS 13+ / Metal 3): the 64-bit pointer argument buffers store for this buffer. */
    public static long gpuAddress(long buffer, long objc_msgSend) {
        return buffer != 0L ? invokePPP(buffer, SEL_GPU_ADDRESS, objc_msgSend) : 0L;
    }

    public long getHandle() { return bufferHandle; }
    public int getElementCount() { return elementCount; }
    public int getFloatStride() { return floatStride; }
}
