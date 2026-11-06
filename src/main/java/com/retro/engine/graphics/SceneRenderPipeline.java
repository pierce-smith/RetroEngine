package com.retro.engine.graphics;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.assets.MeshBufferData;
import com.retro.engine.assets.MeshImporter;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.SceneWorld.WorldTransform;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.audio.AudioEngine;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.ParticleSystem;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.gizmos.GizmoRenderer;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.EngineConsolePanel;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.modding.ModLoader;
import com.retro.engine.ui.GameUIRenderer;
import com.retro.engine.ui.UIFont;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * High-level render pipeline processing 3D scene entities, view-frustum culling,
 * light state, the project's render environment (ambient, distance fog, ground shadow blob toggle from ProjectSettings),
 * GPU skeletal animation bone palette extraction, and downward-raycast ground shadows
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
        // Main Camera post-processing for the scene composite (disabled while authoring through the editor camera)
        public final PostProcessSettings postProcess = new PostProcessSettings();
        // Camera-facing particle quads for the main scene pass (owned, grow-only storage)
        public final ParticleDrawList particles = new ParticleDrawList();

        public void reset() {
            lights.reset();
            particles.clear();
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
            postProcess.disable();
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
    private final float[] listenerEye = new float[3];

    // Backend camera preview target (0L = unsupported); refreshed every submitFrame
    private long cameraPreviewTexture = 0L;
    // Camera preview matrix scratch (3x3 row-major rotations, 4x4 column-major view / projection)
    // Static: only touched on the main thread (render extraction and ViewportPanel's play-camera sync)
    private static final float[] rotX = new float[9], rotY = new float[9], rotZ = new float[9], rotYX = new float[9], rot = new float[9];
    private final float[] previewView = new float[16], previewProj = new float[16];

    // Shadow map frustum fitted around every queued draw. It follows the Directional light; without one it uses the
    // backends' default sun direction (only the directional slot samples the shadow map).
    private final DirectionalShadowFrustum shadowFrustum = new DirectionalShadowFrustum();
    private final GroundHeightIndex groundIndex = new GroundHeightIndex();

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
            float far = camera.getFar();
            camera.computeViewMatrix(viewScratch);
            frustumCuller.updateFromView(viewScratch, camera.getFov(), ViewportPanel.getSceneAspect(editorManager.getDockSpace()), camera.getNear(), far);
            updateDepthRow(camera, far);
            if (camera.hasEntityView()) {
                snapshot.cameraViewActive = true;
                System.arraycopy(viewScratch, 0, snapshot.cameraView, 0, 16);
                camera.computeEyePosition(snapshot.cameraEye);
            }
            syncAudioListener(camera);
        } else {
            depthRowX = depthRowY = depthRowZ = depthRowW = 0.0f;
        }
        extractPostProcess(snapshot, editorManager, camera);
        RenderQueue queue = snapshot.renderQueue;
        shadowFrustum.reset();

        // Project-wide ambient / fog / shadow blob settings ride along with the lights (field copies, no allocation)
        RenderEnvironment environment = snapshot.lights.environment();
        editorManager.getProjectSettings().applyTo(environment);
        com.retro.engine.audio.AudioMixerService.applySettings(editorManager.getProjectSettings());
        boolean shadowBlobs = environment.shadowBlobsEnabled;

        // Selected Camera entity's preview; its frustum also keeps entities only that camera can see in the queue
        boolean previewActive = cameraPreviewTexture != 0L && !GameView.isFullscreen() && editorManager.getDockSpace().isSceneViewportVisible()
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

            if (shadowBlobs) buildGroundIndex(entities, editorManager.getSceneManager());

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

                // View-Frustum Culling Test (Bounding Sphere check). The sphere must enclose the mesh's real extent:
                // a guess from scale alone culled large meshes (e.g. a 10x10 plane scaled 3x, radius ~21) as a
                // 4.5-unit sphere, so floors vanished whenever their origin left the view.
                float scaleRadius = Math.max(Math.abs(wt.sx()), Math.max(Math.abs(wt.sy()), Math.abs(wt.sz())));
                MeshBufferData boundsSource = mc.meshAssetPath != null ? streamingService.getModel(mc.meshAssetPath) : null;
                float sphereRadius = (boundsSource != null && boundsSource.vertices != null && boundsSource.vertices.length > 0)
                        ? Math.max(boundsSource.boundsRadius() * scaleRadius, 0.01f)
                        : Math.max(scaleRadius * 1.5f, 1.0f); // not streamed in yet: conservative unit-mesh guess
                if (anim != null && anim.skeleton != null) {
                    // Bind-pose bounds don't cover the animated pose, and first-person rigs put the camera at the edge
                    // of them (arms_rig: radius 1.67, eye 1.76 above the origin), so a tight sphere culls the arms
                    // whenever the player looks up. Animation can at most swing limbs across the rig, so double it.
                    sphereRadius *= 2.0f;
                }
                // Fog doubles as the draw distance: a sphere whose nearest view depth is past fog end would be fully
                // fogged, so it is culled with no visible pop. Same planar view depth the shaders fog with.
                float mainNearDepth = -(depthRowX * wt.px() + depthRowY * wt.py() + depthRowZ * wt.pz() + depthRowW) - sphereRadius;
                boolean mainVisible = frustumCuller.isSphereVisible(wt.px(), wt.py(), wt.pz(), sphereRadius)
                        && !environment.isFogCulled(mainNearDepth);
                boolean previewVisible = false;
                if (!mainVisible && previewActive) {
                    float previewNearDepth = -(previewView[2] * wt.px() + previewView[6] * wt.py() + previewView[10] * wt.pz() + previewView[14]) - sphereRadius;
                    previewVisible = previewCuller.isSphereVisible(wt.px(), wt.py(), wt.pz(), sphereRadius)
                            && !environment.isFogCulled(previewNearDepth);
                }
                if (!mainVisible && !previewVisible) {
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
                    // Blobs off: skip the per-draw downward raycast; an infinite clearance also fades any blob to zero
                    cmd.groundY = shadowBlobs ? groundIndex.highestSurfaceBelow(ei, wt.px(), wt.py(), wt.pz()) : Float.NEGATIVE_INFINITY;
                    cmd.bonePalette = (anim != null && anim.skeleton != null) ? anim.bonePaletteMatrices : null;
                    cmd.pickId = ViewportPanel.pickIdFor(entity);
                    cmd.unlit = drawn.unlit;
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

        // 3. Billboard particles (after entity residency marks, before the unreferenced-asset sweep)
        if (entities != null && camera != null) {
            extractParticles(snapshot.particles, entities, streamingService, handleEpoch);
            totalActiveVertices += snapshot.particles.vertexCount;
        }

        // Mods: RenderPassEvent may queue extra draws, add lights or tweak this frame's environment before sorting
        activeSnapshot = snapshot;
        ModLoader.onRenderPass(this, streamingService, queue, snapshot.lights, environment, GameView.isFullscreen());
        activeSnapshot = null;

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
        boolean standalone = GameView.isFullscreen();
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

        snapshot.totalDrawCalls = entityDrawCalls * (previewActive ? 2 : 1) + (snapshot.gridVisible ? 1 : 0) + 2 + snapshot.particles.batchCount;
        snapshot.totalActiveVertices = totalActiveVertices;

        return snapshot;
    }

    /** Indexes every visible mesh once, so each draw's ground lookup only looks at its neighbors. */
    private void buildGroundIndex(List<Entity> entities, SceneWorld world) {
        groundIndex.reset();
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            MeshFilter mesh = entity.getComponent(MeshFilter.class);
            if (mesh == null || !mesh.isVisible) continue;
            WorldTransform wt = world.getComputedWorldTransform(entity);
            float radius = Math.max(Math.max(0.2f, wt.sx()), Math.max(0.2f, wt.sz())) * 1.1f;
            groundIndex.add(i, wt.px(), wt.pz(), radius, wt.py() + Math.max(0.2f, wt.sy()));
        }
        groundIndex.build();
    }

    // Particle systems visible this frame and their view depths, sorted back to front (grow-only scratch)
    private ParticleSystem[] particleSystems = new ParticleSystem[16];
    private float[] particleSystemDepth = new float[16];

    /**
     * Builds camera-facing quads for every live ParticleSystem into {@code out}: one batch per system (up to
     * {@link ParticleDrawList#MAX_BATCHES}), systems ordered back to front, and ALPHA systems' particles sorted back to
     * front as well (ADDITIVE blending is order-independent). Allocation-free once the scratch arrays have grown.
     */
    private void extractParticles(ParticleDrawList out, List<Entity> entities, AssetStreamer streamingService, int handleEpoch) {
        // Billboard axes: camera right / up are the first two rows of the column-major world-to-view matrix
        float rX = viewScratch[0], rY = viewScratch[4], rZ = viewScratch[8];
        float uX = viewScratch[1], uY = viewScratch[5], uZ = viewScratch[9];

        int systems = 0;
        for (int ei = 0, n = entities.size(); ei < n; ei++) {
            Entity entity = entities.get(ei);
            ParticleSystem ps = entity.getComponent(ParticleSystem.class);
            if (ps == null) continue;
            String tex = ps.texturePath;
            if (tex != null && !tex.isEmpty()) {
                streamingService.markTextureReferenced(tex);
                if (!streamingService.isTextureLoaded(tex)) streamingService.requestTextureAsync(tex);
            }
            int alive = ps.getAliveCount();
            if (alive == 0 || ps.posX == null) continue;

            // Sort systems by the centroid of their live particles (the emitter may have moved away from them)
            float cx = 0.0f, cy = 0.0f, cz = 0.0f;
            for (int i = 0; i < alive; i++) { cx += ps.posX[i]; cy += ps.posY[i]; cz += ps.posZ[i]; }
            float inv = 1.0f / alive;
            cx *= inv; cy *= inv; cz *= inv;
            if (systems == particleSystems.length) growParticleScratch();
            particleSystems[systems] = ps;
            particleSystemDepth[systems] = -(depthRowX * cx + depthRowY * cy + depthRowZ * cz + depthRowW);
            systems++;
        }
        if (systems == 0) return;

        // Insertion sort, farthest first (a handful of systems)
        for (int i = 1; i < systems; i++) {
            ParticleSystem ps = particleSystems[i];
            float d = particleSystemDepth[i];
            int j = i - 1;
            while (j >= 0 && particleSystemDepth[j] < d) {
                particleSystems[j + 1] = particleSystems[j];
                particleSystemDepth[j + 1] = particleSystemDepth[j];
                j--;
            }
            particleSystems[j + 1] = ps;
            particleSystemDepth[j + 1] = d;
        }

        // Keep the nearest MAX_BATCHES systems when there are more
        int first = Math.max(0, systems - ParticleDrawList.MAX_BATCHES);
        for (int si = first; si < systems; si++) {
            ParticleSystem ps = particleSystems[si];
            particleSystems[si] = null; // don't retain components between frames
            int alive = ps.getAliveCount();
            out.ensureCapacity(alive);

            boolean additive = ps.blendMode == ParticleSystem.BlendMode.ADDITIVE;
            int[] order = ps.drawOrder;
            float[] depth = ps.drawDepth;
            for (int i = 0; i < alive; i++) {
                order[i] = i;
                depth[i] = -(depthRowX * ps.posX[i] + depthRowY * ps.posY[i] + depthRowZ * ps.posZ[i] + depthRowW);
            }
            if (!additive) {
                for (int i = 1; i < alive; i++) {
                    int idx = order[i];
                    float d = depth[idx];
                    int j = i - 1;
                    while (j >= 0 && depth[order[j]] < d) { order[j + 1] = order[j]; j--; }
                    order[j + 1] = idx;
                }
            }

            int batch = out.batchCount++;
            out.batchFirstVertex[batch] = out.vertexCount;
            out.batchTexture[batch] = resolveParticleTexture(ps, streamingService, handleEpoch);

            float[] v = out.vertices;
            int w = out.vertexCount * ParticleDrawList.FLOATS_PER_VERTEX;
            int emitted = 0;
            for (int k = 0; k < alive; k++) {
                int i = order[k];
                float t = ps.life[i] > 0.0f ? Math.min(1.0f, ps.age[i] / ps.life[i]) : 1.0f;
                float h = 0.5f * (ps.startSize + (ps.endSize - ps.startSize) * t);
                float a = clamp(ps.startColorA + (ps.endColorA - ps.startColorA) * t, 0.0f, 1.0f);
                if (h <= 0.0f || a <= 0.0f || depth[i] < depthNear * 0.5f) continue; // invisible or behind the camera
                float r = (ps.startColorR + (ps.endColorR - ps.startColorR) * t) * a;
                float g = (ps.startColorG + (ps.endColorG - ps.startColorG) * t) * a;
                float b = (ps.startColorB + (ps.endColorB - ps.startColorB) * t) * a;
                float outA = additive ? 0.0f : a;

                float px = ps.posX[i], py = ps.posY[i], pz = ps.posZ[i];
                float ax = (rX + uX) * h, ay = (rY + uY) * h, az = (rZ + uZ) * h; // to top-right
                float bx = (rX - uX) * h, by = (rY - uY) * h, bz = (rZ - uZ) * h; // to bottom-right
                // BL, BR, TR, BL, TR, TL
                w = putParticleVertex(v, w, px - ax, py - ay, pz - az, r, g, b, outA, 0.0f, 1.0f);
                w = putParticleVertex(v, w, px + bx, py + by, pz + bz, r, g, b, outA, 1.0f, 1.0f);
                w = putParticleVertex(v, w, px + ax, py + ay, pz + az, r, g, b, outA, 1.0f, 0.0f);
                w = putParticleVertex(v, w, px - ax, py - ay, pz - az, r, g, b, outA, 0.0f, 1.0f);
                w = putParticleVertex(v, w, px + ax, py + ay, pz + az, r, g, b, outA, 1.0f, 0.0f);
                w = putParticleVertex(v, w, px - bx, py - by, pz - bz, r, g, b, outA, 0.0f, 0.0f);
                emitted++;
            }
            int vertexCount = emitted * ParticleDrawList.VERTICES_PER_PARTICLE;
            if (vertexCount == 0) {
                out.batchCount--;
                continue;
            }
            out.batchVertexCount[batch] = vertexCount;
            out.vertexCount += vertexCount;
        }
        for (int si = 0; si < first; si++) particleSystems[si] = null;
    }

    private static int putParticleVertex(float[] v, int w, float x, float y, float z, float r, float g, float b, float a, float u, float t) {
        v[w] = x; v[w + 1] = y; v[w + 2] = z;
        v[w + 3] = r; v[w + 4] = g; v[w + 5] = b; v[w + 6] = a;
        v[w + 7] = u; v[w + 8] = t;
        return w + ParticleDrawList.FLOATS_PER_VERTEX;
    }

    private static long resolveParticleTexture(ParticleSystem ps, AssetStreamer streamingService, int handleEpoch) {
        String tex = ps.texturePath;
        if (tex == null || tex.isEmpty()) return 0L;
        long handle = ps.getCachedTextureHandle(tex, handleEpoch);
        if (handle == 0L) {
            handle = streamingService.getTextureHandle(tex);
            if (handle != 0L) ps.cacheTextureHandle(tex, handle, handleEpoch);
        }
        return handle;
    }

    private void growParticleScratch() {
        int cap = particleSystems.length * 2;
        particleSystems = Arrays.copyOf(particleSystems, cap);
        particleSystemDepth = Arrays.copyOf(particleSystemDepth, cap);
    }

    private static final long POST_FX_EPOCH = System.nanoTime();

    /** Main Camera post-processing applies whenever the scene is viewed through a game camera (Play or entity view). */
    private static void extractPostProcess(RenderSceneSnapshot snapshot, EditorWorkspace editorManager, ViewportCamera camera) {
        PostProcessSettings fx = snapshot.postProcess;
        fx.disable();
        boolean gameView = editorManager.getPlayState() != SceneWorld.PlayState.EDITING || (camera != null && camera.hasEntityView());
        if (!gameView) return;
        Entity main = SceneWorld.findMainCamera(editorManager.getSceneEntities());
        Camera cam = main != null ? main.getComponent(Camera.class) : null;
        if (cam == null || !cam.postProcessEnabled) return;
        fx.enabled = true;
        fx.vignette = clamp(cam.vignetteIntensity, 0.0f, 1.0f);
        fx.scanlines = clamp(cam.scanlineIntensity, 0.0f, 1.0f);
        fx.scanlineSpeed = cam.scanlineSpeed;
        fx.chromatic = clamp(cam.chromaticOffset, 0.0f, 64.0f);
        fx.saturation = clamp(cam.saturation, 0.0f, 4.0f);
        fx.contrast = clamp(cam.contrast, 0.0f, 4.0f);
        fx.time = (float) ((System.nanoTime() - POST_FX_EPOCH) * 1e-9 % 3600.0);
    }

    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

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
        rhi.submitParticles(snapshot.particles);

        rhi.setPickOverlay(snapshot.gizmoPickVertices, snapshot.gizmoPickVertexCount);
        ViewportPanel.updatePicking(rhi);
        rhi.setPostProcess(snapshot.postProcess);
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
        try {
            rhi.reloadShaders(changed);
        } catch (RuntimeException e) {
            // Backends keep their previous pipelines on failure; never let a bad shader take the frame down
            System.err.println("[SceneRenderPipeline] Shader hot reload failed; keeping the previous pipelines: " + e);
        }
    }

    /**
     * Fills the snapshot's UI stream: game UI first (drawn over the composited 3D image, clipped to the game viewport),
     * then the editor UI on top. Standalone builds carry the game UI only.
     */
    private void composeUiStream(RenderSceneSnapshot snapshot, EditorWorkspace editorManager, ViewportCamera camera,
                                 boolean standalone, float[] editorVerts, int editorVertexCount) {
        int windowW = standalone ? GameView.width() : editorManager.getWindowWidth();
        int windowH = standalone ? GameView.height() : editorManager.getWindowHeight();
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
     * Moves the OpenAL listener to the active viewport camera (orbit pose while editing, the Camera entity's view in
     * Play). Rows 1 and 2 of the column-major view matrix are the camera's up and back axes in world space.
     */
    private void syncAudioListener(ViewportCamera camera) {
        if (!AudioEngine.isAvailable()) return;
        camera.computeEyePosition(listenerEye);
        AudioEngine.updateListener(listenerEye[0], listenerEye[1], listenerEye[2],
                -viewScratch[2], -viewScratch[6], -viewScratch[10],
                viewScratch[1], viewScratch[5], viewScratch[9]);
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
    // Snapshot being extracted while RenderPassEvent runs; queueCustomDraw is only valid inside that window
    private RenderSceneSnapshot activeSnapshot;
    // VFS-resolved paths of mod custom draws, rebuilt when the loaded mod set changes (keeps per-frame lookups allocation-free)
    private final java.util.HashMap<String, String> customDrawPaths = new java.util.HashMap<>();
    private Object customDrawPathsOwner;

    /**
     * Services {@link com.retro.engine.modding.events.RenderPassEvent#drawMesh}: queues one static mesh draw into the
     * snapshot being extracted, streaming the mesh / texture in on first use. Returns null when called outside the
     * event or while the mesh is still loading.
     */
    public RenderCommand queueCustomDraw(AssetStreamer streamer, String meshPath, String texturePath,
                                         float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        RenderSceneSnapshot snapshot = activeSnapshot;
        if (snapshot == null || streamer == null || meshPath == null) return null;
        String mesh = resolveCustomDrawPath(meshPath);
        String texture = (texturePath != null) ? resolveCustomDrawPath(texturePath) : null;

        streamer.markMeshReferenced(mesh);
        if (!streamer.isMeshLoaded(mesh)) streamer.requestModelAsync(mesh);
        if (texture != null) {
            streamer.markTextureReferenced(texture);
            if (!streamer.isTextureLoaded(texture)) streamer.requestTextureAsync(texture);
        }
        MeshBufferData model = streamer.getModel(mesh);
        if (model == null || model.vertices == null || model.vertices.length == 0) return null;
        long meshHandle = streamer.getMeshHandle(mesh);
        if (meshHandle == 0L) return null;

        RenderCommand cmd = snapshot.renderQueue.add();
        cmd.meshHandle = meshHandle;
        cmd.px = px; cmd.py = py; cmd.pz = pz;
        cmd.rx = rx; cmd.ry = ry; cmd.rz = rz;
        cmd.sx = sx; cmd.sy = sy; cmd.sz = sz;
        cmd.groundY = Float.NEGATIVE_INFINITY; // no ground shadow blob for custom draws
        if (texture != null && streamer.isTextureLoaded(texture)) cmd.albedoTexture = streamer.getTextureHandle(texture);
        float viewDepth = -(depthRowX * px + depthRowY * py + depthRowZ * pz + depthRowW);
        cmd.sortKey = RenderQueue.packKey(false, RenderQueue.PASS_SCENE, RenderQueue.PIPELINE_STATIC,
                materialId(cmd), RenderQueue.quantizeDepth(viewDepth, depthNear, depthFar));
        float maxScale = Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
        shadowFrustum.include(px, py, pz, model.boundsRadius() * maxScale);
        return cmd;
    }

    private String resolveCustomDrawPath(String path) {
        Object owner = ModLoader.active();
        if (owner != customDrawPathsOwner) {
            customDrawPaths.clear();
            customDrawPathsOwner = owner;
        }
        String resolved = customDrawPaths.get(path);
        if (resolved == null) {
            resolved = ModLoader.resolveAssetPath(path);
            customDrawPaths.put(path, resolved);
        }
        return resolved;
    }

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
}
