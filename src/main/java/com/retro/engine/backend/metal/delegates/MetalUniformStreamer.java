package com.retro.engine.backend.metal.delegates;

import com.retro.engine.backend.metal.MetalBuffer;
import com.retro.engine.backend.metal.MetalDevice;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Delegate managing off-heap ring-buffered uniform streaming (triple buffering)
 * and 4x4 matrix calculations (View, Model, Projection, MVP).
 */
public class MetalUniformStreamer {

    private static final int RING_SLOTS = 3;
    public static final long UNIFORM_STRIDE = 256L; // 256-byte alignment required by Metal
    public static final int MAX_ENTITIES_PER_FRAME = 256;
    private static final long FRAME_SLOT_SIZE = UNIFORM_STRIDE * (1 + MAX_ENTITIES_PER_FRAME);
    private static final long TOTAL_RING_BUFFER_SIZE = FRAME_SLOT_SIZE * RING_SLOTS;

    private long streamingUniformBuffer = 0L;
    private long uniformBufferIdentity = 0L;
    private int frameIndex = 0;

    public void initialize(MetalDevice device) {
        long d = device.getDevice();
        long send = device.getObjcMsgSend();

        streamingUniformBuffer = MetalBuffer.createUniformBuffer(d, send, TOTAL_RING_BUFFER_SIZE);
        initIdentityUniform(device);
    }

    private void initIdentityUniform(MetalDevice device) {
        float[] identityMVP = new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        long send = device.getObjcMsgSend();
        uniformBufferIdentity = MetalBuffer.createUniformBuffer(device.getDevice(), send, 256L);
        long pIdentity = invokePPP(uniformBufferIdentity, sel_registerName("contents"), send);
        if (pIdentity != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pIdentity, 16);
            fb.clear();
            fb.put(identityMVP);
            MemoryUtil.memPutInt(pIdentity + 64L, 1); // hasTexture = 1
        }
    }

    public long getStreamingUniformBuffer() { return streamingUniformBuffer; }
    public long getUniformBufferIdentity() { return uniformBufferIdentity; }

    public int advanceFrameIndex() {
        frameIndex = (frameIndex + 1) % RING_SLOTS;
        return frameIndex;
    }

    public long getCurrentSlotOffset() {
        return frameIndex * FRAME_SLOT_SIZE;
    }

    // --- Matrix Math Calculations ---
    public float[] createCameraViewMatrix(float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        return new float[] {
            cy, sy * sp, sy * cp, 0.0f,
            0.0f, cp, -sp, 0.0f,
            -sy, cy * sp, cy * cp, 0.0f,
            camPanX, camPanY, camZoom, 1.0f
        };
    }

    public float[] createModelMatrix(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float radRx = (float) Math.toRadians(rx);
        float radRy = (float) Math.toRadians(ry);
        float radRz = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radRx), sxM = (float) Math.sin(radRx);
        float cy = (float) Math.cos(radRy), syM = (float) Math.sin(radRy);
        float cz = (float) Math.cos(radRz), szM = (float) Math.sin(radRz);

        float[] rotX = { 1, 0, 0, 0,  0, cx, -sxM, 0,  0, sxM, cx, 0,  0, 0, 0, 1 };
        float[] rotY = { cy, 0, syM, 0,  0, 1, 0, 0,  -syM, 0, cy, 0,  0, 0, 0, 1 };
        float[] rotZ = { cz, -szM, 0, 0,  szM, cz, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1 };

        float[] R = multiplyMat4(rotZ, multiplyMat4(rotY, rotX));

        float[] M = new float[16];
        M[0] = R[0] * sx; M[1] = R[1] * sx; M[2] = R[2] * sx; M[3] = 0.0f;
        M[4] = R[4] * sy; M[5] = R[5] * sy; M[6] = R[6] * sy; M[7] = 0.0f;
        M[8] = R[8] * sz; M[9] = R[9] * sz; M[10] = R[10] * sz; M[11] = 0.0f;
        M[12] = px; M[13] = py; M[14] = pz; M[15] = 1.0f;

        return M;
    }

    public float[] multiplyMat4(float[] A, float[] B) {
        float[] C = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += A[row + k * 4] * B[k + col * 4];
                }
                C[row + col * 4] = sum;
            }
        }
        return C;
    }

    public float[] createPerspectiveMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 100.0f;

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);

        return new float[] {
            p00, 0.0f, 0.0f, 0.0f,
            0.0f, p11, 0.0f, 0.0f,
            0.0f, 0.0f, p22, -1.0f,
            0.0f, 0.0f, p23, 0.0f
        };
    }

    public float[] createGridMVPMatrix(float aspect, float panX, float panY, float zoom, float pitch, float yaw) {
        float[] P = createPerspectiveMatrix(aspect);
        float[] V = createCameraViewMatrix(panX, panY, zoom, pitch, yaw);
        return multiplyMat4(P, V);
    }

    public float[] create3DMVPMatrix(float aspect, float panX, float panY, float zoom, float pitch, float yaw, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float[] P = createPerspectiveMatrix(aspect);
        float[] V = createCameraViewMatrix(panX, panY, zoom, pitch, yaw);
        float[] M = createModelMatrix(px, py, pz, rx, ry, rz, sx, sy, sz);
        float[] VM = multiplyMat4(V, M);
        return multiplyMat4(P, VM);
    }
}
