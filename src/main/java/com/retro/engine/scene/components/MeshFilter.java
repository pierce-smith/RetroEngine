package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;

/**
 * Links an Entity to its 3D geometry asset handle with RenderWare 3.5+ Multi-Atomic Clump support
 * (RpClump / RpAtomic parity). Allows a single Entity container to hold multi-part sub-meshes
 * sharing unified parent transform nodes.
 */
public class MeshFilter implements Component {

    @InspectorProperty(label = "PRIMARY MESH", type = PropertyType.ASSET_PICKER, assetType = "MESH_3D")
    public String meshAssetPath;

    @InspectorProperty(label = "VISIBLE", type = PropertyType.TOGGLE)
    public boolean isVisible = true;

    @InspectorProperty(label = "CLUMP AGGREGATION", type = PropertyType.TOGGLE)
    public boolean isClump = false;

    public String[] subMeshPaths = new String[0];

    public MeshFilter(String meshAssetPath) {
        this.meshAssetPath = meshAssetPath;
    }

    public MeshFilter(String meshAssetPath, String[] subMeshPaths) {
        this.meshAssetPath = meshAssetPath;
        if (subMeshPaths != null && subMeshPaths.length > 0) {
            this.subMeshPaths = subMeshPaths;
            this.isClump = true;
        }
    }

    @Override
    public String getComponentName() {
        return "Mesh Renderer";
    }
}
