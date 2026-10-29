package com.retro.engine.scene.world;

import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.entity.ComponentPool;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntityHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the 64-bit generational entity handle registry and the per-class ComponentPool storage.
 * A slot's generation is bumped whenever its entity is released, so handles to deleted or cleared entities stop
 * validating even after the slot is recycled. Registering an entity also mirrors its components into the pools.
 */
public final class EntityManager {

    private final List<Entity> entitySlots = new ArrayList<>();
    private final List<Integer> entityGenerations = new ArrayList<>();
    private final Map<Class<?>, ComponentPool<?>> componentPools = new HashMap<>();

    // --- GENERATIONAL HANDLE REGISTRY ---

    /** Places the entity in the first free slot (or a new one), stamps its handle and syncs its components to the pools. */
    public EntityHandle registerEntityHandle(Entity entity) {
        if (entity == null) return EntityHandle.NULL;

        int slotIndex = -1;
        for (int i = 0; i < entitySlots.size(); i++) {
            if (entitySlots.get(i) == null) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex == -1) {
            slotIndex = entitySlots.size();
            entitySlots.add(entity);
            entityGenerations.add(1);
        } else {
            entitySlots.set(slotIndex, entity);
        }

        int gen = entityGenerations.get(slotIndex);
        EntityHandle handle = new EntityHandle(slotIndex, gen);
        entity.setHandle(handle);

        syncEntityToPools(entity);
        return handle;
    }

    public void unregisterEntityHandle(Entity entity) {
        if (entity == null || entity.handle == null || !entity.handle.isValid()) return;
        int idx = entity.handle.index();
        if (idx >= 0 && idx < entitySlots.size()) {
            entitySlots.set(idx, null);
            entityGenerations.set(idx, entityGenerations.get(idx) + 1);
        }
        removeEntityFromPools(entity);
        entity.setHandle(EntityHandle.NULL);
    }

    public boolean isValidHandle(EntityHandle handle) {
        if (handle == null || !handle.isValid()) return false;
        int idx = handle.index();
        if (idx < 0 || idx >= entitySlots.size()) return false;
        return entityGenerations.get(idx) == handle.generation() && entitySlots.get(idx) != null;
    }

    public Entity getEntityByHandle(EntityHandle handle) {
        if (!isValidHandle(handle)) return null;
        return entitySlots.get(handle.index());
    }

    /**
     * Releases every occupied slot (bumping its generation) and empties every component pool. Entities keep their
     * stale handle values, which no longer validate.
     */
    public void clear() {
        for (int i = 0; i < entitySlots.size(); i++) {
            if (entitySlots.get(i) != null) {
                entityGenerations.set(i, entityGenerations.get(i) + 1);
                entitySlots.set(i, null);
            }
        }

        for (ComponentPool<?> pool : componentPools.values()) {
            pool.clear();
        }
    }

    // --- COMPONENT POOL ROUTING ---

    @SuppressWarnings("unchecked")
    public <T extends Component> ComponentPool<T> getComponentPool(Class<T> clazz) {
        if (clazz == null) return null;
        return (ComponentPool<T>) componentPools.computeIfAbsent(clazz, k -> new ComponentPool<>(clazz, 256));
    }

    public void syncEntityToPools(Entity entity) {
        if (entity == null || entity.id == null) return;
        for (Component comp : entity.getAllComponents()) {
            if (comp != null) {
                syncComponentToPool(entity.id, comp);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Component> void syncComponentToPool(String entityId, T component) {
        Class<T> clazz = (Class<T>) component.getClass();
        ComponentPool<T> pool = getComponentPool(clazz);
        if (pool != null) {
            pool.add(entityId, component);
        }
    }

    public void removeComponentFromPool(Entity entity, Component component) {
        if (entity == null || entity.id == null || component == null) return;
        ComponentPool<?> pool = componentPools.get(component.getClass());
        if (pool != null && pool.get(entity.id) == component) {
            pool.remove(entity.id);
        }
    }

    public void removeEntityFromPools(Entity entity) {
        if (entity == null || entity.id == null) return;
        for (ComponentPool<?> pool : componentPools.values()) {
            pool.remove(entity.id);
        }
    }
}
