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

    public static void processHierarchyClick(float ndcY, EditorWorkspace manager) {
        List<Entity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
        if (visibleTree.isEmpty()) return;

        float fromTop = HierarchyPanel.ROW_TOP - ndcY;
        int index = fromTop >= 0.0f ? (int) (fromTop / HierarchyPanel.ROW_HEIGHT) : -1;
        if (index >= 0 && index < visibleTree.size()) {
            Entity clickedEntity = visibleTree.get(index).entity;
            if (visibleTree.get(index).hasChildren && manager.getSelectedEntity() == clickedEntity) {
                UISoundPlayer.play(UISound.CLICK);
                clickedEntity.expandedInHierarchy = !clickedEntity.expandedInHierarchy;
            } else {
                UISoundPlayer.play(UISound.CLICK);
                manager.selectEntity(clickedEntity);
            }
        } else {
            manager.selectEntity(null);
        }
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

        String activePath = manager.getAssetDatabase().getCurrentFolderPath();
        List<AssetMeta> assetDirectory = manager.getAssetDatabase().getAssetDirectory();

        List<AssetMeta> visibleAssets = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                .toList() : List.of();

        boolean hasUpLevel = !activePath.isEmpty();
        if (visibleAssets.isEmpty() && !hasUpLevel) {
            return;
        }

        // 2. Click specific Asset or Folder Cards
        float cardX = AssetBrowserPanel.CARD_START_X;

        // Check if clicked the Up Level card
        if (hasUpLevel) {
            if (inCard(ndcX, ndcY, cardX)) {
                UISoundPlayer.play(UISound.CLICK);
                manager.getAssetDatabase().navigateUpFolder();
                return;
            }
            cardX += AssetBrowserPanel.CARD_PITCH;
        }

        for (AssetMeta asset : visibleAssets) {
            if (!AssetBrowserPanel.cardFits(cardX)) break;
            if (inCard(ndcX, ndcY, cardX)) {
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
                break;
            }
            cardX += AssetBrowserPanel.CARD_PITCH;
        }
    }

    private static boolean inCard(float ndcX, float ndcY, float cardX) {
        return ndcX >= cardX && ndcX <= cardX + AssetBrowserPanel.CARD_W && ndcY >= AssetBrowserPanel.CARD_Y0 && ndcY <= AssetBrowserPanel.CARD_Y1;
    }
}
