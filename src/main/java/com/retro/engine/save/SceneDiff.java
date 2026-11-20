package com.retro.engine.save;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Works out what changed in the running level since it was loaded: the {@link SceneState} a save stores. */
final class SceneDiff {

    private SceneDiff() {}

    static SceneState capture(List<Entity> live, SceneBaseline baseline) {
        SceneState state = new SceneState();
        Map<Entity, Boolean> alive = new IdentityHashMap<>();
        for (Entity e : live) if (e != null) alive.put(e, Boolean.TRUE);

        for (Entity e : baseline.sceneEntities()) {
            if (!alive.containsKey(e)) state.destroyedIds.add(baseline.snapshotOf(e).id);
        }
        for (SceneBaseline.SpawnRecord spawn : baseline.spawns()) {
            boolean anyAlive = false;
            for (Entity e : spawn.members) {
                if (alive.containsKey(e)) anyAlive = true;
                else state.destroyedIds.add(baseline.snapshotOf(e).id);
            }
            if (anyAlive) {
                state.spawns.add(spawn.info);
            } else {
                // Spawned and destroyed between two saves: leave no trace in the file
                for (Entity e : spawn.members) state.destroyedIds.remove(baseline.snapshotOf(e).id);
            }
        }

        for (Entity e : live) {
            if (e == null) continue;
            SceneBaseline.EntitySnapshot snap = baseline.snapshotOf(e);
            if (snap == null) continue; // Created outside the spawn API (editor tools, mods); not part of the level's saved state
            SceneState.EntityChange change = diffEntity(e, snap, baseline);
            change.scripts.addAll(ScriptStateCodec.capture(e));
            if (!change.isEmpty()) state.entities.add(change);
        }
        return state;
    }

    private static SceneState.EntityChange diffEntity(Entity e, SceneBaseline.EntitySnapshot snap, SceneBaseline baseline) {
        SceneState.EntityChange change = new SceneState.EntityChange();
        change.id = snap.id;
        if (!Objects.equals(e.name, snap.name)) change.name = e.name != null ? e.name : "";
        if (!Objects.equals(emptyToNull(e.parentId), emptyToNull(snap.parentId))) {
            change.parentId = e.parentId != null ? e.parentId : "";
        }

        Map<Component, SceneBaseline.ComponentSnapshot> before = new IdentityHashMap<>();
        for (SceneBaseline.ComponentSnapshot cs : snap.components) before.put(cs.instance, cs);

        Map<String, Integer> addedSeen = new HashMap<>();
        for (Component c : e.getAllComponents()) {
            if (c == null) continue;
            ComponentSchema schema = ComponentSchema.of(c.getClass());
            if (!schema.isPersistent()) continue;
            SceneBaseline.ComponentSnapshot cs = before.remove(c);
            if (cs != null) {
                SceneState.ComponentChange cc = new SceneState.ComponentChange();
                cc.kind = SceneState.ComponentChange.Kind.CHANGED;
                cc.type = cs.type;
                cc.index = cs.index;
                List<ComponentSchema.Property> props = cs.schema.serializedProperties();
                for (int i = 0; i < props.size(); i++) {
                    Object now = ComponentValues.read(props.get(i), c);
                    if (!ComponentValues.same(now, cs.values[i])) cc.properties.put(props.get(i).name(), now);
                }
                if (!cc.properties.isEmpty()) change.components.add(cc);
            } else {
                SceneState.ComponentChange cc = new SceneState.ComponentChange();
                cc.kind = SceneState.ComponentChange.Kind.ADDED;
                cc.type = SceneBaseline.typeName(c);
                cc.index = addedSeen.merge(cc.type, 1, Integer::sum) - 1;
                for (ComponentSchema.Property p : schema.serializedProperties()) cc.properties.put(p.name(), ComponentValues.read(p, c));
                change.components.add(cc);
            }
        }
        for (SceneBaseline.ComponentSnapshot gone : before.values()) {
            SceneState.ComponentChange cc = new SceneState.ComponentChange();
            cc.kind = SceneState.ComponentChange.Kind.REMOVED;
            cc.type = gone.type;
            cc.index = gone.index;
            change.components.add(cc);
        }
        return change;
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
