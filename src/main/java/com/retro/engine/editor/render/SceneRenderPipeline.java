package com.retro.engine.editor.render;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.dock.DockPanel;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.transform.Transform;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level render pipeline processing 3D scene entities, view-frustum culling,
 * light state, GPU skeletal animation bone palette extraction, and downward-raycast ground shadows
 * through a vendor-agnostic IRenderBackend contract.
 * Fully decoupled from EditorWorkspace via thread-safe RenderSceneSnapshot structures with zero per-frame heap allocations
 * using an auto-expanding descriptor pool and pooled LightDescriptor state.
 * Geometry and textures are referenced by opaque GPU handles registered through AssetStreamer;
 * no vertex or pixel arrays cross the RHI boundary per frame.
 */
public class SceneRenderPipeline {

    public record LightDescriptor(
            int lightType,
            float posX, float posY, float posZ,
            float dirX, float dirY, float dirZ,
            float r, float g, float b,
            float intensity
    ) {}

    public static class MutableLightDescriptor {
        public int lightType;
        public float posX, posY, posZ;
        public float dirX, dirY, dirZ;
        public float r, g, b;
        public float intensity;
        public boolean active = false;

        public void set(int lightType, float posX, float posY, float posZ,
                        float dirX, float dirY, float dirZ,
                        float r, float g, float b, float intensity) {
            this.lightType = lightType;
            this.posX = posX; this.posY = posY; this.posZ = posZ;
            this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
            this.r = r; this.g = g; this.b = b;
            this.intensity = intensity;
            this.active = true;
        }

        public void reset() {
            this.active = false;
        }
    }

    public record RenderEntityDescriptor(
            long meshHandle,
            long textureHandle,
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz,
            float groundY,
            float reflectVal,
            float[][] bonePalette
    ) {}

    public static class MutableRenderEntityDescriptor {
        public long meshHandle;
        public long textureHandle;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;
        public float groundY;
        public float reflectVal;
        public float[][] bonePalette;

        public void set(long meshHandle, long textureHandle, float px, float py, float pz,
                        float rx, float ry, float rz, float sx, float sy, float sz,
                        float groundY, float reflectVal, float[][] bonePalette) {
            this.meshHandle = meshHandle;
            this.textureHandle = textureHandle;
            this.px = px; this.py = py; this.pz = pz;
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.groundY = groundY;
            this.reflectVal = reflectVal;
            this.bonePalette = bonePalette;
        }
    }

    public static class RenderSceneSnapshot {
        public LightDescriptor activeLight = null;
        public final MutableLightDescriptor pooledLight = new MutableLightDescriptor();
        public final List<RenderEntityDescriptor> entities = new ArrayList<>();
        public final List<MutableRenderEntityDescriptor> pooledEntities = new ArrayList<>();
        public int activeEntityCount = 0;
        public boolean gridVisible = true;
        public float[] gridVertices = null;
        public int gridVertexCount = 0;
        public float[] uiVertices = null;
        public int uiVertexCount = 0;
        public int totalDrawCalls = 0;
        public int totalActiveVertices = 0;
        // Docked Scene tile (screen NDC) the offscreen 3D image is composited into; empty when hidden
        public float sceneMinX = -0.55f, sceneMinY = -0.50f, sceneMaxX = 0.55f, sceneMaxY = 0.88f;
        public float sceneAspect = 712.0f / 488.0f;

        public RenderSceneSnapshot() {
            for (int i = 0; i < 1024; i++) {
                pooledEntities.add(new MutableRenderEntityDescriptor());
            }
        }

        public MutableRenderEntityDescriptor obtainDescriptor(int index) {
            while (pooledEntities.size() <= index) {
                pooledEntities.add(new MutableRenderEntityDescriptor());
            }
            return pooledEntities.get(index);
        }

        public void reset() {
            activeLight = null;
            pooledLight.reset();
            entities.clear();
            activeEntityCount = 0;
            gridVisible = true;
            gridVertices = null;
            gridVertexCount = 0;
            uiVertices = null;
            uiVertexCount = 0;
            totalDrawCalls = 0;
            totalActiveVertices = 0;
        }
    }

    // Double-buffered persistent snapshots to avoid per-frame heap allocations
    private final RenderSceneSnapshot[] doubleBufferedSnapshots = new RenderSceneSnapshot[]{
            new RenderSceneSnapshot(), new RenderSceneSnapshot()
    };
    private int snapshotBufferIndex = 0;

    private static final String FALLBACK_CUBE_KEY = "[FALLBACK_CUBE]";
    private final MeshBufferData fallbackCube = MeshImporter.createDefaultCube();
    private final FrustumCuller frustumCuller = new FrustumCuller();

    /**
     * Extracts a decoupled, thread-safe RenderSceneSnapshot from the editor scene graph with zero heap allocations.
     */
    public RenderSceneSnapshot extractSnapshot(EditorWorkspace editorManager, AssetStreamer streamingService) {
        snapshotBufferIndex = (snapshotBufferIndex + 1) % 2;
        RenderSceneSnapshot snapshot = doubleBufferedSnapshots[snapshotBufferIndex];
        snapshot.reset();

        if (editorManager == null || streamingService == null) return snapshot;

        streamingService.processCompletedUploads();

        ViewportCamera camera = editorManager.getCameraController();
        if (camera != null) {
            frustumCuller.updateFromCamera(
                    camera.getPanX(), camera.getPanY(), camera.getZoom(),
                    camera.getPitch(), camera.getYaw(),
                    50.0f, DockSpace.getSceneAspect(), 0.1f, 100.0f
            );
        }

        List<Entity> entities = editorManager.getSceneEntities();
        int handleEpoch = streamingService.getHandleEpoch();
        int totalActiveVertices = 0;
        int entityDrawCalls = 0;

        if (entities != null && !entities.isEmpty()) {

            // 1. Scan and pass active Light component state to snapshot using pooled light descriptor
            for (Entity entity : entities) {
                Light light = entity.getComponent(Light.class);
                Transform lightTransform = entity.getTransform();

                if (light != null && lightTransform != null) {
                    WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);
                    float posX = wt.px();
                    float posY = wt.py();
                    float posZ = wt.pz();

                    float radPitch = (float) Math.toRadians(lightTransform.rotX);
                    float radYaw = (float) Math.toRadians(lightTransform.rotY);

                    float dirX = (float) (Math.sin(radYaw) * Math.cos(radPitch));
                    float dirY = (float) (-Math.sin(radPitch));
                    float dirZ = (float) (-Math.cos(radYaw) * Math.cos(radPitch));

                    int lightType = switch (light.type) {
                        case DIRECTIONAL -> 0;
                        case POINT -> 1;
                        case SPOT -> 2;
                    };

                    snapshot.pooledLight.set(
                            lightType, posX, posY, posZ, dirX, dirY, dirZ,
                            light.r, light.g, light.b, light.intensity
                    );
                    break;
                }
            }

            // 2. Traversal loop across active 3D entities with View-Frustum Culling & Skeletal Palette Extraction
            for (Entity entity : entities) {
                Transform tc = entity.getComponent(Transform.class);
                MeshFilter mc = entity.getComponent(MeshFilter.class);
                Material mat = entity.getComponent(Material.class);
                AnimatorComponent anim = entity.getComponent(AnimatorComponent.class);

                if (tc == null || mc == null) continue;
                if (!mc.isVisible) continue;

                WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);

                // View-Frustum Culling Test (Bounding Sphere check)
                float scaleRadius = Math.max(Math.abs(wt.sx()), Math.max(Math.abs(wt.sy()), Math.abs(wt.sz())));
                float sphereRadius = Math.max(scaleRadius * 1.5f, 1.0f);
                if (!frustumCuller.isSphereVisible(wt.px(), wt.py(), wt.pz(), sphereRadius)) {
                    continue;
                }

                String meshPath = mc.meshAssetPath;
                String texturePath = (mat != null) ? mat.textureAssetPath : null;

                if (meshPath != null) {
                    if (!streamingService.isMeshLoaded(meshPath)) {
                        streamingService.requestModelAsync(meshPath);
                    }

                    if (texturePath != null && !streamingService.isTextureLoaded(texturePath)) {
                        streamingService.requestTextureAsync(texturePath);
                    }

                    MeshBufferData model = streamingService.getModel(meshPath);

                    long meshHandle;
                    int vertexCountToSubmit;

                    if (model != null && model.vertices != null && model.vertices.length > 0) {
                        meshHandle = mc.getCachedMeshHandle(meshPath, handleEpoch);
                        if (meshHandle == 0L) {
                            meshHandle = streamingService.getMeshHandle(meshPath);
                            mc.cacheMeshHandle(meshPath, meshHandle, handleEpoch);
                        }
                        vertexCountToSubmit = model.vertexCount;
                    } else if (streamingService.isMeshLoaded(meshPath)) {
                        meshHandle = streamingService.resolveMeshHandle(FALLBACK_CUBE_KEY, fallbackCube);
                        vertexCountToSubmit = fallbackCube.vertexCount;
                    } else {
                        continue;
                    }

                    // 0L handles (headless/null backends) are still submitted so entity counts stay consistent
                    long textureHandle = 0L;
                    if (texturePath != null && mat != null) {
                        textureHandle = mat.getCachedTextureHandle(texturePath, handleEpoch);
                        if (textureHandle == 0L) {
                            textureHandle = streamingService.getTextureHandle(texturePath);
                            mat.cacheTextureHandle(texturePath, textureHandle, handleEpoch);
                        }
                    }

                    totalActiveVertices += vertexCountToSubmit;
                    entityDrawCalls++;

                    float groundY = findGroundSurfaceY(entity, wt, entities, editorManager);
                    float reflectVal = (mat != null) ? mat.reflect : 0.0f;
                    float[][] bonePalette = (anim != null && anim.skeleton != null) ? anim.bonePaletteMatrices : null;

                    MutableRenderEntityDescriptor desc = snapshot.obtainDescriptor(snapshot.activeEntityCount);
                    desc.set(
                            meshHandle, textureHandle,
                            wt.px(), wt.py(), wt.pz(),
                            wt.rx(), wt.ry(), wt.rz(),
                            wt.sx(), wt.sy(), wt.sz(),
                            groundY, reflectVal, bonePalette
                    );
                    snapshot.activeEntityCount++;
                }
            }
        }

        snapshot.gridVisible = editorManager.getGizmoStateController().is3DGridVisible();
        snapshot.gridVertices = editorManager.getGizmoStateController().get3DGridVertices();
        snapshot.gridVertexCount = editorManager.getGizmoStateController().get3DGridVertexCount();
        snapshot.uiVertices = editorManager.getUiVertices();
        snapshot.uiVertexCount = editorManager.getUiVertexCount();

        if (DockSpace.isSceneViewportVisible()) {
            snapshot.sceneMinX = DockSpace.getContentX0(DockPanel.SCENE);
            snapshot.sceneMinY = DockSpace.getContentY0(DockPanel.SCENE);
            snapshot.sceneMaxX = DockSpace.getContentX1(DockPanel.SCENE);
            snapshot.sceneMaxY = DockSpace.getContentY1(DockPanel.SCENE);
        } else {
            snapshot.sceneMinX = snapshot.sceneMinY = snapshot.sceneMaxX = snapshot.sceneMaxY = 0.0f;
        }
        snapshot.sceneAspect = DockSpace.getSceneAspect();

        if (snapshot.gridVisible) {
            totalActiveVertices += snapshot.gridVertexCount;
        }

        snapshot.totalDrawCalls = entityDrawCalls + (snapshot.gridVisible ? 1 : 0) + 2;
        snapshot.totalActiveVertices = totalActiveVertices;

        return snapshot;
    }

    /**
     * Submits a decoupled RenderSceneSnapshot directly to RHI without touching EditorWorkspace.
     */
    public void submitFrame(RenderSceneSnapshot snapshot, IRenderBackend rhi) {
        if (snapshot == null || rhi == null) return;

        rhi.clearSceneQueue();

        if (snapshot.pooledLight.active) {
            MutableLightDescriptor l = snapshot.pooledLight;
            rhi.setSceneLight(
                    l.lightType, l.posX, l.posY, l.posZ,
                    l.dirX, l.dirY, l.dirZ,
                    l.r, l.g, l.b, l.intensity
            );
        } else if (snapshot.activeLight != null) {
            LightDescriptor l = snapshot.activeLight;
            rhi.setSceneLight(
                    l.lightType(), l.posX(), l.posY(), l.posZ(),
                    l.dirX(), l.dirY(), l.dirZ(),
                    l.r(), l.g(), l.b(), l.intensity()
            );
        }

        for (int i = 0; i < snapshot.activeEntityCount; i++) {
            MutableRenderEntityDescriptor e = snapshot.pooledEntities.get(i);
            rhi.submitEntity(
                    e.meshHandle, e.textureHandle,
                    e.px, e.py, e.pz, e.rx, e.ry, e.rz, e.sx, e.sy, e.sz,
                    e.groundY, e.reflectVal, e.bonePalette
            );
        }

        for (RenderEntityDescriptor e : snapshot.entities) {
            rhi.submitEntity(
                    e.meshHandle(), e.textureHandle(),
                    e.px(), e.py(), e.pz(), e.rx(), e.ry(), e.rz(), e.sx(), e.sy(), e.sz(),
                    e.groundY(), e.reflectVal(), e.bonePalette()
            );
        }

        rhi.setSceneViewport(snapshot.sceneMinX, snapshot.sceneMinY, snapshot.sceneMaxX, snapshot.sceneMaxY, snapshot.sceneAspect);
        rhi.updateGridMesh(snapshot.gridVertices, snapshot.gridVertexCount);
        rhi.updateUiMesh(snapshot.uiVertices, snapshot.uiVertexCount);

        EngineConsolePanel.updateTelemetry(snapshot.totalDrawCalls, snapshot.totalActiveVertices);
    }

    /**
     * Backward-compatible submitFrame overload extracting a snapshot and submitting to RHI.
     */
    public void submitFrame(EditorWorkspace editorManager, AssetStreamer streamingService, IRenderBackend rhi) {
        RenderSceneSnapshot snapshot = extractSnapshot(editorManager, streamingService);
        submitFrame(snapshot, rhi);
    }

    private float findGroundSurfaceY(Entity currentEntity, WorldTransform currentWt, List<Entity> allEntities, EditorWorkspace workspace) {
        float rayX = currentWt.px();
        float rayY = currentWt.py();
        float rayZ = currentWt.pz();
        float bestGroundY = 0.0f;

        if (allEntities != null) {
            for (Entity other : allEntities) {
                if (other == currentEntity) continue;
                MeshFilter otherMc = other.getComponent(MeshFilter.class);
                if (otherMc == null || !otherMc.isVisible) continue;

                WorldTransform otherWt = workspace.getSceneManager().getComputedWorldTransform(other);
                float otherX = otherWt.px();
                float otherY = otherWt.py();
                float otherZ = otherWt.pz();
                float otherSx = Math.max(0.2f, otherWt.sx());
                float otherSy = Math.max(0.2f, otherWt.sy());
                float otherSz = Math.max(0.2f, otherWt.sz());

                float dx = rayX - otherX;
                float dz = rayZ - otherZ;
                float radiusSq = dx * dx + dz * dz;
                float maxRadius = Math.max(otherSx, otherSz) * 1.1f;

                if (radiusSq <= maxRadius * maxRadius) {
                    float topY = otherY + otherSy;
                    if (topY < rayY + 0.05f && topY > bestGroundY) {
                        bestGroundY = topY;
                    }
                }
            }
        }
        return bestGroundY;
    }
}
