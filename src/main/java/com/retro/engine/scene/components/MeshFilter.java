package com.retro.engine.scene.components;

import com.retro.engine.scene.Entity;

import com.retro.engine.scene.components.Component;

/**
 * Links a Entity to its 3D geometry asset handle.
 */
public class MeshFilter implements Component {
    public String meshAssetPath;
    public boolean isVisible = true;

    public MeshFilter(String meshAssetPath) {
        this.meshAssetPath = meshAssetPath;
    }

    @Override
    public String getComponentName() {
        return "Mesh Renderer";
    }
}
