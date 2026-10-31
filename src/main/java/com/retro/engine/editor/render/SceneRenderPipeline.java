package com.retro.engine.editor.render;

import com.retro.engine.editor.gizmos.GizmoRenderer;
import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.batch.UIFont;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.dock.DockPanel;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.editor.ui.panels.ViewportPanel;
import com.retro.engine.render.ui.GameUIRenderer;
import com.retro.engine.rhi.DirectionalShadowFrustum;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.RenderCommand;
import com.retro.engine.rhi.RenderQueue;
import com.retro.engine.rhi.SceneLights;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.world.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.transform.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-level render pipeline processing 3D scene entities, view-frustum culling,
 * light state, GPU skeletal animation bone palette extraction, and downward-raycast ground shadows
 * through a vendor-agnostic IRenderBackend contract.
 * Fully decoupled from EditorWorkspace via thread-safe RenderSceneSnapshot structures with zero per-frame heap allocations:
 * each snapshot owns a pooled, key-sorted RenderQueue of flat RenderCommands plus the fitted shadow light-space matrix.
 * Geometry and textures are referenced by opaque GPU handles registered through AssetStreamer;
 * no vertex or pixel arrays or scene objects cross the RHI boundary per frame.
 */
public class SceneRenderPipeline {

    public static class RenderSceneSnapshot {
        // First Directional, Point and Spot light found in the scene; the lit shaders evaluate all three together
        public final SceneLights lights = new SceneLights();
        // Sorted, flat draw list handed to the backend; owns the fitted shadow light-space matrix
        public final RenderQueue renderQueue = new RenderQueue();
        public boolean gridVisible = true;
        public float[] gridVertices = null;
        public int gridVertexCount = 0;
        public float[] uiVertices = null;
        public int uiVertexCount = 0;
        // Owned storage for the merged [game UI | editor UI] stream, so each snapshot's vertices outlive the next extract
        public float[] uiMergeScratch = new float[0];
        // Gizmo handle triangles for the Color-ID pass (IRenderBackend pick-overlay layout, scene-tile NDC)
        public float[] gizmoPickVertices = new float[0];
        public int gizmoPickVertexCount = 0;
        public int totalDrawCalls = 0;
        public int totalActiveVertices = 0;
        // Docked Scene tile (screen NDC) the offscreen 3D image is composited into; empty when hidden
        public float sceneMinX = -0.55f, sceneMinY = -0.50f, sceneMaxX = 0.55f, sceneMaxY = 0.88f;
        public float sceneAspect = 712.0f / 488.0f;
        // Selected Camera entity's view-projection for the picture-in-picture preview pass
        public boolean cameraPreviewActive = false;
        public final float[] cameraPreviewViewProj = new float[16];
        public float cameraPreviewPosX, cameraPreviewPosY, cameraPreviewPosZ;
        // Play-mode camera entity view (inverse world transform) handed to the backend instead of the orbit pose
        public boolean cameraViewActive = false;
        public final float[] cameraView = new float[16];
        public final float[] cameraEye = new float[3];

        public void reset() {
            lights.reset();
            renderQueue.clear();
            gridVisible = true;
            gridVertices = null;
            gridVertexCount = 0;
            uiVertices = null;
            uiVertexCount = 0;
            totalDrawCalls = 0;
            totalActiveVertices = 0;
            cameraPreviewActive = false;
            cameraViewActive = false;
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
    private final FrustumCuller previewCuller = new FrustumCuller();
    private final GameUIRenderer gameUiRenderer = new GameUIRenderer();
    private final float[] viewScratch = new float[16];

    // Backend camera preview target (0L = unsupported); refreshed every submitFrame
    private long cameraPreviewTexture = 0L;
    // Camera preview matrix scratch (3x3 row-major rotations, 4x4 column-major view / projection)
    // Static: only touched on the main thread (render extraction and ViewportPanel's play-camera sync)
    private static final float[] rotX = new float[9], rotY = new float[9], rotZ = new float[9], rotYX = new float[9], rot = new float[9];
    private final float[] previewView = new float[16], previewProj = new float[16];

    // Shadow map frustum fitted around every queued draw. It follows the Directional light; without one it uses the
    // backends' default sun direction (only the directional slot samples the shadow map).
    private final DirectionalShadowFrustum shadowFrustum = new DirectionalShadowFrustum();

    // Editor camera view-space depth row (view z = dot(row, p) + w) and range used to quantize sort-key depth
    private float depthRowX, depthRowY, depthRowZ, depthRowW;
    private float depthNear = 0.1f, depthFar = 100.0f;

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
            float far = camera.isPlayLocked() ? camera.getFar() : 100.0f;
            camera.computeViewMatrix(viewScratch);
            frustumCuller.updateFromView(viewScratch, camera.getFov(), ViewportPanel.getSceneAspect(editorManager.getDockSpace()), camera.getNear(), far);
            updateDepthRow(camera, far);
            if (camera.hasEntityView()) {
                snapshot.cameraViewActive = true;
                System.arraycopy(viewScratch, 0, snapshot.cameraView, 0, 16);
                camera.computeEyePosition(snapshot.cameraEye);
            }
        } else {
            depthRowX = depthRowY = depthRowZ = depthRowW = 0.0f;
        }
        RenderQueue queue = snapshot.renderQueue;
        shadowFrustum.reset();

        // Selected Camera entity's preview; its frustum also keeps entities only that camera can see in the queue
        boolean previewActive = cameraPreviewTexture != 0L && !ViewportPanel.isStandaloneFullscreen() && editorManager.getDockSpace().isSceneViewportVisible()
                && buildCameraPreview(editorManager, snapshot);

        List<Entity> entities = editorManager.getSceneEntities();
        int handleEpoch = streamingService.getHandleEpoch();
        int totalActiveVertices = 0;
        int entityDrawCalls = 0;

        if (entities != null && !entities.isEmpty()) {

            // Indexed loops: the entity list is random-access, and for-each would allocate an iterator per pass
            int entityCount = entities.size();

            // 1. Collect up to 1 Directional, MAX_POINT_LIGHTS Point and MAX_SPOT_LIGHTS Spot lights into the snapshot's pooled lights
            for (int ei = 0; ei < entityCount; ei++) {
                Entity entity = entities.get(ei);
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
                        case DIRECTIONAL -> SceneLights.DIRECTIONAL;
                        case POINT -> SceneLights.POINT;
                        case SPOT -> SceneLights.SPOT;
                    };

                    // Lights beyond a type's capacity are ignored
                    SceneLights.Light slot = snapshot.lights.add(lightType);
                    if (slot == null) continue;
                    slot.set(posX, posY, posZ, dirX, dirY, dirZ, light.r, light.g, light.b, light.intensity);
                    if (snapshot.lights.isFull()) break;
                }
            }

            // 2. Traversal loop across active 3D entities with View-Frustum Culling & Skeletal Palette Extraction
            for (int ei = 0; ei < entityCount; ei++) {
                Entity entity = entities.get(ei);
                Transform tc = entity.getComponent(Transform.class);
                MeshFilter mc = entity.getComponent(MeshFilter.class);
                Material mat = entity.getComponent(Material.class);
                AnimatorComponent anim = entity.getComponent(AnimatorComponent.class);

                if (tc == null || mc == null) continue;

                // Residency marks precede visibility and culling: hidden or off-screen entities still own their assets
                streamingService.markMeshReferenced(mc.meshAssetPath);
                if (mat != null) {
                    for (int channel = 0; channel < Material.CHANNEL_COUNT; channel++) {
                        streamingService.markTextureReferenced(mat.getChannelPath(channel));
                    }
                }

                if (!mc.isVisible) continue;

                WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);

                // View-Frustum Culling Test (Bounding Sphere check)
                float scaleRadius = Math.max(Math.abs(wt.sx()), Math.max(Math.abs(wt.sy()), Math.abs(wt.sz())));
                float sphereRadius = Math.max(scaleRadius * 1.5f, 1.0f);
                if (!frustumCuller.isSphereVisible(wt.px(), wt.py(), wt.pz(), sphereRadius)
                        && !(previewActive && previewCuller.isSphereVisible(wt.px(), wt.py(), wt.pz(), sphereRadius))) {
                    continue;
                }

                String meshPath = mc.meshAssetPath;
                String texturePath = (mat != null) ? mat.textureAssetPath : null;

                if (meshPath != null) {
                    if (!streamingService.isMeshLoaded(meshPath)) {
                        streamingService.requestModelAsync(meshPath);
                    }

                    if (mat != null) {
                        for (int channel = 0; channel < Material.CHANNEL_COUNT; channel++) {
                            String channelPath = mat.getChannelPath(channel);
                            if (channelPath != null && !streamingService.isTextureLoaded(channelPath)) {
                                streamingService.requestTextureAsync(channelPath);
                            }
                        }
                    }

                    MeshBufferData model = streamingService.getModel(meshPath);

                    long meshHandle;
                    MeshBufferData drawn;

                    if (model != null && model.vertices != null && model.vertices.length > 0) {
                        meshHandle = mc.getCachedMeshHandle(meshPath, handleEpoch);
                        if (meshHandle == 0L) {
                            meshHandle = streamingService.getMeshHandle(meshPath);
                            mc.cacheMeshHandle(meshPath, meshHandle, handleEpoch);
                        }
                        drawn = model;
                    } else if (streamingService.isMeshLoaded(meshPath)) {
                        meshHandle = streamingService.resolveMeshHandle(FALLBACK_CUBE_KEY, fallbackCube);
                        drawn = fallbackCube;
                    } else {
                        continue;
                    }

                    totalActiveVertices += drawn.vertexCount;
                    entityDrawCalls++;
                    mc.cacheDrawnBounds(meshPath, drawn.localBounds());

                    // 0L handles (headless/null backends) are still queued so entity counts stay consistent
                    RenderCommand cmd = queue.add();
                    cmd.meshHandle = meshHandle;
                    cmd.px = wt.px(); cmd.py = wt.py(); cmd.pz = wt.pz();
                    cmd.rx = wt.rx(); cmd.ry = wt.ry(); cmd.rz = wt.rz();
                    cmd.sx = wt.sx(); cmd.sy = wt.sy(); cmd.sz = wt.sz();
                    cmd.groundY = findGroundSurfaceY(entity, wt, entities, editorManager);
                    cmd.bonePalette = (anim != null && anim.skeleton != null) ? anim.bonePaletteMatrices : null;
                    cmd.pickId = ViewportPanel.pickIdFor(entity);
                    if (mat != null) {
                        if (texturePath != null) {
                            long albedo = mat.getCachedTextureHandle(texturePath, handleEpoch);
                            if (albedo == 0L) {
                                albedo = streamingService.getTextureHandle(texturePath);
                                mat.cacheTextureHandle(texturePath, albedo, handleEpoch);
                            }
                            cmd.albedoTexture = albedo;
                        }
                        cmd.normalTexture = resolveChannelHandle(mat, Material.CHANNEL_NORMAL, streamingService, handleEpoch);
                        cmd.specularTexture = resolveChannelHandle(mat, Material.CHANNEL_SPECULAR, streamingService, handleEpoch);
                        cmd.lightmapTexture = resolveChannelHandle(mat, Material.CHANNEL_LIGHTMAP, streamingService, handleEpoch);
                        cmd.emissiveTexture = resolveChannelHandle(mat, Material.CHANNEL_EMISSIVE, streamingService, handleEpoch);
                        cmd.reflect = mat.reflect;
                        cmd.specularStrength = mat.specularStrength;
                        cmd.roughness = mat.roughness;
                        cmd.normalStrength = mat.normalStrength;
                        cmd.emissiveIntensity = mat.emissiveIntensity;
                    }

                    boolean skinned = drawn.stride() >= MeshBufferData.SKINNED_FLOATS_PER_VERTEX;
                    float viewDepth = -(depthRowX * cmd.px + depthRowY * cmd.py + depthRowZ * cmd.pz + depthRowW);
                    cmd.sortKey = RenderQueue.packKey(false, RenderQueue.PASS_SCENE,
                            skinned ? RenderQueue.PIPELINE_SKINNED : RenderQueue.PIPELINE_STATIC,
                            materialId(cmd), RenderQueue.quantizeDepth(viewDepth, depthNear, depthFar));

                    float maxScale = Math.max(Math.abs(cmd.sx), Math.max(Math.abs(cmd.sy), Math.abs(cmd.sz)));
                    shadowFrustum.include(cmd.px, cmd.py, cmd.pz, drawn.boundsRadius() * maxScale);
                }
            }
        }

        SceneLights.Light sun = snapshot.lights.directional();
        if (snapshot.lights.hasDirectional()) {
            shadowFrustum.build(sun.dirX, sun.dirY, sun.dirZ, queue.lightSpaceMatrix);
        } else {
            shadowFrustum.build(SceneLights.DEFAULT_SUN_DIR_X, SceneLights.DEFAULT_SUN_DIR_Y, SceneLights.DEFAULT_SUN_DIR_Z, queue.lightSpaceMatrix);
        }
        queue.sort();

        // Every live entity has marked its assets; evict what deleted entities or an unloaded scene left behind
        streamingService.sweepUnreferenced();

        // The editor grid is an authoring aid: never drawn while playing or when viewing through a game camera
        boolean gameView = editorManager.getPlayState() != SceneWorld.PlayState.EDITING || (camera != null && camera.hasEntityView());
        snapshot.gridVisible = !gameView && editorManager.getGizmoStateController().is3DGridVisible();
        snapshot.gridVertices = snapshot.gridVisible ? editorManager.getGizmoStateController().get3DGridVertices() : null;
        snapshot.gridVertexCount = snapshot.gridVisible ? editorManager.getGizmoStateController().get3DGridVertexCount() : 0;
        editorManager.setCameraPreviewTexture(previewActive ? cameraPreviewTexture : 0L);
        // Building the editor UI also lays out the dock, so the Scene tile read below is this frame's
        boolean standalone = ViewportPanel.isStandaloneFullscreen();
        GizmoRenderer.beginPickCapture();
        float[] editorUi = standalone ? null : editorManager.getUiVertices();
        int editorUiVertexCount = standalone ? 0 : editorManager.getUiVertexCount();

        if (standalone) {
            // Standalone runtime: full-window NDC quad
            snapshot.sceneMinX = snapshot.sceneMinY = -1.0f;
            snapshot.sceneMaxX = snapshot.sceneMaxY = 1.0f;
        } else if (editorManager.getDockSpace().isSceneViewportVisible()) {
            DockSpace dock = editorManager.getDockSpace();
            snapshot.sceneMinX = dock.getContentX0(DockPanel.SCENE);
            snapshot.sceneMinY = dock.getContentY0(DockPanel.SCENE);
            snapshot.sceneMaxX = dock.getContentX1(DockPanel.SCENE);
            snapshot.sceneMaxY = dock.getContentY1(DockPanel.SCENE);
        } else {
            snapshot.sceneMinX = snapshot.sceneMinY = snapshot.sceneMaxX = snapshot.sceneMaxY = 0.0f;
        }
        snapshot.sceneAspect = ViewportPanel.getSceneAspect(editorManager.getDockSpace());
        snapshot.gizmoPickVertices = ViewportPanel.buildGizmoPickOverlay(GizmoRenderer.getPickVertices(), GizmoRenderer.getPickVertexCount(),
                snapshot.sceneMinX, snapshot.sceneMinY, snapshot.sceneMaxX, snapshot.sceneMaxY, snapshot.gizmoPickVertices);
        snapshot.gizmoPickVertexCount = (snapshot.sceneMaxX > snapshot.sceneMinX && snapshot.sceneMaxY > snapshot.sceneMinY)
                ? GizmoRenderer.getPickVertexCount() : 0;
        composeUiStream(snapshot, editorManager, camera, standalone, editorUi, editorUiVertexCount);

        if (snapshot.gridVisible) {
            totalActiveVertices += snapshot.gridVertexCount;
        }

        snapshot.totalDrawCalls = entityDrawCalls * (previewActive ? 2 : 1) + (snapshot.gridVisible ? 1 : 0) + 2;
        snapshot.totalActiveVertices = totalActiveVertices;

        return snapshot;
    }

    /**
     * Submits a decoupled RenderSceneSnapshot directly to RHI without touching EditorWorkspace.
     */
    public void submitFrame(RenderSceneSnapshot snapshot, IRenderBackend rhi) {
        if (snapshot == null || rhi == null) return;

        applyPendingShaderReloads(rhi);
        rhi.setSceneLights(snapshot.lights);

        rhi.setCameraView(snapshot.cameraViewActive ? snapshot.cameraView : null,
                snapshot.cameraEye[0], snapshot.cameraEye[1], snapshot.cameraEye[2]);
        rhi.submitRenderQueue(snapshot.renderQueue);

        rhi.setPickOverlay(snapshot.gizmoPickVertices, snapshot.gizmoPickVertexCount);
        ViewportPanel.updatePicking(rhi);
        rhi.setSceneViewport(snapshot.sceneMinX, snapshot.sceneMinY, snapshot.sceneMaxX, snapshot.sceneMaxY, snapshot.sceneAspect);
        rhi.setCameraPreview(snapshot.cameraPreviewActive, snapshot.cameraPreviewViewProj,
                snapshot.cameraPreviewPosX, snapshot.cameraPreviewPosY, snapshot.cameraPreviewPosZ);
        cameraPreviewTexture = rhi.getCameraPreviewTexture();
        rhi.updateGridMesh(snapshot.gridVertices, snapshot.gridVertexCount);
        rhi.updateUiMesh(snapshot.uiVertices, snapshot.uiVertexCount);
        if (!uiFontResolved) resolveUiFont(rhi);

        EngineConsolePanel.updateTelemetry(snapshot.totalDrawCalls, snapshot.totalActiveVertices);
    }

    private boolean uiFontResolved = false;

    // Shader files modified on disk, reported by the asset watcher and applied at the next frame boundary
    private final ConcurrentLinkedQueue<String> pendingShaderReloads = new ConcurrentLinkedQueue<>();

    /** Thread-safe: queues modified shader files; the backend rebuilds its pipelines before the next frame is recorded. */
    public void requestShaderReload(List<String> changedShaderFiles) {
        if (changedShaderFiles != null) pendingShaderReloads.addAll(changedShaderFiles);
    }

    /** Render thread, before any backend call of the frame: no command buffer is recording, so pipelines can be swapped. */
    private void applyPendingShaderReloads(IRenderBackend rhi) {
        if (pendingShaderReloads.isEmpty()) return;
        List<String> changed = new ArrayList<>();
        for (String path; (path = pendingShaderReloads.poll()) != null; ) {
            if (!changed.contains(path)) changed.add(path);
        }
        System.out.println("[SceneRenderPipeline] Shader hot reload: " + changed.size() + " modified shader file(s).");
        rhi.reloadShaders(changed);
    }

    /**
     * Fills the snapshot's UI stream: game UI first (drawn over the composited 3D image, clipped to the game viewport),
     * then the editor UI on top. Standalone builds carry the game UI only.
     */
    private void composeUiStream(RenderSceneSnapshot snapshot, EditorWorkspace editorManager, ViewportCamera camera,
                                 boolean standalone, float[] editorVerts, int editorVertexCount) {
        int windowW = standalone ? ViewportPanel.getStandaloneWidth() : editorManager.getWindowWidth();
        int windowH = standalone ? ViewportPanel.getStandaloneHeight() : editorManager.getWindowHeight();
        UIGeometryBatch.Buffer game = gameUiRenderer.build(
                editorManager.getSceneEntities(), editorManager.getSceneManager(),
                snapshot.sceneMinX, snapshot.sceneMinY, snapshot.sceneMaxX, snapshot.sceneMaxY,
                windowW, windowH, camera, snapshot.sceneAspect);
        int gameFloats = game.getSize();

        int editorFloats = (editorVerts != null) ? editorVertexCount * UIGeometryBatch.VERTEX_STRIDE : 0;

        int total = gameFloats + editorFloats;
        if (snapshot.uiMergeScratch.length < total) snapshot.uiMergeScratch = new float[Math.max(total, snapshot.uiMergeScratch.length * 2)];
        float[] merged = snapshot.uiMergeScratch;
        System.arraycopy(game.getArray(), 0, merged, 0, gameFloats);
        if (editorFloats > 0) System.arraycopy(editorVerts, 0, merged, gameFloats, editorFloats);
        snapshot.uiVertices = merged;
        snapshot.uiVertexCount = total / UIGeometryBatch.VERTEX_STRIDE;
    }

    /**
     * Bakes the system UI font once and hands its SDF atlas to the backend; glyph layout switches over on the next
     * generated UI frame. Any failure keeps the default bitmap font.
     */
    private void resolveUiFont(IRenderBackend rhi) {
        uiFontResolved = true;
        UIFont font = UIFont.loadSystemFont();
        if (font == null) return;
        long atlas = rhi.registerTexture("editor://ui_font_atlas", font.getAtlasPixels(), font.atlasWidth, font.atlasHeight);
        if (atlas != 0L && rhi.setUiFontAtlas(atlas)) {
            font.activate();
        } else {
            if (atlas != 0L) rhi.unregisterTexture(atlas);
            System.err.println("[SceneRenderPipeline] WARNING: Backend could not bind the UI font atlas; using the default bitmap font.");
        }
    }

    /**
     * Backward-compatible submitFrame overload extracting a snapshot and submitting to RHI.
     */
    public void submitFrame(EditorWorkspace editorManager, AssetStreamer streamingService, IRenderBackend rhi) {
        RenderSceneSnapshot snapshot = extractSnapshot(editorManager, streamingService);
        submitFrame(snapshot, rhi);
    }

    /**
     * Fills the snapshot's camera preview from the selected entity's Camera component and world transform, and aims the
     * preview culler at it. Returns false when nothing with a Camera is selected.
     */
    private boolean buildCameraPreview(EditorWorkspace editorManager, RenderSceneSnapshot snapshot) {
        Entity selected = editorManager.getSelectedEntity();
        Camera camera = (selected != null) ? selected.getComponent(Camera.class) : null;
        if (camera == null) return false;

        WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(selected);
        computeCameraView(wt, previewView);

        float fov = Math.max(1.0f, Math.min(179.0f, camera.fov));
        float near = Math.max(0.01f, camera.nearPlane);
        float far = Math.max(near + 0.01f, camera.farPlane);
        float f = (float) (1.0 / Math.tan(Math.toRadians(fov) / 2.0));
        float aspect = ViewportPanel.getCameraPreviewAspect();
        // Same 0..1 depth, right-handed projection the backends build for the editor camera
        Arrays.fill(previewProj, 0.0f);
        previewProj[0] = f / aspect;
        previewProj[5] = f;
        previewProj[10] = far / (near - far);
        previewProj[11] = -1.0f;
        previewProj[14] = (near * far) / (near - far);

        float[] vp = snapshot.cameraPreviewViewProj;
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) sum += previewProj[k * 4 + row] * previewView[col * 4 + k];
                vp[col * 4 + row] = sum;
            }
        }
        previewCuller.updateFrustumPlanes(vp);

        snapshot.cameraPreviewPosX = wt.px();
        snapshot.cameraPreviewPosY = wt.py();
        snapshot.cameraPreviewPosZ = wt.pz();
        snapshot.cameraPreviewActive = true;
        return true;
    }

    /**
     * Column-major view matrix R^T * T(-p) for an entity transform. R = Rz * Ry * Rx matches the backends' model matrix,
     * so a camera with zero rotation looks down -Z, rotY yaws and positive rotX pitches down (the Light convention).
     */
    public static void computeCameraView(WorldTransform wt, float[] out) {
        float ax = (float) Math.toRadians(wt.rx()), ay = (float) Math.toRadians(wt.ry()), az = (float) Math.toRadians(wt.rz());
        float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
        float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
        float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
        setRows(rotX, 1, 0, 0, 0, cx, sx, 0, -sx, cx);
        setRows(rotY, cy, 0, -sy, 0, 1, 0, sy, 0, cy);
        setRows(rotZ, cz, sz, 0, -sz, cz, 0, 0, 0, 1);
        mul3(rotY, rotX, rotYX);
        mul3(rotZ, rotYX, rot);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) out[j * 4 + i] = rot[j * 3 + i]; // R^T
            out[i * 4 + 3] = 0.0f;
            out[12 + i] = -(rot[i] * wt.px() + rot[3 + i] * wt.py() + rot[6 + i] * wt.pz());
        }
        out[15] = 1.0f;
    }

    private static void setRows(float[] m, float m00, float m01, float m02, float m10, float m11, float m12, float m20, float m21, float m22) {
        m[0] = m00; m[1] = m01; m[2] = m02;
        m[3] = m10; m[4] = m11; m[5] = m12;
        m[6] = m20; m[7] = m21; m[8] = m22;
    }

    /** out = a * b for row-major 3x3 matrices. */
    private static void mul3(float[] a, float[] b, float[] out) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
    }

    /** Row 2 of the view matrix in {@code viewScratch} (view-space z) and the depth range for sort keys. */
    private void updateDepthRow(ViewportCamera camera, float far) {
        depthRowX = viewScratch[2];
        depthRowY = viewScratch[6];
        depthRowZ = viewScratch[10];
        depthRowW = viewScratch[14];
        depthNear = camera.getNear();
        depthFar = far;
    }

    /** 15-bit material bucket hashed from the texture set; collisions only weaken batching, never correctness. */
    private static int materialId(RenderCommand cmd) {
        long h = cmd.albedoTexture;
        h = h * 31L + cmd.normalTexture;
        h = h * 31L + cmd.specularTexture;
        h = h * 31L + cmd.lightmapTexture;
        h = h * 31L + cmd.emissiveTexture;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) (h & ((1 << RenderQueue.MATERIAL_BITS) - 1));
    }

    /** GPU handle for one material channel, cached on the Material per handle epoch; 0L when empty or not yet streamed. */
    private long resolveChannelHandle(Material mat, int channel, AssetStreamer streamingService, int handleEpoch) {
        String path = mat.getChannelPath(channel);
        if (path == null) return 0L;
        long handle = mat.getCachedChannelHandle(channel, path, handleEpoch);
        if (handle == 0L) {
            handle = streamingService.getTextureHandle(path);
            mat.cacheChannelHandle(channel, path, handle, handleEpoch);
        }
        return handle;
    }

    private float findGroundSurfaceY(Entity currentEntity, WorldTransform currentWt, List<Entity> allEntities, EditorWorkspace workspace) {
        float rayX = currentWt.px();
        float rayY = currentWt.py();
        float rayZ = currentWt.pz();
        float bestGroundY = 0.0f;

        if (allEntities != null) {
            for (int i = 0, n = allEntities.size(); i < n; i++) {
                Entity other = allEntities.get(i);
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
