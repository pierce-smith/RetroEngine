package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * SRP Domain Manager: Manages a 3-slot CPU-to-GPU host-visible MTLBuffer ring buffer.
 * Automatically expands capacity when vertex counts exceed current buffer size.
 */
public class MetalRingBuffer {

    private static final int RING_SLOTS = 3;

    private final long[] bufferRing = new long[RING_SLOTS];
    private final int[] capacityRing = new int[RING_SLOTS];

    private int ringIndex = 0;
    private int vertexCount = 0;

    public long getCurrentBufferHandle() {
        return bufferRing[ringIndex];
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public void updateBuffer(MetalDevice metalDevice, float[] vertices, int count, int strideFloats, MetalGpuCache cache) {
        if (vertices == null || count <= 0 || metalDevice == null) {
            this.vertexCount = 0;
            return;
        }

        long device = metalDevice.getDevice();
        long send = metalDevice.getObjcMsgSend();
        if (device == 0L || send == 0L) {
            this.vertexCount = 0;
            return;
        }

        this.ringIndex = (this.ringIndex + 1) % RING_SLOTS;

        int requiredFloats = count * strideFloats;
        long currentBuffer = this.bufferRing[this.ringIndex];
        int currentCapacity = this.capacityRing[this.ringIndex];

        if (currentBuffer == 0L || requiredFloats > currentCapacity) {
            if (currentBuffer != 0L) {
                cache.enqueueDeferredDisposal(currentBuffer);
            }
            int newCap = Math.max(requiredFloats * 2, 2048 * strideFloats);
            this.capacityRing[this.ringIndex] = newCap;
            long byteSize = (long) newCap * 4L;
            currentBuffer = MetalBuffer.createUniformBuffer(device, send, byteSize);
            this.bufferRing[this.ringIndex] = currentBuffer;
        }

        if (currentBuffer != 0L) {
            long pContents = invokePPP(currentBuffer, sel_registerName("contents"), send);
            if (pContents != 0L) {
                FloatBuffer fb = MemoryUtil.memFloatBuffer(pContents, requiredFloats);
                fb.clear();
                fb.put(vertices, 0, requiredFloats);
            }
            this.vertexCount = count;
        }
    }

    public void cleanup(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getObjcMsgSend() == 0L) return;
        long send = metalDevice.getObjcMsgSend();
        long selRelease = sel_registerName("release");

        for (int i = 0; i < RING_SLOTS; i++) {
            if (bufferRing[i] != 0L) {
                invokePPP(bufferRing[i], selRelease, send);
                bufferRing[i] = 0L;
                capacityRing[i] = 0;
            }
        }
        this.vertexCount = 0;
    }
}
