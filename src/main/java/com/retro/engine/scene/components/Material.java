package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;
import com.retro.engine.editor.ui.InspectorProperty.PropertyType;

/**
 * Links an Entity to its shader and 2D texture resources.
 */
public class Material implements Component {

    @InspectorProperty(label = "TEXTURE", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String textureAssetPath;

    @InspectorProperty(label = "TEX NAME", type = PropertyType.TEXT)
    public String textureName = "DEFAULT_CHECKER";

    @InspectorProperty(label = "SHADER", type = PropertyType.TEXT)
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
