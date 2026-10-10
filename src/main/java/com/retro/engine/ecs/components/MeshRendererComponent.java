package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;

/**
 * Links a SceneEntity to its 3D geometry asset handle.
 */
public class MeshRendererComponent implements Component {
    public String meshAssetPath;
    public boolean isVisible = true;

    public MeshRendererComponent(String meshAssetPath) {
        this.meshAssetPath = meshAssetPath;
    }

    @Override
    public String getComponentName() {
        return "Mesh Renderer";
    }
}
