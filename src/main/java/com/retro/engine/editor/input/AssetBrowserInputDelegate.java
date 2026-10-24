package com.retro.engine.editor.input;

import com.retro.engine.editor.ui.InspectorPanel;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.ScriptComponent;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.ui.HierarchyPanel;
import com.retro.engine.editor.commands.EditorCommands;

import java.lang.reflect.Field;
import java.util.List;

public class AssetBrowserInputDelegate {

    public static void processHierarchyClick(float ndcY, EditorWorkspace manager) {
        List<Entity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        List<HierarchyPanel.HierarchyNode> visibleTree = HierarchyPanel.buildFlattenedTree(entities);
        if (visibleTree.isEmpty()) return;

        int index = (int) ((0.85f - ndcY) / 0.08f);
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
        if (ndcX >= 0.30f && ndcX <= 0.62f && ndcY >= -0.50f && ndcY <= -0.42f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.getAssetDatabase().createNewFolder();
            return;
        }

        // [+ IMPORT ASSET] Button
        if (ndcX >= 0.65f && ndcX <= 0.98f && ndcY >= -0.50f && ndcY <= -0.42f) {
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
        float cardX = -0.95f;

        // Check if clicked the Up Level card
        if (hasUpLevel) {
            if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                UISoundPlayer.play(UISound.CLICK);
                manager.getAssetDatabase().navigateUpFolder();
                return;
            }
            cardX += 0.36f;
        }

        for (AssetMeta asset : visibleAssets) {
            if (cardX + 0.35f > 0.95f) break;
            if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
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
            cardX += 0.36f;
        }
    }
}
