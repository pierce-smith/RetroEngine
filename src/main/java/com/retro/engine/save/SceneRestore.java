package com.retro.engine.save;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a freshly loaded level back into a saved {@link SceneState}: removes destroyed entities, spawns again what was
 * spawned during play (under the same ids), and applies changed names, parents and component properties. Script state
 * can only go into running scripts, so it is handed back for {@link PendingScripts} to apply after each {@code onStart}.
 * Runs after the scene's play snapshot and before physics and scripts start, so the level starts in the saved state.
 */
final class SceneRestore {

    private SceneRestore() {}

    static void apply(SaveWorld world, SceneState state, SceneBaseline baseline, PendingScripts pending) {
        for (Entity e : new ArrayList<>(world.entities())) {
            if (e != null && e.id != null && state.destroyedIds.contains(e.id)) world.removeEntity(e);
        }

        for (SceneState.Spawn spawn : state.spawns) respawn(world, spawn, state, baseline);

        for (SceneState.EntityChange change : state.entities) {
            Entity e = world.findEntityById(change.id);
            if (e == null) continue; // Removed from the scene file since the save was made
            if (change.name != null) e.name = change.name;
            if (change.parentId != null) {
                Entity parent = change.parentId.isEmpty() ? null : world.findEntityById(change.parentId);
                if (parent != null || change.parentId.isEmpty()) world.setParent(e, parent);
            }
            if (!change.components.isEmpty()) {
                applyComponents(e, change.components, world);
                world.refreshComponents(e);
            }
            if (!change.scripts.isEmpty()) pending.put(change.id, change.scripts);
        }
    }

    private static void respawn(SaveWorld world, SceneState.Spawn spawn, SceneState state, SceneBaseline baseline) {
        List<Entity> created;
        if (spawn.prefabPath != null) {
            created = world.instantiatePrefab(spawn.prefabPath);
            if (created.isEmpty()) {
                System.err.println("[SaveGame] Prefab '" + spawn.prefabPath + "' from the save could not be spawned again; its entities are missing.");
                return;
            }
            renameIds(created, spawn.ids);
        } else {
            String id = spawn.ids.isEmpty() ? null : spawn.ids.get(0);
            Entity e = world.spawnEntity(id, spawn.name, spawn.meshPath);
            if (e == null) return;
            created = new ArrayList<>(List.of(e));
        }
        SceneState.Spawn recorded = new SceneState.Spawn();
        recorded.prefabPath = spawn.prefabPath;
        recorded.name = spawn.name;
        recorded.meshPath = spawn.meshPath;
        baseline.recordSpawn(recorded, created);
        for (Entity e : created) {
            if (state.destroyedIds.contains(e.id)) world.removeEntity(e);
        }
    }

    /** Gives a prefab's fresh entities the ids they had when saved, so saved changes and references find them. */
    private static void renameIds(List<Entity> created, List<String> savedIds) {
        Map<String, String> remap = new HashMap<>();
        for (int i = 0; i < created.size() && i < savedIds.size(); i++) remap.put(created.get(i).id, savedIds.get(i));
        if (created.size() != savedIds.size()) {
            System.err.println("[SaveGame] A prefab now has " + created.size() + " entities where the save has " + savedIds.size() + "; matching them in order.");
        }
        for (Entity e : created) {
            String newId = remap.get(e.id);
            if (newId != null) e.id = newId;
            if (e.parentId != null && remap.containsKey(e.parentId)) e.parentId = remap.get(e.parentId);
            List<String> children = e.getChildIds();
            for (int i = 0; i < children.size(); i++) {
                String mapped = remap.get(children.get(i));
                if (mapped != null) children.set(i, mapped);
            }
        }
    }

    private static void applyComponents(Entity e, List<SceneState.ComponentChange> changes, SaveWorld world) {
        // Resolve CHANGED / REMOVED targets against the entity as loaded, before anything is added or removed
        List<Component> toRemove = new ArrayList<>();
        List<Object[]> toChange = new ArrayList<>();
        for (SceneState.ComponentChange cc : changes) {
            if (cc.kind == SceneState.ComponentChange.Kind.ADDED) continue;
            Component target = nthOfType(e, cc.type, cc.index);
            if (target == null) continue;
            if (cc.kind == SceneState.ComponentChange.Kind.REMOVED) toRemove.add(target);
            else toChange.add(new Object[]{target, cc});
        }
        for (Object[] pair : toChange) setProperties((Component) pair[0], (SceneState.ComponentChange) pair[1], world);
        for (Component c : toRemove) e.removeComponentInstance(c);
        for (SceneState.ComponentChange cc : changes) {
            if (cc.kind != SceneState.ComponentChange.Kind.ADDED) continue;
            ComponentSchema schema = ComponentSchema.forSerializedName(cc.type);
            Component c = schema != null ? schema.newInstance() : null;
            if (c == null) {
                System.err.println("[SaveGame] Component type '" + cc.type + "' from the save no longer exists; not added to '" + e.name + "'.");
                continue;
            }
            setProperties(c, cc, world);
            e.addComponent(c);
        }
    }

    private static void setProperties(Component c, SceneState.ComponentChange cc, SaveWorld world) {
        ComponentSchema schema = ComponentSchema.of(c.getClass());
        for (ComponentSchema.Property p : schema.serializedProperties()) {
            if (!cc.properties.containsKey(p.name())) continue;
            ComponentValues.write(p, c, cc.properties.get(p.name()), world::resolveAssetPath);
        }
    }

    private static Component nthOfType(Entity e, String type, int index) {
        int seen = 0;
        for (Component c : e.getAllComponents()) {
            if (c == null || !SceneBaseline.typeName(c).equals(type)) continue;
            if (!ComponentSchema.of(c.getClass()).isPersistent()) continue;
            if (seen++ == index) return c;
        }
        return null;
    }
}
