package com.retro.engine.modding;

import com.retro.engine.modding.events.ModEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Synchronous event bus connecting engine hooks to mod subscribers.
 *
 * <p>Subscribers register either an object whose public {@link SubscribeEvent} methods each take one event type
 * ({@link #register(Object)}), or an explicit {@link ModEventListener} ({@link #subscribe}). A handler receives its
 * event type and every subclass of it. Handlers run in registration order, which is mod load order.
 *
 * <p>Any object can be posted ({@link #post(Object)}), so mods can define their own event classes to talk to each
 * other. Only {@link ModEvent} subclasses carry a canceled flag.
 *
 * <p>{@link #post} does not allocate: handlers live in per-type arrays that are rebuilt only when a subscription
 * changes, and engine hot paths reuse one event instance per type. A handler that throws is logged with its mod id
 * and skipped; the remaining handlers and the engine keep running.
 */
public final class ModEventBus {

    private static final Handler[] NONE = new Handler[0];

    private static final class Handler {
        final Class<?> eventType;
        final String owner;
        final Object target;          // @SubscribeEvent receiver, or null
        final Method method;          // @SubscribeEvent method, or null
        final ModEventListener listener;

        Handler(Class<?> eventType, String owner, Object target, Method method, ModEventListener listener) {
            this.eventType = eventType;
            this.owner = owner;
            this.target = target;
            this.method = method;
            this.listener = listener;
        }
    }

    // Every registration in order; dispatch tables are derived from it
    private Handler[] all = NONE;
    // Resolved handlers per concrete posted type (includes handlers for its supertypes)
    private final Map<Class<?>, Handler[]> dispatch = new IdentityHashMap<>();

    /** Registers every public {@code @SubscribeEvent} method of {@code subscriber}. Returns how many were found. */
    public int register(Object subscriber) {
        return register(subscriber, ownerOf(subscriber));
    }

    /** As {@link #register(Object)}, attributing handler errors to {@code ownerModId}. */
    public synchronized int register(Object subscriber, String ownerModId) {
        if (subscriber == null) return 0;
        int found = 0;
        for (Method m : subscriber.getClass().getMethods()) {
            if (!m.isAnnotationPresent(SubscribeEvent.class)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || params.length != 1 || params[0].isPrimitive()) {
                throw new IllegalArgumentException("@SubscribeEvent method " + subscriber.getClass().getName() + "." + m.getName()
                        + " must be a public instance method taking exactly one event object");
            }
            m.setAccessible(true); // Compiled mod classes may be nested or package-private
            add(new Handler(params[0], ownerModId, subscriber, m, null));
            found++;
        }
        return found;
    }

    public void subscribe(Class<?> eventType, ModEventListener listener) {
        subscribe(eventType, listener, ownerOf(listener));
    }

    public synchronized void subscribe(Class<?> eventType, ModEventListener listener, String ownerModId) {
        if (eventType == null || listener == null) throw new IllegalArgumentException("eventType and listener are required");
        add(new Handler(eventType, ownerModId, null, null, listener));
    }

    /** Removes every handler registered for {@code subscriberOrListener}. */
    public synchronized void unregister(Object subscriberOrListener) {
        Handler[] kept = Arrays.stream(all)
                .filter(h -> h.target != subscriberOrListener && h.listener != subscriberOrListener)
                .toArray(Handler[]::new);
        if (kept.length != all.length) {
            all = kept;
            dispatch.clear();
        }
    }

    /** Removes every handler attributed to {@code ownerModId} (used when a mod fails to initialize). */
    public synchronized void unregisterOwner(String ownerModId) {
        Handler[] kept = Arrays.stream(all).filter(h -> !h.owner.equals(ownerModId)).toArray(Handler[]::new);
        if (kept.length != all.length) {
            all = kept;
            dispatch.clear();
        }
    }

    public synchronized void clear() {
        all = NONE;
        dispatch.clear();
    }

    public boolean hasSubscribers(Class<?> eventType) {
        return handlersFor(eventType).length > 0;
    }

    /**
     * Delivers {@code event} to every matching handler. Returns the event's canceled state afterwards ({@code false}
     * for events that are not {@link ModEvent}s).
     */
    public boolean post(Object event) {
        if (event == null) return false;
        ModEvent modEvent = (event instanceof ModEvent e) ? e : null;
        if (modEvent != null) modEvent.setCanceled(false);
        Handler[] handlers = handlersFor(event.getClass());
        for (int i = 0; i < handlers.length; i++) {
            Handler h = handlers[i];
            try {
                if (h.listener != null) {
                    h.listener.onEvent(event);
                } else {
                    h.method.invoke(h.target, event);
                }
            } catch (InvocationTargetException e) {
                logFailure(h, event, e.getCause() != null ? e.getCause() : e);
            } catch (Throwable t) {
                logFailure(h, event, t);
            }
        }
        return modEvent != null && modEvent.isCanceled();
    }

    private synchronized Handler[] handlersFor(Class<?> postedType) {
        Handler[] cached = dispatch.get(postedType);
        if (cached != null) return cached;
        Handler[] matching = Arrays.stream(all).filter(h -> h.eventType.isAssignableFrom(postedType)).toArray(Handler[]::new);
        Handler[] resolved = matching.length == 0 ? NONE : matching;
        dispatch.put(postedType, resolved);
        return resolved;
    }

    private void add(Handler h) {
        all = Arrays.copyOf(all, all.length + 1);
        all[all.length - 1] = h;
        dispatch.clear();
    }

    private static void logFailure(Handler h, Object event, Throwable t) {
        System.err.println("[ModEventBus] Mod '" + h.owner + "' failed handling " + event.getClass().getSimpleName() + ": " + t);
        t.printStackTrace();
    }

    private static String ownerOf(Object o) {
        ModContext ctx = ModContext.current();
        return ctx != null ? ctx.manifest().id : (o != null ? o.getClass().getName() : "unknown");
    }
}
