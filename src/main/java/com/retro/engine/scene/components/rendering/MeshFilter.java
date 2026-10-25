package com.retro.engine.scene.components.rendering;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

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

    // Runtime-only GPU handle cache filled by SceneRenderPipeline; never serialized or shown in the inspector
    private transient long gpuMeshHandle = 0L;
    private transient String gpuMeshKey;
    private transient int gpuHandleEpoch;

    /** Returns the cached GPU mesh handle if it was resolved for this path in this epoch, otherwise 0L. */
    public long getCachedMeshHandle(String path, int epoch) {
        return (gpuMeshHandle != 0L && epoch == gpuHandleEpoch && path.equals(gpuMeshKey)) ? gpuMeshHandle : 0L;
    }

    public void cacheMeshHandle(String path, long handle, int epoch) {
        this.gpuMeshKey = path;
        this.gpuMeshHandle = handle;
        this.gpuHandleEpoch = epoch;
    }

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
