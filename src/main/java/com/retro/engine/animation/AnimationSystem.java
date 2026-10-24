package com.retro.engine.animation;

import com.retro.engine.animation.clips.AnimationClip;
import com.retro.engine.animation.clips.KeyframeTrack;
import com.retro.engine.animation.skeleton.Skeleton;
import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.scene.components.media.AnimatorComponent;

/**
 * System executing per-frame skeletal animation updates, keyframe sampling,
 * AnimatorController state machine transitions, crossfade clip blending,
 * hierarchy pose evaluation, and matrix palette generation for GPU skinning.
 */
public class AnimationSystem {

    // Pre-allocated scratchpad matrices and vectors for zero per-frame JVM allocations
    private final float[][] worldBoneTransforms = new float[AnimatorComponent.MAX_BONES][16];
    private final float[] localScratch = new float[16];

    private final float[] posScratchA = new float[3];
    private final float[] quatScratchA = new float[4];
    private final float[] scaleScratchA = new float[3];

    private final float[] posScratchB = new float[3];
    private final float[] quatScratchB = new float[4];
    private final float[] scaleScratchB = new float[3];

    private final float[] posBlended = new float[3];
    private final float[] quatBlended = new float[4];
    private final float[] scaleBlended = new float[3];

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
        if (skeleton == null) return;

        int boneCount = Math.min(skeleton.getBoneCount(), AnimatorComponent.MAX_BONES);

        // 1. Controller State Machine Execution
        if (animator.controller != null) {
            AnimatorController controller = animator.controller;
            controller.update(deltaTime);

            AnimatorState currentState = controller.getCurrentState();
            AnimatorState targetState = controller.getTargetState();
            boolean transitioning = controller.isTransitioning();
            float weight = controller.getCrossfadeWeight();

            if (currentState == null || currentState.clip == null) return;

            float sampleTimeA = currentState.clip.normalizeTime(controller.getCurrentStateTime());
            float sampleTimeB = (targetState != null && targetState.clip != null) ?
                    targetState.clip.normalizeTime(controller.getTargetStateTime()) : 0.0f;

            for (int i = 0; i < boneCount; i++) {
                Skeleton.BoneNode bone = skeleton.getBone(i);
                if (bone == null) continue;

                KeyframeTrack trackA = currentState.clip.getTrack(bone.name);

                if (!transitioning || targetState == null || targetState.clip == null) {
                    // Single state evaluation
                    if (trackA != null) {
                        trackA.samplePosition(sampleTimeA, posBlended);
                        trackA.sampleRotation(sampleTimeA, quatBlended);
                        trackA.sampleScale(sampleTimeA, scaleBlended);
                        composeTRS(posBlended, quatBlended, scaleBlended, localScratch);
                    } else {
                        System.arraycopy(bone.localBindTransform, 0, localScratch, 0, 16);
                    }
                } else {
                    // Dual-clip crossfade blending
                    KeyframeTrack trackB = targetState.clip.getTrack(bone.name);

                    if (trackA != null) {
                        trackA.samplePosition(sampleTimeA, posScratchA);
                        trackA.sampleRotation(sampleTimeA, quatScratchA);
                        trackA.sampleScale(sampleTimeA, scaleScratchA);
                    } else {
                        posScratchA[0] = bone.localBindTransform[12];
                        posScratchA[1] = bone.localBindTransform[13];
                        posScratchA[2] = bone.localBindTransform[14];
                        quatScratchA[0] = 0.0f; quatScratchA[1] = 0.0f; quatScratchA[2] = 0.0f; quatScratchA[3] = 1.0f;
                        scaleScratchA[0] = 1.0f; scaleScratchA[1] = 1.0f; scaleScratchA[2] = 1.0f;
                    }

                    if (trackB != null) {
                        trackB.samplePosition(sampleTimeB, posScratchB);
                        trackB.sampleRotation(sampleTimeB, quatScratchB);
                        trackB.sampleScale(sampleTimeB, scaleScratchB);
                    } else {
                        posScratchB[0] = posScratchA[0]; posScratchB[1] = posScratchA[1]; posScratchB[2] = posScratchA[2];
                        quatScratchB[0] = quatScratchA[0]; quatScratchB[1] = quatScratchA[1]; quatScratchB[2] = quatScratchA[2]; quatScratchB[3] = quatScratchA[3];
                        scaleScratchB[0] = scaleScratchA[0]; scaleScratchB[1] = scaleScratchA[1]; scaleScratchB[2] = scaleScratchA[2];
                    }

                    lerpVector3(posScratchA, posScratchB, weight, posBlended);
                    slerpQuat(quatScratchA, quatScratchB, weight, quatBlended);
                    lerpVector3(scaleScratchA, scaleScratchB, weight, scaleBlended);

                    composeTRS(posBlended, quatBlended, scaleBlended, localScratch);
                }

                evaluateHierarchyAndPalette(animator, skeleton, bone, i, boneCount);
            }
        } else {
            // 2. Direct Clip Playback Fallback
            AnimationClip clip = animator.currentClip;
            if (clip == null) return;

            animator.playbackTime += deltaTime * animator.playbackSpeed;
            float sampleTime = clip.normalizeTime(animator.playbackTime);

            for (int i = 0; i < boneCount; i++) {
                Skeleton.BoneNode bone = skeleton.getBone(i);
                if (bone == null) continue;

                KeyframeTrack track = clip.getTrack(bone.name);
                if (track != null) {
                    track.samplePosition(sampleTime, posBlended);
                    track.sampleRotation(sampleTime, quatBlended);
                    track.sampleScale(sampleTime, scaleBlended);
                    composeTRS(posBlended, quatBlended, scaleBlended, localScratch);
                } else {
                    System.arraycopy(bone.localBindTransform, 0, localScratch, 0, 16);
                }

                evaluateHierarchyAndPalette(animator, skeleton, bone, i, boneCount);
            }
        }

        animator.setDirty(true);
    }

    private void evaluateHierarchyAndPalette(AnimatorComponent animator, Skeleton skeleton, Skeleton.BoneNode bone, int boneIndex, int boneCount) {
        float[] worldMat = worldBoneTransforms[boneIndex];
        if (bone.parentId >= 0 && bone.parentId < boneCount) {
            float[] parentWorldMat = worldBoneTransforms[bone.parentId];
            multiply4x4(parentWorldMat, localScratch, worldMat);
        } else {
            System.arraycopy(localScratch, 0, worldMat, 0, 16);
        }

        multiply4x4(worldMat, bone.offsetMatrix, animator.bonePaletteMatrices[boneIndex]);
    }

    public static void lerpVector3(float[] a, float[] b, float t, float[] out) {
        out[0] = a[0] + t * (b[0] - a[0]);
        out[1] = a[1] + t * (b[1] - a[1]);
        out[2] = a[2] + t * (b[2] - a[2]);
    }

    public static void slerpQuat(float[] q1, float[] q2, float t, float[] out) {
        float x1 = q1[0], y1 = q1[1], z1 = q1[2], w1 = q1[3];
        float x2 = q2[0], y2 = q2[1], z2 = q2[2], w2 = q2[3];

        float dot = x1 * x2 + y1 * y2 + z1 * z2 + w1 * w2;

        if (dot < 0.0f) {
            dot = -dot;
            x2 = -x2; y2 = -y2; z2 = -z2; w2 = -w2;
        }

        if (dot > 0.9995f) {
            out[0] = x1 + t * (x2 - x1);
            out[1] = y1 + t * (y2 - y1);
            out[2] = z1 + t * (z2 - z1);
            out[3] = w1 + t * (w2 - w1);
            float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
            if (len > 0.0001f) {
                out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len;
            }
            return;
        }

        float theta0 = (float) Math.acos(dot);
        float theta = theta0 * t;
        float sinTheta = (float) Math.sin(theta);
        float sinTheta0 = (float) Math.sin(theta0);

        float s0 = (float) Math.cos(theta) - dot * sinTheta / sinTheta0;
        float s1 = sinTheta / sinTheta0;

        out[0] = s0 * x1 + s1 * x2;
        out[1] = s0 * y1 + s1 * y2;
        out[2] = s0 * z1 + s1 * z2;
        out[3] = s0 * w1 + s1 * w2;
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
