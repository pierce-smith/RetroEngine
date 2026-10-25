package com.retro.engine.animation.state;

import com.retro.engine.animation.clips.AnimationClip;

import java.util.ArrayList;
import java.util.List;

/**
 * Parametric pose blend container owned by an AnimatorState.
 * SIMPLE_1D blends the two motions bracketing a float parameter by threshold;
 * FREEFORM_2D blends all motions by inverse-distance weighting over two float parameters.
 * Weights are written into a pre-allocated array so per-frame evaluation does not allocate.
 */
public class BlendTree {

    public enum BlendType {
        SIMPLE_1D,
        FREEFORM_2D
    }

    public static class Motion {
        public AnimationClip clip;
        public float threshold;   // SIMPLE_1D position along parameterX
        public float posX, posY;  // FREEFORM_2D position in (parameterX, parameterY) space

        public Motion(AnimationClip clip, float threshold) {
            this.clip = clip;
            this.threshold = threshold;
            this.posX = threshold;
        }

        public Motion(AnimationClip clip, float posX, float posY) {
            this.clip = clip;
            this.posX = posX;
            this.posY = posY;
            this.threshold = posX;
        }
    }

    public BlendType type = BlendType.SIMPLE_1D;
    public String parameterX = "Blend";
    public String parameterY = "BlendY";

    private final List<Motion> motions = new ArrayList<>();
    private float[] weights = new float[0];

    public BlendTree(BlendType type, String parameterX) {
        this.type = type;
        this.parameterX = parameterX;
    }

    public BlendTree(BlendType type, String parameterX, String parameterY) {
        this.type = type;
        this.parameterX = parameterX;
        this.parameterY = parameterY;
    }

    public List<Motion> getMotions() {
        return motions;
    }

    /** Adds a 1D motion, keeping motions sorted by ascending threshold. */
    public BlendTree addMotion(AnimationClip clip, float threshold) {
        Motion motion = new Motion(clip, threshold);
        int insertAt = 0;
        while (insertAt < motions.size() && motions.get(insertAt).threshold <= threshold) insertAt++;
        motions.add(insertAt, motion);
        weights = new float[motions.size()];
        return this;
    }

    /** Adds a 2D motion at the given position in parameter space. */
    public BlendTree addMotion(AnimationClip clip, float posX, float posY) {
        motions.add(new Motion(clip, posX, posY));
        weights = new float[motions.size()];
        return this;
    }

    public void removeMotion(int index) {
        if (index < 0 || index >= motions.size()) return;
        motions.remove(index);
        weights = new float[motions.size()];
    }

    /**
     * Recomputes motion weights from the controller's live parameters.
     * Returned array is owned by this tree; weights sum to 1 when any motion exists.
     */
    public float[] evaluateWeights(AnimatorController controller) {
        int count = motions.size();
        if (count == 0) return weights;
        for (int i = 0; i < count; i++) weights[i] = 0.0f;

        float px = controller != null ? controller.getFloat(parameterX) : 0.0f;

        if (type == BlendType.SIMPLE_1D) {
            if (px <= motions.get(0).threshold) {
                weights[0] = 1.0f;
            } else if (px >= motions.get(count - 1).threshold) {
                weights[count - 1] = 1.0f;
            } else {
                for (int i = 0; i < count - 1; i++) {
                    float t0 = motions.get(i).threshold;
                    float t1 = motions.get(i + 1).threshold;
                    if (px >= t0 && px <= t1) {
                        float span = t1 - t0;
                        float t = span > 0.0001f ? (px - t0) / span : 0.0f;
                        weights[i] = 1.0f - t;
                        weights[i + 1] = t;
                        break;
                    }
                }
            }
        } else {
            float py = controller != null ? controller.getFloat(parameterY) : 0.0f;
            float total = 0.0f;
            for (int i = 0; i < count; i++) {
                Motion m = motions.get(i);
                float dx = px - m.posX;
                float dy = py - m.posY;
                float distSq = dx * dx + dy * dy;
                if (distSq < 0.000001f) {
                    // Parameter sits exactly on a motion: that motion owns the full pose
                    for (int j = 0; j < count; j++) weights[j] = 0.0f;
                    weights[i] = 1.0f;
                    return weights;
                }
                weights[i] = 1.0f / distSq;
                total += weights[i];
            }
            for (int i = 0; i < count; i++) weights[i] /= total;
        }
        return weights;
    }

    /** Weighted clip duration, used to phase-synchronise motions of different lengths. */
    public float getBlendedDuration(float[] evaluatedWeights) {
        float duration = 0.0f;
        for (int i = 0; i < motions.size(); i++) {
            AnimationClip clip = motions.get(i).clip;
            if (clip != null) duration += evaluatedWeights[i] * clip.durationSeconds;
        }
        return duration;
    }
}
