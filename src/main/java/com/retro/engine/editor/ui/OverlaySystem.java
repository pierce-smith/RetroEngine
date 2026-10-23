package com.retro.engine.editor.ui;

import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorTransition;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.gizmos.GizmoSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing dropdown menu overlays, asset/parent picker modal dialogs,
 * dynamic context menus, and right-click popup tracking.
 */
public class OverlaySystem {

    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;
    private Entity parentPickerTargetEntity = null;

    private boolean contextMenuVisible = false;
    private float contextMenuNdcX = 0.0f;
    private float contextMenuNdcY = 0.0f;
    private final List<MenuItem> contextMenuItems = new ArrayList<>();

    private boolean isRightMouseDown = false;
    private double rightClickPressX = 0.0;
    private double rightClickPressY = 0.0;
    private float rightClickPressNdcX = 0.0f;
    private float rightClickPressNdcY = 0.0f;
    private double rightClickDragDistance = 0.0;
    private float pressCamPitch = 0.0f, pressCamYaw = 0.0f, pressCamPanX = 0.0f, pressCamPanY = 0.0f, pressCamZoom = 0.0f;

    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public DropdownMenu getActiveDropdown() { return activeDropdownMenu; }

    public void setActiveDropdownMenu(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public void setActiveDropdown(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public void closeDropdownMenu() { this.activeDropdownMenu = DropdownMenu.NONE; }

    public ModalState getActiveModal() { return activeModal; }
    public void setActiveModal(ModalState modal) { this.activeModal = modal; }

    public AssetType getPickerFilterType() { return pickerFilterType; }

    public void openAssetPicker(AssetType filterType) {
        this.pickerFilterType = filterType;
        this.activeModal = ModalState.ASSET_PICKER;
    }
    public void openAssetPickerModal(AssetType filterType) { openAssetPicker(filterType); }

    public void closeModal() {
        this.activeModal = ModalState.NONE;
    }

    public Entity getParentPickerTargetEntity() { return parentPickerTargetEntity; }

    public void openParentPicker(Entity target) {
        if (target != null) {
            this.parentPickerTargetEntity = target;
            this.activeModal = ModalState.PARENT_PICKER;
        }
    }
    public void openParentPickerModal(Entity target) { openParentPicker(target); }

    public boolean isContextMenuVisible() { return contextMenuVisible; }
    public float getContextMenuNdcX() { return contextMenuNdcX; }
    public float getContextMenuNdcY() { return contextMenuNdcY; }
    public List<MenuItem> getContextMenuItems() { return contextMenuItems; }

    public void closeContextMenu() {
        this.contextMenuVisible = false;
        this.contextMenuItems.clear();
    }
    public void hideContextMenu() { closeContextMenu(); }

    public void openContextMenu(float ndcX, float ndcY, List<MenuItem> items) {
        this.contextMenuNdcX = ndcX;
        this.contextMenuNdcY = ndcY;
        this.contextMenuItems.clear();
        if (items != null) this.contextMenuItems.addAll(items);
        this.contextMenuVisible = true;
    }
    public void showContextMenu(float ndcX, float ndcY, List<MenuItem> items) { openContextMenu(ndcX, ndcY, items); }

    public boolean isRightMouseDown() { return isRightMouseDown; }

    public void onRightClickPress(double refX, double refY, float ndcX, float ndcY, ViewportCamera cameraController, float camPitch, float camYaw, float camPanX, float camPanY, float camZoom) {
        if (contextMenuVisible) closeContextMenu();
        this.activeDropdownMenu = DropdownMenu.NONE;
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
    }

    public void addRightClickDragDistance(double dist) {
        if (isRightMouseDown) {
            this.rightClickDragDistance += dist;
        }
    }

    public void onRightClickRelease(double refX, double refY, EditorWorkspace manager, ViewportCamera cameraController, float currentPitch, float currentYaw, float currentPanX, float currentPanY, float currentZoom, long lastOrbitTime) {
        if (!isRightMouseDown) return;
        this.isRightMouseDown = false;

        double directDragDist = Math.hypot(refX - rightClickPressX, refY - rightClickPressY);

        float activePitch = (cameraController != null) ? cameraController.getPitch() : currentPitch;
        float activeYaw = (cameraController != null) ? cameraController.getYaw() : currentYaw;
        float activePanX = (cameraController != null) ? cameraController.getPanX() : currentPanX;
        float activePanY = (cameraController != null) ? cameraController.getPanY() : currentPanY;
        float activeZoom = (cameraController != null) ? cameraController.getZoom() : currentZoom;

        boolean cameraMoved = Math.abs(activePitch - pressCamPitch) > 0.1f ||
                              Math.abs(activeYaw - pressCamYaw) > 0.1f ||
                              Math.abs(activePanX - pressCamPanX) > 0.01f ||
                              Math.abs(activePanY - pressCamPanY) > 0.01f ||
                              Math.abs(activeZoom - pressCamZoom) > 0.01f;

        boolean recentOrbit = (System.currentTimeMillis() - lastOrbitTime) < 350;

        if (!cameraMoved && !recentOrbit && rightClickDragDistance < 12.0 && directDragDist < 12.0) {
            buildAndShowContextMenu(rightClickPressNdcX, rightClickPressNdcY, rightClickPressX, rightClickPressY, manager);
        }
    }

    
    public void buildAndShowContextMenu(float ndcX, float ndcY, double refX, double refY, EditorWorkspace manager) {
        List<MenuItem> items = new ArrayList<>();

        if (manager != null && manager.getActiveWorkspaceTab() == EditorWorkspace.WorkspaceTab.ANIMATOR) {
            buildAnimatorContextMenu(ndcX, ndcY, refX, refY, manager, items);
            openContextMenu(ndcX, ndcY, items);
            return;
        }

        if (ndcY >= 0.92f) {
            items.add(new MenuItem("New Scene", manager::newScene));
            items.add(new MenuItem("Open Scene... (Cmd+O)", manager::promptOpenSceneWithNativeDialog));
            items.add(new MenuItem("Save Scene... (Cmd+S)", manager::promptSaveSceneWithNativeDialog));
        } else if (ndcY <= -0.42f) {
            buildAssetDirectoryContextMenu(ndcX, ndcY, manager, items);
        } else if (ndcX <= -0.55f) {

            List<Entity> entities = manager.getSceneEntities();
            List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
            int index = (int) ((0.85f - ndcY) / 0.08f);

            if (index >= 0 && index < visibleTree.size()) {
                Entity hitEntity = visibleTree.get(index).entity;
                manager.selectEntity(hitEntity);

                items.add(new MenuItem("Rename Entity", () -> manager.promptRenameEntity(hitEntity)));
                items.add(new MenuItem("Duplicate Entity (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Entity (Del)", manager::deleteSelectedEntity));
                items.add(new MenuItem("Save as Prefab...", manager::promptSaveSelectedAsPrefab));
                items.add(new MenuItem("+ Add Script Component", () -> manager.openAssetPicker(AssetType.SCRIPT)));
                items.add(new MenuItem("+ Add Child Cube", () -> manager.spawnChildEntity(hitEntity, "Cube", "Assets/Models/cube.obj")));
                items.add(new MenuItem("+ Add Child Light", () -> manager.spawnChildLight(hitEntity)));

                if (hitEntity.parentId != null && !hitEntity.parentId.isEmpty()) {
                    items.add(new MenuItem("Unparent (Move to Root)", () -> manager.unparentEntity(hitEntity)));
                }

                items.add(new MenuItem("Set Parent...", () -> manager.openParentPicker(hitEntity)));
                items.add(new MenuItem("Deselect All", () -> manager.selectEntity(null)));
            } else {
                items.add(new MenuItem("+ Spawn 3D Cube", manager::spawnDefaultCube));
                items.add(new MenuItem("+ Spawn Light Source", manager::spawnLightSource));
                items.add(new MenuItem("+ Spawn Camera", manager::spawnCameraEntity));
                items.add(new MenuItem("+ Spawn Audio Source", manager::spawnAudioSource));
                items.add(new MenuItem("New Scene", manager::newScene));
            }
        } else if (ndcX >= 0.55f) {
            if (manager.getSelectedEntity() != null) {
                items.add(new MenuItem("Rename Entity", () -> manager.promptRenameEntity(manager.getSelectedEntity())));
                items.add(new MenuItem("Duplicate Entity (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Entity (Del)", manager::deleteSelectedEntity));
                items.add(new MenuItem("Save as Prefab...", manager::promptSaveSelectedAsPrefab));
                items.add(new MenuItem("+ Add Script Component", () -> manager.openAssetPicker(AssetType.SCRIPT)));
            }
            items.add(new MenuItem("+ Import Asset...", manager::promptImportAssetForDirectory));
        } else {
            Entity bestEntity = null;
            double minDistance = 60.0;

            List<Entity> sceneEntities = manager.getSceneEntities();
            if (sceneEntities != null) {
                for (int i = 0; i < sceneEntities.size(); i++) {
                    Entity entity = sceneEntities.get(i);
                    if (entity == null) continue;
                    Transform tc = entity.getTransform();
                    if (tc == null) continue;

                    SceneWorld.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(entity);
                    float[] p0 = GizmoSystem.projectWorldToNdc(wt.px(), wt.py(), wt.pz(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
                    double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
                    double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

                    double dist = Math.hypot(refX - cx, refY - cy);
                    if (dist < minDistance) {
                        minDistance = dist;
                        bestEntity = entity;
                    }
                }
            }

            if (bestEntity != null) {
                manager.selectEntity(bestEntity);
                Entity hitEntity = bestEntity;

                items.add(new MenuItem("Rename Entity", () -> manager.promptRenameEntity(hitEntity)));
                items.add(new MenuItem("Duplicate Object (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Object (Del)", manager::deleteSelectedEntity));
                items.add(new MenuItem("Save as Prefab...", manager::promptSaveSelectedAsPrefab));
                items.add(new MenuItem("+ Add Script Component", () -> manager.openAssetPicker(AssetType.SCRIPT)));
                items.add(new MenuItem("+ Add Child Cube", () -> manager.spawnChildEntity(hitEntity, "Cube", "Assets/Models/cube.obj")));

                if (hitEntity.parentId != null && !hitEntity.parentId.isEmpty()) {
                    items.add(new MenuItem("Unparent (Move to Root)", () -> manager.unparentEntity(hitEntity)));
                }

                items.add(new MenuItem("Reset Position", () -> {
                    if (manager.getSelectedEntity() != null && manager.getSelectedEntity().getTransform() != null) {
                        Transform tc = manager.getSelectedEntity().getTransform();
                        tc.posX = 0.0f; tc.posY = 0.0f; tc.posZ = 0.0f;
                    }
                }));
            } else {
                items.add(new MenuItem("+ Spawn 3D Cube", manager::spawnDefaultCube));
                items.add(new MenuItem("+ Spawn Light Source", manager::spawnLightSource));
                items.add(new MenuItem("+ Spawn Camera", manager::spawnCameraEntity));
                items.add(new MenuItem("+ Spawn Audio Source", manager::spawnAudioSource));
                items.add(new MenuItem("Toggle 3D Grid", manager::toggle3DGrid));
            }
        }

        openContextMenu(ndcX, ndcY, items);
    }

    public void processParentPickerModalClick(float ndcX, float ndcY, EditorWorkspace manager) {
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) {
            closeModal();
            return;
        }
        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) {
            closeModal();
            return;
        }

        if (parentPickerTargetEntity == null) {
            closeModal();
            return;
        }

        if (ndcX >= cardMinX + 0.03f && ndcX <= cardMaxX - 0.03f && ndcY >= cardMaxY - 0.18f && ndcY <= cardMaxY - 0.11f) {
            manager.unparentEntity(parentPickerTargetEntity);
            closeModal();
            return;
        }

        List<Entity> allEntities = manager.getSceneEntities();
        List<Entity> candidates = new ArrayList<>();
        if (allEntities != null) {
            for (int i = 0; i < allEntities.size(); i++) {
                Entity e = allEntities.get(i);
                if (e != null && !e.id.equalsIgnoreCase(parentPickerTargetEntity.id) && !e.isDescendantOf(parentPickerTargetEntity, manager.getSceneEntities())) {
                    candidates.add(e);
                }
            }
        }

        float listY = cardMaxY - 0.24f;
        for (Entity parentCandidate : candidates) {
            if (listY < cardMinY + 0.05f) break;
            if (ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                manager.setParentEntity(parentPickerTargetEntity, parentCandidate);
                closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }

    private void buildAnimatorContextMenu(float ndcX, float ndcY, double refX, double refY, EditorWorkspace manager, List<MenuItem> items) {
        if (ndcY >= 0.92f) {
            items.add(new MenuItem("New Scene", manager::newScene));
            items.add(new MenuItem("Open Scene... (Cmd+O)", manager::promptOpenSceneWithNativeDialog));
            items.add(new MenuItem("Save Scene... (Cmd+S)", manager::promptSaveSceneWithNativeDialog));
            items.add(new MenuItem("Switch to 3D Scene View", () -> manager.setActiveWorkspaceTab(EditorWorkspace.WorkspaceTab.SCENE_3D)));
            return;
        }

        if (ndcY <= -0.42f) {
            buildAssetDirectoryContextMenu(ndcX, ndcY, manager, items);
            return;
        }

        AnimatorController controller = manager.getActiveAnimatorController();

        // 1. Parameters Sidebar (-1.00f to -0.55f)
        if (ndcX < -0.55f) {
            items.add(new MenuItem("+ Add Float Parameter", () -> promptAddParamWithType(controller, "FLOAT")));
            items.add(new MenuItem("+ Add Int Parameter", () -> promptAddParamWithType(controller, "INT")));
            items.add(new MenuItem("+ Add Bool Parameter", () -> promptAddParamWithType(controller, "BOOL")));
            items.add(new MenuItem("+ Add Trigger Parameter", () -> promptAddParamWithType(controller, "TRIGGER")));

            if (controller != null && !controller.getParameters().isEmpty()) {
                items.add(new MenuItem("--- Registered Parameters ---", () -> {}));
                for (AnimatorParameter param : controller.getParameters().values()) {
                    items.add(new MenuItem("Delete Parameter '" + param.name + "'", () -> {
                        controller.getParameters().remove(param.name);
                        UISoundPlayer.play(UISound.CLICK);
                    }));
                }
            }
            return;
        }

        // 2. Animator Inspector Sidebar (0.55f to 1.00f)
        if (ndcX > 0.55f) {
            String selStateName = manager.getSelectedStateName();
            AnimatorState selState = (controller != null && selStateName != null) ? controller.getState(selStateName) : null;
            if (selState == null && controller != null) {
                selState = controller.getCurrentState();
            }

            if (selState != null) {
                final AnimatorState stateToOperate = selState;
                items.add(new MenuItem("Set as Entry/Default State", () -> {
                    controller.setInitialState(stateToOperate.name);
                    UISoundPlayer.play(UISound.CLICK);
                }));
                items.add(new MenuItem("+ Add Outgoing Transition...", () -> promptAddTransition(stateToOperate, controller)));
                items.add(new MenuItem("Toggle Looping (Current: " + (stateToOperate.isLooping ? "ON" : "OFF") + ")", () -> {
                    stateToOperate.isLooping = !stateToOperate.isLooping;
                    UISoundPlayer.play(UISound.CLICK);
                }));
                items.add(new MenuItem("Rename State Node...", () -> promptRenameState(stateToOperate, controller, manager)));
                items.add(new MenuItem("Delete State Node", () -> {
                    controller.getStates().remove(stateToOperate.name);
                    UISoundPlayer.play(UISound.DELETE);
                }));
            } else {
                items.add(new MenuItem("+ Add State Node...", () -> promptAddStateAt(controller, manager, 0.0f, 0.20f)));
                items.add(new MenuItem("+ Add Parameter...", () -> promptAddParamWithType(controller, "FLOAT")));
            }
            return;
        }

        // 3. Central Canvas Area (-0.55f to 0.55f)
        if (controller != null) {
            AnimatorState hitState = null;
            for (AnimatorState state : controller.getStates().values()) {
                float x1 = state.graphX;
                float x2 = state.graphX + 0.22f;
                float y1 = state.graphY - 0.12f;
                float y2 = state.graphY;

                if (ndcX >= x1 && ndcX <= x2 && ndcY >= y1 && ndcY <= y2) {
                    hitState = state;
                    break;
                }
            }

            if (hitState != null) {
                final AnimatorState targetState = hitState;
                manager.setSelectedStateName(targetState.name);

                items.add(new MenuItem("Select & Inspect State '" + targetState.name + "'", () -> manager.setSelectedStateName(targetState.name)));
                items.add(new MenuItem("Set as Entry/Default State", () -> {
                    controller.setInitialState(targetState.name);
                    UISoundPlayer.play(UISound.CLICK);
                }));
                items.add(new MenuItem("+ Add Outgoing Transition...", () -> promptAddTransition(targetState, controller)));
                items.add(new MenuItem("Toggle Motion Looping (" + (targetState.isLooping ? "Enabled" : "Disabled") + ")", () -> {
                    targetState.isLooping = !targetState.isLooping;
                    UISoundPlayer.play(UISound.CLICK);
                }));
                items.add(new MenuItem("Rename State Node...", () -> promptRenameState(targetState, controller, manager)));
                items.add(new MenuItem("Delete State Node", () -> {
                    controller.getStates().remove(targetState.name);
                    UISoundPlayer.play(UISound.DELETE);
                }));
            } else {
                final float spawnX = ndcX - 0.11f;
                final float spawnY = ndcY + 0.06f;
                items.add(new MenuItem("+ Add State Node Here", () -> promptAddStateAt(controller, manager, spawnX, spawnY)));
                items.add(new MenuItem("+ Add Parameter...", () -> promptAddParamWithType(controller, "FLOAT")));
                items.add(new MenuItem("Reset Node Layout Grid", () -> {
                    float gX = -0.20f, gY = 0.65f;
                    int col = 0;
                    for (AnimatorState st : controller.getStates().values()) {
                        st.graphX = gX + (col % 3) * 0.38f;
                        st.graphY = gY - (col / 3) * 0.25f;
                        col++;
                    }
                    UISoundPlayer.play(UISound.CLICK);
                }));
                items.add(new MenuItem("Switch to 3D Viewport", () -> manager.setActiveWorkspaceTab(EditorWorkspace.WorkspaceTab.SCENE_3D)));
            }
        } else {
            items.add(new MenuItem("Switch to 3D Viewport", () -> manager.setActiveWorkspaceTab(EditorWorkspace.WorkspaceTab.SCENE_3D)));
        }
    }

    private void promptAddParamWithType(AnimatorController controller, String typeDefault) {
        if (controller == null) return;
        String name = NativeFileDialog.promptInput("Add Parameter", "Enter parameter name:", "NewParam");
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            String typeStr = NativeFileDialog.promptInput("Parameter Type", "Type (FLOAT, INT, BOOL, TRIGGER):", typeDefault);
            if (typeStr != null) {
                String t = typeStr.trim().toUpperCase();
                if (t.startsWith("I")) controller.setInt(trimmed, 0);
                else if (t.startsWith("B")) controller.setBool(trimmed, false);
                else if (t.startsWith("T")) controller.setTrigger(trimmed);
                else controller.setFloat(trimmed, 0.0f);
                UISoundPlayer.play(UISound.CLICK);
            }
        }
    }

    private void promptAddTransition(AnimatorState srcState, AnimatorController controller) {
        if (srcState == null || controller == null) return;
        String targetName = NativeFileDialog.promptInput("Add Transition", "Enter destination state name for transition from '" + srcState.name + "':", "Idle");
        if (targetName != null && !targetName.trim().isEmpty()) {
            String dest = targetName.trim();
            srcState.addTransition(dest, 0.25f);
            UISoundPlayer.play(UISound.CLICK);
        }
    }

    private void promptRenameState(AnimatorState state, AnimatorController controller, EditorWorkspace manager) {
        if (state == null || controller == null) return;
        String newName = NativeFileDialog.promptInput("Rename State", "Enter new name for state '" + state.name + "':", state.name);
        if (newName != null && !newName.trim().isEmpty() && !newName.trim().equalsIgnoreCase(state.name)) {
            String trimmed = newName.trim();
            controller.getStates().remove(state.name);
            state.name = trimmed;
            controller.getStates().put(trimmed, state);
            if (manager != null) manager.setSelectedStateName(trimmed);
            UISoundPlayer.play(UISound.CLICK);
        }
    }

    private void promptAddStateAt(AnimatorController controller, EditorWorkspace manager, float x, float y) {
        if (controller == null) return;
        String stateName = NativeFileDialog.promptInput("Add State Node", "Enter new state name:", "NewState");
        if (stateName != null && !stateName.trim().isEmpty()) {
            String trimmed = stateName.trim();
            AnimatorState newState = new AnimatorState(trimmed, null);
            newState.graphX = Math.max(-0.50f, Math.min(0.30f, x));
            newState.graphY = Math.max(-0.35f, Math.min(0.80f, y));
            controller.addState(newState);
            if (manager != null) manager.setSelectedStateName(trimmed);
            UISoundPlayer.play(UISound.SPAWN);
        }
    }

    private void buildAssetDirectoryContextMenu(float ndcX, float ndcY, EditorWorkspace manager, List<MenuItem> items) {
        String activePath = manager.getCurrentFolderPath();
        List<AssetMeta> allDirectoryAssets = manager.getAssetDirectory();
        List<AssetMeta> visibleAssets = new ArrayList<>();
        if (allDirectoryAssets != null) {
            for (int i = 0; i < allDirectoryAssets.size(); i++) {
                AssetMeta a = allDirectoryAssets.get(i);
                if (a != null && a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath)) {
                    visibleAssets.add(a);
                }
            }
        }

        boolean hasUpLevel = !activePath.isEmpty();
        float cardX = -0.95f;
        AssetMeta hitCard = null;
        boolean hitUpLevel = false;

        if (hasUpLevel) {
            if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                hitUpLevel = true;
            }
            cardX += 0.36f;
        }

        if (!hitUpLevel) {
            for (AssetMeta asset : visibleAssets) {
                if (cardX + 0.35f > 0.95f) break;
                if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                    hitCard = asset;
                    break;
                }
                cardX += 0.36f;
            }
        }

        if (hitUpLevel) {
            items.add(new MenuItem(".. Return to Parent Folder", manager::navigateUpFolder));
            items.add(new MenuItem("+ Create Folder Here", manager::createNewFolder));
            items.add(new MenuItem("+ Import Asset Here...", manager::promptImportAssetForDirectory));
        } else if (hitCard != null) {
            AssetMeta targetAsset = hitCard;
            if (targetAsset.type == AssetType.FOLDER) {
                String folderName = targetAsset.name;
                String childPath = activePath.isEmpty() ? folderName : activePath + "/" + folderName;
                items.add(new MenuItem("Open Folder '" + folderName + "'", () -> manager.setCurrentFolderPath(childPath)));
                items.add(new MenuItem("Rename Folder", () -> manager.promptRenameFolder(targetAsset)));
                items.add(new MenuItem("Delete Folder", () -> manager.deleteFolder(targetAsset)));
            } else if (targetAsset.type == AssetType.PREFAB) {
                items.add(new MenuItem("Spawn Prefab Entity", () -> manager.spawnPrefabFromAsset(targetAsset)));
                items.add(new MenuItem("Rename Prefab", () -> manager.promptRenameAsset(targetAsset)));
                items.add(new MenuItem("Remove Asset from Directory", () -> manager.removeAssetFromDirectory(targetAsset)));
            } else {
                if (targetAsset.type == AssetType.MESH_3D) {
                    items.add(new MenuItem("Spawn Mesh Entity", () -> manager.spawnEntityFromAsset(targetAsset)));
                } else if (targetAsset.type == AssetType.TEXTURE) {
                    items.add(new MenuItem("Link Texture to Selected", () -> manager.assignTextureToSelectedEntity(targetAsset)));
                } else if (targetAsset.type == AssetType.AUDIO) {
                    items.add(new MenuItem("Link Audio to Selected", () -> manager.assignAudioToSelectedEntity(targetAsset)));
                } else if (targetAsset.type == AssetType.SCRIPT) {
                    items.add(new MenuItem("Link Script to Selected", () -> manager.assignScriptToSelectedEntity(targetAsset)));
                }
                items.add(new MenuItem("Rename Asset", () -> manager.promptRenameAsset(targetAsset)));
                items.add(new MenuItem("Remove Asset from Directory", () -> manager.removeAssetFromDirectory(targetAsset)));
            }
        } else {
            items.add(new MenuItem("+ Create Folder", manager::createNewFolder));
            items.add(new MenuItem("+ Import Asset...", manager::promptImportAssetForDirectory));
            items.add(new MenuItem("+ Import Texture...", manager::promptImportTexture));
            items.add(new MenuItem("+ Import 3D Mesh...", manager::promptImportMesh));
            items.add(new MenuItem("+ Import Audio...", manager::promptImportAudio));
            items.add(new MenuItem("+ Import Script...", manager::promptImportScript));
            if (!manager.getCurrentFolderPath().isEmpty()) {
                items.add(new MenuItem(".. Up Level (" + manager.getCurrentFolderPath() + ")", manager::navigateUpFolder));
            }
        }
    }
}

