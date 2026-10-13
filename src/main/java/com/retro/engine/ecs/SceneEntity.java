package com.retro.engine.ecs;

import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone SceneEntity domain class.
 */
public class SceneEntity {
    public String id;
    public String name;
    public boolean selected = false;

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
        if (mat == null && path != null) {
            mat = new MaterialComponent(path, name);
            addComponent(mat);
        } else if (mat != null) {
            mat.textureAssetPath = path;
            mat.textureName = (name != null) ? name : "CUSTOM";
        }
    }
}
