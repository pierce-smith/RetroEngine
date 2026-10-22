package com.retro.engine.editor.ui;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.ui.HierarchyPanel;

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
        // 1. Header Buttons
        // [+ NEW FOLDER] Button
        if (ndcX >= 0.30f && ndcX <= 0.62f && ndcY >= -0.50f && ndcY <= -0.42f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.createNewFolder();
            return;
        }

        // [+ IMPORT ASSET] Button
        if (ndcX >= 0.65f && ndcX <= 0.98f && ndcY >= -0.50f && ndcY <= -0.42f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            InspectorInputDelegate.promptImportAssetForDirectory(manager);
            return;
        }

        String activePath = manager.getCurrentFolderPath();
        List<AssetMeta> assetDirectory = manager.getAssetDirectory();

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
                manager.navigateUpFolder();
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
                    manager.setCurrentFolderPath(newPath);
                } else if (asset.type == AssetType.PREFAB) {
                    UISoundPlayer.play(UISound.SPAWN);
                    manager.spawnPrefabFromAsset(asset);
                } else if (asset.type == AssetType.MESH_3D) {
                    UISoundPlayer.play(UISound.SPAWN);
                    manager.spawnEntityFromAsset(asset);
                } else if (asset.type == AssetType.TEXTURE) {
                    UISoundPlayer.play(UISound.CLICK);
                    manager.assignTextureToSelectedEntity(asset);
                } else if (asset.type == AssetType.SCRIPT) {
                    UISoundPlayer.play(UISound.CLICK);
                    manager.assignScriptToSelectedEntity(asset);
                }
                break;
            }
            cardX += 0.36f;
        }
    }
}
