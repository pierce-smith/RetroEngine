package com.retro.engine.scene;

import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone Entity domain class with 64-bit Generational Entity Handle support
 * and parent-child scene graph hierarchy capabilities.
 */
public class Entity {
    public EntityHandle handle = EntityHandle.NULL;
    public String id;
    public String name;
    public boolean selected = false;
    public String parentId = null;
    public boolean expandedInHierarchy = true;
    private final List<String> childIds = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();

    public Entity(String id, String name, String meshAssetPath) {
        this.id = id;
        this.name = name;
        addComponent(new Transform());
        if (meshAssetPath != null) {
            addComponent(new MeshFilter(meshAssetPath));
            addComponent(new Material());
        }
    }

    public EntityHandle getHandle() { return handle; }
    public void setHandle(EntityHandle handle) { this.handle = (handle != null) ? handle : EntityHandle.NULL; }

    public void addComponent(Component component) {
        if (component != null) {
            components.add(component);
        }
    }

    public <T extends Component> boolean removeComponent(Class<T> componentClass) {
        if (componentClass == null) return false;
        return components.removeIf(componentClass::isInstance);
    }

    @SuppressWarnings("unchecked")
    public <T extends Component> T getComponent(Class<T> componentClass) {
        for (Component c : components) {
            if (componentClass.isInstance(c)) {
                return (T) c;
            }
        }
        return null;
    }

    public List<Component> getAllComponents() {
        return Collections.unmodifiableList(components);
    }

    public Transform getTransform() {
        return getComponent(Transform.class);
    }

    public String getMeshAssetPath() {
        MeshFilter mc = getComponent(MeshFilter.class);
        return (mc != null) ? mc.meshAssetPath : null;
    }

    public void setMeshAssetPath(String path) {
        MeshFilter mc = getComponent(MeshFilter.class);
        if (mc == null && path != null) {
            mc = new MeshFilter(path);
            addComponent(mc);
            if (getComponent(Material.class) == null) {
                addComponent(new Material());
            }
        } else if (mc != null) {
            mc.meshAssetPath = path;
        }
    }

    public void setTextureAsset(String path, String name) {
        Material mat = getComponent(Material.class);
        if (mat == null) {
            mat = new Material(path, name);
            addComponent(mat);
        } else {
            mat.textureAssetPath = path;
            if (name != null) mat.textureName = name;
        }
    }

    // --- Hierarchy Methods ---
    public List<String> getChildIds() {
        return Collections.unmodifiableList(childIds);
    }

    public void addChildId(String childId) {
        if (childId != null && !childId.equals(this.id) && !childIds.contains(childId)) {
            childIds.add(childId);
        }
    }

    public void removeChildId(String childId) {
        if (childId != null) {
            childIds.remove(childId);
            assert (!childIds.contains(childId)) : "[FATAL] Failed to remove child ID " + childId + " from parent " + this.id;
        }
    }

    public boolean hasChild(String childId) {
        return childIds.contains(childId);
    }

    public boolean isDescendantOf(Entity potentialAncestor, List<Entity> allEntities) {
        if (potentialAncestor == null || allEntities == null) return false;
        if (this.parentId == null) return false;
        if (this.parentId.equalsIgnoreCase(potentialAncestor.id)) return true;

        for (Entity e : allEntities) {
            if (e.id.equalsIgnoreCase(this.parentId)) {
                return e.isDescendantOf(potentialAncestor, allEntities);
            }
        }
        return false;
    }
}
