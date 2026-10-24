package com.retro.engine.editor.ui.overlays;

import com.retro.engine.editor.input.InspectorInputDelegate;
import com.retro.engine.editor.ui.batch.PanelRegion;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.editor.ui.panels.HierarchyPanel;
import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.editor.ui.panels.MenuItem;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.*;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.commands.EditorCommands;

import java.lang.reflect.Field;
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

        if (cameraController != null) {
            this.pressCamPitch = cameraController.getPitch();
            this.pressCamYaw = cameraController.getYaw();
            this.pressCamPanX = cameraController.getPanX();
            this.pressCamPanY = cameraController.getPanY();
            this.pressCamZoom = cameraController.getZoom();
        } else {
            this.pressCamPitch = camPitch;
            this.pressCamYaw = camYaw;
            this.pressCamPanX = camPanX;
            this.pressCamPanY = camPanY;
            this.pressCamZoom = camZoom;
        }
    }

    public void addRightClickDragDistance(double dist) {
        if (isRightMouseDown) {
            this.rightClickDragDistance += dist;
        }
    }

    public void onRightClickRelease(double refX, double refY, EditorWorkspace manager, ViewportCamera cameraController, float currentCamPitch, float currentCamYaw, float currentCamPanX, float currentCamPanY, float currentCamZoom, long lastOrbitTime) {
        if (!isRightMouseDown) return;
        this.isRightMouseDown = false;

        double dx = refX - rightClickPressX;
        double dy = refY - rightClickPressY;
        this.rightClickDragDistance = Math.sqrt(dx * dx + dy * dy);

        long timeSinceOrbit = System.currentTimeMillis() - lastOrbitTime;
        boolean recentlyOrbiting = (timeSinceOrbit < 150L);

        if (rightClickDragDistance < 8.0 && !recentlyOrbiting && manager != null) {
            buildAndShowContextMenuAt(rightClickPressNdcX, rightClickPressNdcY, manager);
        }
    }

    private void buildAndShowContextMenuAt(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        List<MenuItem> items = new ArrayList<>();

        PanelRegion region = PanelRegion.getRegionAt(ndcX, ndcY, this);

        if (region == PanelRegion.ASSET_DIRECTORY) {
            String activePath = manager.getAssetDatabase().getCurrentFolderPath();
            List<AssetMeta> allDirectoryAssets = manager.getAssetDatabase().getAssetDirectory();
            AssetMeta targetAsset = null;

            List<AssetMeta> visibleAssets = (allDirectoryAssets != null) ? allDirectoryAssets.stream()
                    .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                    .toList() : List.of();

            float cardX = -0.95f;
            if (!activePath.isEmpty()) cardX += 0.36f;

            for (AssetMeta asset : visibleAssets) {
                if (cardX + 0.35f > 0.95f) break;
                if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                    targetAsset = asset;
                    break;
                }
                cardX += 0.36f;
            }

            if (targetAsset != null) {
                final AssetMeta finalAsset = targetAsset;
                if (finalAsset.type == AssetType.FOLDER) {
                    final String folderName = finalAsset.name;
                    final String childPath = activePath.isEmpty() ? folderName : activePath + "/" + folderName;
                    items.add(new MenuItem("Open Folder '" + folderName + "'", () -> manager.getAssetDatabase().setCurrentFolderPath(childPath)));
                    items.add(new MenuItem("Rename Folder", () -> manager.getAssetDatabase().promptRenameFolder(finalAsset)));
                    items.add(new MenuItem("Delete Folder", () -> manager.getAssetDatabase().deleteFolder(finalAsset)));
                } else {
                    if (targetAsset.type == AssetType.PREFAB) {
                        items.add(new MenuItem("Spawn Prefab Entity", () -> spawnPrefabFromAsset(manager, finalAsset)));
                        items.add(new MenuItem("Rename Prefab", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                        items.add(new MenuItem("Remove Asset from Directory", () -> manager.getAssetDatabase().removeAssetFromDirectory(finalAsset)));
                    } else if (targetAsset.type == AssetType.MESH_3D) {
                        items.add(new MenuItem("Spawn Mesh Entity", () -> spawnEntityFromAsset(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                        items.add(new MenuItem("Remove Asset from Directory", () -> manager.getAssetDatabase().removeAssetFromDirectory(finalAsset)));
                    } else if (targetAsset.type == AssetType.TEXTURE) {
                        items.add(new MenuItem("Link Texture to Selected", () -> assignTextureToSelectedEntity(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                        items.add(new MenuItem("Remove Asset from Directory", () -> manager.getAssetDatabase().removeAssetFromDirectory(finalAsset)));
                    } else if (targetAsset.type == AssetType.SCRIPT) {
                        items.add(new MenuItem("Link Script to Selected", () -> assignScriptToSelectedEntity(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                        items.add(new MenuItem("Remove Asset from Directory", () -> manager.getAssetDatabase().removeAssetFromDirectory(finalAsset)));
                    }
                }
            } else {
                items.add(new MenuItem("+ New Folder", () -> manager.getAssetDatabase().createNewFolder()));
                items.add(new MenuItem("+ Import Asset...", () -> InspectorInputDelegate.promptImportAssetForDirectory(manager)));
                if (!manager.getAssetDatabase().getCurrentFolderPath().isEmpty()) {
                    items.add(new MenuItem(".. Up Level (" + manager.getAssetDatabase().getCurrentFolderPath() + ")", () -> manager.getAssetDatabase().navigateUpFolder()));
                }
            }

        } else if (region == PanelRegion.SCENE_HIERARCHY) {
            List<Entity> entities = manager.getSceneEntities();
            if (entities != null && !entities.isEmpty()) {
                List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
                int index = (int) ((0.85f - ndcY) / 0.08f);
                if (index >= 0 && index < visibleTree.size()) {
                    final Entity hitEntity = visibleTree.get(index).entity;
                    manager.selectEntity(hitEntity);

                    items.add(new MenuItem("Rename Entity", () -> promptRenameEntity(manager, hitEntity)));
                    items.add(new MenuItem("+ Add Script Component", () -> openAssetPicker(AssetType.SCRIPT)));
                    items.add(new MenuItem("+ Add Child Cube", () -> spawnChildEntity(manager, hitEntity, "Cube", "Assets/Models/cube.obj")));
                    items.add(new MenuItem("+ Add Child Light", () -> spawnChildLight(manager, hitEntity)));
                    items.add(new MenuItem("Unparent (Move to Root)", () -> unparentEntity(manager, hitEntity)));
                    items.add(new MenuItem("Set Parent...", () -> openParentPicker(hitEntity)));
                } else {
                    items.add(new MenuItem("Deselect All", () -> manager.selectEntity(null)));
                }
            } else {
                items.add(new MenuItem("Deselect All", () -> manager.selectEntity(null)));
            }

        } else if (region == PanelRegion.VIEWPORT) {
            Entity bestEntity = null;
            float bestDistSq = Float.MAX_VALUE;

            List<Entity> sceneEntities = manager.getSceneEntities();
            if (sceneEntities != null) {
                for (Entity entity : sceneEntities) {
                    if (entity == null) continue;
                    SceneWorld.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(entity);
                    float[] p0 = GizmoSystem.projectWorldToNdc(wt.px(), wt.py(), wt.pz(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
                    if (p0 != null) {
                        float dx = ndcX - p0[0];
                        float dy = ndcY - p0[1];
                        float distSq = dx * dx + dy * dy;
                        if (distSq < 0.04f && distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestEntity = entity;
                        }
                    }
                }
            }

            if (bestEntity != null) {
                manager.selectEntity(bestEntity);
                final Entity hitEntity = bestEntity;
                items.add(new MenuItem("Rename Entity", () -> promptRenameEntity(manager, hitEntity)));
                items.add(new MenuItem("+ Add Script Component", () -> openAssetPicker(AssetType.SCRIPT)));
                items.add(new MenuItem("+ Add Child Cube", () -> spawnChildEntity(manager, hitEntity, "Cube", "Assets/Models/cube.obj")));
                items.add(new MenuItem("Unparent (Move to Root)", () -> unparentEntity(manager, hitEntity)));
            } else if (manager.getSelectedEntity() != null && manager.getSelectedEntity().getTransform() != null) {
                final Entity selected = manager.getSelectedEntity();
                items.add(new MenuItem("Rename Entity", () -> promptRenameEntity(manager, selected)));
                items.add(new MenuItem("+ Add Script Component", () -> openAssetPicker(AssetType.SCRIPT)));
            }
        }

        if (!items.isEmpty()) {
            openContextMenu(ndcX, ndcY, items);
        }
    }

    public void processParentPickerModalClick(float ndcX, float ndcY, EditorWorkspace manager) {
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            closeModal();
            return;
        }

        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            closeModal();
            return;
        }

        if (parentPickerTargetEntity == null || manager == null) {
            closeModal();
            return;
        }

        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= 0.27f && ndcY <= 0.33f) {
            unparentEntity(manager, parentPickerTargetEntity);
            UISoundPlayer.play(UISound.CLICK);
            closeModal();
            return;
        }

        List<Entity> allEntities = manager.getSceneEntities();
        List<Entity> candidateParents = (allEntities != null) ? allEntities.stream()
                .filter(e -> e != null && !e.id.equalsIgnoreCase(parentPickerTargetEntity.id) && !e.isDescendantOf(parentPickerTargetEntity, manager.getSceneEntities()))
                .toList() : List.of();

        float listY = 0.20f;
        for (Entity parentCandidate : candidateParents) {
            if (listY < -0.38f) break;
            if (ndcX >= -0.42f && ndcX <= 0.42f && ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                UISoundPlayer.play(UISound.CLICK);
                setParentEntity(manager, parentPickerTargetEntity, parentCandidate);
                closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }

    // --- Helper Entity Action Dispatchers ---
    private void spawnPrefabFromAsset(EditorWorkspace manager, AssetMeta asset) {
        if (manager == null || asset == null) return;
        List<Entity> spawned = manager.getSceneManager().instantiatePrefab(asset.absolutePath, manager.getAssetDatabase());
        if (spawned != null && !spawned.isEmpty()) {
            manager.getAssetDatabase().addAssetDirectoryIfNotExists(asset.absolutePath, AssetType.PREFAB);
        }
    }

    private void spawnEntityFromAsset(EditorWorkspace manager, AssetMeta asset) {
        if (manager == null || asset == null) return;
        manager.getSceneManager().spawnEntityFromAsset(asset);
        Entity newlySpawned = manager.getSelectedEntity();
        if (newlySpawned != null) {
            EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(manager, newlySpawned);
            manager.getCommandHistory().executeCommand(cmd);
        }
        UISoundPlayer.play(UISound.SPAWN);
    }

    private void assignTextureToSelectedEntity(EditorWorkspace manager, AssetMeta asset) {
        if (manager == null || asset == null) return;
        final Entity selected = manager.getSelectedEntity();
        if (selected == null) return;
        Material mat = selected.getComponent(Material.class);
        if (mat == null) return;
        try {
            Field field = Material.class.getDeclaredField("textureAssetPath");
            EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, mat.textureAssetPath, asset.absolutePath, "TEXTURE");
            manager.getCommandHistory().executeCommand(cmd);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void assignScriptToSelectedEntity(EditorWorkspace manager, AssetMeta asset) {
        if (manager == null) return;
        final Entity selected = manager.getSelectedEntity();
        if (selected == null) return;
        ScriptComponent sc = selected.getComponent(ScriptComponent.class);
        String oldPath = (sc != null) ? sc.scriptAssetPath : null;
        String oldName = (sc != null) ? sc.scriptName : null;
        String newPath = (asset != null) ? asset.absolutePath : null;
        String newName = (asset != null) ? asset.name : null;
        EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, newPath, newName);
        manager.getCommandHistory().executeCommand(cmd);
        InspectorPanel.resetScroll();
    }

    private void promptRenameEntity(EditorWorkspace manager, Entity entity) {
        if (entity == null || manager == null) return;
        String input = NativeFileDialog.promptInput("Rename Entity", "Enter new entity name:", entity.name);
        if (input != null && !input.trim().isEmpty() && !input.trim().equalsIgnoreCase(entity.name)) {
            String newName = input.trim();
            EditorCommands.EntityRenameCommand cmd = new EditorCommands.EntityRenameCommand(entity, entity.name, newName);
            manager.getCommandHistory().executeCommand(cmd);
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Scene] Renamed entity to '" + newName + "'");
        }
    }

    private void setParentEntity(EditorWorkspace manager, Entity child, Entity parent) {
        if (child == null || manager == null) return;
        Entity oldParent = null;
        if (child.parentId != null && !child.parentId.isEmpty()) {
            for (Entity e : manager.getSceneEntities()) {
                if (e.id.equalsIgnoreCase(child.parentId)) {
                    oldParent = e;
                    break;
                }
            }
        }
        EditorCommands.ParentChangeCommand cmd = new EditorCommands.ParentChangeCommand(manager, child, oldParent, parent);
        manager.getCommandHistory().executeCommand(cmd);
    }

    private void unparentEntity(EditorWorkspace manager, Entity child) { setParentEntity(manager, child, null); }

    private void spawnChildEntity(EditorWorkspace manager, Entity parent, String namePrefix, String meshAssetPath) {
        if (parent == null || manager == null) return;
        String entityId = "entity_" + (manager.getSceneEntities().size() + 1);
        String name = namePrefix + "_" + (manager.getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, meshAssetPath);
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        Transform tc = child.getTransform();
        if (tc != null) { tc.posX = 1.0f; tc.posY = 0.0f; tc.posZ = 0.0f; }
        EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(manager, child);
        manager.getCommandHistory().executeCommand(cmd);
        UISoundPlayer.play(UISound.SPAWN);
    }

    private void spawnChildLight(EditorWorkspace manager, Entity parent) {
        if (parent == null || manager == null) return;
        String entityId = "light_" + (manager.getSceneEntities().size() + 1);
        String name = "Light_" + (manager.getSceneEntities().size() + 1);
        Entity child = new Entity(entityId, name, null);
        child.addComponent(new Light());
        child.parentId = parent.id;
        parent.addChildId(child.id);
        parent.expandedInHierarchy = true;
        EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(manager, child);
        manager.getCommandHistory().executeCommand(cmd);
        UISoundPlayer.play(UISound.SPAWN);
    }
}
