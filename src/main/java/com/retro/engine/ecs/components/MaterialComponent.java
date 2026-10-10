package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;

/**
 * Links a SceneEntity to its shader and 2D texture resources.
 */
public class MaterialComponent implements Component {
    public String textureAssetPath;
    public String textureName = "DEFAULT_CHECKER";
    public String shaderName = "RETRO_MSL_3D";

    public MaterialComponent() {}

    public MaterialComponent(String textureAssetPath, String textureName) {
        this.textureAssetPath = textureAssetPath;
        this.textureName = textureName;
    }

    @Override
    public String getComponentName() {
        return "Material & Shader";
    }
}
