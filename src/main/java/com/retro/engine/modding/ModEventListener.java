package com.retro.engine.modding;

import com.retro.engine.api.PublicApi;

/**
 * Explicit handler for {@link ModEventBus#subscribe}. The event arrives as {@code Object}; cast or pattern-match it:
 * <pre>
 * bus.subscribe(SceneLoadEvent.class, event -> {
 *     if (event instanceof SceneLoadEvent e) { ... }
 * });
 * </pre>
 */
@PublicApi
public interface ModEventListener {
    void onEvent(Object event) throws Exception;
}
