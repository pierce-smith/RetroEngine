package com.retro.engine.scripting.framework;

import com.retro.engine.ecs.Entity;
import com.retro.engine.scripting.ScriptEngine;

/**
 * Global, allocation-free publish/subscribe bus for gameplay scripts. Every subscribed listener receives every event
 * and filters by name ({@code "item_picked".equals(eventName)}).
 *
 * <p>Listeners may subscribe, unsubscribe and publish from inside a callback: unsubscribing marks the slot empty and
 * the list is compacted once the outermost publish returns; listeners added during a publish receive the next event.
 * All subscriptions are dropped when Play stops. {@code SimpleEntityScript.subscribeEvents} unsubscribes
 * automatically when its script is destroyed.</p>
 */
public final class ScriptEvents {

    private static ScriptEventListener[] listeners = new ScriptEventListener[32];
    private static int count = 0;
    private static int publishDepth = 0;
    private static boolean needsCompaction = false;

    private ScriptEvents() {}

    public static void subscribe(ScriptEventListener listener) {
        if (listener == null) return;
        for (int i = 0; i < count; i++) if (listeners[i] == listener) return;
        if (count == listeners.length) {
            ScriptEventListener[] grown = new ScriptEventListener[listeners.length * 2];
            System.arraycopy(listeners, 0, grown, 0, count);
            listeners = grown;
        }
        listeners[count++] = listener;
    }

    public static void unsubscribe(ScriptEventListener listener) {
        if (listener == null) return;
        for (int i = 0; i < count; i++) {
            if (listeners[i] == listener) {
                listeners[i] = null;
                needsCompaction = true;
                break;
            }
        }
        if (publishDepth == 0) compact();
    }

    public static void publish(String eventName, Entity source, String text, float number) {
        if (eventName == null) return;
        publishDepth++;
        try {
            int n = count; // Listeners subscribed during this publish wait for the next event
            for (int i = 0; i < n; i++) {
                ScriptEventListener l = listeners[i];
                if (l == null) continue;
                try {
                    l.onScriptEvent(eventName, source, text, number);
                } catch (RuntimeException e) {
                    // One broken listener must not stop the event reaching the others
                    ScriptEngine.reportFailure("[ScriptEvents] Listener " + l.getClass().getSimpleName() + " threw on '" + eventName + "': " + e);
                }
            }
        } finally {
            publishDepth--;
            if (publishDepth == 0 && needsCompaction) compact();
        }
    }

    public static void publish(String eventName, Entity source) {
        publish(eventName, source, null, 0.0f);
    }

    public static int getListenerCount() {
        int live = 0;
        for (int i = 0; i < count; i++) if (listeners[i] != null) live++;
        return live;
    }

    /** Engine hook: drops every subscription (Play stopped). */
    public static void clear() {
        for (int i = 0; i < count; i++) listeners[i] = null;
        count = 0;
        needsCompaction = false;
    }

    private static void compact() {
        int write = 0;
        for (int read = 0; read < count; read++) {
            if (listeners[read] != null) listeners[write++] = listeners[read];
        }
        for (int i = write; i < count; i++) listeners[i] = null;
        count = write;
        needsCompaction = false;
    }
}
