package com.retro.engine.scripting.framework;

/**
 * A unit of deferred work for {@link ScriptTaskQueue} and tween completion callbacks.
 *
 * <p>Implement it with a lambda, a method reference or a class. Create the instance once (a field, or in onStart) and
 * reuse it every time you schedule it: a capturing lambda written inside onUpdate allocates every frame.</p>
 *
 * <pre>
 * private final ScriptTask closeDoor = this::close;
 * ...
 * runAfter(3.0f, closeDoor);
 * </pre>
 */
public interface ScriptTask {
    void run();
}
