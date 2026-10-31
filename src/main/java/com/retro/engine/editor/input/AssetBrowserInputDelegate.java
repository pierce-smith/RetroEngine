package com.retro.engine.editor.input;

import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.ui.panels.AssetBrowserPanel;
import com.retro.engine.editor.ui.panels.HierarchyPanel;
import com.retro.engine.editor.commands.EditorCommands;

import java.lang.reflect.Field;
import java.util.List;

public class AssetBrowserInputDelegate {

    // --- Hierarchy press / drag-and-drop state ---
    private static final float HIERARCHY_DRAG_THRESHOLD = 0.025f; // design-space Y travel before a press becomes a drag
    private static Entity hierarchyPressEntity = null;
    private static float hierarchyPressY = 0.0f;
    private static float hierarchyDragY = 0.0f;
    private static boolean hierarchyDragging = false;
    private static boolean hierarchyPendingToggle = false;
    private static boolean draggingHierarchyScrollbar = false;
    private static float hierarchyScrollbarGrabOffset = 0.0f;

    /**
     * Press in the Hierarchy (design space). Selects the row under the cursor, or arms an expand/collapse toggle when
     * the row is already selected; the toggle fires on release only if the press never turned into a drag.
     */
    public static void processHierarchyClick(float ndcX, float ndcY, EditorWorkspace manager) {
        resetHierarchyPress();

        // Scrollbar: grab the thumb, or jump-to-click on the track, then drag
        if (HierarchyPanel.inScrollbar(ndcX, ndcY)) {
            float top = HierarchyPanel.thumbTop();
            float h = HierarchyPanel.thumbHeight();
            if (ndcY > top || ndcY < top - h) {
                HierarchyPanel.setThumbTop(ndcY + h * 0.5f);
                top = HierarchyPanel.thumbTop();
            }
            hierarchyScrollbarGrabOffset = top - ndcY;
            draggingHierarchyScrollbar = true;
            return;
        }

        List<Entity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
        if (visibleTree.isEmpty()) return;

        int index = HierarchyPanel.rowAt(ndcY, visibleTree.size());
        if (index >= 0) {
            HierarchyPanel.HierarchyNode node = visibleTree.get(index);
            Entity clickedEntity = node.entity;
            UISoundPlayer.play(UISound.CLICK);
            if (node.hasChildren && manager.getSelectedEntity() == clickedEntity) {
                hierarchyPendingToggle = true;
            } else {
                manager.selectEntity(clickedEntity);
            }
            hierarchyPressEntity = clickedEntity;
            hierarchyPressY = ndcY;
            hierarchyDragY = ndcY;
        } else if (ndcY <= HierarchyPanel.VIEW_TOP && ndcY >= HierarchyPanel.VIEW_BOTTOM) {
            manager.selectEntity(null);
        }
    }

    /** Cursor move in Hierarchy design space; drives scrollbar drags and row drag-and-drop. */
    public static boolean processHierarchyDrag(float ndcY) {
        if (draggingHierarchyScrollbar) {
            HierarchyPanel.setThumbTop(ndcY + hierarchyScrollbarGrabOffset);
            return true;
        }
        if (hierarchyPressEntity == null) return false;

        hierarchyDragY = ndcY;
        if (!hierarchyDragging && Math.abs(ndcY - hierarchyPressY) > HIERARCHY_DRAG_THRESHOLD) {
            hierarchyDragging = true;
            hierarchyPendingToggle = false;
        }
        if (hierarchyDragging) {
            HierarchyPanel.setDrag(hierarchyPressEntity, ndcY);
        }
        return true;
    }

    /** Mouse wheel over the Hierarchy: same rate as the Inspector and Asset Browser. */
    public static boolean handleHierarchyScroll(double yOffset) {
        HierarchyPanel.scroll((float) -yOffset * 0.18f);
        if (hierarchyDragging) HierarchyPanel.setDrag(hierarchyPressEntity, hierarchyDragY);
        return true;
    }

    private static void processHierarchyRelease(EditorWorkspace manager) {
        if (manager != null && hierarchyPressEntity != null) {
            if (hierarchyDragging) {
                List<Entity> entities = manager.getSceneEntities();
                List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
                applyHierarchyDrop(manager, hierarchyPressEntity, HierarchyPanel.computeDrop(hierarchyDragY, hierarchyPressEntity, visibleTree, entities));
            } else if (hierarchyPendingToggle) {
                hierarchyPressEntity.expandedInHierarchy = !hierarchyPressEntity.expandedInHierarchy;
            }
        }
        resetHierarchyPress();
    }

    private static void resetHierarchyPress() {
        hierarchyPressEntity = null;
        hierarchyDragging = false;
        hierarchyPendingToggle = false;
        draggingHierarchyScrollbar = false;
        HierarchyPanel.clearDrag();
    }

    /**
     * Commits a drop as one undoable move: re-parent (cycle-checked at any depth) plus a scene-list reorder, since
     * list order is sibling order in the tree and in saved .rscene files.
     */
    private static void applyHierarchyDrop(EditorWorkspace manager, Entity source, HierarchyPanel.DropTarget drop) {
        if (drop.kind == HierarchyPanel.DropKind.NONE || !drop.valid) return;
        List<Entity> entities = manager.getSceneEntities();
        int from = entities.indexOf(source);
        if (from < 0) return;

        Entity oldParent = HierarchyPanel.findEntity(entities, source.parentId);
        Entity newParent;
        int toIndex;
        switch (drop.kind) {
            case INTO -> {
                newParent = drop.target;
                toIndex = entities.size() - 1; // last among its new siblings
            }
            case ROOT_END -> {
                newParent = null;
                toIndex = entities.size() - 1;
            }
            default -> {
                newParent = drop.newParent;
                int targetIndex = entities.indexOf(drop.target);
                if (targetIndex < 0) return;
                // moveEntity removes the source first, which shifts later indices down by one
                int before = (from < targetIndex) ? targetIndex - 1 : targetIndex;
                toIndex = (drop.kind == HierarchyPanel.DropKind.AFTER) ? before + 1 : before;
            }
        }

        if (!HierarchyPanel.isCycleSafeParent(source, newParent, entities)) return;
        if (newParent == oldParent && toIndex == from) return;

        if (newParent != null) newParent.expandedInHierarchy = true;
        manager.getCommandHistory().executeCommand(new EditorCommands.HierarchyMoveCommand(manager, source, oldParent, from, newParent, toIndex));
        manager.selectEntity(source);
        UISoundPlayer.play(UISound.CLICK);
    }

    public static void processAssetDirectoryClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;

        // 1. Header Buttons
        // [+ NEW FOLDER] Button
        if (ndcX >= AssetBrowserPanel.NEW_FOLDER_X0 && ndcX <= AssetBrowserPanel.NEW_FOLDER_X1 && ndcY >= AssetBrowserPanel.HEADER_Y0 && ndcY <= AssetBrowserPanel.HEADER_Y1) {
            UISoundPlayer.play(UISound.CLICK);
            manager.getAssetDatabase().createNewFolder();
            return;
        }

        // [+ IMPORT ASSET] Button
        if (ndcX >= AssetBrowserPanel.IMPORT_X0 && ndcX <= AssetBrowserPanel.IMPORT_X1 && ndcY >= AssetBrowserPanel.HEADER_Y0 && ndcY <= AssetBrowserPanel.HEADER_Y1) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            InspectorInputDelegate.promptImportAssetForDirectory(manager);
            return;
        }

        // Scrollbar: grab the thumb, or jump-to-click on the track, then drag
        if (AssetBrowserPanel.inScrollbar(ndcX, ndcY)) {
            float top = AssetBrowserPanel.thumbTop();
            float h = AssetBrowserPanel.thumbHeight();
            if (ndcY > top || ndcY < top - h) {
                AssetBrowserPanel.setThumbTop(ndcY + h * 0.5f);
                top = AssetBrowserPanel.thumbTop();
            }
            scrollbarGrabOffset = top - ndcY;
            draggingScrollbar = true;
            return;
        }

        String activePath = manager.getAssetDatabase().getCurrentFolderPath();
        List<AssetMeta> assetDirectory = manager.getAssetDatabase().getAssetDirectory();

        List<AssetMeta> visibleAssets = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                .toList() : List.of();

        boolean hasUpLevel = !activePath.isEmpty();
        if (visibleAssets.isEmpty() && !hasUpLevel) {
            return;
        }

        // 2. Click specific Asset or Folder Cards (grid slot under cursor, scroll offset applied)
        int slot = AssetBrowserPanel.slotAt(ndcX, ndcY, visibleAssets.size() + (hasUpLevel ? 1 : 0));
        if (slot < 0) return;

        // Check if clicked the Up Level card
        if (hasUpLevel) {
            if (slot == 0) {
                UISoundPlayer.play(UISound.CLICK);
                manager.getAssetDatabase().navigateUpFolder();
                return;
            }
            slot--;
        }

        AssetMeta asset = visibleAssets.get(slot);
        if (asset.type == AssetType.FOLDER) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String newPath = activePath.isEmpty() ? asset.name : activePath + "/" + asset.name;
            manager.getAssetDatabase().setCurrentFolderPath(newPath);
        } else if (asset.type == AssetType.PREFAB) {
            UISoundPlayer.play(UISound.SPAWN);
            List<Entity> spawned = manager.getSceneManager().instantiatePrefab(asset.absolutePath, manager.getAssetDatabase());
            if (spawned != null && !spawned.isEmpty()) {
                manager.getAssetDatabase().addAssetDirectoryIfNotExists(asset.absolutePath, AssetType.PREFAB);
            }
        } else if (asset.type == AssetType.MESH_3D) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getSceneManager().spawnEntityFromAsset(asset);
            Entity newlySpawned = manager.getSelectedEntity();
            if (newlySpawned != null) {
                EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(manager, newlySpawned);
                manager.getCommandHistory().executeCommand(cmd);
            }
        } else if (asset.type == AssetType.TEXTURE) {
            UISoundPlayer.play(UISound.CLICK);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                Material mat = selected.getComponent(Material.class);
                if (mat != null) {
                    try {
                        Field field = Material.class.getDeclaredField("textureAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, mat.textureAssetPath, asset.absolutePath, "TEXTURE");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else if (asset.type == AssetType.SCENE) {
            // Scene loads swap entities only; the project asset registry is left untouched
            UISoundPlayer.play(UISound.SPAWN);
            manager.getSceneManager().loadSceneFromFile(asset.absolutePath, manager.getAssetDatabase());
        } else if (asset.type == AssetType.SCRIPT) {
            UISoundPlayer.play(UISound.CLICK);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                String oldName = (sc != null) ? sc.scriptName : null;
                EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, asset.absolutePath, asset.name);
                manager.getCommandHistory().executeCommand(cmd);
                InspectorPanel.resetScroll();
            }
        }
    }

    private static boolean draggingScrollbar = false;
    private static float scrollbarGrabOffset = 0.0f;

    /** Mouse wheel over the Asset Browser: smooth scroll, same rate as the Inspector. */
    public static boolean handleScroll(double yOffset) {
        AssetBrowserPanel.scroll((float) -yOffset * 0.18f);
        return true;
    }

    /** Cursor move in Asset Browser design space; drives an active scrollbar drag. */
    public static boolean processDrag(float ndcY) {
        if (!draggingScrollbar) return false;
        AssetBrowserPanel.setThumbTop(ndcY + scrollbarGrabOffset);
        return true;
    }

    public static void onMouseRelease(EditorWorkspace manager) {
        draggingScrollbar = false;
        processHierarchyRelease(manager);
    }
}
