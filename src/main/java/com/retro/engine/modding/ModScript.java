package com.retro.engine.modding;

/**
 * Base class of a mod's {@code entry_point} (mod.json). ModLoader compiles the mod's {@code script_sources} with
 * {@link com.retro.engine.scripting.ScriptCompiler} and instantiates the entry point through its public no-arg constructor. Lifecycle, per mod set:
 * {@link #onInitialize} (default: {@link #onModInit}) in load order, then {@link #onModEnable} in load order once all
 * mods are up; on unload {@link #onModDisable} then {@link #onShutdown} in reverse load order.
 *
 * <p>Subscribe with {@link SubscribeEvent} methods or a {@link ModEventListener} lambda. The helpers below are public
 * (not protected) so that classes nested in a mod, which may load in another class loader, can always reach them.
 */
public abstract class ModScript {

    private ModContext context;

    final void attach(ModContext context) {
        this.context = context;
    }

    /**
     * Load-time entry: register components ({@link ModContext#registerComponent}) and event subscribers here. Runs in
     * load order once every mod's assets are mounted, before any scene loads. The default calls {@link #onModInit()}.
     */
    public void onInitialize(ModContext context) {
        onModInit();
    }

    /** Argument-free form of {@link #onInitialize}; {@link #context()} is already set when it runs. */
    public void onModInit() {}

    /** Runs after every mod has initialized (just before {@code ModsLoadedEvent}): safe to talk to other mods. */
    public void onModEnable() {}

    /** Runs when the mod set is unloaded (project closed, mods reloaded), in reverse load order, before {@link #onShutdown}. */
    public void onModDisable() {}

    /** Final cleanup after {@link #onModDisable}. */
    public void onShutdown() {}

    /** This mod's context; null before {@link #onInitialize}. */
    public ModContext context() {
        return context;
    }

    public void logInfo(String message) {
        if (context != null) context.logInfo(message);
    }

    public void logWarn(String message) {
        if (context != null) context.logWarn(message);
    }

    public void logError(String message) {
        if (context != null) context.logError(message);
    }
}
