package com.retro.engine.ecs.serialization;

import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of the edit scene taken when Play is pressed, restored in place when Stop is pressed.
 *
 * <p>The snapshot covers everything the scene file stores, so Stop puts the scene back exactly as it would load:
 * <ul>
 *   <li>entity membership and order (entities spawned during play are dropped, deleted ones come back),</li>
 *   <li>each entity's name, parent, child list and editor flags,</li>
 *   <li>the exact component instances attached, in order (components added or removed during play are undone),</li>
 *   <li>every persisted property of every component, read through {@link ComponentSchema}, so new or mod
 *       components are covered without changes here.</li>
 * </ul>
 * Runtime-only physics state (controller velocities, vehicle speed and wheel contacts) is zeroed, playing audio
 * is stopped, and animator playback and parameter values are put back.
 *
 * <p>Backups are pooled and reused across Play sessions. Capturing still boxes property values, which is fine:
 * it runs once per Play press, never per frame.
 */
public class EntityStateBackupManager {

    /** Pre-Play state of one entity. */
    private static final class EntityBackup {
        Entity entity;
        String id, name, parentId;
        boolean selected, expandedInHierarchy;
        final List<String> childIds = new ArrayList<>();
        final List<Component> components = new ArrayList<>();
        Object[] values = new Object[64];
        int valueCount;

        void capture(Entity e) {
            entity = e;
            id = e.id;
            name = e.name;
            parentId = e.parentId;
            selected = e.selected;
            expandedInHierarchy = e.expandedInHierarchy;
            childIds.clear();
            childIds.addAll(e.getChildIds());
            components.clear();
            components.addAll(e.getAllComponents());

            valueCount = 0;
            for (int i = 0; i < components.size(); i++) {
                Component comp = components.get(i);
                for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).serializedProperties()) {
                    push(copyValue(read(p, comp)));
                }
                if (comp instanceof AnimatorComponent anim) captureAnimator(anim);
            }
        }

        void restore() {
            Entity e = entity;
            e.id = id;
            e.name = name;
            e.parentId = parentId;
            e.selected = selected;
            e.expandedInHierarchy = expandedInHierarchy;
            List<String> liveChildren = e.getChildIds();
            liveChildren.clear();
            liveChildren.addAll(childIds);

            List<Component> live = e.getAllComponents();
            for (int i = 0; i < live.size(); i++) {
                Component comp = live.get(i);
                if (!components.contains(comp) && comp instanceof AudioSource audio) audio.dispose();
            }
            live.clear();
            live.addAll(components);

            int k = 0;
            for (int i = 0; i < components.size(); i++) {
                Component comp = components.get(i);
                for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).serializedProperties()) {
                    write(p, comp, copyValue(values[k++]));
                }
                if (comp instanceof AnimatorComponent anim) k = restoreAnimator(anim, k);
                resetRuntimeState(comp);
            }
        }

        private void captureAnimator(AnimatorComponent anim) {
            push(anim.activeClipName);
            push(anim.playbackTime);
            push(anim.playbackSpeed);
            push(anim.isPlaying);
            push(anim.parameterValues.size());
            for (AnimatorParameter param : anim.parameterValues.values()) {
                push(param);
                push(param.floatValue);
                push(param.intValue);
                push(param.boolValue);
            }
        }

        private int restoreAnimator(AnimatorComponent anim, int k) {
            anim.activeClipName = (String) values[k++];
            anim.playbackTime = (Float) values[k++];
            anim.playbackSpeed = (Float) values[k++];
            anim.isPlaying = (Boolean) values[k++];
            int count = (Integer) values[k++];
            for (int i = 0; i < count; i++) {
                AnimatorParameter param = (AnimatorParameter) values[k++];
                param.floatValue = (Float) values[k++];
                param.intValue = (Integer) values[k++];
                param.boolValue = (Boolean) values[k++];
            }
            return k;
        }

        private void push(Object value) {
            if (valueCount == values.length) values = Arrays.copyOf(values, valueCount * 2);
            values[valueCount++] = value;
        }

        void clear() {
            entity = null;
            components.clear();
            childIds.clear();
            Arrays.fill(values, 0, valueCount, null);
            valueCount = 0;
        }
    }

    private final List<EntityBackup> pool = new ArrayList<>();
    private final List<Entity> initialEntities = new ArrayList<>();
    private int backupCount = 0;
    private boolean captured = false;

    /** Records the full persisted state of {@code activeEntities}. Call right before entering SIMULATING. */
    public void captureSnapshot(List<Entity> activeEntities) {
        if (activeEntities == null) return;
        initialEntities.clear();
        backupCount = 0;
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            initialEntities.add(entity);
            if (backupCount == pool.size()) pool.add(new EntityBackup());
            pool.get(backupCount++).capture(entity);
        }
        captured = true;
    }

    /**
     * Puts {@code activeEntities} back to the captured state, in place.
     *
     * @return true when entity membership or component sets changed during play, so the caller must rebuild its
     *         entity handles and component pools from the restored list
     */
    public boolean restoreSnapshot(List<Entity> activeEntities) {
        if (activeEntities == null || !captured) return false;
        captured = false;

        boolean structureChanged = activeEntities.size() != initialEntities.size();
        if (!structureChanged) {
            for (int i = 0; i < initialEntities.size(); i++) {
                if (activeEntities.get(i) != initialEntities.get(i)) { structureChanged = true; break; }
            }
        }

        if (structureChanged) {
            // Entities spawned during play own native audio clips nobody will dispose otherwise
            Map<Entity, Boolean> initial = new IdentityHashMap<>();
            for (Entity e : initialEntities) initial.put(e, Boolean.TRUE);
            for (Entity e : activeEntities) {
                if (e != null && !initial.containsKey(e)) disposeAudio(e);
            }
            activeEntities.clear();
            activeEntities.addAll(initialEntities);
        }

        for (int i = 0; i < backupCount; i++) {
            EntityBackup backup = pool.get(i);
            if (!structureChanged && !sameComponents(backup)) structureChanged = true;
            backup.restore();
            backup.clear();
        }
        initialEntities.clear();
        backupCount = 0;
        return structureChanged;
    }

    private static boolean sameComponents(EntityBackup backup) {
        List<Component> live = backup.entity.getAllComponents();
        if (live.size() != backup.components.size()) return false;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) != backup.components.get(i)) return false;
        }
        return true;
    }

    /** Clears simulation-only state that the scene file does not store. */
    private static void resetRuntimeState(Component comp) {
        switch (comp) {
            case Transform t -> t.markDirty();
            case CharacterControllerComponent c -> {
                c.velocityX = 0.0f;
                c.velocityY = 0.0f;
                c.velocityZ = 0.0f;
            }
            case VehicleControllerComponent v -> {
                v.currentSpeed = 0.0f;
                v.currentSteering = 0.0f;
                Arrays.fill(v.wheelGrounded, false);
                Arrays.fill(v.suspensionCompression, 0.0f);
            }
            case AudioSource a -> {
                if (a.state != AudioSource.PlaybackState.PLAYING) a.stop();
            }
            default -> {}
        }
    }

    private static void disposeAudio(Entity e) {
        for (Component c : e.getAllComponents()) {
            if (c instanceof AudioSource audio) audio.dispose();
        }
    }

    /** Mutable property values are copied so play-mode edits to the live object cannot reach the snapshot. */
    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) return new HashMap<>((Map<String, String>) map);
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }

    private static Object read(ComponentSchema.Property p, Component comp) {
        try {
            return p.get(comp);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + comp.getClass().getName() + "." + p.name(), e);
        }
    }

    private static void write(ComponentSchema.Property p, Component comp, Object value) {
        try {
            p.set(comp, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write " + comp.getClass().getName() + "." + p.name(), e);
        }
    }
}
