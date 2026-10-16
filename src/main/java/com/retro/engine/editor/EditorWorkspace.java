package com.retro.engine.editor;

import com.retro.engine.editor.gizmos.ViewportCamera;
import com.retro.engine.editor.ui.AssetBrowserPanel;
import com.retro.engine.editor.ui.ContextMenuOverlay;
import com.retro.engine.editor.ui.HierarchyPanel;
import com.retro.engine.editor.ui.InspectorPanel;
import com.retro.engine.editor.ui.MainMenuBar;
import com.retro.engine.editor.ui.MenuItem;
import com.retro.engine.editor.ui.ModalDialogOverlay;
import com.retro.engine.editor.ui.PanelRegion;
import com.retro.engine.editor.ui.ViewportPanel;
import com.retro.engine.scene.SceneSerializer.SceneLoadData;
import com.retro.engine.scene.SceneSerializer;
import com.retro.engine.scene.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.EntityHandle;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.SceneWorld.PlayState;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.CommandStack;
import com.retro.engine.editor.commands.TransformCommand;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.editor.ui.OverlaySystem;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.ui.InspectorInputDelegate;
import com.retro.engine.editor.ui.AssetBrowserInputDelegate;
import com.retro.engine.editor.ui.MainMenuBarInputDelegate;
import com.retro.engine.editor.ui.*;

import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;

/**
 * Ultra-lean EditorWorkspace acting as a Backwards-Compatible Facade Bridge.
 * All domain responsibilities are fully delegated to AssetDatabase,
 * GizmoState, and OverlaySystem.
 */
@SuppressWarnings("unused")
public class EditorWorkspace {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER, PARENT_PICKER }

    private final SceneWorld sceneManager;
    private final CommandStack commandHistory = new CommandStack();

    // Isolated Delegate Sub-Managers
    private final AssetDatabase assetDirectoryManager = new AssetDatabase();
    private final GizmoState gizmoStateController = new GizmoState();
    private final OverlaySystem overlayManager = new OverlaySystem();

    private ViewportCamera cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float[] cachedUiVertices = null;
    private final UIGeometryBatch.Buffer uiGeometryBuffer = new UIGeometryBatch.Buffer(16384);

    public EditorWorkspace() { this(new SceneWorld()); }

    public EditorWorkspace(SceneWorld sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneWorld();
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

    public void setCameraController(ViewportCamera cameraController) { this.cameraController = cameraController; }
    public ViewportCamera getCameraController() { return cameraController; }
    public SceneWorld getSceneManager() { return sceneManager; }

    // --- SCENE & PLAYBACK LIFECYCLE ---
    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }
    public void update(float deltaTime) { sceneManager.update(deltaTime); }

    public List<Entity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public Entity getSelectedEntity() { return sceneManager.getSelectedEntity(); }
    public void selectEntity(Entity entity) { sceneManager.selectEntity(entity); }

    // --- GENERATIONAL HANDLE SAFETY (PHASE 4) ---
    public EntityHandle getSelectedEntityHandle() { return sceneManager.getSelectedEntityHandle(); }
    public void selectEntityByHandle(EntityHandle handle) { sceneManager.selectEntityByHandle(handle); }
    public Entity getEntityByHandle(EntityHandle handle) { return sceneManager.getEntityByHandle(handle); }
    public boolean isValidEntityHandle(EntityHandle handle) { return sceneManager.isValidHandle(handle); }

    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }

    public void newScene() {
        sceneManager.newScene();
        assetDirectoryManager.clear();
    }

    public void promptOpenSceneWithNativeDialog() {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            loadSceneFromFile(importedPath);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog() {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        sceneManager.promptSaveSceneWithNativeDialog(assetDirectoryManager.getMutableAssetDirectory());
    }

    public void saveActiveScene() { sceneManager.saveActiveScene(getActiveScenePath(), assetDirectoryManager.getMutableAssetDirectory()); }
    public void saveActiveScene(String filePath) { sceneManager.saveActiveScene(filePath, assetDirectoryManager.getMutableAssetDirectory()); }

    public void loadSceneFromFile(String filePath) {
        com.retro.engine.scene.SceneSerializer.SceneLoadData data = sceneManager.loadSceneFromFile(filePath);
        assetDirectoryManager.clear();
        if (data != null) {
            if (data.assetDirectory() != null && !data.assetDirectory().isEmpty()) {
                assetDirectoryManager.restoreAssetDirectory(data.assetDirectory());
            }
            if (data.entities() != null) {
                for (Entity entity : data.entities()) {
                    com.retro.engine.scene.components.MeshFilter mc = entity.getComponent(com.retro.engine.scene.components.MeshFilter.class);
                    if (mc != null && mc.meshAssetPath != null && !mc.meshAssetPath.trim().isEmpty()) {
                        assetDirectoryManager.addAssetDirectoryIfNotExists(mc.meshAssetPath, AssetType.MESH_3D);
                    }
                    com.retro.engine.scene.components.Material mat = entity.getComponent(com.retro.engine.scene.components.Material.class);
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
    public List<AssetMeta> getAssetDirectory() { return assetDirectoryManager.getAssetDirectory(); }
    public void renameFolder(AssetMeta folder, String newName) { assetDirectoryManager.renameFolder(folder, newName); }
    public void promptRenameFolder(AssetMeta folder) { assetDirectoryManager.promptRenameFolder(folder); }
    public void promptRenameAsset(AssetMeta asset) { assetDirectoryManager.promptRenameAsset(asset); }
    public void deleteFolder(AssetMeta folder) { assetDirectoryManager.deleteFolder(folder); }
    public void removeAssetFromDirectory(AssetMeta asset) { assetDirectoryManager.removeAssetFromDirectory(asset); }
    public void onFilesDropped(List<String> filePaths) { assetDirectoryManager.onFilesDropped(filePaths); }
    public void scanDirectory(String path) { assetDirectoryManager.addAssetDirectoryIfNotExists(path, null); }
    public void scanDirectory() { scanDirectory("Assets"); }
    public void clearAssetDirectory() { assetDirectoryManager.clear(); }
    public void addAssetDirectoryIfNotExists(String path, AssetType type) { assetDirectoryManager.addAssetDirectoryIfNotExists(path, type); }

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
        return GizmoSystem.projectWorldToNdc(wx, wy, wz, cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw());
    }

    // --- DELEGATED OVERLAY & MODAL OPERATIONS ---
    public DropdownMenu getActiveDropdownMenu() { return overlayManager.getActiveDropdownMenu(); }
    public DropdownMenu getActiveDropdown() { return overlayManager.getActiveDropdownMenu(); }
    public void setActiveDropdownMenu(DropdownMenu menu) { overlayManager.setActiveDropdownMenu(menu); }
    public void setActiveDropdown(DropdownMenu menu) { overlayManager.setActiveDropdownMenu(menu); }

    public ModalState getActiveModal() { return overlayManager.getActiveModal(); }
    public void setActiveModal(ModalState modal) { overlayManager.closeModal(); if (modal != ModalState.NONE) { if (modal == ModalState.ASSET_PICKER) openAssetPicker(AssetType.TEXTURE); } }

    public AssetType getPickerFilterType() { return overlayManager.getPickerFilterType(); }
    public void openAssetPicker(AssetType filterType) { overlayManager.openAssetPicker(filterType); }
    public void openAssetPickerModal(AssetType filterType) { overlayManager.openAssetPicker(filterType); }

    public void closeModal() { overlayManager.closeModal(); }
    public Entity getParentPickerTargetEntity() { return overlayManager.getParentPickerTargetEntity(); }
    public void openParentPicker(Entity target) { overlayManager.openParentPicker(target); }
    public void openParentPickerModal(Entity target) { overlayManager.openParentPicker(target); }

    public boolean isContextMenuVisible() { return overlayManager.isContextMenuVisible(); }
    public void closeContextMenu() { overlayManager.closeContextMenu(); }
    public void hideContextMenu() { overlayManager.closeContextMenu(); }
    public void openContextMenu(float ndcX, float ndcY, List<MenuItem> items) { overlayManager.openContextMenu(ndcX, ndcY, items); }
    public void showContextMenu(float ndcX, float ndcY, List<MenuItem> items) { overlayManager.openContextMenu(ndcX, ndcX, items); }

    // --- REGIONAL HIT-TESTING ---
    public PanelRegion getRegionAt(float ndcX, float ndcY) {
        return PanelRegion.getRegionAt(ndcX, ndcY, overlayManager);
    }

    // --- ENTITY MANIPULATION & COMMANDS ---
    public void deleteSelectedEntity() { if (getSelectedEntity() != null) { sceneManager.deleteSelectedEntity(); UISoundPlayer.play(UISound.DELETE); } }
    public void duplicateSelectedEntity() { if (getSelectedEntity() != null) { sceneManager.duplicateSelectedEntity(); UISoundPlayer.play(UISound.DUPLICATE); } }
    public void spawnDefaultCube() { sceneManager.spawnDefaultCube(); UISoundPlayer.play(UISound.SPAWN); }
    public void spawnLightSource() { sceneManager.spawnLightSource(); UISoundPlayer.play(UISound.SPAWN); }
    public void spawnCameraEntity() { sceneManager.spawnCameraEntity(); UISoundPlayer.play(UISound.SPAWN); }
    public void spawnEntityFromAsset(AssetMeta meshAsset) { sceneManager.spawnEntityFromAsset(meshAsset); UISoundPlayer.play(UISound.SPAWN); }
    public void assignTextureToSelectedEntity(AssetMeta textureAsset) { sceneManager.assignTextureToSelectedEntity(textureAsset); }
    public void assignMeshToSelectedEntity(AssetMeta meshAsset) { sceneManager.assignMeshToSelectedEntity(meshAsset); }
    public void spawnAudioSource() { sceneManager.spawnAudioSource(); UISoundPlayer.play(UISound.SPAWN); }
    public void assignAudioToSelectedEntity(AssetMeta audioAsset) { sceneManager.assignAudioToSelectedEntity(audioAsset); }
    public void setParentEntity(Entity child, Entity parent) { sceneManager.setParent(child, parent); }
    public void unparentEntity(Entity child) { sceneManager.setParent(child, null); }

    public void spawnChildEntity(Entity parent, String namePrefix, String meshAssetPath) {
        if (parent == null) return;
        String entityId = "entity_" + (getSceneEntities().size() + 1);
        String name = namePrefix + "_" + (getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, meshAssetPath);
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        com.retro.engine.scene.components.Transform tc = child.getTransform();
        if (tc != null) { tc.posX = 1.0f; tc.posY = 0.0f; tc.posZ = 0.0f; }
        getSceneEntities().add(child);
        selectEntity(child);
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void spawnChildLight(Entity parent) {
        if (parent == null) return;
        String entityId = "light_" + (getSceneEntities().size() + 1);
        String name = "Light_" + (getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, null);
        child.addComponent(new com.retro.engine.scene.components.Light());
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        getSceneEntities().add(child);
        selectEntity(child);
        UISoundPlayer.play(UISound.SPAWN);
    }

    public CommandStack getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    // --- DIALOG & IMPORT PASSTHROUGHS ---
    public void promptImportTexture() { InspectorInputDelegate.promptImportTexture(this); }
    public void promptImportMesh() { InspectorInputDelegate.promptImportMesh(this); }
    public void promptImportAssetForDirectory() { InspectorInputDelegate.promptImportAssetForDirectory(this); }
    public void promptImportAudio() { InspectorInputDelegate.promptImportAudio(this); }

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
                boolean consumed = ContextMenuOverlay.processClick(ndcX, ndcY, overlayManager.getContextMenuNdcX(), overlayManager.getContextMenuNdcY(), overlayManager.getContextMenuItems());
                closeContextMenu();
                return true;
            }

            if (overlayManager.getActiveModal() == ModalState.ASSET_PICKER) {
                MainMenuBarInputDelegate.processModalClick(ndcX, ndcY, this);
                return true;
            }

            if (overlayManager.getActiveModal() == ModalState.PARENT_PICKER) {
                overlayManager.processParentPickerModalClick(ndcX, ndcY, this);
                return true;
            }

            if (overlayManager.getActiveDropdownMenu() != DropdownMenu.NONE) {
                if (MainMenuBarInputDelegate.processDropdownOverlayClick(ndcX, ndcY, this)) {
                    setActiveDropdownMenu(DropdownMenu.NONE);
                }
                return true;
            }

            switch (region) {
                case TOP_BAR -> {
                    MainMenuBarInputDelegate.processTopBarClick(ndcX, this);
                    return true;
                }
                case ASSET_DIRECTORY -> {
                    AssetBrowserInputDelegate.processAssetDirectoryClick(ndcX, ndcY, this);
                    return true;
                }
                case SCENE_HIERARCHY -> {
                    AssetBrowserInputDelegate.processHierarchyClick(ndcY, this);
                    return true;
                }
                case INSPECTOR -> {
                    InspectorInputDelegate.processInspectorClick(ndcX, ndcY, this);
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
                com.retro.engine.scene.components.Transform tc = getSelectedEntity().getTransform();
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
        uiGeometryBuffer.reset();
        ViewportPanel.renderFraming(uiGeometryBuffer);
        ViewportPanel.renderToolbar(uiGeometryBuffer, getCurrentGizmoMode());

        Entity selected = getSelectedEntity();
        if (selected != null && getActiveModal() == ModalState.NONE) {
            SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(selected);
            GizmoSystem.render3DGizmosAtWorldPosition(uiGeometryBuffer, wt.px(), wt.py(), wt.pz(), getCurrentGizmoMode(), getActiveGizmoAxis(), cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw());
        }

        HierarchyPanel.render(uiGeometryBuffer, getSceneEntities());
        InspectorPanel.render(uiGeometryBuffer, getSelectedEntity(), getAssetDirectory());
        AssetBrowserPanel.render(uiGeometryBuffer, getAssetDirectory(), getCurrentFolderPath());

        MainMenuBar.render(uiGeometryBuffer, getActiveDropdownMenu(), getActiveScenePath(), getPlayState());

        if (getActiveDropdownMenu() != DropdownMenu.NONE) {
            MainMenuBar.renderDropdownMenuOverlay(uiGeometryBuffer, getActiveDropdownMenu(), commandHistory, is3DGridVisible());
        }

        if (getActiveModal() == ModalState.ASSET_PICKER) {
            ModalDialogOverlay.renderAssetPickerModal(uiGeometryBuffer, getPickerFilterType(), getAssetDirectory());
        } else if (getActiveModal() == ModalState.PARENT_PICKER && getParentPickerTargetEntity() != null) {
            ModalDialogOverlay.renderEntityParentPickerModal(uiGeometryBuffer, getParentPickerTargetEntity(), getSceneEntities());
        }

        if (isContextMenuVisible()) {
            ContextMenuOverlay.render(uiGeometryBuffer, overlayManager.getContextMenuNdcX(), overlayManager.getContextMenuNdcY(), overlayManager.getContextMenuItems());
        }

        this.cachedUiVertices = uiGeometryBuffer.toFloatArray();
        return this.cachedUiVertices;
    }
}
