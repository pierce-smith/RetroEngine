package com.retro.engine.ecs;

import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone SceneEntity domain class with parent-child scene graph hierarchy support.
 */
public class SceneEntity {
    public String id;
    public String name;
    public boolean selected = false;
    public String parentId = null;
    public boolean expandedInHierarchy = true;
    private final List<String> childIds = new ArrayList<>();
    private final List<Component> components = new ArrayList<>();

    public SceneEntity(String id, String name, String meshAssetPath) {
        this.id = id;
        this.name = name;
        addComponent(new TransformComponent());
        if (meshAssetPath != null) {
            addComponent(new MeshRendererComponent(meshAssetPath));
            addComponent(new MaterialComponent());
        }
    }

    public void addComponent(Component component) {
        if (component != null) {
            components.add(component);
        }
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

    public TransformComponent getTransform() {
        return getComponent(TransformComponent.class);
    }

    public String getMeshAssetPath() {
        MeshRendererComponent mc = getComponent(MeshRendererComponent.class);
        return (mc != null) ? mc.meshAssetPath : null;
    }

    public void setMeshAssetPath(String path) {
        MeshRendererComponent mc = getComponent(MeshRendererComponent.class);
        if (mc == null && path != null) {
            mc = new MeshRendererComponent(path);
            addComponent(mc);
            if (getComponent(MaterialComponent.class) == null) {
                addComponent(new MaterialComponent());
            }
        } else if (mc != null) {
            mc.meshAssetPath = path;
        }
    }

    public void setTextureAsset(String path, String name) {
        MaterialComponent mat = getComponent(MaterialComponent.class);
        if (mat == null) {
            mat = new MaterialComponent(path, name);
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

    public boolean isDescendantOf(SceneEntity potentialAncestor, List<SceneEntity> allEntities) {
        if (potentialAncestor == null || allEntities == null) return false;
        if (this.parentId == null) return false;
        if (this.parentId.equalsIgnoreCase(potentialAncestor.id)) return true;

        for (SceneEntity e : allEntities) {
            if (e.id.equalsIgnoreCase(this.parentId)) {
                return e.isDescendantOf(potentialAncestor, allEntities);
            }
        }
        return false;
    }
}
