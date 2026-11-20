package com.retro.engine.save;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the running level looked when it was loaded, and how each entity spawned during play looked right after it was
 * spawned. A save stores only the differences from this, see {@link SceneDiff}.
 */
final class SceneBaseline {

    static final class ComponentSnapshot {
        final Component instance;
        final String type;
        /** Position among this entity's components of the same type. */
        final int index;
        final ComponentSchema schema;
        final Object[] values;

        ComponentSnapshot(Component instance, String type, int index) {
            this.instance = instance;
            this.type = type;
            this.index = index;
            this.schema = ComponentSchema.of(instance.getClass());
            List<ComponentSchema.Property> props = schema.serializedProperties();
            this.values = new Object[props.size()];
            for (int i = 0; i < values.length; i++) values[i] = ComponentValues.read(props.get(i), instance);
        }
    }

    static final class EntitySnapshot {
        final String id, name, parentId;
        final List<ComponentSnapshot> components = new ArrayList<>();

        EntitySnapshot(Entity e) {
            id = e.id;
            name = e.name;
            parentId = e.parentId;
            Map<String, Integer> seen = new java.util.HashMap<>();
            for (Component c : e.getAllComponents()) {
                if (c == null || !ComponentSchema.of(c.getClass()).isPersistent()) continue;
                String type = typeName(c);
                int index = seen.merge(type, 1, Integer::sum) - 1;
                components.add(new ComponentSnapshot(c, type, index));
            }
        }
    }

    /** One spawn call made during play, with the entities it created in creation order. */
    static final class SpawnRecord {
        final SceneState.Spawn info;
        final List<Entity> members;

        SpawnRecord(SceneState.Spawn info, List<Entity> members) {
            this.info = info;
            this.members = members;
        }
    }

    private final Map<Entity, EntitySnapshot> snapshots = new IdentityHashMap<>();
    /** Entities loaded from the scene file, in scene order. */
    private final List<Entity> sceneEntities = new ArrayList<>();
    private final List<SpawnRecord> spawns = new ArrayList<>();

    void captureScene(List<Entity> entities) {
        clear();
        for (Entity e : entities) {
            if (e == null) continue;
            sceneEntities.add(e);
            snapshots.put(e, new EntitySnapshot(e));
        }
    }

    void recordSpawn(SceneState.Spawn info, List<Entity> members) {
        List<Entity> kept = new ArrayList<>();
        for (Entity e : members) {
            if (e == null || snapshots.containsKey(e)) continue;
            kept.add(e);
            snapshots.put(e, new EntitySnapshot(e));
            info.ids.add(e.id);
        }
        if (!kept.isEmpty()) spawns.add(new SpawnRecord(info, kept));
    }

    EntitySnapshot snapshotOf(Entity e) { return snapshots.get(e); }

    List<Entity> sceneEntities() { return sceneEntities; }

    List<SpawnRecord> spawns() { return spawns; }

    void clear() {
        snapshots.clear();
        sceneEntities.clear();
        spawns.clear();
    }

    /** The name a component is saved under; the same name {@link ComponentSchema#forSerializedName} finds it by. */
    static String typeName(Component c) {
        return c.getClass().getSimpleName();
    }
}
