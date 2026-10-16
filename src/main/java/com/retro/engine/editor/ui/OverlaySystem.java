package com.retro.engine.editor.ui;

import com.retro.engine.scene.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Light;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.gizmos.ViewportCamera;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.ui.MenuItem;
import com.retro.engine.editor.ui.HierarchyPanel;

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
    public void setActiveDropdownMenu(DropdownMenu menu) { this.activeDropdownMenu = menu; }

    public ModalState getActiveModal() { return activeModal; }

    public AssetType getPickerFilterType() { return pickerFilterType; }

    public void openAssetPicker(AssetType filterType) {
        this.pickerFilterType = filterType;
        this.activeModal = ModalState.ASSET_PICKER;
    }

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

    public boolean isContextMenuVisible() { return contextMenuVisible; }
    public float getContextMenuNdcX() { return contextMenuNdcX; }
    public float getContextMenuNdcY() { return contextMenuNdcY; }
    public List<MenuItem> getContextMenuItems() { return contextMenuItems; }

    public void closeContextMenu() {
        this.contextMenuVisible = false;
        this.contextMenuItems.clear();
    }

    public void openContextMenu(float ndcX, float ndcY, List<MenuItem> items) {
        this.contextMenuNdcX = ndcX;
        this.contextMenuNdcY = ndcY;
        this.contextMenuItems.clear();
        if (items != null) this.contextMenuItems.addAll(items);
        this.contextMenuVisible = true;
    }

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

        if (ndcY >= 0.92f) {
            items.add(new MenuItem("New Scene", manager::newScene));
            items.add(new MenuItem("Open Scene... (Cmd+O)", manager::promptOpenSceneWithNativeDialog));
            items.add(new MenuItem("Save Scene... (Cmd+S)", manager::promptSaveSceneWithNativeDialog));
        } else if (ndcY <= -0.42f) {
            String activePath = manager.getCurrentFolderPath();
            List<AssetMeta> visibleAssets = manager.getAssetDirectory().stream()
                    .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                    .toList();

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
                } else {
                    if (targetAsset.type == AssetType.MESH_3D) {
                        items.add(new MenuItem("Spawn Mesh Entity", () -> manager.spawnEntityFromAsset(targetAsset)));
                    } else if (targetAsset.type == AssetType.TEXTURE) {
                        items.add(new MenuItem("Link Texture to Selected", () -> manager.assignTextureToSelectedEntity(targetAsset)));
                    } else if (targetAsset.type == AssetType.AUDIO) {
                        items.add(new MenuItem("Link Audio to Selected", () -> manager.assignAudioToSelectedEntity(targetAsset)));
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
                if (!manager.getCurrentFolderPath().isEmpty()) {
                    items.add(new MenuItem(".. Up Level (" + manager.getCurrentFolderPath() + ")", manager::navigateUpFolder));
                }
            }
        } else if (ndcX <= -0.55f) {
            List<Entity> entities = manager.getSceneEntities();
            List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
            int index = (int) ((0.85f - ndcY) / 0.08f);

            if (index >= 0 && index < visibleTree.size()) {
                Entity hitEntity = visibleTree.get(index).entity;
                manager.selectEntity(hitEntity);

                items.add(new MenuItem("Duplicate Entity (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Entity (Del)", manager::deleteSelectedEntity));
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
                items.add(new MenuItem("Duplicate Entity (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Entity (Del)", manager::deleteSelectedEntity));
            }
            items.add(new MenuItem("+ Import Asset...", manager::promptImportAssetForDirectory));
        } else {
            Entity bestEntity = null;
            double minDistance = 60.0;

            for (Entity entity : manager.getSceneEntities()) {
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

            if (bestEntity != null) {
                manager.selectEntity(bestEntity);
                Entity hitEntity = bestEntity;

                items.add(new MenuItem("Duplicate Object (Cmd+D)", manager::duplicateSelectedEntity));
                items.add(new MenuItem("Delete Object (Del)", manager::deleteSelectedEntity));
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

        List<Entity> candidates = manager.getSceneEntities().stream()
                .filter(e -> !e.id.equalsIgnoreCase(parentPickerTargetEntity.id) && !e.isDescendantOf(parentPickerTargetEntity, manager.getSceneEntities()))
                .toList();

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
}
