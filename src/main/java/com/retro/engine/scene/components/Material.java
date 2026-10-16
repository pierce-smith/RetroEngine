package com.retro.engine.scene.components;

import com.retro.engine.scene.Entity;

import com.retro.engine.scene.components.Component;

/**
 * Links a Entity to its shader and 2D texture resources.
 */
public class Material implements Component {
    public String textureAssetPath;
    public String textureName = "DEFAULT_CHECKER";
    public String shaderName = "RETRO_MSL_3D";

    public Material() {}

    public Material(String textureAssetPath, String textureName) {
        this.textureAssetPath = textureAssetPath;
        this.textureName = textureName;
    }

    @Override
    public String getComponentName() {
        return "Material & Shader";
    }
}
