package com.retro.engine.graphics.metal;

import com.retro.engine.graphics.RenderEnvironment;
import com.retro.engine.graphics.SceneLights;

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
    // 368-byte Uniforms struct rounded up to keep every slot 256-byte aligned for constant buffer binding
    public static final long UNIFORM_STRIDE = 512L;
    // Draws the streams start with. They grow to fit the scene, so there is no limit on draws per frame.
    public static final int INITIAL_DRAW_CAPACITY = 1024;
    private static final int PARTICLE_SLOTS = com.retro.engine.graphics.ParticleDrawList.MAX_BATCHES;

    /** Draw capacity needed for {@code draws} draws: the next power of two, so growth is rare. */
    public static int capacityFor(int draws) {
        return Math.max(INITIAL_DRAW_CAPACITY, Integer.highestOneBit(Math.max(draws, 1) - 1) << 1);
    }

    /** Uniform slots in one frame: the grid, six per-draw regions, then the particle batches. */
    public static int slotsPerFrame(int capacity) {
        return 1 + REGIONS * capacity + PARTICLE_SLOTS;
    }

    // Per-frame slot layout: [grid] [shadow blobs] [lit entities] [shadow map casters] [camera preview shadow blobs]
    // [camera preview lit entities] [Color-ID pick entities] [particle batches, main scene pass only]. Each region holds
    // one slot per draw. The camera preview pass gets its own slots because both passes are encoded before the GPU reads
    // any of them.
    private static final int REGIONS = 6;
    // One LocalLights block (blinn_phong.metal) per frame follows the uniform slots: point and spot light arrays are
    // written once per frame and every lit draw references them through Uniforms.localLights
    public static final long LIGHT_BLOCK_POINT_STRIDE = 32L;  // position, color (a = intensity)
    public static final long LIGHT_BLOCK_SPOT_STRIDE = 48L;   // position, direction, color (a = intensity)
    public static final long LIGHT_BLOCK_SPOTS_OFFSET = LIGHT_BLOCK_POINT_STRIDE * SceneLights.MAX_POINT_LIGHTS;
    // 1408-byte LocalLights struct rounded up to keep the frame regions 256-byte aligned
    public static final long LIGHT_BLOCK_SIZE = 1536L;

    // Locked Byte Offsets for MSL Uniforms Struct
    public static final long OFFSET_MVP = 0L;
    public static final long OFFSET_MODEL = 64L;
    public static final long OFFSET_DIR_LIGHT = 128L;        // xyz = direction, w = intensity
    public static final long OFFSET_NUM_LIGHTS = 144L;       // x = directional (1.0 = active), y = point count, z = spot count
    public static final long OFFSET_DIR_LIGHT_COLOR = 160L;  // rgb = color
    public static final long OFFSET_AMBIENT = 176L;          // rgb = ambient color * intensity
    public static final long OFFSET_FOG = 192L;              // rgb = fog color, w = fog opacity at fog end (1 = on, 0 = off)
    public static final long OFFSET_MAT_PARAMS = 208L;
    public static final long OFFSET_MATERIAL_MASK = 224L;
    public static final long OFFSET_IS_UNLIT = 228L;
    /** isUnlit value for a model drawn without scene lighting (see blinn_phong.metal). */
    public static final int UNLIT_MODEL = 3;
    public static final long OFFSET_STRIDE = 232L;
    public static final long OFFSET_PASS_FLAGS = 236L;
    public static final long OFFSET_LIGHT_SPACE = 240L;
    public static final long OFFSET_SURFACE = 304L;
    public static final long OFFSET_VIEW_POS = 320L;
    public static final long OFFSET_PICK_ID = 336L;
    public static final long OFFSET_LOCAL_LIGHTS = 344L;      // gpuAddress of the frame's LocalLights block
    public static final long OFFSET_FOG_PARAMS = 352L;        // x = fog start, y = fog end, z = ramp exponent, w = shading model (368 bytes total)

    // MSL layout guard: float4 / float4x4 members need 16-byte offsets, the LocalLights pointer 8, and both structs
    // must fit their slots. Array sizes must match the ones compiled into blinn_phong.metal.
    static {
        long[] float4Aligned = { OFFSET_MVP, OFFSET_MODEL, OFFSET_DIR_LIGHT, OFFSET_NUM_LIGHTS, OFFSET_DIR_LIGHT_COLOR, OFFSET_AMBIENT,
                OFFSET_FOG, OFFSET_MAT_PARAMS, OFFSET_LIGHT_SPACE, OFFSET_SURFACE, OFFSET_VIEW_POS, OFFSET_FOG_PARAMS,
                LIGHT_BLOCK_POINT_STRIDE, LIGHT_BLOCK_SPOT_STRIDE, LIGHT_BLOCK_SPOTS_OFFSET, UNIFORM_STRIDE };
        for (long offset : float4Aligned) {
            if (offset % 16 != 0) throw new ExceptionInInitializerError("Metal uniform offset " + offset + " is not 16-byte aligned");
        }
        if (OFFSET_LOCAL_LIGHTS % 8 != 0 || OFFSET_FOG_PARAMS + 16 > UNIFORM_STRIDE
                || LIGHT_BLOCK_SPOTS_OFFSET + LIGHT_BLOCK_SPOT_STRIDE * SceneLights.MAX_SPOT_LIGHTS > LIGHT_BLOCK_SIZE
                || SceneLights.MAX_POINT_LIGHTS != 32 || SceneLights.MAX_SPOT_LIGHTS != 8) {
            throw new ExceptionInInitializerError("Metal Uniforms / LocalLights layout no longer matches blinn_phong.metal");
        }
    }

    // materialMask bits: which DrawArgs texture channels the fragment stage samples
    public static final int MATERIAL_ALBEDO = 1;
    public static final int MATERIAL_NORMAL = 2;
    public static final int MATERIAL_SPECULAR = 4;
    public static final int MATERIAL_LIGHTMAP = 8;
    public static final int MATERIAL_EMISSIVE = 16;

    // Metal 3 argument buffer table: one 64-byte DrawArgs entry per uniform slot, so a draw's [[base_instance]]
    // is both its DrawArgs index and its uniform slot. Pointers are gpuAddress values, textures gpuResourceID values.
    public static final long DRAW_ARGS_STRIDE = 64L;
    public static final long DRAW_ARGS_VERTICES = 0L;
    public static final long DRAW_ARGS_UNIFORMS = 8L;
    public static final long DRAW_ARGS_BONES = 16L;
    public static final long DRAW_ARGS_TEXTURES = 24L; // albedo, normal, specular, lightmap, emissive (8 bytes each)
    public static final int MATERIAL_CHANNELS = 5;

    // passFlags bit 4: vertex shader applies the bone palette referenced by DrawArgs.bones
    public static final int PASS_FLAG_SKINNED = 16;
    // passFlags bit 5: decal blending in the lit shader, with the opacity in matParams.w and growth in matParams.y
    public static final int PASS_FLAG_DECAL = 32;

    // Bone palettes (64 column-major float4x4) are too large for the 256-byte uniform slot,
    // so they stream through a dedicated ring buffer referenced from each skinned draw's DrawArgs.bones.
    public static final int MAX_PALETTE_BONES = 64;
    public static final long BONE_PALETTE_STRIDE = MAX_PALETTE_BONES * 64L;
    public static final int MAX_SKINNED_ENTITIES_PER_FRAME = 64;
    private static final long BONE_FRAME_SLOT_SIZE = BONE_PALETTE_STRIDE * MAX_SKINNED_ENTITIES_PER_FRAME;

    private long streamingUniformBuffer = 0L;
    private long bonePaletteBuffer = 0L;
    private long drawArgsBuffer = 0L;
    // gpuAddress is fixed for a buffer's lifetime, so these are resolved once instead of per draw
    private long streamingUniformAddress = 0L;
    private long bonePaletteAddress = 0L;
    private int frameIndex = 0;
    private int drawCapacity = INITIAL_DRAW_CAPACITY;
    private long lightBlockFrameOffset, frameSlotSize, drawArgsFrameSize;

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
    private final float[] matLightSpace = new float[16];
    private final float[] matLightMVP = new float[16];
    private final float[] cameraPos = new float[3];

    public void initialize(MetalDevice device) {
        long d = device.getDevice();
        long send = device.getObjcMsgSend();

        allocateDrawBuffers(d, send);
        bonePaletteBuffer = MetalBuffer.createUniformBuffer(d, send, BONE_FRAME_SLOT_SIZE * RING_SLOTS);
        bonePaletteAddress = MetalBuffer.gpuAddress(bonePaletteBuffer, send);
    }

    private void allocateDrawBuffers(long d, long send) {
        int slots = slotsPerFrame(drawCapacity);
        lightBlockFrameOffset = UNIFORM_STRIDE * slots;
        frameSlotSize = lightBlockFrameOffset + LIGHT_BLOCK_SIZE;
        drawArgsFrameSize = DRAW_ARGS_STRIDE * slots;
        streamingUniformBuffer = MetalBuffer.createUniformBuffer(d, send, frameSlotSize * RING_SLOTS);
        drawArgsBuffer = MetalBuffer.createUniformBuffer(d, send, drawArgsFrameSize * RING_SLOTS);
        streamingUniformAddress = MetalBuffer.gpuAddress(streamingUniformBuffer, send);
    }

    /** True when the buffers can't hold {@code draws} draws, so {@link #growTo} has to run before they're encoded. */
    public boolean needsGrowth(int draws) {
        return draws > drawCapacity;
    }

    /** Replaces the per-draw buffers with larger ones. The GPU must not be reading them: every frame has to have retired. */
    public void growTo(MetalDevice device, int draws) {
        long send = device.getObjcMsgSend();
        long release = sel_registerName("release");
        if (streamingUniformBuffer != 0L) invokePPP(streamingUniformBuffer, release, send);
        if (drawArgsBuffer != 0L) invokePPP(drawArgsBuffer, release, send);
        drawCapacity = capacityFor(draws);
        allocateDrawBuffers(device.getDevice(), send);
        System.out.println("[MetalUniformStream] Scene needs " + draws + " draws; per-draw buffers grown to " + drawCapacity + ".");
    }

    public int slotBaseShadowBlobs() { return 1; }
    public int slotBaseEntities() { return 1 + drawCapacity; }
    public int slotBaseShadowCasters() { return 1 + 2 * drawCapacity; }
    public int slotBasePreviewShadowBlobs() { return 1 + 3 * drawCapacity; }
    public int slotBasePreviewEntities() { return 1 + 4 * drawCapacity; }
    public int slotBasePickEntities() { return 1 + 5 * drawCapacity; }
    public int slotBaseParticles() { return 1 + REGIONS * drawCapacity; }

    public long getStreamingUniformBuffer() { return streamingUniformBuffer; }
    public long getBonePaletteBuffer() { return bonePaletteBuffer; }
    public long getDrawArgsBuffer() { return drawArgsBuffer; }

    /** GPU virtual address of uniform slot {@code slot} in the current frame, for DrawArgs.uniforms. */
    public long getUniformSlotAddress(int slot) {
        return streamingUniformAddress + getCurrentSlotOffset() + UNIFORM_STRIDE * slot;
    }

    /** GPU virtual address of a skinned entity's palette in the current frame (slot 0 for unskinned draws). */
    public long getBonePaletteAddress(int skinnedIndex) {
        return bonePaletteAddress + getBonePaletteOffset(Math.max(skinnedIndex, 0));
    }

    /** Byte offset of the current frame's DrawArgs table inside {@link #getDrawArgsBuffer()}. */
    public long getCurrentDrawArgsOffset() {
        return frameIndex * drawArgsFrameSize;
    }

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

    /** Copies the scene pipeline's fitted directional light view-projection (column-major) for this frame's passes. */
    public void setLightSpaceMatrix(float[] lightSpace) {
        System.arraycopy(lightSpace, 0, matLightSpace, 0, 16);
    }

    public float[] getLightSpaceMatrix() { return matLightSpace; }

    /** Writes this frame's light-space matrix into the uniform slot at baseAddr. */
    public void writeLightSpaceMatrix(long baseAddr) {
        FloatBuffer fb = MemoryUtil.memFloatBuffer(baseAddr + OFFSET_LIGHT_SPACE, 16);
        fb.put(matLightSpace);
    }

    /**
     * Writes the Point and Spot light arrays into the current frame's LocalLights block. Call once per frame, before the
     * lit draws that reference it through {@link #writeDrawLights}. Only the first pointCount / spotCount entries are
     * written; the shader loops stop at those counts.
     */
    public void writeLightBlock(long pBufferContents, SceneLights lights) {
        long block = pBufferContents + getCurrentSlotOffset() + lightBlockFrameOffset;
        for (int i = 0; i < lights.pointCount(); i++) {
            SceneLights.Light point = lights.point(i);
            long addr = block + LIGHT_BLOCK_POINT_STRIDE * i;
            putFloat4(addr, point.posX, point.posY, point.posZ, 0.0f);
            putFloat4(addr + 16L, point.r, point.g, point.b, point.intensity);
        }
        for (int i = 0; i < lights.spotCount(); i++) {
            SceneLights.Light spot = lights.spot(i);
            long addr = block + LIGHT_BLOCK_SPOTS_OFFSET + LIGHT_BLOCK_SPOT_STRIDE * i;
            putFloat4(addr, spot.posX, spot.posY, spot.posZ, 0.0f);
            putFloat4(addr + 16L, spot.dirX, spot.dirY, spot.dirZ, 0.0f);
            putFloat4(addr + 32L, spot.r, spot.g, spot.b, spot.intensity);
        }
    }

    /**
     * Writes the project render environment (premultiplied ambient, fog color / opacity, fog start / end / curve) into
     * the lit uniform slot at baseAddr. Disabled fog is written as opacity 0, which the shader's mix turns into a no-op.
     * The shading model rides in fogParams.w (0 = Blinn-Phong, 1 = Gouraud).
     */
    public void writeDrawEnvironment(long baseAddr, RenderEnvironment env) {
        float ambient = env.ambientIntensity;
        putFloat4(baseAddr + OFFSET_AMBIENT, env.ambientR * ambient, env.ambientG * ambient, env.ambientB * ambient, 1.0f);
        putFloat4(baseAddr + OFFSET_FOG, env.fogR, env.fogG, env.fogB, env.fogOpacity());
        putFloat4(baseAddr + OFFSET_FOG_PARAMS, env.safeFogStart(), env.safeFogEnd(), env.fogCurveExponent(), env.shadingModelShaderValue());
    }

    /**
     * Writes the directional light, the numLights counts and the current frame's LocalLights address into the uniform
     * slot at baseAddr. An absent directional light is zeroed; the lit shader skips it through numLights.x.
     */
    public void writeDrawLights(long baseAddr, SceneLights lights) {
        boolean hasDir = lights.hasDirectional();
        SceneLights.Light dir = lights.directional();

        putFloat4(baseAddr + OFFSET_DIR_LIGHT, dir.dirX, dir.dirY, dir.dirZ, hasDir ? dir.intensity : 0.0f);
        putFloat4(baseAddr + OFFSET_NUM_LIGHTS, hasDir ? 1.0f : 0.0f, lights.pointCount(), lights.spotCount(), 0.0f);
        if (hasDir) {
            putFloat4(baseAddr + OFFSET_DIR_LIGHT_COLOR, dir.r, dir.g, dir.b, dir.intensity);
        } else {
            putFloat4(baseAddr + OFFSET_DIR_LIGHT_COLOR, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        MemoryUtil.memPutLong(baseAddr + OFFSET_LOCAL_LIGHTS, streamingUniformAddress + getCurrentSlotOffset() + lightBlockFrameOffset);
    }

    private static void putFloat4(long addr, float x, float y, float z, float w) {
        MemoryUtil.memPutFloat(addr, x);
        MemoryUtil.memPutFloat(addr + 4L, y);
        MemoryUtil.memPutFloat(addr + 8L, z);
        MemoryUtil.memPutFloat(addr + 12L, w);
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
        return frameIndex * frameSlotSize;
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

    /** Camera world position (-R^T * t of the view matrix) for specular and reflection view vectors. */
    public float[] computeCameraPosition(float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        createCameraViewMatrix(camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int i = 0; i < 3; i++) {
            cameraPos[i] = -(matV[i * 4] * matV[12] + matV[i * 4 + 1] * matV[13] + matV[i * 4 + 2] * matV[14]);
        }
        return cameraPos;
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

    private float projFovDeg = 50.0f, projNear = 0.1f, projFar = 500.0f;

    public void setProjection(float fovDeg, float near, float far) {
        projFovDeg = fovDeg; projNear = near; projFar = far;
    }

    public float[] createPerspectiveMatrix(float aspect) {
        float fov = (float) Math.toRadians(projFovDeg);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float zNear = projNear, zFar = projFar;

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

    /** Projection * explicit view (column-major) for the current projection parameters. */
    public float[] createViewProjMatrix(float aspect, float[] view) {
        createPerspectiveMatrix(aspect);
        multiplyMat4(matP, view, matMVP);
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

    /** viewProj * model for a precomputed view-projection; also leaves the model matrix in getModelMatrix(). */
    public float[] createMVPFromViewProj(float[] viewProj, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        createModelMatrix(px, py, pz, rx, ry, rz, sx, sy, sz);
        multiplyMat4(viewProj, matM, matMVP);
        return matMVP;
    }

    public float[] getModelMatrix() { return matM; }
}
