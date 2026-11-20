package com.retro.engine.save;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.EntityScript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Saved script state waiting for its script to start, keyed by entity id. Each entry is applied once. */
final class PendingScripts {

    private final Map<String, List<SceneState.ScriptState>> byEntity = new HashMap<>();

    void put(String entityId, List<SceneState.ScriptState> states) {
        byEntity.put(entityId.toLowerCase(java.util.Locale.ROOT), new ArrayList<>(states));
    }

    boolean isEmpty() { return byEntity.isEmpty(); }

    void clear() { byEntity.clear(); }

    /** Called right after {@code script.onStart()}. */
    void onScriptStarted(Entity entity, ScriptComponent sc, EntityScript script) {
        if (byEntity.isEmpty() || entity == null || entity.id == null) return;
        String key = entity.id.toLowerCase(java.util.Locale.ROOT);
        List<SceneState.ScriptState> states = byEntity.get(key);
        if (states == null) return;
        int index = entity.getComponents(ScriptComponent.class).indexOf(sc);
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i).index != index) continue;
            ScriptStateCodec.restore(states.remove(i), script, entity);
            break;
        }
        if (states.isEmpty()) byEntity.remove(key);
    }
}
