package com.retro.engine.editor;

import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.*;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.ui.*;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.EntityHandle;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.SceneWorld.PlayState;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.components.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Ultra-lean EditorWorkspace acting as a Backwards-Compatible Facade Bridge.
 */
@SuppressWarnings("unused")
public class EditorWorkspace {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER, PARENT_PICKER }

    private final SceneWorld sceneManager;
    private final CommandStack commandHistory = new CommandStack();

    private final AssetDatabase assetDirectoryManager = new AssetDatabase();
    private final GizmoState gizmoStateController = new GizmoState();
    private final OverlaySystem overlayManager = new OverlaySystem();

    private ViewportCamera cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float lastDeltaTime = 0.016f;

    private float lastNdcX = 0.0f;
    private float lastNdcY = 0.0f;

    private float[] cachedUiVertices = new float[16384 * 6];
    private int cachedUiVertexCount = 0;
    private final UIGeometryBatch.Buffer uiGeometryBuffer = new UIGeometryBatch.Buffer(16384 * 6);

    public EditorWorkspace() { this(new SceneWorld()); }

    public EditorWorkspace(SceneWorld sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneWorld();
        EngineConsolePanel.installConsoleRedirect();
    }

    public void setWindowHandle(long handle) { this.windowHandle = handle; }

    public void setWindowDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.windowWidth = width;
            this.windowHeight = height;
        }
    }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }

    public void setCameraController(ViewportCamera cameraController) { this.cameraController = cameraController; }
    public ViewportCamera getCameraController() { return cameraController; }
    public void resetViewportCamera() { if (cameraController != null) cameraController.reset(); }
    public SceneWorld getSceneManager() { return sceneManager; }

    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }
    public void update(float deltaTime) { 
        this.lastDeltaTime = deltaTime;
        sceneManager.update(deltaTime); 
    }

    public List<Entity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public Entity getSelectedEntity() { return sceneManager.getSelectedEntity(); }
    public void selectEntity(Entity entity) { 
        sceneManager.selectEntity(entity);
        InspectorPanel.resetScroll();
    }

    public void deleteEntity(Entity entity) { if (entity != null) sceneManager.deleteEntity(entity); }
    public void addEntity(Entity entity, int index) { if (entity != null) sceneManager.addEntity(entity, index); }

    public EntityHandle getSelectedEntityHandle() { return sceneManager.getSelectedEntityHandle(); }
    public void selectEntityByHandle(EntityHandle handle) { sceneManager.selectEntityByHandle(handle); }
    public Entity getEntityByHandle(EntityHandle handle) { return sceneManager.getEntityByHandle(handle); }
    public boolean isValidEntityHandle(EntityHandle handle) { return sceneManager.isValidHandle(handle); }

    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }

    public void newScene() {
        sceneManager.newScene();
        assetDirectoryManager.clear();
        assetDirectoryManager.scanWorkspaceDirectory("Assets");
    }

    public void promptOpenSceneWithNativeDialog() {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            loadSceneFromFile(importedPath);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog() {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        sceneManager.promptSaveSceneWithNativeDialog(assetDirectoryManager.getMutableAssetDirectory(), assetDirectoryManager);
    }

    public void saveActiveScene() { sceneManager.saveActiveScene(getActiveScenePath(), assetDirectoryManager.getMutableAssetDirectory(), assetDirectoryManager); }
    public void saveActiveScene(String filePath) { sceneManager.saveActiveScene(filePath, assetDirectoryManager.getMutableAssetDirectory(), assetDirectoryManager); }

    public void loadSceneFromFile(String filePath) {
        assetDirectoryManager.clear();
        assetDirectoryManager.scanWorkspaceDirectory("Assets");
        com.retro.engine.scene.SceneSerializer.SceneLoadData data = sceneManager.loadSceneFromFile(filePath, assetDirectoryManager);

        if (data != null) {
            if (data.assetDirectory() != null && !data.assetDirectory().isEmpty()) {
                assetDirectoryManager.restoreAssetDirectory(data.assetDirectory());
            }
            if (data.entities() != null) {
                for (Entity entity : data.entities()) {
                    MeshFilter mc = entity.getComponent(MeshFilter.class);
                    if (mc != null && mc.meshAssetPath != null && !mc.meshAssetPath.trim().isEmpty()) {
                        assetDirectoryManager.addAssetDirectoryIfNotExists(mc.meshAssetPath, AssetType.MESH_3D);
                    }
                    Material mat = entity.getComponent(Material.class);
                    if (mat != null && mat.textureAssetPath != null && !mat.textureAssetPath.trim().isEmpty()) {
                        assetDirectoryManager.addAssetDirectoryIfNotExists(mat.textureAssetPath, AssetType.TEXTURE);
                    }
                }
            }
        }
    }

    public void promptSaveSelectedAsPrefab() {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        sceneManager.promptSaveSelectedAsPrefab(assetDirectoryManager.getMutableAssetDirectory(), assetDirectoryManager);
    }

    public void instantiatePrefab(String prefabPath) {
        if (prefabPath == null || prefabPath.trim().isEmpty()) return;
        List<Entity> spawned = sceneManager.instantiatePrefab(prefabPath, assetDirectoryManager);
        if (spawned != null && !spawned.isEmpty()) {
            assetDirectoryManager.addAssetDirectoryIfNotExists(prefabPath, AssetType.PREFAB);
        }
    }

    public void spawnPrefabFromAsset(AssetMeta prefabAsset) {
        if (prefabAsset == null) return;
        instantiatePrefab(prefabAsset.absolutePath);
    }

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

    public DropdownMenu getActiveDropdownMenu() { return overlayManager.getActiveDropdownMenu(); }
    public DropdownMenu getActiveDropdown() { return overlayManager.getActiveDropdownMenu(); }
    public void setActiveDropdownMenu(DropdownMenu menu) { overlayManager.setActiveDropdownMenu(menu); }
    public void setActiveDropdown(DropdownMenu menu) { overlayManager.setActiveDropdownMenu(menu); }
    public void closeDropdownMenu() { overlayManager.closeDropdownMenu(); }

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
    public void showContextMenu(float ndcX, float ndcY, List<MenuItem> items) { overlayManager.openContextMenu(ndcX, ndcY, items); }

    public PanelRegion getRegionAt(float ndcX, float ndcY) {
        return PanelRegion.getRegionAt(ndcX, ndcY, overlayManager);
    }

    public void promptRenameEntity(Entity entity) {
        if (entity == null) return;
        String input = NativeFileDialog.promptInput("Rename Entity", "Enter new entity name:", entity.name);
        if (input != null && !input.trim().isEmpty() && !input.trim().equalsIgnoreCase(entity.name)) {
            String newName = input.trim();
            EntityRenameCommand cmd = new EntityRenameCommand(entity, entity.name, newName);
            commandHistory.executeCommand(cmd);
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Scene] Renamed entity to '" + newName + "'");
        }
    }

    public void deleteSelectedEntity() {
        Entity selected = getSelectedEntity();
        if (selected != null) {
            String entityName = selected.name;
            EntityDeleteCommand cmd = new EntityDeleteCommand(this, selected);
            commandHistory.executeCommand(cmd);
            UISoundPlayer.play(UISound.DELETE);
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Scene] Deleted entity '" + entityName + "'.");
        }
    }

    public void duplicateSelectedEntity() {
        if (getSelectedEntity() != null) {
            sceneManager.duplicateSelectedEntity();
            Entity newlySpawned = getSelectedEntity();
            if (newlySpawned != null) {
                EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
                commandHistory.executeCommand(cmd);
            }
            UISoundPlayer.play(UISound.DUPLICATE);
        }
    }

    public void spawnDefaultCube() {
        sceneManager.spawnDefaultCube();
        Entity newlySpawned = getSelectedEntity();
        if (newlySpawned != null) {
            EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
            commandHistory.executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void spawnLightSource() {
        sceneManager.spawnLightSource();
        Entity newlySpawned = getSelectedEntity();
        if (newlySpawned != null) {
            EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
            commandHistory.executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void spawnCameraEntity() {
        sceneManager.spawnCameraEntity();
        Entity newlySpawned = getSelectedEntity();
        if (newlySpawned != null) {
            EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
            commandHistory.executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void spawnEntityFromAsset(AssetMeta meshAsset) {
        sceneManager.spawnEntityFromAsset(meshAsset);
        Entity newlySpawned = getSelectedEntity();
        if (newlySpawned != null) {
            EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
            commandHistory.executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void assignTextureToSelectedEntity(AssetMeta textureAsset) {
        Entity selected = getSelectedEntity();
        if (selected == null || textureAsset == null) return;
        Material mat = selected.getComponent(Material.class);
        if (mat == null) return;
        modifyFieldViaCommand(mat, "textureAssetPath", mat.textureAssetPath, textureAsset.absolutePath, "TEXTURE");
    }

    public void assignMeshToSelectedEntity(AssetMeta meshAsset) {
        Entity selected = getSelectedEntity();
        if (selected == null || meshAsset == null) return;
        MeshFilter mc = selected.getComponent(MeshFilter.class);
        if (mc == null) return;
        modifyFieldViaCommand(mc, "meshAssetPath", mc.meshAssetPath, meshAsset.absolutePath, "MESH_3D");
    }

    public void assignScriptToSelectedEntity(AssetMeta scriptAsset) {
        Entity selected = getSelectedEntity();
        if (selected == null) return;
        ScriptComponent sc = selected.getComponent(ScriptComponent.class);
        String oldPath = (sc != null) ? sc.scriptAssetPath : null;
        String oldName = (sc != null) ? sc.scriptName : null;
        String newPath = (scriptAsset != null) ? scriptAsset.absolutePath : null;
        String newName = (scriptAsset != null) ? scriptAsset.name : null;
        ScriptAssignCommand cmd = new ScriptAssignCommand(selected, oldPath, oldName, newPath, newName);
        commandHistory.executeCommand(cmd);
        InspectorPanel.resetScroll();
    }

    public void spawnAudioSource() {
        sceneManager.spawnAudioSource();
        Entity newlySpawned = getSelectedEntity();
        if (newlySpawned != null) {
            EntitySpawnCommand cmd = new EntitySpawnCommand(this, newlySpawned);
            commandHistory.executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void assignAudioToSelectedEntity(AssetMeta audioAsset) {
        Entity selected = getSelectedEntity();
        if (selected == null || audioAsset == null) return;
        AudioSource ac = selected.getComponent(AudioSource.class);
        if (ac == null) {
            ac = new AudioSource(audioAsset.absolutePath);
            selected.addComponent(ac);
        } else {
            modifyFieldViaCommand(ac, "audioAssetPath", ac.audioAssetPath, audioAsset.absolutePath, "AUDIO");
        }
        InspectorPanel.resetScroll();
    }

    private void modifyFieldViaCommand(Component comp, String fieldName, Object oldVal, Object newVal, String label) {
        try {
            Field field = comp.getClass().getDeclaredField(fieldName);
            ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, oldVal, newVal, label);
            commandHistory.executeCommand(cmd);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setParentEntity(Entity child, Entity parent) {
        if (child == null) return;
        Entity oldParent = null;
        if (child.parentId != null && !child.parentId.isEmpty()) {
            for (Entity e : getSceneEntities()) {
                if (e.id.equalsIgnoreCase(child.parentId)) {
                    oldParent = e;
                    break;
                }
            }
        }
        ParentChangeCommand cmd = new ParentChangeCommand(this, child, oldParent, parent);
        commandHistory.executeCommand(cmd);
    }

    public void unparentEntity(Entity child) { setParentEntity(child, null); }

    public void spawnChildEntity(Entity parent, String namePrefix, String meshAssetPath) {
        if (parent == null) return;
        String entityId = "entity_" + (getSceneEntities().size() + 1);
        String name = namePrefix + "_" + (getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, meshAssetPath);
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        Transform tc = child.getTransform();
        if (tc != null) { tc.posX = 1.0f; tc.posY = 0.0f; tc.posZ = 0.0f; }
        EntitySpawnCommand cmd = new EntitySpawnCommand(this, child);
        commandHistory.executeCommand(cmd);
        UISoundPlayer.play(UISound.SPAWN);
    }

    public void spawnChildLight(Entity parent) {
        if (parent == null) return;
        String entityId = "light_" + (getSceneEntities().size() + 1);
        String name = "Light_" + (getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, null);
        child.addComponent(new Light());
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        EntitySpawnCommand cmd = new EntitySpawnCommand(this, child);
        commandHistory.executeCommand(cmd);
        UISoundPlayer.play(UISound.SPAWN);
    }

    public CommandStack getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    public void promptImportTexture() { InspectorInputDelegate.promptImportTexture(this); }
    public void promptImportMesh() { InspectorInputDelegate.promptImportMesh(this); }
    public void promptImportAssetForDirectory() { InspectorInputDelegate.promptImportAssetForDirectory(this); }
    public void promptImportAudio() { InspectorInputDelegate.promptImportAudio(this); }
    public void promptImportScript() { InspectorInputDelegate.promptImportScript(this); }

    public boolean handleMouseClick(double rawX, double rawY, int button, int action) {
        if (button != 0 && button != 1) return false;

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
                if (ndcY >= 0.92f) {
                    MainMenuBarInputDelegate.processTopBarClick(ndcX, this);
                } else {
                    MainMenuBarInputDelegate.processDropdownOverlayClick(ndcX, ndcY, this);
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
                Transform tc = getSelectedEntity().getTransform();
                if (tc != null) {
                    if (tc.posX != gizmoStateController.getDragStartPx() || tc.posY != gizmoStateController.getDragStartPy() || tc.posZ != gizmoStateController.getDragStartPz() ||
                        tc.rotX != gizmoStateController.getDragStartRx() || tc.rotY != gizmoStateController.getDragStartRy() || tc.rotZ != gizmoStateController.getDragStartRz() ||
                        tc.scaleX != gizmoStateController.getDragStartSx() || tc.scaleY != gizmoStateController.getDragStartSy() || tc.scaleZ != gizmoStateController.getDragStartSz()) {

                        TransformCommand cmd = new TransformCommand(tc,
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
        if (windowWidth > 0 && windowHeight > 0) {
            this.lastNdcX = (float) ((rawX / (double) windowWidth) * 2.0 - 1.0);
            this.lastNdcY = (float) (1.0 - (rawY / (double) windowHeight) * 2.0);
        }
        return gizmoStateController.onCursorPos(rawX, rawY, this, overlayManager);
    }

    public boolean handleCursorPos(double rawX, double rawY) {
        return onCursorPos(rawX, rawY);
    }

    public boolean handleScroll(double yOffset) {
        if (lastNdcX >= 0.55f && lastNdcY >= -0.50f && lastNdcY <= 0.88f) {
            InspectorPanel.scroll((float) yOffset * 0.06f);
            return true;
        }
        if (cameraController != null) {
            cameraController.onScroll(yOffset);
            return true;
        }
        return false;
    }

    public float[] getUiVertices() {
        return generateUiVertices();
    }

    public int getUiVertexCount() {
        return this.cachedUiVertexCount;
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

        EngineConsolePanel.renderProfilerHUD(uiGeometryBuffer, lastDeltaTime);
        if (EngineConsolePanel.isConsoleVisible()) {
            EngineConsolePanel.renderConsole(uiGeometryBuffer);
        }

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

        int size = uiGeometryBuffer.getSize();
        if (cachedUiVertices.length < size) {
            cachedUiVertices = new float[size * 2];
        }
        System.arraycopy(uiGeometryBuffer.getArray(), 0, cachedUiVertices, 0, size);
        this.cachedUiVertexCount = size / 6;

        return this.cachedUiVertices;
    }
}
