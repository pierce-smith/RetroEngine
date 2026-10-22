package com.retro.engine.scene;

import com.retro.engine.scene.components.Component;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Contiguous Dense Component Array Storage Pool for L1/L2 CPU Cache Friendly Iteration.
 * Replaces scattered JVM heap pointer-chasing with dense array packing.
 */
public class ComponentPool<T extends Component> {

    private final Class<T> componentClass;
    private T[] denseComponents;
    private String[] denseEntityIds;
    private final Map<String, Integer> sparseMap = new HashMap<>(256);
    private int size = 0;

    @SuppressWarnings("unchecked")
    public ComponentPool(Class<T> componentClass, int initialCapacity) {
        this.componentClass = componentClass;
        this.denseComponents = (T[]) Array.newInstance(componentClass, Math.max(16, initialCapacity));
        this.denseEntityIds = new String[Math.max(16, initialCapacity)];
    }

    public int getSize() {
        return size;
    }

    public T[] getDenseComponents() {
        return denseComponents;
    }

    public String[] getDenseEntityIds() {
        return denseEntityIds;
    }

    public T get(String entityId) {
        if (entityId == null) return null;
        Integer index = sparseMap.get(entityId);
        if (index == null || index < 0 || index >= size) return null;
        return denseComponents[index];
    }

    public void add(String entityId, T component) {
        if (entityId == null || component == null) return;
        Integer existingIdx = sparseMap.get(entityId);
        if (existingIdx != null) {
            denseComponents[existingIdx] = component;
            return;
        }

        if (size >= denseComponents.length) {
            int newCap = denseComponents.length * 2;
            denseComponents = Arrays.copyOf(denseComponents, newCap);
            denseEntityIds = Arrays.copyOf(denseEntityIds, newCap);
        }

        denseComponents[size] = component;
        denseEntityIds[size] = entityId;
        sparseMap.put(entityId, size);
        size++;
    }

    public boolean remove(String entityId) {
        if (entityId == null) return false;
        Integer index = sparseMap.get(entityId);
        if (index == null) return false;

        int lastIndex = size - 1;
        if (index != lastIndex) {
            // Swap and pop to preserve tight array packing
            T lastComp = denseComponents[lastIndex];
            String lastEntityId = denseEntityIds[lastIndex];

            denseComponents[index] = lastComp;
            denseEntityIds[index] = lastEntityId;
            sparseMap.put(lastEntityId, index);
        }

        denseComponents[lastIndex] = null;
        denseEntityIds[lastIndex] = null;
        sparseMap.remove(entityId);
        size--;
        return true;
    }

    public void clear() {
        Arrays.fill(denseComponents, 0, size, null);
        Arrays.fill(denseEntityIds, 0, size, null);
        sparseMap.clear();
        size = 0;
    }
}
