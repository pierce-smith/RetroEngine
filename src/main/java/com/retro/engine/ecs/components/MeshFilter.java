package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Links an Entity to its 3D geometry asset handle with RenderWare 3.5+ Multi-Atomic Clump support
 * (RpClump / RpAtomic parity). Allows a single Entity container to hold multi-part sub-meshes
 * sharing unified parent transform nodes.
 */
@Component.LegacyNames("MeshRendererComponent")
public class MeshFilter extends Component {

    @InspectorProperty(order = 0, label = "PRIMARY MESH", type = PropertyType.ASSET_PICKER, assetType = "MESH_3D")
    public String meshAssetPath;

    @InspectorProperty(order = 1, label = "VISIBLE", type = PropertyType.TOGGLE)
    public boolean isVisible = true;

    @InspectorProperty(order = 2, label = "CLUMP AGGREGATION", type = PropertyType.TOGGLE, serialized = false)
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

    // Runtime-only local AABB of the last drawn geometry for this path (the editor gizmo plants itself on its base)
    private transient float[] drawnLocalBounds;
    private transient String drawnBoundsKey;

    public void cacheDrawnBounds(String path, float[] localBounds) {
        this.drawnBoundsKey = path;
        this.drawnLocalBounds = localBounds;
    }

    /** Local {minX, minY, minZ, maxX, maxY, maxZ} of the drawn mesh, or null until the current mesh has rendered. */
    public float[] getDrawnLocalBounds() {
        return (drawnLocalBounds != null && meshAssetPath != null && meshAssetPath.equals(drawnBoundsKey)) ? drawnLocalBounds : null;
    }

    public MeshFilter() {}

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
    public void onDeserialized(Entity owner) {
        // Renderable entities always carry a Material (mirrors Entity.setMeshAssetPath)
        if (owner.getComponent(Material.class) == null) {
            owner.addComponent(new Material());
        }
    }

    @Override
    public String getComponentName() {
        return "Mesh Renderer";
    }
}
