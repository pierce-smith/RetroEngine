package com.retro.engine.modding;

/**
 * Explicit handler for {@link ModEventBus#subscribe}. Deliberately non-generic: mod scripts compile with Janino,
 * which supports neither lambdas nor generic bridge methods, so implement it as an anonymous class and cast:
 * <pre>
 * bus.subscribe(SceneLoadEvent.class, new ModEventListener() {
 *     public void onEvent(Object event) {
 *         SceneLoadEvent e = (SceneLoadEvent) event;
 *         ...
 *     }
 * });
 * </pre>
 */
public interface ModEventListener {
    void onEvent(Object event) throws Exception;
}
