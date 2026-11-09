package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;

/**
 * One state of a {@link ScriptStateMachine}. Override only the callbacks you need.
 *
 * <p>Declare states as (non-static) inner classes of your script, so they can read and write the
 * script's fields directly, and create each state once as a field:</p>
 *
 * <pre>
 * private class PatrolState extends ScriptState {
 *     PatrolState() { super("Patrol"); }
 *     public void onUpdate(float dt) { ... if (seesPlayer()) fsm.changeState(chase); }
 * }
 * private final ScriptStateMachine fsm = new ScriptStateMachine();
 * private ScriptState patrol, chase;
 * public void onStart() { patrol = new PatrolState(); chase = new ChaseState(); fsm.changeState(patrol); }
 * public void onUpdate(float dt) { fsm.update(dt); }
 * </pre>
 */
@PublicApi
public abstract class ScriptState {

    private final String name;

    protected ScriptState(String name) {
        this.name = (name != null) ? name : "State";
    }

    public String getName() {
        return name;
    }

    /** Called once when the machine enters this state. */
    public void onEnter() {}

    /** Called from {@link ScriptStateMachine#update(float)} every frame while this state is current. */
    public void onUpdate(float deltaTime) {}

    /** Called from {@link ScriptStateMachine#physicsUpdate(float)} every fixed step while this state is current. */
    public void onPhysicsUpdate(float fixedDeltaTime) {}

    /** Called once when the machine leaves this state. */
    public void onExit() {}

    @Override
    public String toString() {
        return name;
    }
}
