package com.retro.engine.modding.events;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Entity;

/**
 * Posted for every entity instantiated by a scene load (after .rpatch deltas), a prefab spawn, or
 * {@code SceneWorld.spawnEntity} from a script or mod. Cancelable:
 * a canceled entity (and its descendants) is removed before it becomes part of the scene.
 * The instance is reused between posts; do not store it.
 */
@PublicApi
public final class EntitySpawnEvent extends ModEvent {

    public enum Source { SCENE_LOAD, PREFAB, SCRIPT }

    public Entity entity;
    public Source source;
    /** Scene path for {@link Source#SCENE_LOAD}, prefab file path for {@link Source#PREFAB}, null for {@link Source#SCRIPT}. */
    public String origin;

    @Override
    public boolean isCancelable() {
        return true;
    }
}
