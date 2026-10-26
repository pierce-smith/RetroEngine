package com.retro.engine.animation.state;

import com.retro.engine.animation.clips.AnimationClip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime state machine asset/controller evaluating parameter state transitions,
 * crossfade blending between clips, and active skeleton animation poses.
 */
public class AnimatorController {

    public String name = "DefaultController";

    private final Map<String, AnimatorState> states = new LinkedHashMap<>();
    private final Map<String, AnimatorParameter> parameters = new LinkedHashMap<>();

    private AnimatorState entryState = null;
    private AnimatorState currentState = null;
    private AnimatorState targetState = null;

    private float currentStateTime = 0.0f;
    private float targetStateTime = 0.0f;

    private float crossfadeTimer = 0.0f;
    private float crossfadeDuration = 0.25f;
    private boolean transitioning = false;

    public AnimatorController() {
        this("DefaultController");
    }

    public AnimatorController(String name) {
        this.name = name;
        setupDefaultStateMachine();
    }

    private void setupDefaultStateMachine() {
        // Default Parameters
        setFloat("Speed", 1.25f);
        setInt("StateID", 0);
        setBool("IsGrounded", true);

        AnimatorParameter jumpTrig = new AnimatorParameter("JumpTrigger", AnimatorParameter.ParameterType.TRIGGER);
        parameters.put("JumpTrigger", jumpTrig);

        // Default Clips
        AnimationClip idleClip = new AnimationClip("clip_idle_loop", 2.0f);
        AnimationClip walkClip = new AnimationClip("clip_walk_forward", 1.2f);
        AnimationClip runClip = new AnimationClip("clip_run_forward", 0.8f);
        AnimationClip jumpClip = new AnimationClip("clip_jump_action", 1.0f);

        // Default States with Graph Positions
        AnimatorState idleState = new AnimatorState("Idle", idleClip).setGraphPos(-0.20f, 0.65f);
        AnimatorState walkState = new AnimatorState("Walk", walkClip).setGraphPos(0.18f, 0.65f);
        AnimatorState runState = new AnimatorState("Run", runClip).setGraphPos(0.18f, 0.25f);
        AnimatorState jumpState = new AnimatorState("Jump", jumpClip).setGraphPos(-0.20f, 0.25f);

        // Transitions
        idleState.addTransition("Walk", 0.25f)
                .addCondition("Speed", AnimatorCondition.Comparison.GREATER, 0.1f);

        walkState.addTransition("Idle", 0.20f)
                .addCondition("Speed", AnimatorCondition.Comparison.LESS_EQUAL, 0.1f);

        walkState.addTransition("Run", 0.25f)
                .addCondition("Speed", AnimatorCondition.Comparison.GREATER, 2.0f);

        runState.addTransition("Walk", 0.25f)
                .addCondition("Speed", AnimatorCondition.Comparison.LESS_EQUAL, 2.0f);

        addState(idleState);
        addState(walkState);
        addState(runState);
        addState(jumpState);

        setEntryState("Idle");
    }

    public void clearStates() {
        states.clear();
        entryState = null;
        currentState = null;
        targetState = null;
        transitioning = false;
    }

    public void addState(AnimatorState state) {
        if (state == null || state.name == null) return;
        states.put(state.name, state);
        if (entryState == null) entryState = state;
        if (currentState == null) {
            currentState = state;
            currentStateTime = 0.0f;
        }
    }

    /**
     * Removes a state and every transition targeting it. If the removed state was the entry,
     * current, or crossfade target, the controller falls back to the first remaining state.
     */
    public boolean removeState(String name) {
        AnimatorState removed = states.remove(name);
        if (removed == null) return false;

        for (AnimatorState state : states.values()) {
            state.transitions.removeIf(t -> name.equals(t.destinationStateName));
        }

        AnimatorState fallback = states.isEmpty() ? null : states.values().iterator().next();
        if (entryState == removed) entryState = fallback;
        if (targetState == removed) {
            targetState = null;
            targetStateTime = 0.0f;
            transitioning = false;
            crossfadeTimer = 0.0f;
        }
        if (currentState == removed) {
            if (transitioning && targetState != null) {
                currentState = targetState;
                currentStateTime = targetStateTime;
                targetState = null;
                transitioning = false;
                crossfadeTimer = 0.0f;
            } else {
                currentState = entryState;
                currentStateTime = 0.0f;
            }
        }
        return true;
    }

    /** Returns base, or base suffixed with the lowest free index, so state keys never collide. */
    public String uniqueStateName(String base) {
        if (!states.containsKey(base)) return base;
        int suffix = 1;
        while (states.containsKey(base + " " + suffix)) suffix++;
        return base + " " + suffix;
    }

    public AnimatorState getState(String name) {
        return states.get(name);
    }

    public Map<String, AnimatorState> getStates() {
        return states;
    }

    public void setInitialState(String name) {
        setEntryState(name);
        resetToEntryState();
    }

    /** Designates the default state entered on Play. Does not interrupt the running state. */
    public boolean setEntryState(String name) {
        AnimatorState state = states.get(name);
        if (state == null) return false;
        entryState = state;
        return true;
    }

    public AnimatorState getEntryState() {
        return entryState;
    }

    /** Hard-resets runtime playback to the entry state, cancelling any crossfade. */
    public void resetToEntryState() {
        if (entryState == null && !states.isEmpty()) entryState = states.values().iterator().next();
        currentState = entryState;
        currentStateTime = 0.0f;
        targetState = null;
        targetStateTime = 0.0f;
        crossfadeTimer = 0.0f;
        transitioning = false;
    }

    // --- Parameter Mutators ---
    public void setFloat(String name, float value) {
        AnimatorParameter param = parameters.computeIfAbsent(name, k -> new AnimatorParameter(k, AnimatorParameter.ParameterType.FLOAT));
        param.floatValue = value;
    }

    public float getFloat(String name) {
        AnimatorParameter param = parameters.get(name);
        return param != null ? param.floatValue : 0.0f;
    }

    public void setInt(String name, int value) {
        AnimatorParameter param = parameters.computeIfAbsent(name, k -> new AnimatorParameter(k, AnimatorParameter.ParameterType.INT));
        param.intValue = value;
    }

    public int getInt(String name) {
        AnimatorParameter param = parameters.get(name);
        return param != null ? param.intValue : 0;
    }

    public int getInteger(String name) {
        return getInt(name);
    }

    public void setBool(String name, boolean value) {
        AnimatorParameter param = parameters.computeIfAbsent(name, k -> new AnimatorParameter(k, AnimatorParameter.ParameterType.BOOL));
        param.boolValue = value;
    }

    public boolean getBool(String name) {
        AnimatorParameter param = parameters.get(name);
        return param != null && param.boolValue;
    }

    public void setTrigger(String name) {
        AnimatorParameter param = parameters.computeIfAbsent(name, k -> new AnimatorParameter(k, AnimatorParameter.ParameterType.TRIGGER));
        param.setTrigger();
    }

    public Map<String, AnimatorParameter> getParameters() {
        return parameters;
    }

    /**
     * Advances controller simulation, checks transition conditions, and updates crossfade weights.
     */
    public void update(float deltaTime) {
        if (currentState == null) return;

        // Looping states wrap, single-shot states freeze at their terminal frame
        currentStateTime = currentState.boundStateTime(currentStateTime + deltaTime * currentState.speed, this);

        if (transitioning && targetState != null) {
            targetStateTime = targetState.boundStateTime(targetStateTime + deltaTime * targetState.speed, this);
            crossfadeTimer += deltaTime;

            if (crossfadeTimer >= crossfadeDuration) {
                // Complete crossfade transition
                currentState = targetState;
                currentStateTime = targetStateTime;
                targetState = null;
                targetStateTime = 0.0f;
                transitioning = false;
                crossfadeTimer = 0.0f;
            }
        }

        // Evaluate outgoing transitions if not already crossfading
        if (!transitioning) {
            float normalizedTime = currentState.getNormalizedTime(currentStateTime, this);

            for (AnimatorTransition transition : currentState.transitions) {
                if (transition.canTransition(normalizedTime, parameters)) {
                    AnimatorState next = states.get(transition.destinationStateName);
                    if (next != null && next != currentState) {
                        startTransition(next, transition.duration);
                        resetTriggersUsedIn(transition);
                        break;
                    }
                }
            }
        }
    }

    private void startTransition(AnimatorState destination, float duration) {
        targetState = destination;
        targetStateTime = 0.0f;
        crossfadeTimer = 0.0f;
        crossfadeDuration = Math.max(0.01f, duration);
        transitioning = true;
    }

    private void resetTriggersUsedIn(AnimatorTransition transition) {
        for (AnimatorCondition cond : transition.conditions) {
            AnimatorParameter param = parameters.get(cond.parameterName);
            if (param != null && param.type == AnimatorParameter.ParameterType.TRIGGER) {
                param.resetTrigger();
            }
        }
    }

    // --- State Getters ---
    public AnimatorState getCurrentState() { return currentState; }
    public AnimatorState getTargetState() { return targetState; }
    public float getCurrentStateTime() { return currentStateTime; }
    public float getTargetStateTime() { return targetStateTime; }
    /** Seeks the current state's timeline (editor scrubbing), bounded exactly like normal playback. */
    public void setCurrentStateTime(float stateTime) {
        if (currentState == null) return;
        currentStateTime = currentState.boundStateTime(stateTime, this);
    }

    public boolean isTransitioning() { return transitioning; }
    public boolean isCrossfading() { return transitioning; }

    /** True when the current single-shot state has played to its end and no crossfade is leaving it. */
    public boolean isCurrentStateFinished() {
        return currentState != null && !transitioning && currentState.isFinished(currentStateTime, this);
    }

    public float getCrossfadeWeight() {
        if (!transitioning || crossfadeDuration <= 0.0f) return 0.0f;
        return Math.min(1.0f, Math.max(0.0f, crossfadeTimer / crossfadeDuration));
    }
}
