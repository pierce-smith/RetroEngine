package com.retro.engine.save;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What changed in one level since it was loaded from its scene file: entities destroyed, entities spawned during play,
 * and the properties and script state of entities that differ from how they were loaded or spawned. Applying it to a
 * freshly loaded copy of the scene gives back the level as it was, so a patched scene file still applies under old saves.
 */
final class SceneState {

    /** One {@code instantiatePrefab} / {@code spawnEntity} call made during play, in call order. */
    static final class Spawn {
        /** Prefab path as the script passed it; null for a plain {@code spawnEntity}. */
        String prefabPath;
        /** {@code spawnEntity} only: name and mesh path it was called with. */
        String name, meshPath;
        /** Ids of the entities the call created, in creation order. */
        final List<String> ids = new ArrayList<>();
    }

    /** One component on an entity, matched by type name and position among components of that type. */
    static final class ComponentChange {
        enum Kind { CHANGED, ADDED, REMOVED }
        Kind kind;
        String type;
        int index;
        /** Property name -> value (Float, Integer, Boolean, enum name String, String, Map, byte[]), in schema order. */
        final java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
    }

    /** Saved state of one script on an entity. */
    static final class ScriptState {
        int index;
        String scriptName;
        final SaveRecord fields = new SaveRecord();
        final SaveRecord custom = new SaveRecord();
    }

    static final class EntityChange {
        String id;
        /** Null when unchanged. */
        String name;
        /** Null when unchanged; empty string means "moved to the root". */
        String parentId;
        final List<ComponentChange> components = new ArrayList<>();
        final List<ScriptState> scripts = new ArrayList<>();

        boolean isEmpty() { return name == null && parentId == null && components.isEmpty() && scripts.isEmpty(); }
    }

    final Set<String> destroyedIds = new LinkedHashSet<>();
    final List<Spawn> spawns = new ArrayList<>();
    final List<EntityChange> entities = new ArrayList<>();
}
