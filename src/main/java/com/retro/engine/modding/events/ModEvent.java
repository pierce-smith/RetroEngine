package com.retro.engine.modding.events;

import com.retro.engine.api.PublicApi;

/**
 * Base class of everything posted on {@link com.retro.engine.modding.ModEventBus}. Engine hot paths reuse one
 * instance per event type, so subscribers must not keep a reference to an event after their handler returns.
 */
@PublicApi
public abstract class ModEvent {

    private boolean cancelled;

    /** True for events whose {@link #setCanceled} vetoes the engine action (see each event's docs). */
    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return cancelled;
    }

    public void setCanceled(boolean cancel) {
        if (cancel && !isCancelable()) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot be canceled");
        }
        this.cancelled = cancel;
    }
}
