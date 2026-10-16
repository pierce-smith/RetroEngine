package com.retro.engine.editor;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.audio.UISoundManager;
import com.retro.engine.editor.audio.UISoundManager.UISound;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.commands.TransformCommand;
import com.retro.engine.editor.delegates.AssetDirectoryManager;
import com.retro.engine.editor.delegates.EditorOverlayManager;
import com.retro.engine.editor.delegates.GizmoStateController;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.input.GizmoInteractor;
import com.retro.engine.editor.input.InspectorInteractor;
import com.retro.engine.editor.input.PanelInteractor;
import com.retro.engine.editor.input.TopBarInteractor;
import com.retro.engine.editor.ui.*;

import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;

/**
 * Ultra-lean EditorPanelManager acting as a Backwards-Compatible Facade Bridge.
 * All domain responsibilities are fully delegated to AssetDirectoryManager,
 * GizmoStateController, and EditorOverlayManager.
 */
@SuppressWarnings("unused")
public class EditorPanelManager {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER, PARENT_PICKER }

    private final SceneManager sceneManager;
    private final CommandHistory commandHistory = new CommandHistory();

    // Isolated Delegate Sub-Managers
    private final AssetDirectoryManager assetDirectoryManager = new AssetDirectoryManager();
    private final GizmoStateController gizmoStateController = new GizmoStateController();
    private final EditorOverlayManager overlayManager = new EditorOverlayManager();

    private ViewportCameraController cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float[] cachedUiVertices = null;

    public EditorPanelManager() { this(new SceneManager()); }

    public EditorPanelManager(SceneManager sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneManager();
    }

    // --- WINDOW MANAGEMENT ---
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

    // --- SCENE & PLAYBACK LIFECYCLE ---
    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }
    public void update(float deltaTime) { sceneManager.update(deltaTime); }

    public List<SceneEntity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public SceneEntity getSelectedEntity() { return sceneManager.getSelectedEntity(); }
    public void selectEntity(SceneEntity entity) { sceneManager.selectEntity(entity); }
    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }

    public void newScene() {
        sceneManager.newScene();
        assetDirectoryManager.clear();
    }

    public void promptOpenSceneWithNativeDialog() {
        UISoundManager.play(UISound.OPEN_MODAL);
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            UISoundManager.play(UISound.SPAWN);
            loadSceneFromFile(importedPath);
        } else {
            UISoundManager.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog() {
        UISoundManager.play(UISound.OPEN_MODAL);
        sceneManager.promptSaveSceneWithNativeDialog(assetDirectoryManager.getMutableAssetDirectory());
    }

    public void saveActiveScene() { sceneManager.saveActiveScene(getActiveScenePath(), assetDirectoryManager.getMutableAssetDirectory()); }
    public void saveActiveScene(String filePath) { sceneManager.saveActiveScene(filePath, assetDirectoryManager.getMutableAssetDirectory()); }

    public void loadSceneFromFile(String filePath) {
        com.retro.engine.serialization.SceneSerializer.SceneLoadData data = sceneManager.loadSceneFromFile(filePath);
        assetDirectoryManager.clear();
        if (data != null) {
            if (data.assetDirectory() != null && !data.assetDirectory().isEmpty()) {
                assetDirectoryManager.restoreAssetDirectory(data.assetDirectory());
            }
            if (data.entities() != null) {
                for (SceneEntity entity : data.entities()) {
                    com.retro.engine.ecs.components.MeshRendererComponent mc = entity.getComponent(com.retro.engine.ecs.components.MeshRendererComponent.class);
                    if (mc != null && mc.meshAssetPath != null && !mc.meshAssetPath.trim().isEmpty()) {
                        assetDirectoryManager.addAssetDirectoryIfNotExists(mc.meshAssetPath, AssetType.MESH_3D);
                    }
                    com.retro.engine.ecs.components.MaterialComponent mat = entity.getComponent(com.retro.engine.ecs.components.MaterialComponent.class);
                    if (mat != null && mat.textureAssetPath != null && !mat.textureAssetPath.trim().isEmpty()) {
                        assetDirectoryManager.addAssetDirectoryIfNotExists(mat.textureAssetPath, AssetType.TEXTURE);
                    }
                }
            }
        }
    }

    // --- DELEGATED ASSET DIRECTORY OPERATIONS ---
    public String getCurrentFolderPath() { return assetDirectoryManager.getCurrentFolderPath(); }
    public void setCurrentFolderPath(String path) { assetDirectoryManager.setCurrentFolderPath(path); }
    public void navigateUpFolder() { assetDirectoryManager.navigateUpFolder(); }
    public void createNewFolder() { assetDirectoryManager.createNewFolder(); }
    public List<AssetEntry> getAssetDirectory() { return assetDirectoryManager.getAssetDirectory(); }
    public void renameFolder(AssetEntry folder, String newName) { assetDirectoryManager.renameFolder(folder, newName); }
    public void promptRenameFolder(AssetEntry folder) { assetDirectoryManager.promptRenameFolder(folder); }
    public void promptRenameAsset(AssetEntry asset) { assetDirectoryManager.promptRenameAsset(asset); }
    public void deleteFolder(AssetEntry folder) { assetDirectoryManager.deleteFolder(folder); }
    public void removeAssetFromDirectory(AssetEntry asset) { assetDirectoryManager.removeAssetFromDirectory(asset); }
    public void onFilesDropped(List<String> filePaths) { assetDirectoryManager.onFilesDropped(filePaths); }

    // --- DELEGATED GIZMO & VIEWPORT STATE ---
    public GizmoMode getCurrentGizmoMode() { return gizmoStateController.getGizmoMode(); }
    public void setCurrentGizmoMode(GizmoMode mode) { gizmoStateController.setGizmoMode(mode); }
    public GizmoAxis getActiveGizmoAxis() { return gizmoStateController.getActiveAxis(); }
    public void setActiveGizmoAxis(GizmoAxis axis) { gizmoStateController.setActiveAxis(axis); }
    public boolean is3DGridVisible() { return gizmoStateController.is3DGridVisible(); }
    public void toggle3DGrid() { gizmoStateController.toggle3DGrid(); }
    public float[] get3DGridVertices() { return gizmoStateController.get3DGridVertices(); }
    public int get3DGridVertexCount() { return gizmoStateController.get3DGridVertexCount(); }
    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) { gizmoStateController.setCameraState(panX, panY, zoom, pitch, yaw); }
    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        return GizmoInteractor.projectWorldToNdc(wx, wy, wz, cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw());
    }

    // --- DELEGATED OVERLAY & MODAL OPERATIONS ---
    public DropdownMenu getActiveDropdownMenu() { return overlayManager.getActiveDropdownMenu(); }
    public void setActiveDropdownMenu(DropdownMenu menu) { overlayManager.setActiveDropdownMenu(menu); }
    public ModalState getActiveModal() { return overlayManager.getActiveModal(); }
    public AssetType getPickerFilterType() { return overlayManager.getPickerFilterType(); }
    public void openAssetPicker(AssetType filterType) { overlayManager.openAssetPicker(filterType); }
    public void closeModal() { overlayManager.closeModal(); }
    public SceneEntity getParentPickerTargetEntity() { return overlayManager.getParentPickerTargetEntity(); }
    public void openParentPicker(SceneEntity target) { overlayManager.openParentPicker(target); }
    public boolean isContextMenuVisible() { return overlayManager.isContextMenuVisible(); }
    public void closeContextMenu() { overlayManager.closeContextMenu(); }
    public void openContextMenu(float ndcX, float ndcY, List<ContextMenuItem> items) { overlayManager.openContextMenu(ndcX, ndcY, items); }

    // --- REGIONAL HIT-TESTING ---
    public PanelRegion getRegionAt(float ndcX, float ndcY) {
        return PanelRegion.getRegionAt(ndcX, ndcY, overlayManager);
    }

    // --- ENTITY MANIPULATION & COMMANDS ---
    public void deleteSelectedEntity() { if (getSelectedEntity() != null) { sceneManager.deleteSelectedEntity(); UISoundManager.play(UISound.DELETE); } }
    public void duplicateSelectedEntity() { if (getSelectedEntity() != null) { sceneManager.duplicateSelectedEntity(); UISoundManager.play(UISound.DUPLICATE); } }
    public void spawnDefaultCube() { sceneManager.spawnDefaultCube(); UISoundManager.play(UISound.SPAWN); }
    public void spawnLightSource() { sceneManager.spawnLightSource(); UISoundManager.play(UISound.SPAWN); }
    public void spawnCameraEntity() { sceneManager.spawnCameraEntity(); UISoundManager.play(UISound.SPAWN); }
    public void spawnEntityFromAsset(AssetEntry meshAsset) { sceneManager.spawnEntityFromAsset(meshAsset); UISoundManager.play(UISound.SPAWN); }
    public void assignTextureToSelectedEntity(AssetEntry textureAsset) { sceneManager.assignTextureToSelectedEntity(textureAsset); }
    public void assignMeshToSelectedEntity(AssetEntry meshAsset) { sceneManager.assignMeshToSelectedEntity(meshAsset); }
    public void spawnAudioSource() { sceneManager.spawnAudioSource(); UISoundManager.play(UISound.SPAWN); }
    public void assignAudioToSelectedEntity(AssetEntry audioAsset) { sceneManager.assignAudioToSelectedEntity(audioAsset); }
    public void setParentEntity(SceneEntity child, SceneEntity parent) { sceneManager.setParent(child, parent); }
    public void unparentEntity(SceneEntity child) { sceneManager.setParent(child, null); }

    public void spawnChildEntity(SceneEntity parent, String namePrefix, String meshAssetPath) {
        if (parent == null) return;
        String entityId = "entity_" + (getSceneEntities().size() + 1);
        String name = namePrefix + "_" + (getSceneEntities().size() + 1);
        SceneEntity child = new SceneEntity(entityId, name, meshAssetPath);
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        com.retro.engine.ecs.components.TransformComponent tc = child.getTransform();
        if (tc != null) { tc.posX = 1.0f; tc.posY = 0.0f; tc.posZ = 0.0f; }
        getSceneEntities().add(child);
        selectEntity(child);
        UISoundManager.play(UISound.SPAWN);
    }

    public void spawnChildLight(SceneEntity parent) {
        if (parent == null) return;
        String entityId = "light_" + (getSceneEntities().size() + 1);
        String name = "Light_" + (getSceneEntities().size() + 1);
        SceneEntity child = new SceneEntity(entityId, name, null);
        child.addComponent(new com.retro.engine.ecs.components.LightComponent());
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        getSceneEntities().add(child);
        selectEntity(child);
        UISoundManager.play(UISound.SPAWN);
    }

    public CommandHistory getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    // --- DIALOG & IMPORT PASSTHROUGHS ---
    public void promptImportTexture() { InspectorInteractor.promptImportTexture(this); }
    public void promptImportMesh() { InspectorInteractor.promptImportMesh(this); }
    public void promptImportAssetForDirectory() { InspectorInteractor.promptImportAssetForDirectory(this); }
    public void promptImportAudio() { InspectorInteractor.promptImportAudio(this); }

    // --- INPUT ROUTING & CLICK HANDLING ---
    public boolean handleMouseClick(double rawX, double rawY, int button, int action) {
        if (button != 0 && button != 1) return false;
        updateWindowDimensionsFromGlfw();

        float ndcX = (float) ((rawX / (double) windowWidth) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (rawY / (double) windowHeight) * 2.0);
        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        PanelRegion region = getRegionAt(ndcX, ndcY);

        if (action == 1) { // Press
            if (button == 1) {
                overlayManager.onRightClickPress(refX, refY, ndcX, ndcY, cameraController, gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw(), gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom());
                return true;
            }

            if (overlayManager.isContextMenuVisible()) {
                boolean consumed = ContextMenuOverlayManager.processClick(ndcX, ndcY, overlayManager.getContextMenuNdcX(), overlayManager.getContextMenuNdcY(), overlayManager.getContextMenuItems());
                closeContextMenu();
                return true;
            }

            if (overlayManager.getActiveModal() == ModalState.ASSET_PICKER) {
                TopBarInteractor.processModalClick(ndcX, ndcY, this);
                return true;
            }

            if (overlayManager.getActiveModal() == ModalState.PARENT_PICKER) {
                overlayManager.processParentPickerModalClick(ndcX, ndcY, this);
                return true;
            }

            if (overlayManager.getActiveDropdownMenu() != DropdownMenu.NONE) {
                if (TopBarInteractor.processDropdownOverlayClick(ndcX, ndcY, this)) {
                    setActiveDropdownMenu(DropdownMenu.NONE);
                }
                return true;
            }

            switch (region) {
                case TOP_BAR -> {
                    TopBarInteractor.processTopBarClick(ndcX, this);
                    return true;
                }
                case ASSET_DIRECTORY -> {
                    PanelInteractor.processAssetDirectoryClick(ndcX, ndcY, this);
                    return true;
                }
                case SCENE_HIERARCHY -> {
                    PanelInteractor.processHierarchyClick(ndcY, this);
                    return true;
                }
                case INSPECTOR -> {
                    InspectorInteractor.processInspectorClick(ndcX, ndcY, this);
                    return true;
                }
                case VIEWPORT -> {
                    if (gizmoStateController.processViewportToolbarClick(ndcX, ndcY)) {
                        return true;
                    }
                    gizmoStateController.processViewportGizmoClick(refX, refY, this);
                    return gizmoStateController.getActiveAxis() != GizmoAxis.NONE;
                }
                default -> {
                    return false;
                }
            }
        } else if (action == 0) { // Release
            if (button == 1) {
                overlayManager.onRightClickRelease(refX, refY, this, cameraController, gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw(), gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(), gizmoStateController.getLastOrbitTime());
                return true;
            }

            if (button == 0 && getActiveGizmoAxis() != GizmoAxis.NONE && getSelectedEntity() != null) {
                com.retro.engine.ecs.components.TransformComponent tc = getSelectedEntity().getTransform();
                if (tc != null) {
                    if (tc.posX != gizmoStateController.getDragStartPx() || tc.posY != gizmoStateController.getDragStartPy() || tc.posZ != gizmoStateController.getDragStartPz() ||
                        tc.rotX != gizmoStateController.getDragStartRx() || tc.rotY != gizmoStateController.getDragStartRy() || tc.rotZ != gizmoStateController.getDragStartRz() ||
                        tc.scaleX != gizmoStateController.getDragStartSx() || tc.scaleY != gizmoStateController.getDragStartSy() || tc.scaleZ != gizmoStateController.getDragStartSz()) {

                        TransformCommand cmd = new TransformCommand(
                            getSelectedEntity(),
                            gizmoStateController.getDragStartPx(), gizmoStateController.getDragStartPy(), gizmoStateController.getDragStartPz(),
                            gizmoStateController.getDragStartRx(), gizmoStateController.getDragStartRy(), gizmoStateController.getDragStartRz(),
                            gizmoStateController.getDragStartSx(), gizmoStateController.getDragStartSy(), gizmoStateController.getDragStartSz(),
                            tc.posX, tc.posY, tc.posZ,
                            tc.rotX, tc.rotY, tc.rotZ,
                            tc.scaleX, tc.scaleY, tc.scaleZ
                        );
                        commandHistory.executeCommand(cmd);
                    }
                }
                setActiveGizmoAxis(GizmoAxis.NONE);
                return true;
            }
        }
        return false;
    }

    public boolean onCursorPos(double rawX, double rawY) {
        updateWindowDimensionsFromGlfw();
        return gizmoStateController.onCursorPos(rawX, rawY, this, overlayManager);
    }

    // --- UI BATCH GENERATION ---
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
        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, getCurrentGizmoMode());

        SceneEntity selected = getSelectedEntity();
        if (selected != null && getActiveModal() == ModalState.NONE) {
            SceneManager.WorldTransform wt = sceneManager.getComputedWorldTransform(selected);
            GizmoInteractor.render3DGizmosAtWorldPosition(verts, wt.px(), wt.py(), wt.pz(), getCurrentGizmoMode(), getActiveGizmoAxis(), cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw());
        }

        SceneHierarchyPanel.render(verts, getSceneEntities());
        InspectorPanel.render(verts, getSelectedEntity(), getAssetDirectory());
        AssetDirectoryPanel.render(verts, getAssetDirectory(), getCurrentFolderPath());

        TopMenuBar.render(verts, getActiveDropdownMenu(), getActiveScenePath(), getPlayState());

        if (getActiveDropdownMenu() != DropdownMenu.NONE) {
            TopMenuBar.renderDropdownMenuOverlay(verts, getActiveDropdownMenu(), commandHistory, is3DGridVisible());
        }

        if (getActiveModal() == ModalState.ASSET_PICKER) {
            ModalOverlayManager.renderAssetPickerModal(verts, getPickerFilterType(), getAssetDirectory());
        } else if (getActiveModal() == ModalState.PARENT_PICKER && getParentPickerTargetEntity() != null) {
            ModalOverlayManager.renderEntityParentPickerModal(verts, getParentPickerTargetEntity(), getSceneEntities());
        }

        if (isContextMenuVisible()) {
            ContextMenuOverlayManager.render(verts, overlayManager.getContextMenuNdcX(), overlayManager.getContextMenuNdcY(), overlayManager.getContextMenuItems());
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }
}
