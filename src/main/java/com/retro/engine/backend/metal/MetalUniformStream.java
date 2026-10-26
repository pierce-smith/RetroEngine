package com.retro.engine.backend.metal;

import com.retro.engine.rhi.DirectionalShadowFrustum;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * Manages triple-buffered uniform streaming and matrix calculations with locked
 * public static offset constants for byte-level GPU alignment.
 * Optimized for zero-allocation hot paths during per-entity matrix generation.
 */
public class MetalUniformStream {

    private static final int RING_SLOTS = 3;
    // 304-byte Uniforms struct rounded up to keep every slot 256-byte aligned for constant buffer binding
    public static final long UNIFORM_STRIDE = 512L;
    public static final int MAX_ENTITIES_PER_FRAME = 256;

    // Per-frame slot layout: [grid] [64 shadow blobs] [MAX_ENTITIES lit entities] [MAX_ENTITIES shadow map casters]
    public static final int SLOT_BASE_SHADOW_BLOBS = 1;
    public static final int SLOT_BASE_ENTITIES = 1 + 64;
    public static final int SLOT_BASE_SHADOW_CASTERS = 1 + 64 + MAX_ENTITIES_PER_FRAME;
    private static final long FRAME_SLOT_SIZE = UNIFORM_STRIDE * (1 + 64 + 2 * MAX_ENTITIES_PER_FRAME);
    private static final long TOTAL_RING_BUFFER_SIZE = FRAME_SLOT_SIZE * RING_SLOTS;

    // Locked Byte Offsets for MSL Uniforms Struct
    public static final long OFFSET_MVP = 0L;
    public static final long OFFSET_MODEL = 64L;
    public static final long OFFSET_LIGHT_DIR = 128L;
    public static final long OFFSET_LIGHT_POS = 144L;
    public static final long OFFSET_LIGHT_COLOR = 160L;
    public static final long OFFSET_AMBIENT = 176L;
    public static final long OFFSET_FOG = 192L;
    public static final long OFFSET_MAT_PARAMS = 208L;
    public static final long OFFSET_HAS_TEXTURE = 224L;
    public static final long OFFSET_IS_UNLIT = 228L;
    public static final long OFFSET_STRIDE = 232L;
    public static final long OFFSET_PASS_FLAGS = 236L;
    public static final long OFFSET_LIGHT_SPACE = 240L;

    // passFlags bit 4: vertex shader applies the bone palette bound at vertex buffer index 2
    public static final int PASS_FLAG_SKINNED = 16;

    // Bone palettes (64 column-major float4x4) are too large for the 256-byte uniform slot,
    // so they stream through a dedicated ring buffer bound at vertex buffer index 2.
    public static final int MAX_PALETTE_BONES = 64;
    public static final long BONE_PALETTE_STRIDE = MAX_PALETTE_BONES * 64L;
    public static final int MAX_SKINNED_ENTITIES_PER_FRAME = 64;
    private static final long BONE_FRAME_SLOT_SIZE = BONE_PALETTE_STRIDE * MAX_SKINNED_ENTITIES_PER_FRAME;

    private long streamingUniformBuffer = 0L;
    private long uniformBufferIdentity = 0L;
    private long bonePaletteBuffer = 0L;
    private int frameIndex = 0;

    private final float[] matP = new float[16];
    private final float[] matV = new float[16];
    private final float[] matM = new float[16];
    private final float[] matMVP = new float[16];
    private final float[] matVM = new float[16]; // Reusable buffer for View*Model matrix
    private final float[] matRotX = new float[16];
    private final float[] matRotY = new float[16];
    private final float[] matRotZ = new float[16];
    private final float[] matR = new float[16];
    private final float[] matTempR = new float[16];

    // Directional shadow map view-projection, refit to the scene bounds once per frame and streamed into every slot
    private final DirectionalShadowFrustum shadowFrustum = new DirectionalShadowFrustum();
    private final float[] matLightSpace = new float[16];
    private final float[] matLightMVP = new float[16];

    public void initialize(MetalDevice device) {
        long d = device.getDevice();
        long send = device.getObjcMsgSend();

        streamingUniformBuffer = MetalBuffer.createUniformBuffer(d, send, TOTAL_RING_BUFFER_SIZE);
        bonePaletteBuffer = MetalBuffer.createUniformBuffer(d, send, BONE_FRAME_SLOT_SIZE * RING_SLOTS);
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
        uniformBufferIdentity = MetalBuffer.createUniformBuffer(device.getDevice(), send, UNIFORM_STRIDE);
        long pIdentity = invokePPP(uniformBufferIdentity, sel_registerName("contents"), send);
        if (pIdentity != 0L) {
            FloatBuffer fb = MemoryUtil.memFloatBuffer(pIdentity + OFFSET_MVP, 16);
            fb.clear();
            fb.put(identityMVP);

            FloatBuffer fbModel = MemoryUtil.memFloatBuffer(pIdentity + OFFSET_MODEL, 16);
            fbModel.clear();
            fbModel.put(identityMVP);

            MemoryUtil.memPutInt(pIdentity + OFFSET_HAS_TEXTURE, 1);
            MemoryUtil.memPutInt(pIdentity + OFFSET_IS_UNLIT, 2); // 2 = Viewport Pass-through Mode
            MemoryUtil.memPutInt(pIdentity + OFFSET_STRIDE, 9);
            MemoryUtil.memPutInt(pIdentity + OFFSET_PASS_FLAGS, 0);
        }
    }

    public long getStreamingUniformBuffer() { return streamingUniformBuffer; }
    public long getUniformBufferIdentity() { return uniformBufferIdentity; }
    public long getBonePaletteBuffer() { return bonePaletteBuffer; }

    public long getBonePaletteOffset(int skinnedIndex) {
        return frameIndex * BONE_FRAME_SLOT_SIZE + skinnedIndex * BONE_PALETTE_STRIDE;
    }

    /** Copies up to 64 column-major 4x4 matrices into the current frame's palette slot. */
    public void writeBonePalette(long pBoneContents, int skinnedIndex, float[][] palette) {
        if (pBoneContents == 0L || palette == null) return;
        FloatBuffer fb = MemoryUtil.memFloatBuffer(pBoneContents + getBonePaletteOffset(skinnedIndex), MAX_PALETTE_BONES * 16);
        int count = Math.min(palette.length, MAX_PALETTE_BONES);
        for (int b = 0; b < count; b++) {
            fb.put(palette[b], 0, 16);
        }
    }

    /** Bounds accumulator for this frame's shadow frustum; reset, include every entity, then call updateLightSpaceMatrix. */
    public DirectionalShadowFrustum getShadowFrustum() { return shadowFrustum; }

    public float[] updateLightSpaceMatrix(float lightDirX, float lightDirY, float lightDirZ) {
        shadowFrustum.build(lightDirX, lightDirY, lightDirZ, matLightSpace);
        return matLightSpace;
    }

    public float[] getLightSpaceMatrix() { return matLightSpace; }

    /** Writes this frame's light-space matrix into the uniform slot at baseAddr. */
    public void writeLightSpaceMatrix(long baseAddr) {
        FloatBuffer fb = MemoryUtil.memFloatBuffer(baseAddr + OFFSET_LIGHT_SPACE, 16);
        fb.put(matLightSpace);
    }

    /** lightSpace * model for the shadow map depth pass; also leaves the model matrix in getModelMatrix(). */
    public float[] createLightSpaceMVPMatrix(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        createModelMatrix(px, py, pz, rx, ry, rz, sx, sy, sz);
        multiplyMat4(matLightSpace, matM, matLightMVP);
        return matLightMVP;
    }

    public int advanceFrameIndex() {
        frameIndex = (frameIndex + 1) % RING_SLOTS;
        return frameIndex;
    }

    public long getCurrentSlotOffset() {
        return frameIndex * FRAME_SLOT_SIZE;
    }

    public float[] createCameraViewMatrix(float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        matV[0] = cy;        matV[1] = sy * sp;   matV[2] = sy * cp;   matV[3] = 0.0f;
        matV[4] = 0.0f;      matV[5] = cp;        matV[6] = -sp;       matV[7] = 0.0f;
        matV[8] = -sy;       matV[9] = cy * sp;   matV[10] = cy * cp;  matV[11] = 0.0f;
        matV[12] = camPanX;  matV[13] = camPanY;  matV[14] = camZoom;  matV[15] = 1.0f;

        return matV;
    }

    public float[] createModelMatrix(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        float radRx = (float) Math.toRadians(rx);
        float radRy = (float) Math.toRadians(ry);
        float radRz = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radRx), sxM = (float) Math.sin(radRx);
        float cy = (float) Math.cos(radRy), syM = (float) Math.sin(radRy);
        float cz = (float) Math.cos(radRz), szM = (float) Math.sin(radRz);

        matRotX[0]=1; matRotX[1]=0;   matRotX[2]=0;    matRotX[3]=0;
        matRotX[4]=0; matRotX[5]=cx;  matRotX[6]=-sxM; matRotX[7]=0;
        matRotX[8]=0; matRotX[9]=sxM; matRotX[10]=cx;  matRotX[11]=0;
        matRotX[12]=0;matRotX[13]=0;  matRotX[14]=0;   matRotX[15]=1;

        matRotY[0]=cy;  matRotY[1]=0; matRotY[2]=syM; matRotY[3]=0;
        matRotY[4]=0;   matRotY[5]=1; matRotY[6]=0;   matRotY[7]=0;
        matRotY[8]=-syM;matRotY[9]=0; matRotY[10]=cy; matRotY[11]=0;
        matRotY[12]=0;  matRotY[13]=0;matRotY[14]=0;  matRotY[15]=1;

        matRotZ[0]=cz;  matRotZ[1]=-szM;matRotZ[2]=0; matRotZ[3]=0;
        matRotZ[4]=szM; matRotZ[5]=cz;  matRotZ[6]=0; matRotZ[7]=0;
        matRotZ[8]=0;   matRotZ[9]=0;   matRotZ[10]=1;matRotZ[11]=0;
        matRotZ[12]=0;  matRotZ[13]=0;  matRotZ[14]=0;matRotZ[15]=1;

        multiplyMat4(matRotY, matRotX, matTempR);
        multiplyMat4(matRotZ, matTempR, matR);

        matM[0] = matR[0] * sx;  matM[1] = matR[1] * sx;  matM[2] = matR[2] * sx;  matM[3] = 0.0f;
        matM[4] = matR[4] * sy;  matM[5] = matR[5] * sy;  matM[6] = matR[6] * sy;  matM[7] = 0.0f;
        matM[8] = matR[8] * sz;  matM[9] = matR[9] * sz;  matM[10] = matR[10] * sz;matM[11] = 0.0f;
        matM[12] = px;           matM[13] = py;           matM[14] = pz;           matM[15] = 1.0f;

        return matM;
    }

    public void multiplyMat4(float[] A, float[] B, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += A[row + k * 4] * B[k + col * 4];
                }
                out[row + col * 4] = sum;
            }
        }
    }

    public float[] createPerspectiveMatrix(float aspect) {
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = 0.1f, zFar = 500.0f;

        float p00 = f / aspect;
        float p11 = f;
        float p22 = zFar / (zNear - zFar);
        float p23 = (zNear * zFar) / (zNear - zFar);

        matP[0] = p00;  matP[1] = 0.0f; matP[2] = 0.0f; matP[3] = 0.0f;
        matP[4] = 0.0f; matP[5] = p11;  matP[6] = 0.0f; matP[7] = 0.0f;
        matP[8] = 0.0f; matP[9] = 0.0f; matP[10] = p22;matP[11] = -1.0f;
        matP[12] = 0.0f;matP[13] = 0.0f;matP[14] = p23;matP[15] = 0.0f;

        return matP;
    }

    public float[] createGridMVPMatrix(float aspect, float panX, float panY, float zoom, float pitch, float yaw) {
        createPerspectiveMatrix(aspect);
        createCameraViewMatrix(panX, panY, zoom, pitch, yaw);
        multiplyMat4(matP, matV, matMVP);
        return matMVP;
    }

    public float[] create3DMVPMatrix(float aspect, float panX, float panY, float zoom, float pitch, float yaw, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        createPerspectiveMatrix(aspect);
        createCameraViewMatrix(panX, panY, zoom, pitch, yaw);
        createModelMatrix(px, py, pz, rx, ry, rz, sx, sy, sz);

        multiplyMat4(matV, matM, matVM);
        multiplyMat4(matP, matVM, matMVP);
        return matMVP;
    }

    public float[] getModelMatrix() { return matM; }
}
