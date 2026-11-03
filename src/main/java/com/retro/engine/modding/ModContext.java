package com.retro.engine.modding;

import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.ComponentSchema;

import java.io.File;
import java.io.IOException;

/** Per-mod view of the mod runtime handed to {@link ModScript#onInitialize}. */
public final class ModContext {

    // Mod whose scripts are being initialized on the loading thread, for attributing bus registrations and logs
    private static volatile ModContext current;

    private final ModManifest manifest;
    private final ModEventBus eventBus;
    private final ModLoader loader;

    ModContext(ModManifest manifest, ModEventBus eventBus, ModLoader loader) {
        this.manifest = manifest;
        this.eventBus = eventBus;
        this.loader = loader;
    }

    static ModContext current() {
        return current;
    }

    static void setCurrent(ModContext ctx) {
        current = ctx;
    }

    public ModManifest manifest() {
        return manifest;
    }

    /** Shared bus for every mod; handlers registered through it are attributed to this mod in error logs. */
    public ModEventBus eventBus() {
        return eventBus;
    }

    /** The mounted overlay stack (rebuilt by ModLoader if a mod fails to load, so do not cache it). */
    public ModVirtualFileSystem vfs() {
        return loader.vfs();
    }

    /** Registers {@code @SubscribeEvent} methods of {@code subscriber}, attributed to this mod. */
    public int registerEvents(Object subscriber) {
        return eventBus.register(subscriber, manifest.id);
    }

    /**
     * Registers a mod-defined ECS component so .rpatch files (ATTACH_COMPONENT / ADD_ENTITY), prefabs and the
     * Inspector can reference it by simple class name. Its {@code @InspectorProperty} fields drive serialization and
     * editing exactly like built-in components.
     */
    public void registerComponent(Class<? extends Component> type) {
        ComponentSchema.registerModComponent(type, manifest.id);
    }

    /** A file inside this mod's folder ("Textures/Icon.png"); throws if the path escapes the folder. */
    public File modFile(String relativePath) throws IOException {
        return manifest.resolve(relativePath);
    }

    /** {@code mod://<this mod>/<relativePath>}, the form .rpatch files and asset fields use for this mod's files. */
    public String modUri(String relativePath) {
        return ModVirtualFileSystem.MOD_SCHEME + manifest.id + "/" + relativePath.replace('\\', '/');
    }

    public void logInfo(String message) {
        System.out.println("[Mod:" + manifest.id + "] " + message);
    }

    public void logWarn(String message) {
        System.out.println("[Mod:" + manifest.id + "] WARNING: " + message);
    }

    public void logError(String message) {
        System.err.println("[Mod:" + manifest.id + "] " + message);
    }
}
