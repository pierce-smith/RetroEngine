package com.retro.engine.animation;

import com.retro.engine.assets.MeshImporter;
import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.ecs.components.AnimatorComponent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * System executing per-frame skeletal animation updates, keyframe sampling,
 * AnimatorController state machine transitions, crossfade clip blending,
 * hierarchy pose evaluation, and matrix palette generation for GPU skinning.
 *
 * Skeletons and imported clips are shared, read-only assets, and controllers are only read here. Every per-entity value the system advances (state
 * machine position, state times, crossfade, parameter values) and writes (the bone palette) lives on the entity's
 * AnimatorComponent, so duplicated models sharing one rig animate independently.
 */
public class AnimationSystem {

    // Pre-allocated scratchpad matrices and vectors for zero per-frame JVM allocations
    // Sized to the whole skeleton (grown on demand): bones past the 64-slot GPU palette still carry their children
    private float[][] worldBoneTransforms = new float[AnimatorComponent.MAX_BONES][16];
    private float[][] localBoneTransforms = new float[AnimatorComponent.MAX_BONES][16];
    private final float[] preLocalScratch = new float[16];

    private final float[] posScratchA = new float[3];
    private final float[] quatScratchA = new float[4];
    private final float[] scaleScratchA = new float[3];

    private final float[] posScratchB = new float[3];
    private final float[] quatScratchB = new float[4];
    private final float[] scaleScratchB = new float[3];

    private final float[] posBlended = new float[3];
    private final float[] quatBlended = new float[4];
    private final float[] scaleBlended = new float[3];

    private final float[] posMotion = new float[3];
    private final float[] quatMotion = new float[4];
    private final float[] scaleMotion = new float[3];

    // Mirrors the simulation play state. Only SIMULATING advances animation time: EDITING holds the
    // static bind pose at frame 0, PAUSED holds the last evaluated pose.
    private PlayState playState = PlayState.EDITING;

    public AnimationSystem() {
        for (int i = 0; i < AnimatorComponent.MAX_BONES; i++) {
            for (int j = 0; j < 16; j++) {
                worldBoneTransforms[i][j] = (j % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    /**
     * Snaps the animator's controller to its Entry State, mirrors the entry clip onto the
     * component, starts playback, and evaluates the first pose immediately so the bone palette
     * is valid on the very first rendered frame of a Play session.
     */
    public void enterEntryState(AnimatorComponent animator) {
        if (animator == null) return;
        animator.playbackTime = 0.0f;
        animator.isPlaying = true;
        if (animator.controller != null) {
            animator.resetToEntryState();
            syncActiveClip(animator, animator.getCurrentState());
        }
        update(animator, 0.0f);
    }

    public void setPlayState(PlayState playState) {
        if (playState != null) this.playState = playState;
    }

    public PlayState getPlayState() {
        return playState;
    }

    /**
     * Prepares an animator for editing: controller rewound to its Entry State at frame 0 and the mesh
     * shown in its bind pose. Playback starts only when Play calls enterEntryState().
     */
    public void armEntryState(AnimatorComponent animator) {
        if (animator == null) return;
        animator.playbackTime = 0.0f;
        animator.isPlaying = true;
        if (animator.controller != null) {
            animator.resetToEntryState();
            syncActiveClip(animator, animator.getCurrentState());
        }
        resetToBindPose(animator);
    }

    /**
     * Stops playback: rewinds the controller to its Entry State (ready for the next Play) and
     * drops the mesh back into its rest pose instead of freezing on the last sampled frame.
     */
    public void stop(AnimatorComponent animator) {
        if (animator == null) return;
        animator.isPlaying = false;
        animator.playbackTime = 0.0f;
        if (animator.controller != null) {
            animator.resetToEntryState();
        }
        resetToBindPose(animator);
    }

    /**
     * Overwrites the full 64-matrix palette with the skeleton's bind pose (world bind * inverse bind
     * offset, which is identity for a consistently rigged mesh), with any editor pose edits
     * ({@link AnimatorComponent#editPoseLocal}) replacing their bones' bind transforms. Slots beyond the skeleton's bone count
     * are set to identity so no stale keyframe matrices survive. The palette is re-streamed to the GPU
     * bone uniforms every frame, so the next frame renders the rest pose.
     */
    public void resetToBindPose(AnimatorComponent animator) {
        if (animator == null) return;
        Skeleton skeleton = animator.skeleton;
        int jointCount = (skeleton != null) ? skeleton.getBoneCount() : 0;
        ensureCapacity(jointCount);

        for (int i = 0; i < jointCount; i++) {
            Skeleton.BoneNode bone = skeleton.getBone(i);
            if (bone != null) {
                System.arraycopy(restPose(animator, bone, i), 0, localBoneTransforms[i], 0, 16);
            } else {
                setIdentity(localBoneTransforms[i]);
            }
        }
        if (jointCount > 0) {
            evaluateHierarchyAndPalette(animator, skeleton, jointCount);
        }
        for (int i = 0; i < AnimatorComponent.MAX_BONES; i++) {
            if (i >= jointCount || skeleton.getBone(i) == null) setIdentity(animator.bonePaletteMatrices[i]);
        }

        animator.bindPoseApplied = true;
        animator.setDirty(true);
    }

    /** A bone's rest local pose: the animator's editor pose edit if any (getLocalPose), else the bind pose. */
    private static float[] restPose(AnimatorComponent animator, Skeleton.BoneNode bone, int index) {
        float[] local = (index < AnimatorComponent.MAX_BONES) ? animator.getLocalPose(index) : null;
        return (local != null) ? local : bone.localBindTransform;
    }

    private void ensureCapacity(int jointCount) {
        if (jointCount <= localBoneTransforms.length) return;
        localBoneTransforms = new float[jointCount][16];
        worldBoneTransforms = new float[jointCount][16];
    }

    private void applyBindPoseOnce(AnimatorComponent animator) {
        if (!animator.bindPoseApplied) resetToBindPose(animator);
    }

    private static void setIdentity(float[] m) {
        for (int j = 0; j < 16; j++) m[j] = (j % 5 == 0) ? 1.0f : 0.0f;
    }

    /**
     * Updates and evaluates the skeleton animation pose for a target AnimatorComponent.
     */
    public void update(AnimatorComponent animator, float deltaTime) {
        // Play-state gate: no animation time accumulates outside an active Play session
        if (playState != PlayState.SIMULATING) {
            if (animator == null) return;
            if (playState == PlayState.EDITING) {
                // Editor Mode: hold frame 0 so the scene stays static, and show the bind pose
                animator.playbackTime = 0.0f;
                applyBindPoseOnce(animator);
            }
            // PAUSED: freeze timers and leave the last evaluated palette untouched, so characters
            // hold their exact mid-motion pose (no bind-pose reset, no phase drift)
            return;
        }
        if (animator == null) return;
        if (!animator.isPlaying) {
            // A stopped animator must not keep presenting its last sampled (often mid-motion) pose
            applyBindPoseOnce(animator);
            return;
        }

        Skeleton skeleton = animator.skeleton;
        if (skeleton == null) return;

        int jointCount = skeleton.getBoneCount();
        ensureCapacity(jointCount);

        // 1. Controller State Machine Execution (on this animator's instance state; the controller is only read)
        if (animator.controller != null) {
            stepStateMachine(animator, deltaTime);
            Map<String, AnimatorParameter> parameters = animator.getParameters();

            AnimatorState currentState = animator.getCurrentState();
            AnimatorState targetState = animator.getTargetState();
            if (currentState == null || !currentState.hasMotion() || animator.isCurrentStateFinished()) {
                // Empty state, or a single-shot state that ran out with no transition leaving it
                applyBindPoseOnce(animator);
                return;
            }
            syncActiveClip(animator, currentState);

            boolean blendTarget = targetState != null && targetState.hasMotion();
            float weight = animator.getCrossfadeWeight();
            float stateTimeA = animator.currentTime;
            float stateTimeB = animator.targetTime;

            // Blend weights depend only on parameters, so evaluate them once per frame rather than per bone
            float[] weightsA = currentState.blendTree != null ? currentState.blendTree.evaluateWeights(parameters) : null;
            float[] weightsB = (blendTarget && targetState.blendTree != null) ? targetState.blendTree.evaluateWeights(parameters) : null;

            for (int i = 0; i < jointCount; i++) {
                Skeleton.BoneNode bone = skeleton.getBone(i);
                if (bone == null) continue;
                float[] localScratch = localBoneTransforms[i];

                // Bones a motion has no track for hold the rest pose: the editor pose edit if any, else the bind pose
                float[] rest = restPose(animator, bone, i);
                boolean sampledA = sampleStatePose(currentState, weightsA, stateTimeA, bone, rest, posScratchA, quatScratchA, scaleScratchA);

                if (!blendTarget) {
                    if (sampledA) {
                        composeTRS(posScratchA, quatScratchA, scaleScratchA, localScratch);
                    } else {
                        System.arraycopy(rest, 0, localScratch, 0, 16);
                    }
                    continue;
                }

                // Dual-state crossfade blending
                boolean sampledB = sampleStatePose(targetState, weightsB, stateTimeB, bone, rest, posScratchB, quatScratchB, scaleScratchB);
                if (!sampledA && !sampledB) {
                    System.arraycopy(rest, 0, localScratch, 0, 16);
                    continue;
                }

                lerpVector3(posScratchA, posScratchB, weight, posBlended);
                slerpQuat(quatScratchA, quatScratchB, weight, quatBlended);
                lerpVector3(scaleScratchA, scaleScratchB, weight, scaleBlended);

                composeTRS(posBlended, quatBlended, scaleBlended, localScratch);
            }
        } else {
            // 2. Direct Clip Playback Fallback
            AnimationClip clip = animator.currentClip;
            if (clip == null) {
                applyBindPoseOnce(animator);
                return;
            }

            float duration = clip.durationSeconds;
            animator.playbackTime += deltaTime * animator.playbackSpeed;
            if (clip.loopMode == AnimationClip.LoopMode.ONCE) {
                if (animator.playbackTime >= duration) {
                    // Single-shot finished: freeze the timeline at its end and drop to the rest pose
                    stop(animator);
                    animator.playbackTime = duration;
                    return;
                }
            } else {
                float period = (clip.loopMode == AnimationClip.LoopMode.PING_PONG) ? duration * 2.0f : duration;
                animator.playbackTime %= period;
                if (animator.playbackTime < 0.0f) animator.playbackTime += period;
            }
            float sampleTime = clip.normalizeTime(animator.playbackTime);

            for (int i = 0; i < jointCount; i++) {
                Skeleton.BoneNode bone = skeleton.getBone(i);
                if (bone == null) continue;
                float[] localScratch = localBoneTransforms[i];

                KeyframeTrack track = clip.getTrack(bone.name);
                if (track != null) {
                    track.samplePosition(sampleTime, posBlended);
                    track.sampleRotation(sampleTime, quatBlended);
                    track.sampleScale(sampleTime, scaleBlended);
                    composeTRS(posBlended, quatBlended, scaleBlended, localScratch);
                } else {
                    System.arraycopy(restPose(animator, bone, i), 0, localScratch, 0, 16);
                }
            }
        }

        evaluateHierarchyAndPalette(animator, skeleton, jointCount);
        animator.bindPoseApplied = false;
        animator.setDirty(true);
    }

    /**
     * Advances {@code animator}'s state machine instance by {@code deltaTime}: state times, crossfade completion, and
     * outgoing transitions evaluated against the animator's own parameter values. The controller only supplies
     * the graph. An animator whose state is unset or was removed from the controller restarts at the entry state.
     */
    private static void stepStateMachine(AnimatorComponent animator, float deltaTime) {
        AnimatorController controller = animator.controller;
        Map<String, AnimatorParameter> parameters = animator.getParameters();
        AnimatorState current = animator.getCurrentState();
        if (current == null) {
            animator.resetToEntryState();
            current = animator.getCurrentState();
            if (current == null) return;
        }

        // Looping states wrap, single-shot states freeze at their terminal frame
        animator.currentTime = current.boundStateTime(animator.currentTime + deltaTime * current.speed, parameters);

        if (animator.transitioning) {
            AnimatorState target = animator.getTargetState();
            if (target == null) {
                animator.cancelTransition(); // target state removed mid-crossfade
            } else {
                animator.targetTime = target.boundStateTime(animator.targetTime + deltaTime * target.speed, parameters);
                animator.crossfadeTimer += deltaTime;
                if (animator.crossfadeTimer >= animator.crossfadeDuration) {
                    // Complete crossfade transition
                    animator.currentStateId = animator.targetStateId;
                    animator.currentTime = animator.targetTime;
                    animator.cancelTransition();
                    current = target;
                }
            }
        }

        // Evaluate outgoing transitions if not already crossfading
        if (!animator.transitioning) {
            float normalizedTime = current.getNormalizedTime(animator.currentTime, parameters);
            for (int t = 0, n = current.transitions.size(); t < n; t++) {
                AnimatorTransition transition = current.transitions.get(t);
                if (!transition.canTransition(normalizedTime, parameters)) continue;
                AnimatorState next = controller.getState(transition.destinationStateName);
                if (next != null && next != current) {
                    animator.targetStateId = next.name;
                    animator.targetTime = 0.0f;
                    animator.crossfadeTimer = 0.0f;
                    animator.crossfadeDuration = Math.max(0.01f, transition.duration);
                    animator.transitioning = true;
                    resetTriggersUsedIn(transition, parameters);
                    break;
                }
            }
        }
    }

    private static void resetTriggersUsedIn(AnimatorTransition transition, Map<String, AnimatorParameter> parameters) {
        for (int c = 0, n = transition.conditions.size(); c < n; c++) {
            AnimatorCondition cond = transition.conditions.get(c);
            AnimatorParameter param = parameters.get(cond.parameterName);
            if (param != null && param.type == AnimatorParameter.ParameterType.TRIGGER) param.resetTrigger();
        }
    }

    /**
     * Makes {@code clip} playable from {@code controller}: returns the state already playing it, else re-points the
     * single-clip state of the same name at it (a re-exported clip replaces its old version), else adds a new state
     * below the existing graph. Sampling is by bone name, so recorded and .ranim clips play like imported ones.
     */
    public static AnimatorState assignClipToState(AnimatorController controller, AnimationClip clip) {
        if (controller == null || clip == null || clip.name == null) return null;
        for (AnimatorState state : controller.getStates().values()) {
            if (state.clip == clip) return state;
        }
        AnimatorState state = controller.getState(clip.name);
        if (state != null && state.blendTree == null) {
            state.clip = clip;
            return state;
        }

        float x = -0.20f, y = 0.65f;
        boolean any = false;
        for (AnimatorState existing : controller.getStates().values()) {
            if (!any || existing.graphY - 0.25f < y) {
                y = existing.graphY - 0.25f;
                x = existing.graphX;
            }
            any = true;
        }
        state = new AnimatorState(controller.uniqueStateName(clip.name), clip).setGraphPos(x, y);
        controller.addState(state);
        return state;
    }

    /**
     * Restores an animator's authored clips onto its (freshly rebuilt) controller: the recorded pose, every assigned
     * .ranim clip, and the entry state saved with the scene.
     */
    public static void installAuthoredClips(AnimatorComponent animator) {
        if (animator == null || animator.controller == null) return;
        AnimatorController controller = animator.controller;
        if (animator.recordedPose != null && !animator.recordedPose.getTracks().isEmpty()) {
            assignClipToState(controller, animator.recordedPose);
        }
        for (String path : animator.clipAssetPaths) {
            AnimationClip clip = loadClipAsset(path);
            if (clip != null) assignClipToState(controller, clip);
        }
        String entry = animator.takeSavedEntryState();
        if (entry != null && controller.setEntryState(entry)) animator.resetToEntryState();
    }

    /** Loads a .ranim clip, resolving project-relative or moved paths like mesh assets; null (logged) on failure. */
    public static AnimationClip loadClipAsset(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        if (!file.isFile()) file = MeshImporter.resolveModelFile(path);
        if (file == null || !file.isFile()) {
            System.err.println("[AnimationSystem] Animation clip not found: " + path);
            return null;
        }
        try {
            return AnimationClipIO.load(file);
        } catch (IOException e) {
            System.err.println("[AnimationSystem] Failed to load animation clip " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static void syncActiveClip(AnimatorComponent animator, AnimatorState state) {
        AnimationClip clip = (state != null) ? state.clip : null;
        if (clip != null && animator.currentClip != clip) {
            animator.currentClip = clip;
            animator.activeClipName = clip.name;
        }
    }

    /**
     * Samples one bone's local TRS for a state (single clip or parametric blend tree).
     * Blend tree motions are phase-synchronised and accumulated with incremental normalised
     * LERP/SLERP: each motion i is folded in with factor w_i / (w_0 + ... + w_i).
     *
     * @param rest the bone's rest local transform, used for motions without a track for it
     * @return false if no motion had a track for this bone (outputs then hold the decomposed rest pose)
     */
    private boolean sampleStatePose(AnimatorState state, float[] blendWeights, float stateTime, Skeleton.BoneNode bone, float[] rest,
                                    float[] outPos, float[] outQuat, float[] outScale) {
        BlendTree tree = state.blendTree;
        if (tree == null) {
            KeyframeTrack track = state.clip.getTrack(bone.name);
            if (track == null) {
                decomposeTRS(rest, outPos, outQuat, outScale);
                return false;
            }
            float sampleTime = state.getClipSampleTime(stateTime);
            track.samplePosition(sampleTime, outPos);
            track.sampleRotation(sampleTime, outQuat);
            track.sampleScale(sampleTime, outScale);
            return true;
        }

        List<BlendTree.Motion> motions = tree.getMotions();
        // blendWeights is the tree's own array, so phase is derived here rather than via
        // getNormalizedTime(), which would re-evaluate (and overwrite) the weights
        float phase = state.phaseFor(stateTime, tree.getBlendedDuration(blendWeights));

        float accumulated = 0.0f;
        boolean anyTrack = false;
        for (int m = 0; m < motions.size(); m++) {
            float w = blendWeights[m];
            AnimationClip clip = motions.get(m).clip;
            if (w <= 0.0f || clip == null) continue;

            KeyframeTrack track = clip.getTrack(bone.name);
            if (track != null) {
                float sampleTime = phase * clip.durationSeconds;
                track.samplePosition(sampleTime, posMotion);
                track.sampleRotation(sampleTime, quatMotion);
                track.sampleScale(sampleTime, scaleMotion);
                anyTrack = true;
            } else {
                decomposeTRS(rest, posMotion, quatMotion, scaleMotion);
            }

            accumulated += w;
            if (accumulated == w) {
                copy3(posMotion, outPos);
                copy4(quatMotion, outQuat);
                copy3(scaleMotion, outScale);
            } else {
                float t = w / accumulated;
                lerpVector3(outPos, posMotion, t, outPos);
                slerpQuat(outQuat, quatMotion, t, outQuat);
                lerpVector3(outScale, scaleMotion, t, outScale);
            }
        }

        if (accumulated <= 0.0f) {
            decomposeTRS(rest, outPos, outQuat, outScale);
            return false;
        }
        return anyTrack;
    }

    /** Decomposes a column-major TRS matrix into translation, rotation quaternion (x, y, z, w), and axis scale. */
    public static void decomposeTRS(float[] m, float[] pos, float[] quat, float[] scale) {
        pos[0] = m[12]; pos[1] = m[13]; pos[2] = m[14];

        float sx = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        float sy = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        float sz = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        scale[0] = sx; scale[1] = sy; scale[2] = sz;

        float isx = sx > 0.000001f ? 1.0f / sx : 0.0f;
        float isy = sy > 0.000001f ? 1.0f / sy : 0.0f;
        float isz = sz > 0.000001f ? 1.0f / sz : 0.0f;
        // Column-major rotation r(row, col) = m[col * 4 + row] / scale[col]
        float r00 = m[0] * isx, r10 = m[1] * isx, r20 = m[2] * isx;
        float r01 = m[4] * isy, r11 = m[5] * isy, r21 = m[6] * isy;
        float r02 = m[8] * isz, r12 = m[9] * isz, r22 = m[10] * isz;

        float trace = r00 + r11 + r22;
        if (trace > 0.0f) {
            float s = (float) Math.sqrt(trace + 1.0f) * 2.0f;
            quat[3] = 0.25f * s;
            quat[0] = (r21 - r12) / s;
            quat[1] = (r02 - r20) / s;
            quat[2] = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1.0f + r00 - r11 - r22) * 2.0f;
            quat[3] = (r21 - r12) / s;
            quat[0] = 0.25f * s;
            quat[1] = (r01 + r10) / s;
            quat[2] = (r02 + r20) / s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1.0f + r11 - r00 - r22) * 2.0f;
            quat[3] = (r02 - r20) / s;
            quat[0] = (r01 + r10) / s;
            quat[1] = 0.25f * s;
            quat[2] = (r12 + r21) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + r22 - r00 - r11) * 2.0f;
            quat[3] = (r10 - r01) / s;
            quat[0] = (r02 + r20) / s;
            quat[1] = (r12 + r21) / s;
            quat[2] = 0.25f * s;
        }
    }

    private static void copy3(float[] src, float[] dst) {
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2];
    }

    private static void copy4(float[] src, float[] dst) {
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; dst[3] = src[3];
    }

    /**
     * Forward kinematics over the whole skeleton in parent-first order ({@link Skeleton#getEvaluationOrder()}):
     * world[i] = world[parent] * preTransform * local[i] (identity in place of world[parent] for a root), then
     * palette[i] = world[i] * offset[i] for the bones that fit the GPU palette. Each parent's world is final before
     * any child reads it, so a posed upper arm moves the forearm, hand and fingers in the same pass.
     */
    private void evaluateHierarchyAndPalette(AnimatorComponent animator, Skeleton skeleton, int jointCount) {
        for (int i : skeleton.getEvaluationOrder()) {
            if (i >= jointCount) continue;
            Skeleton.BoneNode bone = skeleton.getBone(i);
            if (bone == null) continue;

            float[] local = localBoneTransforms[i];
            if (bone.hasPreTransform) {
                // Folded non-bone ancestors (FBX pivots, armature nodes) sit between the parent bone and this bone's local
                multiply4x4(bone.preTransform, local, preLocalScratch);
                local = preLocalScratch;
            }
            int parent = skeleton.getEvaluationParent(i);
            if (parent >= 0 && parent < jointCount) {
                multiply4x4(worldBoneTransforms[parent], local, worldBoneTransforms[i]);
            } else {
                System.arraycopy(local, 0, worldBoneTransforms[i], 0, 16);
            }
            if (i < AnimatorComponent.MAX_BONES) {
                multiply4x4(worldBoneTransforms[i], bone.offsetMatrix, animator.bonePaletteMatrices[i]);
            }
        }
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
