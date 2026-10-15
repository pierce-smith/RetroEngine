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
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.*;
import com.retro.engine.editor.input.GizmoInteractor;
import com.retro.engine.editor.input.InspectorInteractor;
import com.retro.engine.editor.input.TopBarInteractor;
import com.retro.engine.editor.input.PanelInteractor;

import org.lwjgl.system.MemoryStack;
import java.io.File;
import java.nio.IntBuffer;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Aggregated EditorPanelManager & Unified Input Router.
 * Automatically queries GLFW Window Size (Screen Points) to compute 100% exact GPU NDC coordinates,
 * resolving window resize / Retina display hitbox drift across all panels.
 */
public class EditorPanelManager {
    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER }

    private final SceneManager sceneManager;
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private final CommandHistory commandHistory = new CommandHistory();

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;
    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;

    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;
    private float[] cachedUiVertices = null;

    private ViewportCameraController cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    private float camPanX = 0.0f, camPanY = 0.25f, camZoom = -5.0f, camPitch = 0.0f, camYaw = 0.0f;

    public EditorPanelManager() { this(new SceneManager()); }

    public EditorPanelManager(SceneManager sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneManager();
    }

    public void setWindowHandle(long handle) {
        this.windowHandle = handle;
        updateWindowDimensionsFromGlfw();
    }

    public void setWindowDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.windowWidth = width;
            this.windowHeight = height;
        }
    }

    private void updateWindowDimensionsFromGlfw() {
        if (windowHandle != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetWindowSize(windowHandle, w, h);
                if (w.get(0) > 0 && h.get(0) > 0) {
                    this.windowWidth = w.get(0);
                    this.windowHeight = h.get(0);
                }
            } catch (Exception ignored) {}
        }
    }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }

    public void setCameraController(ViewportCameraController cameraController) { this.cameraController = cameraController; }
    public ViewportCameraController getCameraController() { return cameraController; }
    public SceneManager getSceneManager() { return sceneManager; }

    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }
    public void update(float deltaTime) { sceneManager.update(deltaTime); }

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

    public CommandHistory getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    public GizmoMode getCurrentGizmoMode() { return currentGizmoMode; }
    public void setCurrentGizmoMode(GizmoMode mode) { this.currentGizmoMode = mode; }
    public GizmoAxis getActiveGizmoAxis() { return activeGizmoAxis; }
    public void setActiveGizmoAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }
    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public void setActiveDropdownMenu(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public ModalState getActiveModal() { return activeModal; }
    public AssetType getPickerFilterType() { return pickerFilterType; }
    public void openAssetPicker(AssetType filterType) { this.pickerFilterType = filterType; this.activeModal = ModalState.ASSET_PICKER; }
    public void closeModal() { this.activeModal = ModalState.NONE; }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() { this.show3DGrid = !this.show3DGrid; cached3DGridVertices = null; }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    // --- UNIFIED GPU NDC INPUT ROUTER ---
    public void handleMouseClick(double rawX, double rawY, int button, int action) {
        if (button != 0) return; // Left Click

        updateWindowDimensionsFromGlfw();

        float ndcX = (float) ((rawX / (double) windowWidth) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (rawY / (double) windowHeight) * 2.0);

        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        if (action == 1) { // PRESS
            this.lastMouseX = refX;
            this.lastMouseY = refY;

            // 1. Modal Picker Overlay
            if (activeModal == ModalState.ASSET_PICKER) {
                TopBarInteractor.processModalClick(ndcX, ndcY, this);
                return;
            }

            // 2. Dropdown Menu Overlay
            if (activeDropdownMenu != DropdownMenu.NONE) {
                if (TopBarInteractor.processDropdownOverlayClick(ndcX, ndcY, this)) {
                    activeDropdownMenu = DropdownMenu.NONE;
                    return;
                }
            }

            // 3. Region Routing by NDC Bounds
            if (ndcY >= 0.92f) {
                TopBarInteractor.processTopBarClick(ndcX, this);
            } else if (ndcY <= -0.42f) {
                PanelInteractor.processAssetDirectoryClick(ndcX, ndcY, this);
            } else if (ndcX <= -0.55f) {
                PanelInteractor.processHierarchyClick(ndcY, this);
            } else if (ndcX >= 0.55f) {
                processInspectorClick(ndcX, ndcY);
            } else {
                if (!processViewportToolbarClick(ndcX, ndcY)) {
                    processViewportGizmoClick(refX, refY);
                }
            }
        } else if (action == 0) { // RELEASE
            if (activeGizmoAxis != GizmoAxis.NONE) {
                activeGizmoAxis = GizmoAxis.NONE;
            }
        }
    }

    public boolean onCursorPos(double rawX, double rawY) {
        updateWindowDimensionsFromGlfw();

        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        double deltaX = refX - lastMouseX;
        double deltaY = refY - lastMouseY;
        this.lastMouseX = refX;
        this.lastMouseY = refY;

        if (activeGizmoAxis != GizmoAxis.NONE && getSelectedEntity() != null) {
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, getSelectedEntity(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
    }

    private void processTopBarClick(float ndcX) {
        TopBarInteractor.processTopBarClick(ndcX, this);
    }

    private boolean processDropdownOverlayClick(float ndcX, float ndcY) {
        return TopBarInteractor.processDropdownOverlayClick(ndcX, ndcY, this);
    }

    private void processHierarchyClick(float ndcY) {
        PanelInteractor.processHierarchyClick(ndcY, this);
    }

    private boolean processViewportToolbarClick(float ndcX, float ndcY) {
        if (ndcY >= 0.80f && ndcY <= 0.86f) {
            if (ndcX >= -0.51f && ndcX <= -0.38f) { setCurrentGizmoMode(GizmoMode.TRANSLATE); return true; }
            if (ndcX >= -0.37f && ndcX <= -0.25f) { setCurrentGizmoMode(GizmoMode.ROTATE); return true; }
            if (ndcX >= -0.24f && ndcX <= -0.11f) { setCurrentGizmoMode(GizmoMode.SCALE); return true; }
        }
        return false;
    }

    private void processViewportGizmoClick(double refX, double refY) {
        this.activeGizmoAxis = GizmoInteractor.pickGizmoAxis(refX, refY, getSelectedEntity(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    private void processInspectorClick(float ndcX, float ndcY) {
        InspectorInteractor.processInspectorClick(ndcX, ndcY, this);
    }

    private void processAssetDirectoryClick(float ndcX, float ndcY) {
        PanelInteractor.processAssetDirectoryClick(ndcX, ndcY, this);
    }

    private void processModalClick(float ndcX, float ndcY) {
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        // Outside modal or [X] close button
        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) { closeModal(); return; }
        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) { closeModal(); return; }

        // [NONE / UNBIND] Button
        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= 0.27f && ndcY <= 0.33f) {
            SceneEntity selected = getSelectedEntity();
            if (selected != null) {
                if (pickerFilterType == AssetType.TEXTURE) selected.setTextureAsset(null, "DEFAULT_CHECKER");
                else if (pickerFilterType == AssetType.MESH_3D) selected.setMeshAssetPath(null);
            }
            closeModal();
            return;
        }

        // [IMPORT DISK...] Button inside Modal Overlay
        if (ndcX >= 0.07f && ndcX <= 0.42f && ndcY >= 0.27f && ndcY <= 0.33f) {
            closeModal();
            if (pickerFilterType == AssetType.TEXTURE) InspectorInteractor.promptImportTexture(this);
            else if (pickerFilterType == AssetType.MESH_3D) InspectorInteractor.promptImportMesh(this);
            return;
        }

        // Item List Selection
        List<AssetEntry> filtered = assetDirectory.stream().filter(a -> a.type == pickerFilterType).toList();
        float listY = cardMaxY - 0.24f;
        for (AssetEntry asset : filtered) {
            if (listY < cardMinY + 0.05f) break;
            if (ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                if (pickerFilterType == AssetType.TEXTURE) assignTextureToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.MESH_3D) assignMeshToSelectedEntity(asset);
                closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }

    // --- 3D GRID GENERATION (Preserving origin axes: Red X, Green Y, Blue Z) ---
    public float[] get3DGridVertices() {
        if (!show3DGrid) return new float[0];
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) cached3DGridVertices = generate3DGridVertices();
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (!show3DGrid) return 0;
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    private float[] generate3DGridVertices() {
        List<Float> verts = new ArrayList<>();
        int gridSize = 10;
        for (int i = -gridSize; i <= gridSize; i++) {
            float alpha = (i == 0) ? 0.9f : 0.35f;
            float rZ = (i == 0) ? 0.2f : 0.4f, gZ = (i == 0) ? 0.2f : 0.4f, bZ = (i == 0) ? 1.0f : 0.5f;
            addGridLine(verts, i, 0, -gridSize, i, 0, gridSize, rZ, gZ, bZ, alpha);

            float rX = (i == 0) ? 1.0f : 0.4f, gX = (i == 0) ? 0.2f : 0.4f, bX = (i == 0) ? 0.2f : 0.5f;
            addGridLine(verts, -gridSize, 0, i, gridSize, 0, i, rX, gX, bX, alpha);
        }
        // Vertical Green Y-Axis Line
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.2f, 1.0f, 0.2f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        return GizmoInteractor.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
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


    // --- NATIVE OS FILE DIALOG PROMPTS ---
    public void promptImportTexture() {
        InspectorInteractor.promptImportTexture(this);
    }

    public void promptImportMesh() {
        InspectorInteractor.promptImportMesh(this);
    }

    public void promptImportAssetForDirectory() {
        InspectorInteractor.promptImportAssetForDirectory(this);
    }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null) getUiVertices();
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
        GizmoInteractor.render3DGizmos(verts, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }
}
