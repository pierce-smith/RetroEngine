package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.WorkspaceTab;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.dock.DockPanel;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld.PlayState;
import com.retro.engine.scene.world.SceneWorld.WorldTransform;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

/**
 * ViewportPanel handles rendering the central Viewport framing, canvas tab switcher buttons, and Toolbar controls.
 * It also owns pixel-exact GPU Color-ID picking: entities carry stable pick IDs, the cursor drives the backend's pick
 * probe, and viewport clicks resolve through {@link IRenderBackend#queryPick} once the readback catches up.
 */
public class ViewportPanel {

    // Frames a click waits for the asynchronous pick readback before it is dropped (selection left unchanged)
    private static final int PICK_TIMEOUT_FRAMES = 15;

    private static IRenderBackend pickBackend = null;
    private static float hoverU = -1.0f, hoverV = -1.0f;
    // Stable per-entity pick IDs: async readbacks may describe a frame whose submission order differs from the current one
    private static final Map<Entity, Integer> pickIds = new WeakHashMap<>();
    private static final Map<Integer, WeakReference<Entity>> pickEntities = new HashMap<>();
    private static int nextPickId = 1;
    private static float pendingU = -1.0f, pendingV = -1.0f;
    private static IntConsumer pendingCallback = null;
    private static int pendingFrames = 0;

    private static String rhiCanvasTitle = "3D VIEWPORT [RHI CANVAS]";

    private static final StringBuilder cameraTitleSb = new StringBuilder(64);
    private static final StringBuilder cameraSpecsSb = new StringBuilder(64);
    // On-screen width/height of the last drawn viewfinder; the camera preview pass projects with it so the
    // fixed-size preview target is stretched into the frame without distortion
    private static volatile float cameraPreviewAspect = 16.0f / 9.0f;

    private static final float[] playCameraView = new float[16];

    // Standalone runtime: the scene fills 100% of the WindowHost instead of the docked Scene tile
    private static volatile boolean standaloneFullscreen = false;
    private static volatile float standaloneAspect = 16.0f / 9.0f;
    private static volatile int standaloneWidth = 1280, standaloneHeight = 720;

    /** Called each runtime frame with the WindowHost size; the scene viewport then covers the whole window. */
    public static void setStandaloneFullscreen(int windowWidth, int windowHeight) {
        standaloneFullscreen = true;
        if (windowWidth > 0 && windowHeight > 0) {
            standaloneAspect = (float) windowWidth / (float) windowHeight;
            standaloneWidth = windowWidth;
            standaloneHeight = windowHeight;
        }
    }

    public static int getStandaloneWidth() { return standaloneWidth; }
    public static int getStandaloneHeight() { return standaloneHeight; }

    public static boolean isStandaloneFullscreen() { return standaloneFullscreen; }

    /** Scene projection aspect: the full window in standalone runtime, the docked Scene tile in the editor. */
    public static float getSceneAspect(DockSpace dock) {
        if (standaloneFullscreen) return standaloneAspect;
        return (dock != null) ? dock.getSceneAspect() : 712.0f / 488.0f;
    }

    /**
     * Per-frame Play-mode camera lock. While the scene is playing (SIMULATING or PAUSED) the viewport camera follows
     * the Main Camera with the camera preview's view transform and projection, and editor navigation is disabled;
     * back in EDITING the pre-play editor pose and free flight controls are restored. A scene without a Camera
     * leaves the editor camera locked in place.
     */
    public static void syncPlayCamera(EditorWorkspace workspace, ViewportCamera viewportCamera) {
        if (workspace == null || viewportCamera == null) return;
        if (workspace.getPlayState() == PlayState.EDITING) {
            viewportCamera.unlockFromPlay();
            return;
        }
        viewportCamera.lockForPlay();

        Entity mainCamera = findMainCamera(workspace.getSceneEntities());
        if (mainCamera == null) {
            viewportCamera.clearEntityView();
            return;
        }
        Camera camera = mainCamera.getComponent(Camera.class);
        WorldTransform wt = workspace.getSceneManager().getComputedWorldTransform(mainCamera);
        SceneRenderPipeline.computeCameraView(wt, playCameraView);

        // Same clamps as the camera preview pass
        float fov = Math.max(1.0f, Math.min(179.0f, camera.fov));
        float near = Math.max(0.01f, camera.nearPlane);
        float far = Math.max(near + 0.01f, camera.farPlane);
        viewportCamera.snapToView(playCameraView, wt.px(), wt.py(), wt.pz(), fov, near, far);
    }

    /** First entity with a primary Camera, falling back to the first entity with any Camera. */
    public static Entity findMainCamera(List<Entity> entities) {
        if (entities == null) return null;
        Entity fallback = null;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            Camera camera = entity.getComponent(Camera.class);
            if (camera == null) continue;
            if (camera.isPrimary) return entity;
            if (fallback == null) fallback = entity;
        }
        return fallback;
    }

    public static float getCameraPreviewAspect() {
        return cameraPreviewAspect;
    }

    public static String getRhiCanvasTitle() {
        return rhiCanvasTitle;
    }

    public static void setRhiCanvasTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            rhiCanvasTitle = title;
        }
    }

    // ------------------------------------------------------------------------------------------
    // GPU Color-ID picking
    // ------------------------------------------------------------------------------------------

    /** Stable non-zero pick ID for {@code entity}; IDs are never reused within a session. */
    public static int pickIdFor(Entity entity) {
        if (entity == null) return IRenderBackend.PICK_ID_NONE;
        Integer id = pickIds.get(entity);
        if (id == null) {
            id = nextPickId++;
            if (nextPickId <= 0) nextPickId = 1; // 2^31 spawns: wrap, skipping NONE and the negative UNAVAILABLE
            pickIds.put(entity, id);
            pickEntities.put(id, new WeakReference<>(entity));
        }
        return id;
    }

    /** Entity drawn with {@code pickId}, or null for the background, unknown IDs, or entities no longer in the scene. */
    public static Entity resolvePick(int pickId, List<Entity> sceneEntities) {
        if (pickId == IRenderBackend.PICK_ID_NONE || pickId == IRenderBackend.PICK_UNAVAILABLE) return null;
        WeakReference<Entity> ref = pickEntities.get(pickId);
        Entity entity = (ref != null) ? ref.get() : null;
        if (ref != null && entity == null) pickEntities.remove(pickId);
        return (entity != null && sceneEntities != null && sceneEntities.contains(entity)) ? entity : null;
    }

    /** Scene-tile UV (origin top-left) of a screen-space NDC point, or {-1, -1} when the Scene tile is hidden. */
    private static float[] screenToSceneUv(DockSpace dock, float screenX, float screenY) {
        if (!dock.isSceneViewportVisible()) return new float[] { -1.0f, -1.0f };
        float x0 = dock.getContentX0(DockPanel.SCENE), x1 = dock.getContentX1(DockPanel.SCENE);
        float y0 = dock.getContentY0(DockPanel.SCENE), y1 = dock.getContentY1(DockPanel.SCENE);
        return new float[] { (screenX - x0) / (x1 - x0), (y1 - screenY) / (y1 - y0) };
    }

    /** Points the pick probe at the cursor (screen-space NDC); points off the Scene tile disarm it. */
    public static void setPickProbeScreen(DockSpace dock, float screenX, float screenY) {
        float[] uv = screenToSceneUv(dock, screenX, screenY);
        hoverU = uv[0];
        hoverV = uv[1];
    }

    /** Pick ID under a screen-space NDC point from the newest readback, or PICK_UNAVAILABLE. Never blocks. */
    public static int queryPickAtScreen(DockSpace dock, float screenX, float screenY) {
        if (pickBackend == null) return IRenderBackend.PICK_UNAVAILABLE;
        float[] uv = screenToSceneUv(dock, screenX, screenY);
        return pickBackend.queryPick(uv[0], uv[1]);
    }

    /** Pick ID under the hover probe from the newest readback, or PICK_UNAVAILABLE. */
    public static int queryPickAtHover() {
        return pickBackend != null ? pickBackend.queryPick(hoverU, hoverV) : IRenderBackend.PICK_UNAVAILABLE;
    }

    /**
     * Resolves a viewport click given in Scene design space. Answers at once when the readback already covers the texel;
     * otherwise the probe is moved there and {@code onResolved} runs from {@link #updatePicking} once it catches up.
     * A newer request replaces a pending one.
     */
    public static void requestPickAtDesign(DockSpace dock, float designX, float designY, IntConsumer onResolved) {
        float screenX = dock.toScreenX(DockPanel.SCENE, designX);
        float screenY = dock.toScreenY(DockPanel.SCENE, designY);
        float[] uv = screenToSceneUv(dock, screenX, screenY);
        if (uv[0] < 0.0f || uv[0] >= 1.0f || uv[1] < 0.0f || uv[1] >= 1.0f) return;

        int id = (pickBackend != null) ? pickBackend.queryPick(uv[0], uv[1]) : IRenderBackend.PICK_UNAVAILABLE;
        if (id != IRenderBackend.PICK_UNAVAILABLE) {
            pendingCallback = null;
            onResolved.accept(id);
            return;
        }
        pendingU = uv[0];
        pendingV = uv[1];
        pendingCallback = onResolved;
        pendingFrames = 0;
    }

    /**
     * Converts gizmo pick triangles captured in window NDC (x, y, id per vertex) into the Scene tile's own NDC for
     * {@link IRenderBackend#setPickOverlay}, reusing {@code out} when it is large enough. The pick target covers exactly
     * that tile, so every handle pixel lands on the texel the probe reads for the same cursor position.
     */
    public static float[] buildGizmoPickOverlay(float[] screenVerts, int vertexCount,
                                                float sceneMinX, float sceneMinY, float sceneMaxX, float sceneMaxY, float[] out) {
        int floats = vertexCount * IRenderBackend.PICK_OVERLAY_VERTEX_FLOATS;
        if (out == null || out.length < floats) out = new float[Math.max(floats, out != null ? out.length * 2 : 0)];
        float w = sceneMaxX - sceneMinX, h = sceneMaxY - sceneMinY;
        if (w <= 0.0f || h <= 0.0f) return out;
        for (int o = 0; o < floats; o += IRenderBackend.PICK_OVERLAY_VERTEX_FLOATS) {
            out[o] = (screenVerts[o] - sceneMinX) / w * 2.0f - 1.0f;
            out[o + 1] = (screenVerts[o + 1] - sceneMinY) / h * 2.0f - 1.0f;
            out[o + 2] = screenVerts[o + 2];
        }
        return out;
    }

    /** Once per submitted frame: resolves a pending click, then aims the probe (pending click first, else the cursor). */
    public static void updatePicking(IRenderBackend rhi) {
        pickBackend = rhi;
        if (rhi == null) return;
        if (pendingCallback != null) {
            int id = rhi.queryPick(pendingU, pendingV);
            if (id != IRenderBackend.PICK_UNAVAILABLE || ++pendingFrames > PICK_TIMEOUT_FRAMES) {
                IntConsumer callback = pendingCallback;
                pendingCallback = null;
                if (id != IRenderBackend.PICK_UNAVAILABLE) callback.accept(id);
            }
        }
        if (pendingCallback != null) rhi.setPickProbe(pendingU, pendingV);
        else rhi.setPickProbe(hoverU, hoverV);
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer, WorkspaceTab activeTab, Entity selectedEntity, long cameraPreviewTexture) {
        renderFraming(buffer, activeTab);
        renderCameraPreviewHUD(buffer, selectedEntity, cameraPreviewTexture);
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer, WorkspaceTab activeTab) {
        // Viewport Header Bar (-0.55f to 0.55f, 0.86f to 0.92f)
        UIGeometryBatch.addQuad(buffer, -0.55f, 0.86f, 0.55f, 0.92f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -0.55f, -0.50f, 0.55f, 0.92f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);

        boolean is3D = (activeTab == WorkspaceTab.SCENE_3D);

        // Tab 1: [3D SCENE] (-0.53f to -0.28f)
        float t1R = is3D ? 0.0f : 0.12f;
        float t1G = is3D ? 0.45f : 0.14f;
        float t1B = is3D ? 0.70f : 0.18f;
        UIGeometryBatch.addQuad(buffer, -0.53f, 0.865f, -0.28f, 0.915f, t1R, t1G, t1B, 1.0f);
        if (is3D) {
            UIGeometryBatch.addFrameBorder(buffer, -0.53f, 0.865f, -0.28f, 0.915f, 0.0015f, 0.0f, 0.8f, 1.0f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "3D SCENE", -0.515f, 0.882f, -0.29f, 1.0f, is3D ? 1.0f : 0.6f, is3D ? 1.0f : 0.6f, is3D ? 1.0f : 0.6f, 1.0f);

        // Tab 2: [ANIMATOR GRAPH] (-0.26f to -0.01f)
        float t2R = !is3D ? 0.0f : 0.12f;
        float t2G = !is3D ? 0.45f : 0.14f;
        float t2B = !is3D ? 0.70f : 0.18f;
        UIGeometryBatch.addQuad(buffer, -0.26f, 0.865f, -0.01f, 0.915f, t2R, t2G, t2B, 1.0f);
        if (!is3D) {
            UIGeometryBatch.addFrameBorder(buffer, -0.26f, 0.865f, -0.01f, 0.915f, 0.0015f, 0.0f, 0.8f, 1.0f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "ANIMATOR GRAPH", -0.245f, 0.882f, -0.02f, 1.0f, !is3D ? 1.0f : 0.6f, !is3D ? 1.0f : 0.6f, !is3D ? 1.0f : 0.6f, 1.0f);

        // Canvas Title Header (0.02f to 0.53f)
        String title = is3D ? rhiCanvasTitle : "ANIMATOR STATE MACHINE WORKSPACE";
        UIGeometryBatch.addBoundedText(buffer, title, 0.02f, 0.882f, 0.53f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer) {
        renderFraming(buffer, WorkspaceTab.SCENE_3D);
    }

    public static void renderFraming(List<Float> verts) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderFraming(tempBuf, WorkspaceTab.SCENE_3D);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    /**
     * Picture-in-Picture viewfinder card (bottom-right of the viewport) shown while a Camera entity is selected. Shows the
     * backend's live camera preview target when {@code cameraPreviewTexture} is non-zero, otherwise an empty crosshair frame.
     */
    public static void renderCameraPreviewHUD(UIGeometryBatch.Buffer buffer, Entity selectedEntity, long cameraPreviewTexture) {
        if (selectedEntity == null) return;
        Camera camera = selectedEntity.getComponent(Camera.class);
        if (camera == null) return;

        // Scene panel design space ends at x = 0.55; the card keeps the profiler HUD's 0.03 right margin
        float minX = 0.14f, maxX = 0.52f;
        float minY = -0.48f, maxY = -0.20f;

        UIGeometryBatch.addRoundedCard(buffer, minX, minY, maxX, maxY, EditorTheme.RADIUS * 1.5f, EditorTheme.BACKGROUND, EditorTheme.BORDER, 0.90f);

        // Header Title
        cameraTitleSb.setLength(0);
        cameraTitleSb.append("CAMERA PREVIEW: ").append(selectedEntity.name);
        UIGeometryBatch.addTruncatedText(buffer, cameraTitleSb, minX + 0.015f, maxY - 0.030f, maxX - 0.015f, 0.9f, EditorTheme.ACCENT, 1.0f);

        // Viewfinder Frame
        float vfX0 = minX + 0.02f, vfX1 = maxX - 0.02f;
        float vfY0 = minY + 0.04f, vfY1 = maxY - 0.05f;
        float borderX = UIGeometryBatch.squareWidth(buffer, EditorTheme.HAIRLINE);
        float imgX0 = vfX0 + borderX, imgX1 = vfX1 - borderX;
        float imgY0 = vfY0 + EditorTheme.HAIRLINE, imgY1 = vfY1 - EditorTheme.HAIRLINE;
        cameraPreviewAspect = (imgX1 - imgX0) / Math.max(1e-5f, UIGeometryBatch.squareWidth(buffer, imgY1 - imgY0));

        if (cameraPreviewTexture != 0L) {
            UIGeometryBatch.addCameraPreviewQuad(buffer, imgX0, imgY0, imgX1, imgY1, 1.0f);
        } else {
            UIGeometryBatch.addQuad(buffer, vfX0, vfY0, vfX1, vfY1, EditorTheme.SURFACE_SUNKEN, 1.0f);

            // Center Crosshairs
            float cx = (vfX0 + vfX1) * 0.5f, cy = (vfY0 + vfY1) * 0.5f;
            float armY = 0.025f, halfT = EditorTheme.HAIRLINE * 0.5f;
            float armX = UIGeometryBatch.squareWidth(buffer, armY);
            float halfTX = UIGeometryBatch.squareWidth(buffer, halfT);
            UIGeometryBatch.addQuad(buffer, cx - armX, cy - halfT, cx + armX, cy + halfT, EditorTheme.TEXT_MUTED, 0.5f);
            UIGeometryBatch.addQuad(buffer, cx - halfTX, cy - armY, cx + halfTX, cy + armY, EditorTheme.TEXT_MUTED, 0.5f);
        }
        UIGeometryBatch.addFrameBorder(buffer, vfX0, vfY0, vfX1, vfY1, EditorTheme.HAIRLINE, EditorTheme.BORDER_STRONG, 1.0f);

        // Footer Specs
        cameraSpecsSb.setLength(0);
        cameraSpecsSb.append("FOV: ");
        appendFloat1Decimal(cameraSpecsSb, camera.fov);
        cameraSpecsSb.append(" deg | NEAR: ");
        appendFloat2Decimal(cameraSpecsSb, camera.nearPlane);
        cameraSpecsSb.append(" | PRI: ").append(camera.isPrimary ? "YES" : "NO");
        UIGeometryBatch.addTruncatedText(buffer, cameraSpecsSb, minX + 0.015f, minY + 0.012f, maxX - 0.015f, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void appendFloat1Decimal(StringBuilder sb, float value) {
        int scaled = Math.round(value * 10.0f);
        if (scaled < 0) { sb.append('-'); scaled = -scaled; }
        sb.append(scaled / 10).append('.').append(scaled % 10);
    }

    private static void appendFloat2Decimal(StringBuilder sb, float value) {
        int scaled = Math.round(value * 100.0f);
        if (scaled < 0) { sb.append('-'); scaled = -scaled; }
        int frac = scaled % 100;
        sb.append(scaled / 100).append('.');
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    public static void renderToolbar(UIGeometryBatch.Buffer buffer, GizmoMode currentGizmoMode) {
        float barX1 = -0.52f, barY1 = 0.79f, barX2 = -0.10f, barY2 = 0.85f;
        UIGeometryBatch.addQuad(buffer, barX1, barY1, barX2, barY2, 0.10f, 0.10f, 0.13f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, barX1, barY1, barX2, barY2, 0.002f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Button 1: [1/W] MOVE
        float btn1X1 = -0.51f, btn1X2 = -0.38f;
        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            UIGeometryBatch.addQuad(buffer, btn1X1, barY1 + 0.005f, btn1X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 2: [2/E] ROT
        float btn2X1 = -0.37f, btn2X2 = -0.25f;
        if (currentGizmoMode == GizmoMode.ROTATE) {
            UIGeometryBatch.addQuad(buffer, btn2X1, barY1 + 0.005f, btn2X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 3: [3/R] SCL
        float btn3X1 = -0.24f, btn3X2 = -0.11f;
        if (currentGizmoMode == GizmoMode.SCALE) {
            UIGeometryBatch.addQuad(buffer, btn3X1, barY1 + 0.005f, btn3X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }
    }

    public static void renderToolbar(List<Float> verts, GizmoMode currentGizmoMode) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderToolbar(tempBuf, currentGizmoMode);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}
