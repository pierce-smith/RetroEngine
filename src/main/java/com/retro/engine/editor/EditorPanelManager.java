package com.retro.engine.editor;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.commands.TransformCommand;
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

@SuppressWarnings("unused")
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

    private String currentFolderPath = "";

    private boolean contextMenuVisible = false;
    private float contextMenuNdcX = 0.0f;
    private float contextMenuNdcY = 0.0f;
    private final List<ContextMenuItem> contextMenuItems = new ArrayList<>();

    private boolean isRightMouseDown = false;
    private double rightClickPressX = 0.0;
    private double rightClickPressY = 0.0;
    private float rightClickPressNdcX = 0.0f;
    private float rightClickPressNdcY = 0.0f;
    private double rightClickDragDistance = 0.0;
    private float pressCamPitch = 0.0f, pressCamYaw = 0.0f, pressCamPanX = 0.0f, pressCamPanY = 0.0f, pressCamZoom = 0.0f;
    private long lastOrbitTime = 0L;

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

    private float dragStartPx, dragStartPy, dragStartPz;
    private float dragStartRx, dragStartRy, dragStartRz;
    private float dragStartSx, dragStartSy, dragStartSz;

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
    public void newScene() {
        sceneManager.newScene();
        this.assetDirectory.clear();
    }
    public void promptOpenSceneWithNativeDialog() {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog(
            "Open Scene File", defaultDir, "Retro Scene (*.scene)", "scene"
        );
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            loadSceneFromFile(importedPath);
        }
    }
    public void promptSaveSceneWithNativeDialog() {
        sceneManager.promptSaveSceneWithNativeDialog(assetDirectory);
    }
    public void saveActiveScene() {
        sceneManager.saveActiveScene(getActiveScenePath(), assetDirectory);
    }
    public void saveActiveScene(String filePath) {
        sceneManager.saveActiveScene(filePath, assetDirectory);
    }
    public void loadSceneFromFile(String filePath) {
        com.retro.engine.serialization.SceneSerializer.SceneLoadData data = sceneManager.loadSceneFromFile(filePath);
        this.assetDirectory.clear();
        if (data != null) {
            if (data.assetDirectory() != null && !data.assetDirectory().isEmpty()) {
                this.assetDirectory.addAll(data.assetDirectory());
                System.out.println("[EditorPanelManager] Restored " + data.assetDirectory().size() + " Asset Directory entry(ies) from scene file.");
            }
            if (data.entities() != null) {
                for (SceneEntity entity : data.entities()) {
                    com.retro.engine.ecs.components.MeshRendererComponent mc = entity.getComponent(com.retro.engine.ecs.components.MeshRendererComponent.class);
                    if (mc != null && mc.meshAssetPath != null && !mc.meshAssetPath.trim().isEmpty()) {
                        addAssetDirectoryIfNotExists(mc.meshAssetPath, AssetType.MESH_3D);
                    }
                    com.retro.engine.ecs.components.MaterialComponent mat = entity.getComponent(com.retro.engine.ecs.components.MaterialComponent.class);
                    if (mat != null && mat.textureAssetPath != null && !mat.textureAssetPath.trim().isEmpty()) {
                        addAssetDirectoryIfNotExists(mat.textureAssetPath, AssetType.TEXTURE);
                    }
                }
            }
        }
    }

        public String getCurrentFolderPath() { return currentFolderPath; }
    public void setCurrentFolderPath(String path) { this.currentFolderPath = (path != null) ? path : ""; }

    public void navigateUpFolder() {
        if (currentFolderPath == null || currentFolderPath.isEmpty()) return;
        int lastSlash = currentFolderPath.lastIndexOf('/');
        if (lastSlash != -1) {
            currentFolderPath = currentFolderPath.substring(0, lastSlash);
        } else {
            currentFolderPath = "";
        }
    }

    public void createNewFolder() {
        int count = 1;
        for (AssetEntry entry : assetDirectory) {
            if (entry.type == AssetType.FOLDER && entry.parentFolderPath.equalsIgnoreCase(currentFolderPath)) {
                count++;
            }
        }
        String defaultName = "New_Folder_" + count;
        String inputName = NativeFileDialog.promptInput("Create New Folder", "Enter folder name:", defaultName);
        if (inputName == null) return; // User pressed Cancel
        String folderName = !inputName.trim().isEmpty() ? inputName.trim() : defaultName;

        String virtualPath = currentFolderPath.isEmpty() ? folderName : currentFolderPath + "/" + folderName;
        assetDirectory.add(new AssetEntry(folderName, virtualPath, AssetType.FOLDER, currentFolderPath));
        System.out.println("[EditorPanelManager] Created new folder: " + folderName + " in " + (currentFolderPath.isEmpty() ? "Assets" : currentFolderPath));
    }

    private void addAssetDirectoryIfNotExists(String absolutePath, AssetType type) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) return;
        File file = new File(absolutePath);
        if (type == AssetType.UNKNOWN || type == null) {
            String ext = getFileExtension(file.getName()).toLowerCase();
            type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
        }
        for (AssetEntry entry : assetDirectory) {
            if (entry.absolutePath != null && entry.absolutePath.equalsIgnoreCase(absolutePath)) {
                if (entry.type == AssetType.UNKNOWN && type != AssetType.UNKNOWN) {
                    entry.type = type;
                }
                return;
            }
        }
        assetDirectory.add(new AssetEntry(file.getName(), absolutePath, type, currentFolderPath));
    }
    public void deleteSelectedEntity() { sceneManager.deleteSelectedEntity(); }
    public void duplicateSelectedEntity() { sceneManager.duplicateSelectedEntity(); }
    public void spawnDefaultCube() { sceneManager.spawnDefaultCube(); }
    public void spawnLightSource() { sceneManager.spawnLightSource(); }
    public void spawnCameraEntity() { sceneManager.spawnCameraEntity(); }
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

    public boolean isContextMenuVisible() { return contextMenuVisible; }
    public void closeContextMenu() { this.contextMenuVisible = false; this.contextMenuItems.clear(); }
    public void openContextMenu(float ndcX, float ndcY, List<ContextMenuItem> items) {
        this.contextMenuNdcX = ndcX;
        this.contextMenuNdcY = ndcY;
        this.contextMenuItems.clear();
        if (items != null) this.contextMenuItems.addAll(items);
        this.contextMenuVisible = true;
    }

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() { this.show3DGrid = !this.show3DGrid; cached3DGridVertices = null; }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        if (Math.abs(this.camPitch - pitch) > 0.05f || Math.abs(this.camYaw - yaw) > 0.05f || Math.abs(this.camPanX - panX) > 0.01f || Math.abs(this.camPanY - panY) > 0.01f) {
            this.lastOrbitTime = System.currentTimeMillis();
        }
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    public void handleMouseClick(double rawX, double rawY, int button, int action) {
        if (button != 0 && button != 1) return;

        updateWindowDimensionsFromGlfw();

        float ndcX = (float) ((rawX / (double) windowWidth) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (rawY / (double) windowHeight) * 2.0);

        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        if (action == 1) { // Press
            this.lastMouseX = refX;
            this.lastMouseY = refY;

            // Handle Right Click Press (button == 1)
            if (button == 1) {
                if (contextMenuVisible) closeContextMenu();
                activeDropdownMenu = DropdownMenu.NONE;
                closeModal();

                this.isRightMouseDown = true;
                this.rightClickPressX = refX;
                this.rightClickPressY = refY;
                this.rightClickPressNdcX = ndcX;
                this.rightClickPressNdcY = ndcY;
                this.rightClickDragDistance = 0.0;

                this.pressCamPitch = (cameraController != null) ? cameraController.getPitch() : camPitch;
                this.pressCamYaw = (cameraController != null) ? cameraController.getYaw() : camYaw;
                this.pressCamPanX = (cameraController != null) ? cameraController.getPanX() : camPanX;
                this.pressCamPanY = (cameraController != null) ? cameraController.getPanY() : camPanY;
                this.pressCamZoom = (cameraController != null) ? cameraController.getZoom() : camZoom;
                return;
            }

            // Handle Left Click (button == 0)
            if (contextMenuVisible) {
                boolean consumed = ContextMenuOverlayManager.processClick(ndcX, ndcY, contextMenuNdcX, contextMenuNdcY, contextMenuItems);
                closeContextMenu();
                if (consumed) return;
            }

            if (activeModal == ModalState.ASSET_PICKER) {
                TopBarInteractor.processModalClick(ndcX, ndcY, this);
                return;
            }

            if (activeDropdownMenu != DropdownMenu.NONE) {
                if (TopBarInteractor.processDropdownOverlayClick(ndcX, ndcY, this)) {
                    activeDropdownMenu = DropdownMenu.NONE;
                    return;
                }
            }

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
        } else if (action == 0) { // Release
            if (button == 1) { // Right Click Release
                if (isRightMouseDown) {
                    isRightMouseDown = false;

                    double directDragDist = Math.hypot(refX - rightClickPressX, refY - rightClickPressY);

                    float currentPitch = (cameraController != null) ? cameraController.getPitch() : camPitch;
                    float currentYaw = (cameraController != null) ? cameraController.getYaw() : camYaw;
                    float currentPanX = (cameraController != null) ? cameraController.getPanX() : camPanX;
                    float currentPanY = (cameraController != null) ? cameraController.getPanY() : camPanY;
                    float currentZoom = (cameraController != null) ? cameraController.getZoom() : camZoom;

                    boolean cameraMoved = Math.abs(currentPitch - pressCamPitch) > 0.1f ||
                                          Math.abs(currentYaw - pressCamYaw) > 0.1f ||
                                          Math.abs(currentPanX - pressCamPanX) > 0.01f ||
                                          Math.abs(currentPanY - pressCamPanY) > 0.01f ||
                                          Math.abs(currentZoom - pressCamZoom) > 0.01f;

                    boolean recentOrbit = (System.currentTimeMillis() - lastOrbitTime) < 350;

                    if (!cameraMoved && !recentOrbit && rightClickDragDistance < 12.0 && directDragDist < 12.0) {
                        buildAndShowContextMenu(rightClickPressNdcX, rightClickPressNdcY, rightClickPressX, rightClickPressY);
                    }
                }
                return;
            }

            if (button == 0 && activeGizmoAxis != GizmoAxis.NONE && getSelectedEntity() != null) {
                TransformComponent tc = getSelectedEntity().getTransform();
                if (tc != null) {
                    if (tc.posX != dragStartPx || tc.posY != dragStartPy || tc.posZ != dragStartPz ||
                        tc.rotX != dragStartRx || tc.rotY != dragStartRy || tc.rotZ != dragStartRz ||
                        tc.scaleX != dragStartSx || tc.scaleY != dragStartSy || tc.scaleZ != dragStartSz) {

                        TransformCommand cmd = new TransformCommand(
                            getSelectedEntity(),
                            dragStartPx, dragStartPy, dragStartPz,
                            dragStartRx, dragStartRy, dragStartRz,
                            dragStartSx, dragStartSy, dragStartSz,
                            tc.posX, tc.posY, tc.posZ,
                            tc.rotX, tc.rotY, tc.rotZ,
                            tc.scaleX, tc.scaleY, tc.scaleZ
                        );
                        commandHistory.executeCommand(cmd);
                    }
                }
                activeGizmoAxis = GizmoAxis.NONE;
            }
        }
    }

    private void buildAndShowContextMenu(float ndcX, float ndcY, double refX, double refY) {
        List<ContextMenuItem> items = new ArrayList<>();

        if (ndcY >= 0.92f) {
            items.add(new ContextMenuItem("New Scene", this::newScene));
            items.add(new ContextMenuItem("Open Scene... (Cmd+O)", this::promptOpenSceneWithNativeDialog));
            items.add(new ContextMenuItem("Save Scene... (Cmd+S)", this::promptSaveSceneWithNativeDialog));
        } else if (ndcY <= -0.42f) {
            String activePath = (currentFolderPath != null) ? currentFolderPath : "";
            List<AssetEntry> visibleAssets = assetDirectory.stream()
                    .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                    .toList();

            boolean hasUpLevel = !activePath.isEmpty();
            float cardX = -0.95f;
            AssetEntry hitCard = null;
            boolean hitUpLevel = false;

            if (hasUpLevel) {
                if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                    hitUpLevel = true;
                }
                cardX += 0.36f;
            }

            if (!hitUpLevel) {
                for (AssetEntry asset : visibleAssets) {
                    if (cardX + 0.35f > 0.95f) break;
                    if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                        hitCard = asset;
                        break;
                    }
                    cardX += 0.36f;
                }
            }

            if (hitUpLevel) {
                items.add(new ContextMenuItem(".. Return to Parent Folder", this::navigateUpFolder));
                items.add(new ContextMenuItem("+ Create Folder Here", this::createNewFolder));
                items.add(new ContextMenuItem("+ Import Asset Here...", this::promptImportAssetForDirectory));
            } else if (hitCard != null) {
                AssetEntry targetAsset = hitCard;
                if (targetAsset.type == AssetType.FOLDER) {
                    String folderName = targetAsset.name;
                    String childPath = activePath.isEmpty() ? folderName : activePath + "/" + folderName;
                    items.add(new ContextMenuItem("Open Folder '" + folderName + "'", () -> setCurrentFolderPath(childPath)));
                    items.add(new ContextMenuItem("Rename Folder", () -> promptRenameFolder(targetAsset)));
                    items.add(new ContextMenuItem("Delete Folder", () -> deleteFolder(targetAsset)));
                } else {
                    if (targetAsset.type == AssetType.MESH_3D) {
                        items.add(new ContextMenuItem("Spawn Mesh Entity", () -> spawnEntityFromAsset(targetAsset)));
                    } else if (targetAsset.type == AssetType.TEXTURE) {
                        items.add(new ContextMenuItem("Link Texture to Selected", () -> assignTextureToSelectedEntity(targetAsset)));
                    }
                    items.add(new ContextMenuItem("Rename Asset", () -> promptRenameAsset(targetAsset)));
                    items.add(new ContextMenuItem("Remove Asset from Directory", () -> removeAssetFromDirectory(targetAsset)));
                }
            } else {
                items.add(new ContextMenuItem("+ Create Folder", this::createNewFolder));
                items.add(new ContextMenuItem("+ Import Asset...", this::promptImportAssetForDirectory));
                items.add(new ContextMenuItem("+ Import Texture...", this::promptImportTexture));
                items.add(new ContextMenuItem("+ Import 3D Mesh...", this::promptImportMesh));
                if (!currentFolderPath.isEmpty()) {
                    items.add(new ContextMenuItem(".. Up Level (" + currentFolderPath + ")", this::navigateUpFolder));
                }
            }
        } else if (ndcX <= -0.55f) {
            List<SceneEntity> entities = getSceneEntities();
            int index = (int) ((0.85f - ndcY) / 0.08f);
            if (index >= 0 && index < entities.size()) {
                selectEntity(entities.get(index));
                items.add(new ContextMenuItem("Duplicate Entity (Cmd+D)", this::duplicateSelectedEntity));
                items.add(new ContextMenuItem("Delete Entity (Del)", this::deleteSelectedEntity));
                items.add(new ContextMenuItem("+ Spawn 3D Cube", this::spawnDefaultCube));
                items.add(new ContextMenuItem("+ Spawn Light Source", this::spawnLightSource));
                items.add(new ContextMenuItem("+ Spawn Camera", this::spawnCameraEntity));
                items.add(new ContextMenuItem("Deselect All", () -> selectEntity(null)));
            } else {
                items.add(new ContextMenuItem("+ Spawn 3D Cube", this::spawnDefaultCube));
                items.add(new ContextMenuItem("+ Spawn Light Source", this::spawnLightSource));
                items.add(new ContextMenuItem("+ Spawn Camera", this::spawnCameraEntity));
                items.add(new ContextMenuItem("New Scene", this::newScene));
            }
        } else if (ndcX >= 0.55f) {
            if (getSelectedEntity() != null) {
                items.add(new ContextMenuItem("Duplicate Entity (Cmd+D)", this::duplicateSelectedEntity));
                items.add(new ContextMenuItem("Delete Entity (Del)", this::deleteSelectedEntity));
            }
            items.add(new ContextMenuItem("+ Import Asset...", this::promptImportAssetForDirectory));
        } else {
            SceneEntity bestEntity = null;
            double minDistance = 60.0;

            for (SceneEntity entity : getSceneEntities()) {
                TransformComponent tc = entity.getTransform();
                if (tc == null) continue;

                float[] p0 = GizmoInteractor.projectWorldToNdc(tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
                double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

                double dist = Math.hypot(refX - cx, refY - cy);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestEntity = entity;
                }
            }

            if (bestEntity != null) {
                selectEntity(bestEntity);
                items.add(new ContextMenuItem("Duplicate Object (Cmd+D)", this::duplicateSelectedEntity));
                items.add(new ContextMenuItem("Delete Object (Del)", this::deleteSelectedEntity));
                items.add(new ContextMenuItem("Reset Position", () -> {
                    if (getSelectedEntity() != null && getSelectedEntity().getTransform() != null) {
                        TransformComponent tc = getSelectedEntity().getTransform();
                        tc.posX = 0.0f; tc.posY = 0.0f; tc.posZ = 0.0f;
                    }
                }));
            } else {
                items.add(new ContextMenuItem("+ Spawn 3D Cube", this::spawnDefaultCube));
                items.add(new ContextMenuItem("+ Spawn Light Source", this::spawnLightSource));
                items.add(new ContextMenuItem("+ Spawn Camera", this::spawnCameraEntity));
                items.add(new ContextMenuItem("Toggle 3D Grid", this::toggle3DGrid));
            }
        }

        openContextMenu(ndcX, ndcY, items);
    }

    public boolean onCursorPos(double rawX, double rawY) {
        updateWindowDimensionsFromGlfw();

        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        double deltaX = refX - lastMouseX;
        double deltaY = refY - lastMouseY;
        this.lastMouseX = refX;
        this.lastMouseY = refY;

        if (isRightMouseDown) {
            this.rightClickDragDistance += Math.hypot(deltaX, deltaY);
            this.lastOrbitTime = System.currentTimeMillis();
        }

        if (activeGizmoAxis != GizmoAxis.NONE && getSelectedEntity() != null) {
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, getSelectedEntity(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
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
        if (this.activeGizmoAxis != GizmoAxis.NONE && getSelectedEntity() != null) {
            TransformComponent tc = getSelectedEntity().getTransform();
            if (tc != null) {
                this.dragStartPx = tc.posX; this.dragStartPy = tc.posY; this.dragStartPz = tc.posZ;
                this.dragStartRx = tc.rotX; this.dragStartRy = tc.rotY; this.dragStartRz = tc.rotZ;
                this.dragStartSx = tc.scaleX; this.dragStartSy = tc.scaleY; this.dragStartSz = tc.scaleZ;
            }
        } else {
            SceneEntity bestEntity = null;
            double minDistance = 60.0;

            for (SceneEntity entity : getSceneEntities()) {
                TransformComponent tc = entity.getTransform();
                if (tc == null) continue;

                float[] p0 = GizmoInteractor.projectWorldToNdc(tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
                double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

                double dist = Math.hypot(refX - cx, refY - cy);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestEntity = entity;
                }
            }

            if (bestEntity != null) {
                selectEntity(bestEntity);
            }
        }
    }

    private void processInspectorClick(float ndcX, float ndcY) { InspectorInteractor.processInspectorClick(ndcX, ndcY, this); }

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
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.2f, 1.0f, 0.2f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public float[] projectWorldToNdc(float wx, float wy, float wz) { return GizmoInteractor.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw); }

        public void onFilesDropped(List<String> filePaths) {
        if (filePaths == null) return;
        for (String path : filePaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();
            AssetType type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
            addAssetDirectoryIfNotExists(path, type);
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }

    public void promptImportTexture() { InspectorInteractor.promptImportTexture(this); }
    public void promptImportMesh() { InspectorInteractor.promptImportMesh(this); }
    public void promptImportAssetForDirectory() { InspectorInteractor.promptImportAssetForDirectory(this); }

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
        AssetDirectoryPanel.render(verts, assetDirectory, currentFolderPath);
        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, currentGizmoMode);
        if (getSelectedEntity() != null && activeModal == ModalState.NONE) {
            render3DGizmos(verts, getSelectedEntity(), currentGizmoMode, activeGizmoAxis);
        }

        TopMenuBar.render(verts, activeDropdownMenu, getActiveScenePath(), getPlayState());

        if (activeDropdownMenu != DropdownMenu.NONE) {
            TopMenuBar.renderDropdownMenuOverlay(verts, activeDropdownMenu, commandHistory, show3DGrid);
        }

        if (activeModal == ModalState.ASSET_PICKER) {
            ModalOverlayManager.renderAssetPickerModal(verts, pickerFilterType, assetDirectory);
        }

        if (contextMenuVisible) {
            ContextMenuOverlayManager.render(verts, contextMenuNdcX, contextMenuNdcY, contextMenuItems);
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis) {
        GizmoInteractor.render3DGizmos(verts, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public void renameFolder(AssetEntry folder, String newName) {
        if (folder == null || folder.type != AssetType.FOLDER || newName == null || newName.trim().isEmpty()) return;

        String oldFolderName = folder.name;
        String cleanNewName = newName.trim();
        if (cleanNewName.equalsIgnoreCase(oldFolderName)) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String oldVirtualPath = parentPath.isEmpty() ? oldFolderName : parentPath + "/" + oldFolderName;
        String newVirtualPath = parentPath.isEmpty() ? cleanNewName : parentPath + "/" + cleanNewName;

        folder.name = cleanNewName;
        folder.absolutePath = newVirtualPath;

        for (AssetEntry entry : assetDirectory) {
            if (entry.parentFolderPath != null) {
                if (entry.parentFolderPath.equalsIgnoreCase(oldVirtualPath)) {
                    entry.parentFolderPath = newVirtualPath;
                } else if (entry.parentFolderPath.toLowerCase().startsWith(oldVirtualPath.toLowerCase() + "/")) {
                    entry.parentFolderPath = newVirtualPath + entry.parentFolderPath.substring(oldVirtualPath.length());
                }
            }
        }

        if (currentFolderPath.equalsIgnoreCase(oldVirtualPath)) {
            currentFolderPath = newVirtualPath;
        } else if (currentFolderPath.toLowerCase().startsWith(oldVirtualPath.toLowerCase() + "/")) {
            currentFolderPath = newVirtualPath + currentFolderPath.substring(oldVirtualPath.length());
        }
        System.out.println("[EditorPanelManager] Renamed folder '" + oldFolderName + "' to '" + cleanNewName + "'");
    }

    public void promptRenameFolder(AssetEntry folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Folder", "Enter new folder name:", folder.name);
        if (input != null && !input.trim().isEmpty()) {
            renameFolder(folder, input.trim());
        }
    }

    public void promptRenameAsset(AssetEntry asset) {
        if (asset == null || asset.type == AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Asset", "Enter new asset name:", asset.name);
        if (input != null && !input.trim().isEmpty()) {
            asset.name = input.trim();
            System.out.println("[EditorPanelManager] Renamed asset to  + asset.name + ");
        }
    }

    public void deleteFolder(AssetEntry folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String folderVirtualPath = parentPath.isEmpty() ? folder.name : parentPath + "/" + folder.name;

        for (AssetEntry entry : assetDirectory) {
            if (entry.parentFolderPath != null && entry.parentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
                entry.parentFolderPath = parentPath;
            }
        }

        assetDirectory.remove(folder);

        if (currentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
            currentFolderPath = parentPath;
        }
        System.out.println("[EditorPanelManager] Deleted folder '" + folder.name + "' (reparented contents to '" + (parentPath.isEmpty() ? "Assets" : parentPath) + "')");
    }

    public void removeAssetFromDirectory(AssetEntry asset) {
        if (asset != null) {
            assetDirectory.remove(asset);
            System.out.println("[EditorPanelManager] Removed asset '" + asset.name + "' from directory.");
        }
    }

}
