package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalBuffer manages MTLBuffer GPU allocations for 3D meshes, 2D UI geometry, and MVP uniform matrices.
 */
public class MetalBuffer {

    private long bufferHandle = 0L;
    private int elementCount = 0;

    public static MetalBuffer createWithFloatArray(long device, long objc_msgSend, float[] data) {
        if (device == 0L || data == null || data.length == 0) return new MetalBuffer(0L, 0);

        FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
        fb.put(data).flip();

        long sel_newBufferWithBytes = sel_registerName("newBufferWithBytes:length:options:");
        long handle = invokePPPPPP(device, sel_newBufferWithBytes, MemoryUtil.memAddress(fb), (long) data.length * 4L, 0L, objc_msgSend);

        MemoryUtil.memFree(fb);
        return new MetalBuffer(handle, data.length / 9); // 9 float vertex stride
    }

    public static long createUniformBuffer(long device, long objc_msgSend, long length) {
        if (device == 0L) return 0L;
        return invokePPPPP(device, sel_registerName("newBufferWithLength:options:"), length, 0L, objc_msgSend);
    }

    public MetalBuffer(long bufferHandle, int elementCount) {
        this.bufferHandle = bufferHandle;
        this.elementCount = elementCount;
    }

    public long getHandle() { return bufferHandle; }
    public int getElementCount() { return elementCount; }
}
