package com.retro.engine.animation;

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

        currentState = idleState;
    }

    public void clearStates() {
        states.clear();
        currentState = null;
        targetState = null;
    }

    public void addState(AnimatorState state) {
        if (state == null || state.name == null) return;
        states.put(state.name, state);
        if (currentState == null) {
            currentState = state;
            currentStateTime = 0.0f;
        }
    }

    public AnimatorState getState(String name) {
        return states.get(name);
    }

    public Map<String, AnimatorState> getStates() {
        return states;
    }

    public void setInitialState(String name) {
        AnimatorState state = states.get(name);
        if (state != null) {
            currentState = state;
            currentStateTime = 0.0f;
            transitioning = false;
        }
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

        currentStateTime += deltaTime * currentState.speed;

        if (transitioning && targetState != null) {
            targetStateTime += deltaTime * targetState.speed;
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
        if (!transitioning && currentState.clip != null) {
            float normalizedTime = currentState.clip.normalizeTime(currentStateTime);

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
    public boolean isTransitioning() { return transitioning; }
    public boolean isCrossfading() { return transitioning; }

    public float getCrossfadeWeight() {
        if (!transitioning || crossfadeDuration <= 0.0f) return 0.0f;
        return Math.min(1.0f, Math.max(0.0f, crossfadeTimer / crossfadeDuration));
    }
}
