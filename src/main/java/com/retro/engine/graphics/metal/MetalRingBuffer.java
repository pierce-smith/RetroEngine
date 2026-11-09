package com.retro.engine.graphics.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * A 3-slot ring of host-visible MTLBuffers for per-frame vertex streams (UI, grid, particles, pick overlay).
 * Automatically expands capacity when vertex counts exceed current buffer size.
 *
 * <p>Writes are two-step. {@link #stage} copies the caller's vertices into a CPU array; {@link #flush} copies them into
 * the next GPU slot. The backend calls flush only after its frame-in-flight permit is acquired, so a slot is never
 * rewritten while an earlier frame's GPU work can still read it. Writing at stage time raced the GPU whenever three
 * frames were queued, which vsync usually prevented and an uncapped frame rate made constant (flashing UI).
 */
public class MetalRingBuffer {

    private static final int RING_SLOTS = 3;

    private final long[] bufferRing = new long[RING_SLOTS];
    private final int[] capacityRing = new int[RING_SLOTS];

    private int ringIndex = 0;
    private int vertexCount = 0;

    // CPU copy waiting for flush(); grows to the largest frame and is then reused, so steady state allocates nothing
    private float[] staged = new float[0];
    private int stagedCount = 0;
    private int stagedStride = 0;
    private boolean pending = false;

    public long getCurrentBufferHandle() {
        return bufferRing[ringIndex];
    }

    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Records this frame's vertices for the next {@link #flush}. The data is copied, never retained.
     * {@link #getVertexCount} reports the staged count straight away; the buffer handle changes at flush.
     */
    public void stage(float[] vertices, int count, int strideFloats) {
        if (vertices == null || count <= 0 || strideFloats <= 0) {
            stagedCount = 0;
            vertexCount = 0;
            pending = true;
            return;
        }
        int floats = Math.min(count * strideFloats, vertices.length);
        count = floats / strideFloats;
        if (staged.length < floats) staged = new float[Math.max(floats, staged.length * 2)];
        System.arraycopy(vertices, 0, staged, 0, count * strideFloats);
        stagedCount = count;
        stagedStride = strideFloats;
        vertexCount = count;
        pending = true;
    }

    /** Copies staged vertices into the next ring slot. Call only once the GPU has retired that slot's last frame. */
    public void flush(MetalDevice metalDevice, MetalGpuCache cache) {
        if (!pending) return;
        pending = false;
        updateBuffer(metalDevice, staged, stagedCount, stagedStride, cache);
    }

    private void updateBuffer(MetalDevice metalDevice, float[] vertices, int count, int strideFloats, MetalGpuCache cache) {
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
