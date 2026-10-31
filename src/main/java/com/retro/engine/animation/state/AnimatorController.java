package com.retro.engine.animation.state;

import com.retro.engine.animation.clips.AnimationClip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * State machine asset: states, transitions, parameter definitions (with their default values), and the entry state.
 * Read-only at runtime; each entity owns its controller (duplicates get a {@link #copy}, so editing one entity's
 * graph never changes another's). Playback state (current / target state, state times, crossfade, live parameter values) lives on each
 * AnimatorComponent and is advanced by AnimationSystem.
 */
public class AnimatorController {

    public String name = "DefaultController";

    private final Map<String, AnimatorState> states = new LinkedHashMap<>();
    private final Map<String, AnimatorParameter> parameters = new LinkedHashMap<>();

    private AnimatorState entryState = null;

    public AnimatorController() {
        this("DefaultController");
    }

    public AnimatorController(String name) {
        this.name = name;
        setupDefaultStateMachine();
    }

    /**
     * Independent copy of the whole graph: states, transitions, blend trees, parameter definitions and the entry state.
     * Clips are passed through {@code clipMap}, so read-only imported clips can be shared while editable ones are copied.
     */
    public AnimatorController copy(UnaryOperator<AnimationClip> clipMap) {
        AnimatorController c = new AnimatorController(name);
        c.states.clear();
        c.parameters.clear();
        for (AnimatorState state : states.values()) c.states.put(state.name, state.copy(clipMap));
        for (AnimatorParameter param : parameters.values()) c.parameters.put(param.name, param.copy());
        c.entryState = (entryState != null) ? c.states.get(entryState.name) : null;
        return c;
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
    }

    public void addState(AnimatorState state) {
        if (state == null || state.name == null) return;
        states.put(state.name, state);
        if (entryState == null) entryState = state;
    }

    /**
     * Removes a state and every transition targeting it. If the removed state was the entry, the first remaining
     * state becomes the entry; animators playing it fall back to the entry on their next update.
     */
    public boolean removeState(String name) {
        AnimatorState removed = states.remove(name);
        if (removed == null) return false;

        for (AnimatorState state : states.values()) {
            state.transitions.removeIf(t -> name.equals(t.destinationStateName));
        }

        if (entryState == removed) entryState = states.isEmpty() ? null : states.values().iterator().next();
        return true;
    }

    /**
     * Renames a state in place (keeping its graph order) and retargets every transition into it. Fails when the state
     * does not exist or {@code newName} is taken.
     */
    public boolean renameState(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank()) return false;
        if (oldName.equals(newName)) return states.containsKey(oldName);
        if (!states.containsKey(oldName) || states.containsKey(newName)) return false;

        Map<String, AnimatorState> ordered = new LinkedHashMap<>(states);
        states.clear();
        for (Map.Entry<String, AnimatorState> entry : ordered.entrySet()) {
            AnimatorState state = entry.getValue();
            if (entry.getKey().equals(oldName)) {
                state.name = newName;
                states.put(newName, state);
            } else {
                states.put(entry.getKey(), state);
            }
            for (AnimatorTransition t : state.transitions) {
                if (oldName.equals(t.destinationStateName)) t.destinationStateName = newName;
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

    /** Designates the default state entered on Play. Does not interrupt running animators. */
    public boolean setEntryState(String name) {
        AnimatorState state = states.get(name);
        if (state == null) return false;
        entryState = state;
        return true;
    }

    /** The entry state, else the first state (null when there are none). */
    public AnimatorState getEntryState() {
        if (entryState == null && !states.isEmpty()) return states.values().iterator().next();
        return entryState;
    }

    // --- Parameter definitions and default values (copied into each animator's own parameter values) ---
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
}
