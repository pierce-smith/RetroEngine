package com.retro.engine.animation.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transition rule connecting an AnimatorState to a destination AnimatorState.
 * Supports exit time thresholds, duration crossfading, and parameter conditions.
 */
public class AnimatorTransition {

    public String destinationStateName;
    public float duration = 0.25f; // Crossfade blend duration in seconds
    public boolean hasExitTime = false;
    public float exitTime = 0.90f;  // Normalized time threshold (e.g. 0.90 = 90% clip completed)

    public final List<AnimatorCondition> conditions = new ArrayList<>();

    public AnimatorTransition(String destinationStateName) {
        this.destinationStateName = destinationStateName;
    }

    public AnimatorTransition(String destinationStateName, float duration) {
        this.destinationStateName = destinationStateName;
        this.duration = duration;
    }

    public AnimatorTransition(String destinationStateName, float duration, boolean hasExitTime, float exitTime) {
        this.destinationStateName = destinationStateName;
        this.duration = duration;
        this.hasExitTime = hasExitTime;
        this.exitTime = exitTime;
    }

    /** Independent copy, conditions included. */
    public AnimatorTransition copy() {
        AnimatorTransition c = new AnimatorTransition(destinationStateName, duration, hasExitTime, exitTime);
        for (AnimatorCondition cond : conditions) c.conditions.add(new AnimatorCondition(cond.parameterName, cond.mode, cond.threshold));
        return c;
    }

    public AnimatorTransition addCondition(String paramName, AnimatorCondition.Comparison mode, float threshold) {
        conditions.add(new AnimatorCondition(paramName, mode, threshold));
        return this;
    }

    public AnimatorTransition addCondition(String paramName, AnimatorCondition.Comparison mode) {
        conditions.add(new AnimatorCondition(paramName, mode, 0.0f));
        return this;
    }

    /**
     * Checks if all conditions and exit-time rules are satisfied to execute this transition.
     */
    public boolean canTransition(float currentNormalizedTime, Map<String, AnimatorParameter> parameters) {
        if (hasExitTime && currentNormalizedTime < exitTime) {
            return false;
        }

        for (AnimatorCondition cond : conditions) {
            AnimatorParameter param = parameters.get(cond.parameterName);
            if (!cond.evaluate(param)) {
                return false;
            }
        }

        return true;
    }
}
