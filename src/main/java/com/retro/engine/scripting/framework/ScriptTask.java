package com.retro.engine.scripting.framework;

/**
 * A unit of deferred work for {@link ScriptTaskQueue} and tween completion callbacks.
 *
 * <p>Scripts are compiled with Janino, which has no lambdas or method references, so implement this with a named
 * inner class or an anonymous class. Create the instance once (a field, or in onStart) and reuse it every time you
 * schedule it; constructing a new anonymous class inside onUpdate allocates every frame.</p>
 *
 * <pre>
 * private final ScriptTask closeDoor = new ScriptTask() {
 *     public void run() { close(); }
 * };
 * ...
 * runAfter(3.0f, closeDoor);
 * </pre>
 */
public interface ScriptTask {
    void run();
}
