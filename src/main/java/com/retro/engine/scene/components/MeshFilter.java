package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;
import com.retro.engine.editor.ui.InspectorProperty.PropertyType;

/**
 * Links an Entity to its 3D geometry asset handle.
 */
public class MeshFilter implements Component {

    @InspectorProperty(label = "MESH PATH", type = PropertyType.ASSET_PICKER, assetType = "MESH_3D")
    public String meshAssetPath;

    @InspectorProperty(label = "VISIBLE", type = PropertyType.TOGGLE)
    public boolean isVisible = true;

    public MeshFilter(String meshAssetPath) {
        this.meshAssetPath = meshAssetPath;
    }

    @Override
    public String getComponentName() {
        return "Mesh Renderer";
    }
}
