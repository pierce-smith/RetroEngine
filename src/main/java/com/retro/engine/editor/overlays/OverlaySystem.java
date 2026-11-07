package com.retro.engine.editor.overlays;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorCommands;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.AssetBrowserInputDelegate;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.EngineConsolePanel;
import com.retro.engine.editor.panels.HierarchyPanel;
import com.retro.engine.editor.panels.InspectorPanel;
import com.retro.engine.editor.panels.MenuItem;
import com.retro.engine.editor.panels.PanelRegion;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing dropdown menu overlays, asset/parent picker modal dialogs,
 * dynamic context menus, and right-click popup tracking.
 */
public class OverlaySystem {
    private DockSpace dockSpace;


    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;
    private String pickerTargetField = null;
    private Component pickerTargetComponent = null;
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
    private ViewportCamera cameraController;

    public void setCameraController(ViewportCamera cameraController) { this.cameraController = cameraController; }

    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public DropdownMenu getActiveDropdown() { return activeDropdownMenu; }

    public void setActiveDropdownMenu(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public void setActiveDropdown(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public void closeDropdownMenu() { this.activeDropdownMenu = DropdownMenu.NONE; }

    public ModalState getActiveModal() { return activeModal; }
    public void setActiveModal(ModalState modal) { this.activeModal = modal; }

    public AssetType getPickerFilterType() { return pickerFilterType; }

    public void openAssetPicker(AssetType filterType) {
        openAssetPicker(filterType, null);
    }

    /**
     * Opens the picker for a specific component field (e.g. one Material texture channel). A null target keeps the
     * legacy behaviour of assigning the selected entity's primary texture / mesh.
     */
    public void openAssetPicker(AssetType filterType, String targetFieldName) {
        openAssetPicker(filterType, targetFieldName, null);
    }

    /**
     * Opens the picker for one specific component instance (e.g. one of several ScriptComponents). A null component
     * on a SCRIPT picker attaches a new ScriptComponent instead of overwriting an existing one.
     */
    public void openAssetPicker(AssetType filterType, String targetFieldName, Component targetComponent) {
        this.pickerFilterType = filterType;
        this.pickerTargetField = targetFieldName;
        this.pickerTargetComponent = targetComponent;
        this.activeModal = ModalState.ASSET_PICKER;
        ModalDialogOverlay.resetPickerScroll();
    }

    /** Component field the open asset picker writes to, or null for the primary texture / mesh. */
    public String getPickerTargetField() { return pickerTargetField; }
    /** Component instance the open asset picker writes to, or null when it is not bound to one. */
    public Component getPickerTargetComponent() { return pickerTargetComponent; }
    public void openAssetPickerModal(AssetType filterType) { openAssetPicker(filterType); }

    public void closeModal() {
        this.activeModal = ModalState.NONE;
    }

    public Entity getParentPickerTargetEntity() { return parentPickerTargetEntity; }

    public void openParentPicker(Entity target) {
        if (target != null) {
            this.parentPickerTargetEntity = target;
            this.activeModal = ModalState.PARENT_PICKER;
            ModalDialogOverlay.resetPickerScroll();
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
        // The RMB release that opened the menu can be swallowed by the overlay, so make sure
        // neither the right-click tracker nor the viewport camera is left in a drag state
        this.isRightMouseDown = false;
        if (cameraController != null) cameraController.releaseRightMouse();
    }
    public void hideContextMenu() { closeContextMenu(); }

    /** Dock layout used to convert panel design-space input to screen space; set once by the owning workspace. */
    public void setDockSpace(DockSpace dockSpace) { this.dockSpace = dockSpace; }
    public DockSpace getDockSpace() { return dockSpace; }

    public void openContextMenu(float ndcX, float ndcY, List<MenuItem> items) {
        // Panel handlers run in their docked design space; the menu itself is drawn in screen space
        this.contextMenuNdcX = dockSpace.inputToScreenX(ndcX);
        this.contextMenuNdcY = dockSpace.inputToScreenY(ndcY);
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
        // Flying with the keys moves the camera without moving the cursor, and that hold isn't a click either
        boolean cameraMoved = Math.abs(currentCamPanX - pressCamPanX) > 0.01f || Math.abs(currentCamPanY - pressCamPanY) > 0.01f
                || Math.abs(currentCamZoom - pressCamZoom) > 0.01f;

        if (rightClickDragDistance < 8.0 && !recentlyOrbiting && !cameraMoved && manager != null) {
            buildAndShowContextMenuAt(rightClickPressNdcX, rightClickPressNdcY, manager);
        }
    }

    private void buildAndShowContextMenuAt(float screenX, float screenY, EditorWorkspace manager) {
        if (manager == null) return;
        List<MenuItem> items = new ArrayList<>();

        PanelRegion region = PanelRegion.getRegionAt(screenX, screenY, this, dockSpace);

        // Hit-tests below are authored in each panel's design space; the menu opens at the screen point
        DockPanel dockPanel = dockSpace.panelAt(screenX, screenY);
        float ndcX = (dockPanel != null) ? dockSpace.toDesignX(dockPanel, screenX) : screenX;
        float ndcY = (dockPanel != null) ? dockSpace.toDesignY(dockPanel, screenY) : screenY;

        if (region == PanelRegion.ASSET_DIRECTORY) {
            String activePath = manager.getAssetDatabase().getCurrentFolderPath();
            AssetMeta targetAsset = AssetBrowserInputDelegate.assetAt(ndcX, ndcY, manager);

            if (targetAsset != null) {
                final AssetMeta finalAsset = targetAsset;
                if (finalAsset.type == AssetType.FOLDER) {
                    final String folderName = finalAsset.name;
                    final String childPath = activePath.isEmpty() ? folderName : activePath + "/" + folderName;
                    items.add(new MenuItem("Open Folder '" + folderName + "'", () -> manager.getAssetDatabase().setCurrentFolderPath(childPath)));
                    items.add(new MenuItem("Rename Folder", () -> manager.getAssetDatabase().promptRenameFolder(finalAsset)));
                    items.add(new MenuItem(AssetBrowserInputDelegate.revealLabel(), () -> AssetBrowserInputDelegate.revealInFileManager(finalAsset)));
                    items.add(new MenuItem("Delete Folder", () -> manager.getAssetDatabase().deleteFolder(finalAsset)));
                } else {
                    if (targetAsset.type == AssetType.PREFAB) {
                        items.add(new MenuItem("Spawn Prefab Entity", () -> spawnPrefabFromAsset(manager, finalAsset)));
                        items.add(new MenuItem("Rename Prefab", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                    } else if (targetAsset.type == AssetType.MESH_3D) {
                        items.add(new MenuItem("Spawn Mesh Entity", () -> spawnEntityFromAsset(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                    } else if (targetAsset.type == AssetType.TEXTURE) {
                        items.add(new MenuItem("Link Texture to Selected", () -> assignTextureToSelectedEntity(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                    } else if (targetAsset.type == AssetType.SCRIPT) {
                        items.add(new MenuItem("Link Script to Selected", () -> assignScriptToSelectedEntity(manager, finalAsset)));
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                    } else {
                        if (targetAsset.type == AssetType.SCENE) {
                            items.add(new MenuItem("Open Scene", () -> manager.getSceneManager().loadSceneFromFile(finalAsset.absolutePath, manager.getAssetDatabase())));
                        }
                        items.add(new MenuItem("Rename Asset", () -> manager.getAssetDatabase().promptRenameAsset(finalAsset)));
                    }
                    AssetBrowserInputDelegate.addFileMenuItems(items, finalAsset, manager);
                }
            } else {
                items.addAll(AssetBrowserInputDelegate.backgroundMenuItems(manager));
            }

        } else if (region == PanelRegion.SCENE_HIERARCHY) {
            List<Entity> entities = manager.getSceneEntities();
            if (entities != null && !entities.isEmpty()) {
                List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
                int index = HierarchyPanel.rowAt(ndcY, visibleTree.size());
                if (index >= 0) {
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

        } else if (region == PanelRegion.ANIMATOR) {
            // Animator graph canvas owns this region; its menu is opened on press by AnimatorCanvasDelegate
            return;
        } else if (region == PanelRegion.VIEWPORT) {
            // Pixel-exact GPU Color-ID pick at the press point; the release lands within a few pixels of it, so fall
            // back to the hover probe's texel when the press texel has no completed readback
            int pickId = ViewportPanel.queryPickAtScreen(dockSpace, screenX, screenY);
            if (pickId == IRenderBackend.PICK_UNAVAILABLE) pickId = ViewportPanel.queryPickAtHover();
            Entity bestEntity = ViewportPanel.resolvePick(pickId, manager.getSceneEntities());

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
            openContextMenu(screenX, screenY, items);
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

        if (beginPickerScrollbarDrag(ndcX, ndcY)) return;

        if (ndcX >= ModalDialogOverlay.PICKER_ROW_X0 && ndcX <= ModalDialogOverlay.PICKER_ROW_X1
                && ndcY >= ModalDialogOverlay.PICKER_ACTION_Y0 && ndcY <= ModalDialogOverlay.PICKER_ACTION_Y1) {
            unparentEntity(manager, parentPickerTargetEntity);
            UISoundPlayer.play(UISound.CLICK);
            closeModal();
            return;
        }

        // Same flattened tree the modal draws (Hierarchy order, indentation, and expand/collapse state)
        List<Entity> sceneEntities = manager.getSceneEntities();
        List<HierarchyPanel.HierarchyNode> tree = HierarchyPanel.buildFlattenedTree(sceneEntities);
        ModalDialogOverlay.updatePickerScrollBounds(tree.size());
        int row = ModalDialogOverlay.pickerRowAt(ndcX, ndcY, tree.size());
        if (row < 0) return;

        HierarchyPanel.HierarchyNode node = tree.get(row);
        if (node.hasChildren && ModalDialogOverlay.hitsParentPickerDisclosure(ndcX, node.depth, HierarchyPanel.getLastMaxDepth())) {
            UISoundPlayer.play(UISound.CLICK);
            node.entity.expandedInHierarchy = !node.entity.expandedInHierarchy;
            return;
        }

        // The target itself and its descendants are shown but disabled: picking one would form a parent loop
        if (!ModalDialogOverlay.isSelectableParent(parentPickerTargetEntity, node.entity, sceneEntities)) return;

        UISoundPlayer.play(UISound.CLICK);
        setParentEntity(manager, parentPickerTargetEntity, node.entity);
        closeModal();
    }

    private boolean draggingModalScrollbar = false;
    private float modalScrollbarGrabOffset = 0.0f;

    /**
     * Press on an open picker modal's scrollbar: grabs the thumb, or jumps to the click on the track, then drags.
     * Returns false (and does nothing) when the press is not on the scrollbar.
     */
    public boolean beginPickerScrollbarDrag(float ndcX, float ndcY) {
        if (!ModalDialogOverlay.inPickerScrollbar(ndcX, ndcY)) return false;
        float top = ModalDialogOverlay.pickerThumbTop();
        float h = ModalDialogOverlay.pickerThumbHeight();
        if (ndcY > top || ndcY < top - h) {
            ModalDialogOverlay.setPickerThumbTop(ndcY + h * 0.5f);
            top = ModalDialogOverlay.pickerThumbTop();
        }
        modalScrollbarGrabOffset = top - ndcY;
        draggingModalScrollbar = true;
        return true;
    }

    private boolean isPickerModalOpen() {
        return activeModal == ModalState.PARENT_PICKER || activeModal == ModalState.ASSET_PICKER;
    }

    /** Mouse wheel while a picker modal is open: scrolls its list. Returns false when no picker is open. */
    public boolean handlePickerScroll(double yOffset) {
        if (!isPickerModalOpen()) return false;
        ModalDialogOverlay.scrollPicker((float) -yOffset * 0.18f);
        return true;
    }

    /** Cursor move in screen space; drives an active modal scrollbar drag. */
    public boolean processModalDrag(float ndcY) {
        if (!draggingModalScrollbar) return false;
        if (!isPickerModalOpen()) {
            draggingModalScrollbar = false;
            return false;
        }
        ModalDialogOverlay.setPickerThumbTop(ndcY + modalScrollbarGrabOffset);
        return true;
    }

    public void onModalMouseRelease() {
        draggingModalScrollbar = false;
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
        // Walks the full ancestor chain, so a loop is rejected at any depth (child -> ... -> great-grandchild)
        if (!HierarchyPanel.isCycleSafeParent(child, parent, manager.getSceneEntities())) {
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.WARN, "[Scene] Cannot parent '" + child.name + "' under its own descendant");
            return;
        }
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
