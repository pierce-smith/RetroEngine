package com.retro.engine.save;

import com.retro.engine.ecs.Entity;

import java.util.List;

/** The parts of the running scene the save system reads and changes. {@code SceneWorld} provides it. */
public interface SaveWorld {
    List<Entity> entities();
    String activeScenePath();
    Entity findEntityById(String id);
    /** Removes the entity right away (only called before the level starts running). */
    void removeEntity(Entity e);
    /** Every entity the prefab created, root first, or an empty list when it could not be loaded. */
    List<Entity> instantiatePrefab(String prefabPath);
    Entity spawnEntity(String id, String name, String meshPath);
    void setParent(Entity child, Entity parentOrNull);
    void refreshComponents(Entity e);
    String resolveAssetPath(String assetsRelativePath);
    boolean isPlaying();
    void requestSceneLoad(String scenePath);
    boolean sceneExists(String scenePath);
}
