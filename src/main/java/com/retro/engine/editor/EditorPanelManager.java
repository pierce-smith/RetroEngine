package com.retro.engine.editor;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.ui.*;

import java.io.File;
import java.util.*;

/**
 * EditorPanelManager orchestrates the editor 4-panel layout, top menu bar dropdowns,
 * Play/Pause simulation state toggles, modal dialog overlays, and command history.
 * Bound to ViewportCameraController for Single Source of Truth camera projection.
 */
public class EditorPanelManager {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER }

    private final SceneManager sceneManager;
    private final List<AssetEntry> assetDirectory = new ArrayList<>();

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;
    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;

    // Modal Overlays
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;

    private final CommandHistory commandHistory = new CommandHistory();
    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;
    private float[] cachedUiVertices = null;

    // Viewport Camera Reference (Single Source of Truth)
    private ViewportCameraController cameraController;

    // Fallback Viewport Camera parameters
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    public EditorPanelManager() {
        this(new SceneManager());
    }

    public EditorPanelManager(SceneManager sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneManager();
    }

    public void setCameraController(ViewportCameraController cameraController) {
        this.cameraController = cameraController;
    }

    public ViewportCameraController getCameraController() {
        return cameraController;
    }

    public SceneManager getSceneManager() {
        return sceneManager;
    }

    // --- Simulation State Delegation ---
    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }

    // --- Delegated Scene Operations ---
    public List<SceneEntity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public SceneEntity getSelectedEntity() { return sceneManager.getSelectedEntity(); }
    public void selectEntity(SceneEntity entity) { sceneManager.selectEntity(entity); }
    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }
    public void newScene() { sceneManager.newScene(); }
    public void promptOpenSceneWithNativeDialog() { sceneManager.promptOpenSceneWithNativeDialog(); }
    public void promptSaveSceneWithNativeDialog() { sceneManager.promptSaveSceneWithNativeDialog(); }
    public void saveActiveScene() { sceneManager.saveActiveScene(); }
    public void saveActiveScene(String filePath) { sceneManager.saveActiveScene(filePath); }
    public void loadSceneFromFile(String filePath) { sceneManager.loadSceneFromFile(filePath); }
    public void deleteSelectedEntity() { sceneManager.deleteSelectedEntity(); }
    public void spawnDefaultCube() { sceneManager.spawnDefaultCube(); }
    public void spawnLightSource() { sceneManager.spawnLightSource(); }
    public void spawnEntityFromAsset(AssetEntry meshAsset) { sceneManager.spawnEntityFromAsset(meshAsset); }
    public void assignTextureToSelectedEntity(AssetEntry textureAsset) { sceneManager.assignTextureToSelectedEntity(textureAsset); }
    public void assignMeshToSelectedEntity(AssetEntry meshAsset) { sceneManager.assignMeshToSelectedEntity(meshAsset); }

    // --- Grid & Viewport Navigation ---
    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() {
        this.show3DGrid = !this.show3DGrid;
        System.out.println("[View] Toggle 3D Grid -> " + (this.show3DGrid ? "VISIBLE" : "HIDDEN"));
    }

    public CommandHistory getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    /**
     * Projects 3D world coordinates (wx, wy, wz) to 2D Editor UI Canvas space.
     * Guaranteed 1:1 match with MetalBackend's 3D perspective matrix.
     */
    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        float aspect = 712.0f / 488.0f;
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float activeYaw = (cameraController != null) ? cameraController.getYaw() : camYaw;
        float activePitch = (cameraController != null) ? cameraController.getPitch() : camPitch;
        float activePanX = (cameraController != null) ? cameraController.getPanX() : camPanX;
        float activePanY = (cameraController != null) ? cameraController.getPanY() : camPanY;
        float activeZoom = (cameraController != null) ? cameraController.getZoom() : camZoom;

        float radYaw = (float) Math.toRadians(activeYaw);
        float radPitch = (float) Math.toRadians(activePitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        float vx = cy * wx - sy * wz + activePanX;
        float vy = sy * sp * wx + cp * wy + cy * sp * wz + activePanY;
        float vz = sy * cp * wx - sp * wy + cy * cp * wz + activeZoom;

        float clipW = -vz;
        if (clipW <= 0.001f) clipW = 0.001f;

        float ndcX = (p00 * vx) / clipW;
        float ndcY = (p11 * vy) / clipW;

        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public void update(float deltaTime) {
        sceneManager.update(deltaTime);
    }

    public float[] get3DGridVertices() {
        if (!show3DGrid) return new float[0];
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) {
            cached3DGridVertices = generate3DGridVertices();
        }
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (!show3DGrid) return 0;
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    public float[] generate3DGridVertices() {
        List<Float> verts = new ArrayList<>();
        int extent = 15;
        float y = 0.0f;

        for (int z_i = -extent; z_i <= extent; z_i++) {
            float z = (float) z_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (z_i == 0) { r = 1.0f; g = 0.25f; b = 0.25f; a = 1.0f; }
            addGridLine(verts, -extent, y, z, extent, y, z, r, g, b, a);
        }

        for (int x_i = -extent; x_i <= extent; x_i++) {
            float x = (float) x_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (x_i == 0) { r = 0.25f; g = 0.55f; b = 1.0f; a = 1.0f; }
            addGridLine(verts, x, y, -extent, x, y, extent, r, g, b, a);
        }

        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.25f, 1.0f, 0.25f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();
            AssetType type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "tga" -> AssetType.TEXTURE;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
            assetDirectory.add(new AssetEntry(name, path, type));
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }

    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }
    public GizmoMode getCurrentGizmoMode() { return currentGizmoMode; }
    public void setCurrentGizmoMode(GizmoMode mode) {
        this.currentGizmoMode = mode;
        System.out.println("[Gizmo] Switched Gizmo Mode -> " + mode);
    }

    public GizmoAxis getActiveGizmoAxis() { return activeGizmoAxis; }
    public void setActiveGizmoAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }
    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public void setActiveDropdownMenu(DropdownMenu menu) {
        this.activeDropdownMenu = menu;
        if (menu != DropdownMenu.NONE) {
            System.out.println("[Top Menu Bar] Opened Dropdown -> " + menu);
        }
    }

    public ModalState getActiveModal() { return activeModal; }
    public AssetType getPickerFilterType() { return pickerFilterType; }

    public void openAssetPicker(AssetType filterType) {
        this.pickerFilterType = filterType;
        this.activeModal = ModalState.ASSET_PICKER;
        System.out.println("[EditorUI] Opened In-Engine Asset Picker Modal for type: " + filterType);
    }

    public void closeModal() {
        this.activeModal = ModalState.NONE;
        System.out.println("[EditorUI] Closed Modal Overlay.");
    }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null || cachedUiVertices.length == 0) getUiVertices();
        return cachedUiVertices.length / 6;
    }

    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();

        SceneHierarchyPanel.render(verts, getSceneEntities());
        InspectorPanel.render(verts, getSelectedEntity(), assetDirectory);
        AssetDirectoryPanel.render(verts, assetDirectory);

        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, currentGizmoMode);

        TopMenuBar.render(verts, activeDropdownMenu, getActiveScenePath(), getPlayState());

        SceneEntity activeEntity = getSelectedEntity();
        if (activeEntity != null) {
            render3DGizmos(verts, activeEntity, currentGizmoMode, activeGizmoAxis);
        }

        if (activeDropdownMenu != DropdownMenu.NONE) {
            TopMenuBar.renderDropdownMenuOverlay(verts, activeDropdownMenu, commandHistory, show3DGrid);
        }

        if (activeModal == ModalState.ASSET_PICKER) {
            ModalOverlayManager.renderAssetPickerModal(verts, pickerFilterType, assetDirectory);
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis) {
        TransformComponent tc = entity.getTransform();
        if (tc == null) return;

        float px = tc.posX;
        float py = tc.posY;
        float pz = tc.posZ;

        float[] p0 = projectWorldToNdc(px, py, pz);
        float cx = p0[0];
        float cy = p0[1];

        float axisWorldLen = 1.0f;

        float[] pX = projectWorldToNdc(px + axisWorldLen, py, pz);
        float[] pY = projectWorldToNdc(px, py + axisWorldLen, pz);
        float[] pZ = projectWorldToNdc(px, py, pz + axisWorldLen);

        float dirXX = pX[0] - cx, dirXY = pX[1] - cy;
        float dirYX = pY[0] - cx, dirYY = pY[1] - cy;
        float dirZX = pZ[0] - cx, dirZY = pZ[1] - cy;

        float lenX = (float) Math.hypot(dirXX, dirXY);
        float lenY = (float) Math.hypot(dirYX, dirYY);
        float lenZ = (float) Math.hypot(dirZX, dirZY);

        float screenLen = 0.16f;

        float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
        float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

        float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
        float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

        float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
        float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

        float xTipX = cx + uXX * screenLen, xTipY = cy + uXY * screenLen;
        float yTipX = cx + uYX * screenLen, yTipY = cy + uYY * screenLen;
        float zTipX = cx + uZX * screenLen, zTipY = cy + uZY * screenLen;

        float lineThick = 0.006f;

        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        float rx = 1.0f, rg = 0.25f, rb = 0.25f;
        float gx = 0.25f, gg = 1.0f, gb = 0.25f;
        float bx = 0.25f, bg = 0.55f, bb = 1.0f;

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        if (mode == GizmoMode.TRANSLATE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, xTipX + uXX * 0.02f, xTipY + uXY * 0.02f, uXX, uXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, yTipX + uYX * 0.02f, yTipY + uYY * 0.02f, uYX, uYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zTipX + uZX * 0.02f, zTipY + uZY * 0.02f, uZX, uZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }
}
