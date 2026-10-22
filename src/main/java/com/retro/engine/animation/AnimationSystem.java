package com.retro.engine.animation;

import com.retro.engine.scene.components.AnimatorComponent;

/**
 * System executing per-frame skeletal animation updates, keyframe sampling,
 * hierarchy pose evaluation, and matrix palette generation for GPU skinning.
 */
public class AnimationSystem {

    // Pre-allocated scratchpad matrices for zero per-frame JVM allocations
    private final float[][] worldBoneTransforms = new float[AnimatorComponent.MAX_BONES][16];
    private final float[] localScratch = new float[16];
    private final float[] posScratch = new float[3];
    private final float[] quatScratch = new float[4];
    private final float[] scaleScratch = new float[3];

    public AnimationSystem() {
        for (int i = 0; i < AnimatorComponent.MAX_BONES; i++) {
            for (int j = 0; j < 16; j++) {
                worldBoneTransforms[i][j] = (j % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    /**
     * Updates and evaluates the skeleton animation pose for a target AnimatorComponent.
     */
    public void update(AnimatorComponent animator, float deltaTime) {
        if (animator == null || !animator.isPlaying) return;

        Skeleton skeleton = animator.skeleton;
        AnimationClip clip = animator.currentClip;
        if (skeleton == null || clip == null) return;

        animator.playbackTime += deltaTime * animator.playbackSpeed;
        float sampleTime = clip.normalizeTime(animator.playbackTime);

        int boneCount = Math.min(skeleton.getBoneCount(), AnimatorComponent.MAX_BONES);

        for (int i = 0; i < boneCount; i++) {
            Skeleton.BoneNode bone = skeleton.getBone(i);
            if (bone == null) continue;

            KeyframeTrack track = clip.getTrack(bone.name);
            if (track != null) {
                track.samplePosition(sampleTime, posScratch);
                track.sampleRotation(sampleTime, quatScratch);
                track.sampleScale(sampleTime, scaleScratch);
                composeTRS(posScratch, quatScratch, scaleScratch, localScratch);
            } else {
                System.arraycopy(bone.localBindTransform, 0, localScratch, 0, 16);
            }

            float[] worldMat = worldBoneTransforms[i];
            if (bone.parentId >= 0 && bone.parentId < boneCount) {
                float[] parentWorldMat = worldBoneTransforms[bone.parentId];
                multiply4x4(parentWorldMat, localScratch, worldMat);
            } else {
                System.arraycopy(localScratch, 0, worldMat, 0, 16);
            }

            multiply4x4(worldMat, bone.offsetMatrix, animator.bonePaletteMatrices[i]);
        }

        animator.setDirty(true);
    }

    public static void composeTRS(float[] pos, float[] q, float[] scale, float[] out) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        float sx = scale[0], sy = scale[1], sz = scale[2];

        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;

        out[0] = (1.0f - 2.0f * (yy + zz)) * sx;
        out[1] = (2.0f * (xy + wz)) * sx;
        out[2] = (2.0f * (xz - wy)) * sx;
        out[3] = 0.0f;

        out[4] = (2.0f * (xy - wz)) * sy;
        out[5] = (1.0f - 2.0f * (xx + zz)) * sy;
        out[6] = (2.0f * (yz + wx)) * sy;
        out[7] = 0.0f;

        out[8] = (2.0f * (xz + wy)) * sz;
        out[9] = (2.0f * (yz - wx)) * sz;
        out[10] = (1.0f - 2.0f * (xx + yy)) * sz;
        out[11] = 0.0f;

        out[12] = pos[0];
        out[13] = pos[1];
        out[14] = pos[2];
        out[15] = 1.0f;
    }

    public static void multiply4x4(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] =
                        a[0 * 4 + row] * b[col * 4 + 0] +
                        a[1 * 4 + row] * b[col * 4 + 1] +
                        a[2 * 4 + row] * b[col * 4 + 2] +
                        a[3 * 4 + row] * b[col * 4 + 3];
            }
        }
    }
}
