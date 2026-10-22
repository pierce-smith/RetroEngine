package com.retro.engine.animation;

import java.util.ArrayList;
import java.util.List;

/**
 * State in an AnimatorController representing an animation clip, playback parameters,
 * 2D graph layout coordinates, and outgoing transition rules.
 */
public class AnimatorState {

    public String name;
    public AnimationClip clip;
    public float speed = 1.0f;
    public boolean isLooping = true;

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

    public AnimatorState setGraphPos(float x, float y) {
        this.graphX = x;
        this.graphY = y;
        return this;
    }

    public boolean isLooping() {
        return isLooping;
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
