package com.retro.engine.modding.events;

import com.retro.engine.ecs.Entity;

import java.util.List;

/**
 * Posted immediately after a scene's binary deserialization and its .rpatch deltas, before the entities are bound
 * into the SceneWorld. {@link #entities} is the live, mutable list: subscribers may add, remove or edit entities
 * (keep parentId / child ids consistent, as SceneDeltaPatcher does).
 */
public final class SceneLoadEvent extends ModEvent {

    /** Project-relative scene path, e.g. {@code Assets/Scenes/Main.rscene}. */
    public String scenePath;
    public List<Entity> entities;
    /** Number of .rpatch files applied to this scene before the event was posted. */
    public int patchesApplied;
}
