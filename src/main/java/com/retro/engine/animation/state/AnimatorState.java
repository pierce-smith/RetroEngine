package com.retro.engine.animation.state;

import com.retro.engine.animation.clips.AnimationClip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * State in an AnimatorController representing an animation clip, playback parameters,
 * 2D graph layout coordinates, and outgoing transition rules.
 */
public class AnimatorState {

    public String name;
    public AnimationClip clip;
    public float speed = 1.0f;
    public boolean isLooping = true;

    // Optional parametric blend container; when set it takes precedence over clip
    public BlendTree blendTree = null;

    // 2D Canvas Layout Coordinates
    public float graphX = 0.0f;
    public float graphY = 0.0f;
    public float graphW = 0.22f;
    public float graphH = 0.12f;

    public final List<AnimatorTransition> transitions = new ArrayList<>();

    public AnimatorState(String name, AnimationClip clip) {
        this.name = name;
        this.clip = clip;
    }

    public AnimatorState(String name, AnimationClip clip, float speed, boolean isLooping) {
        this.name = name;
        this.clip = clip;
        this.speed = speed;
        this.isLooping = isLooping;
    }

    public AnimatorState(String name, AnimationClip clip, float x, float y) {
        this.name = name;
        this.clip = clip;
        this.graphX = x;
        this.graphY = y;
    }

    /** Independent copy (transitions, blend tree, layout); clips are passed through {@code clipMap}. */
    public AnimatorState copy(UnaryOperator<AnimationClip> clipMap) {
        AnimatorState c = new AnimatorState(name, clipMap.apply(clip), speed, isLooping);
        c.blendTree = (blendTree != null) ? blendTree.copy(clipMap) : null;
        c.graphX = graphX;
        c.graphY = graphY;
        c.graphW = graphW;
        c.graphH = graphH;
        for (AnimatorTransition t : transitions) c.transitions.add(t.copy());
        return c;
    }

    public AnimatorState setGraphPos(float x, float y) {
        this.graphX = x;
        this.graphY = y;
        return this;
    }

    public boolean isLooping() {
        return isLooping;
    }

    public boolean isBlendTree() {
        return blendTree != null;
    }

    public boolean hasMotion() {
        return clip != null || blendTree != null;
    }

    /**
     * Length of one playback cycle in seconds: the clip duration (doubled for PING_PONG clips,
     * whose period spans forward + reverse), or the weight-blended duration of a blend tree.
     * Returns 0 for states with no motion.
     */
    public float getCycleDuration(Map<String, AnimatorParameter> parameters) {
        if (blendTree != null) {
            return blendTree.getBlendedDuration(blendTree.evaluateWeights(parameters));
        }
        if (clip != null) {
            boolean pingPong = isLooping && clip.loopMode == AnimationClip.LoopMode.PING_PONG;
            return pingPong ? clip.durationSeconds * 2.0f : clip.durationSeconds;
        }
        return 0.0f;
    }

    /**
     * Bounds an accumulated state time to this state's timeline: looping states wrap by modulo
     * so the accumulator never grows without bound; single-shot states clamp at the terminal frame.
     */
    public float boundStateTime(float stateTime, Map<String, AnimatorParameter> parameters) {
        float duration = getCycleDuration(parameters);
        if (duration <= 0.0001f) return 0.0f;
        if (isLooping) {
            float wrapped = stateTime % duration;
            return wrapped < 0.0f ? wrapped + duration : wrapped;
        }
        return Math.max(0.0f, Math.min(stateTime, duration));
    }

    /** True once a single-shot state's timeline has reached its terminal frame. */
    public boolean isFinished(float stateTime, Map<String, AnimatorParameter> parameters) {
        if (isLooping || !hasMotion()) return false;
        float duration = getCycleDuration(parameters);
        return duration > 0.0001f && stateTime >= duration;
    }

    /**
     * Clip-local sample time for this state's single clip. Looping states defer to the clip's
     * LOOP/PING_PONG mapping; single-shot states hold the final frame instead of wrapping to frame 0.
     */
    public float getClipSampleTime(float stateTime) {
        if (clip == null) return 0.0f;
        if (isLooping) return clip.normalizeTime(stateTime);
        return Math.max(0.0f, Math.min(stateTime, clip.durationSeconds));
    }

    /**
     * Playback phase in [0, 1] for exit-time evaluation and blend-tree clip synchronisation.
     * Single-shot states saturate at 1. Returns 0 for states with no motion.
     */
    public float getNormalizedTime(float stateTime, Map<String, AnimatorParameter> parameters) {
        float duration = getCycleDuration(parameters);
        return phaseFor(stateTime, duration);
    }

    /** Phase in [0, 1] for a precomputed cycle duration (avoids re-evaluating blend weights). */
    public float phaseFor(float stateTime, float cycleDuration) {
        if (cycleDuration <= 0.0001f) return 0.0f;
        if (!isLooping) return Math.max(0.0f, Math.min(stateTime / cycleDuration, 1.0f));
        if (clip != null && blendTree == null) {
            return clip.normalizeTime(stateTime) / clip.durationSeconds;
        }
        float phase = (stateTime / cycleDuration) % 1.0f;
        return phase < 0.0f ? phase + 1.0f : phase;
    }

    public AnimatorTransition addTransition(String destinationStateName, float crossfadeDuration) {
        AnimatorTransition transition = new AnimatorTransition(destinationStateName, crossfadeDuration);
        transitions.add(transition);
        return transition;
    }

    public void addTransition(AnimatorTransition transition) {
        if (transition != null) {
            transitions.add(transition);
        }
    }
}
