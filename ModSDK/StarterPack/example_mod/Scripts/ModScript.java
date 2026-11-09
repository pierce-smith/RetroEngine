package com.example.examplemod;

import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.modding.ModContext;
import com.retro.engine.modding.ModEventListener;
import com.retro.engine.modding.SubscribeEvent;
import com.retro.engine.modding.events.EntitySpawnEvent;
import com.retro.engine.modding.events.CollisionEvent;
import com.retro.engine.modding.events.ModsLoadedEvent;
import com.retro.engine.modding.events.SceneLoadEvent;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.Entity;

/**
 * Example mod entry point (mod.json "entry_point"). Compiled in memory by javac when the game starts.
 *
 * The class is named ModScript to match its file; it extends the engine's ModScript by its fully qualified name.
 */
public class ModScript extends com.retro.engine.modding.ModScript {

    /**
     * A mod-defined ECS component. @InspectorProperty fields are what .rpatch files can set, what prefabs persist and
     * what the Inspector edits. Needs a public no-arg constructor; order values must be unique within the class.
     */
    public static class WobbleComponent extends Component {

        @InspectorProperty(order = 0, label = "AMPLITUDE", min = 0.0f, max = 5.0f, step = 0.05f)
        public float amplitude = 0.25f;

        @InspectorProperty(order = 1, label = "SPEED", min = 0.0f, max = 20.0f, step = 0.1f)
        public float speed = 2.0f;

        public String getComponentName() {
            return "Wobble";
        }
    }

    public void onInitialize(ModContext context) {
        // 1. Dynamic component registration: .rpatch files can now say "component": "WobbleComponent" (or "Wobble")
        context.registerComponent(WobbleComponent.class);

        // 2. Annotation-based subscribers: every public @SubscribeEvent method on this object
        context.registerEvents(this);

        // 3. Explicit listener (anonymous class, cast to the concrete event)
        context.eventBus().subscribe(ModsLoadedEvent.class, new ModEventListener() {
            public void onEvent(Object event) {
                ModsLoadedEvent loaded = (ModsLoadedEvent) event;
                logInfo("Ready; " + loaded.loadOrder.size() + " mod(s) active.");
            }
        });

        logInfo("Initialized " + context.manifest().name + " " + context.manifest().version);
    }

    /** Runs after every mod has initialized: the place to look up or talk to other mods. */
    public void onModEnable() {
        logInfo("Enabled.");
    }

    /** Runs after the scene's .rpatch deltas, before entities join the world. */
    @SubscribeEvent
    public void onSceneLoad(SceneLoadEvent event) {
        logInfo(event.scenePath + ": " + event.entities.size() + " entities, " + event.patchesApplied + " patch(es) applied.");
    }

    /** Runs once per entity on scene load and prefab spawn; event.setCanceled(true) would drop the entity. */
    @SubscribeEvent
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.entity;
        // Any entity named "Crate..." (scene or prefab) starts bobbing: attach the mod component plus the
        // EntityScript that animates it. Patches can do the same declaratively (see Patches/example.rpatch).
        if (entity.name == null || !entity.name.startsWith("Crate") || entity.getComponent(WobbleComponent.class) != null) return;
        entity.addComponent(new WobbleComponent());
        String script = context().vfs().resolveOverride(context().modUri("Scripts/WobbleScript.java"));
        entity.addComponent(new ScriptComponent(script, "WobbleScript.java"));
    }

    /**
     * Physics contacts (60 Hz, before the bodies are separated). Crates bounce harder than vanilla bodies;
     * event.setCanceled(true) would let the two bodies pass through each other instead.
     */
    @SubscribeEvent
    public void onCollision(CollisionEvent event) {
        boolean crate = (event.entityA.name != null && event.entityA.name.startsWith("Crate"))
                || (event.entityB.name != null && event.entityB.name.startsWith("Crate"));
        if (crate) event.restitution = Math.max(event.restitution, 0.8f);
    }

    public void onShutdown() {
        logInfo("Shutting down.");
    }
}
