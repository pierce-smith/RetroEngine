package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.util.List;

public class PanelInteractor {

    public static void processHierarchyClick(float ndcY, EditorPanelManager manager) {
        List<SceneEntity> entities = manager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        int index = (int) ((0.85f - ndcY) / 0.08f);
        if (index >= 0 && index < entities.size()) {
            manager.selectEntity(entities.get(index));
        } else {
            manager.selectEntity(null);
        }
    }

    public static void processAssetDirectoryClick(float ndcX, float ndcY, EditorPanelManager manager) {
        // 1. Header Buttons
        // [+ NEW FOLDER] Button
        if (ndcX >= 0.30f && ndcX <= 0.62f && ndcY >= -0.50f && ndcY <= -0.42f) {
            manager.createNewFolder();
            return;
        }

        // [+ IMPORT ASSET] Button
        if (ndcX >= 0.65f && ndcX <= 0.98f && ndcY >= -0.50f && ndcY <= -0.42f) {
            InspectorInteractor.promptImportAssetForDirectory(manager);
            return;
        }

        String activePath = manager.getCurrentFolderPath();
        List<AssetEntry> assetDirectory = manager.getAssetDirectory();

        List<AssetEntry> visibleAssets = (assetDirectory != null) ? assetDirectory.stream()
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
                manager.navigateUpFolder();
                return;
            }
            cardX += 0.36f;
        }

        for (AssetEntry asset : visibleAssets) {
            if (cardX + 0.35f > 0.95f) break;
            if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                if (asset.type == AssetType.FOLDER) {
                    String newPath = activePath.isEmpty() ? asset.name : activePath + "/" + asset.name;
                    manager.setCurrentFolderPath(newPath);
                } else if (asset.type == AssetType.MESH_3D) {
                    manager.spawnEntityFromAsset(asset);
                } else if (asset.type == AssetType.TEXTURE) {
                    manager.assignTextureToSelectedEntity(asset);
                }
                break;
            }
            cardX += 0.36f;
        }
    }
}
