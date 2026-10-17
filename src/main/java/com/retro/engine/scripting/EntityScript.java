package com.retro.engine.scripting;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;

/**
 * Base abstract class for all gameplay logic scripts.
 * Provides access to the owning entity and scene world, along with lifecycle hooks.
 */
public abstract class EntityScript {

    protected Entity entity;
    protected SceneWorld sceneWorld;

    public void init(Entity entity, SceneWorld sceneWorld) {
        this.entity = entity;
        this.sceneWorld = sceneWorld;
    }

    public Entity getEntity() { return entity; }
    public SceneWorld getSceneWorld() { return sceneWorld; }

    /**
     * Called once when the simulation starts or script is initialized.
     */
    public void onStart() {}

    /**
     * Called every frame tick during PlayState.SIMULATING.
     * @param deltaTime frame delta time in seconds
     */
    public void onUpdate(float deltaTime) {}

    /**
     * Called when simulation stops or the script is detached/destroyed.
     */
    public void onDestroy() {}
}
