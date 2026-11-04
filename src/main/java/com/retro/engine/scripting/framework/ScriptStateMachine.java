package com.retro.engine.scripting.framework;

/**
 * Lightweight finite state machine for entity behaviours (Idle / Patrol / Attack ...). States are
 * {@link ScriptState} instances created once by the owning script; switching states never allocates.
 *
 * <p>The owner drives it explicitly: call {@link #update(float)} from onUpdate and, if states use physics,
 * {@link #physicsUpdate(float)} from onPhysicsUpdate.</p>
 *
 * <p>{@link #changeState(ScriptState)} may be called from anywhere, including a state's own onEnter / onExit /
 * onUpdate. A change requested while a transition is running is queued and applied right after it, so onExit and
 * onEnter calls always pair up. Changing to the current state does nothing unless {@link #restartState()} is used.</p>
 */
public final class ScriptStateMachine {

    // Guards against two states that hand control back and forth from their onEnter forever
    private static final int MAX_CHAINED_TRANSITIONS = 16;

    private ScriptState current;
    private ScriptState previous;
    private ScriptState pending;
    private boolean hasPending = false;
    private boolean transitioning = false;
    private float timeInState = 0.0f;
    private int transitionCount = 0;

    /** Switches to {@code next} (null = no state): current.onExit, then next.onEnter. */
    public void changeState(ScriptState next) {
        if (transitioning) {
            pending = next;
            hasPending = true;
            return;
        }
        if (next == current) return;
        transition(next);
    }

    /** Re-runs the current state's onExit and onEnter and resets its timer. */
    public void restartState() {
        if (current == null) return;
        if (transitioning) {
            pending = current;
            hasPending = true;
            return;
        }
        transition(current);
    }

    /** Returns to the state that was active before the current one (no-op when there is none). */
    public void revertToPrevious() {
        if (previous != null) changeState(previous);
    }

    /** Exits the current state and leaves the machine empty. */
    public void stop() {
        changeState(null);
    }

    public void update(float deltaTime) {
        if (current == null) return;
        timeInState += deltaTime;
        current.onUpdate(deltaTime);
    }

    public void physicsUpdate(float fixedDeltaTime) {
        if (current != null) current.onPhysicsUpdate(fixedDeltaTime);
    }

    public ScriptState getCurrentState() {
        return current;
    }

    public ScriptState getPreviousState() {
        return previous;
    }

    public boolean isInState(ScriptState state) {
        return current == state;
    }

    /** Name of the current state, or "None". Does not allocate. */
    public String getCurrentStateName() {
        return current != null ? current.getName() : "None";
    }

    /** Seconds spent in the current state (game time, accumulated by {@link #update(float)}). */
    public float getTimeInState() {
        return timeInState;
    }

    /** Number of completed transitions since creation; handy for debugging state flicker. */
    public int getTransitionCount() {
        return transitionCount;
    }

    private void transition(ScriptState next) {
        transitioning = true;
        try {
            ScriptState target = next;
            for (int guard = 0; guard < MAX_CHAINED_TRANSITIONS; guard++) {
                ScriptState from = current;
                if (from != null) from.onExit();
                previous = from;
                current = target;
                timeInState = 0.0f;
                transitionCount++;
                if (target != null) target.onEnter();

                if (!hasPending) return;
                hasPending = false;
                target = pending;
                pending = null;
                if (target == current) return;
            }
            System.err.println("[ScriptStateMachine] More than " + MAX_CHAINED_TRANSITIONS
                    + " chained state changes in one call; stopping in state '" + getCurrentStateName() + "'.");
        } finally {
            transitioning = false;
            hasPending = false;
            pending = null;
        }
    }
}
