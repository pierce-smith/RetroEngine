package com.retro.engine.scripting.framework;

import com.retro.engine.ecs.Entity;

/**
 * Receives events published through {@link ScriptEvents}. Scripts are compiled separately and cannot refer
 * to each other's classes, so events are the type-safe way for one script to notify another (an inventory, a HUD, a
 * game manager).
 *
 * <p>Payload is passed as plain arguments rather than an object so publishing never allocates: {@code source} is the
 * entity that raised it, {@code text} a free string (an item id, a door name...), {@code number} a free float
 * (an amount, a score delta...). Either may be unused (null / 0).</p>
 */
public interface ScriptEventListener {
    void onScriptEvent(String eventName, Entity source, String text, float number);
}
