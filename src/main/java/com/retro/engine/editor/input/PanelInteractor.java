package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.util.List;

/**
 * PanelInteractor handles Scene Hierarchy panel entity selections and Asset Directory card/import interactions.
 */
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
        // 1. [+ IMPORT ASSET] Header Button
        if (ndcX >= 0.65f && ndcX <= 0.98f && ndcY >= -0.50f && ndcY <= -0.42f) {
            InspectorInteractor.promptImportAssetForDirectory(manager);
            return;
        }

        List<AssetEntry> assetDirectory = manager.getAssetDirectory();

        // 2. Asset Cards or Empty State Click
        if (assetDirectory.isEmpty()) {
            if (ndcY >= -0.92f && ndcY <= -0.42f) {
                InspectorInteractor.promptImportAssetForDirectory(manager);
            }
            return;
        }

        float cardX = -0.95f;
        for (AssetEntry asset : assetDirectory) {
            if (cardX + 0.35f > 0.95f) break;
            if (ndcX >= cardX && ndcX <= cardX + 0.32f && ndcY >= -0.92f && ndcY <= -0.55f) {
                if (asset.type == AssetType.MESH_3D) manager.spawnEntityFromAsset(asset);
                else if (asset.type == AssetType.TEXTURE) manager.assignTextureToSelectedEntity(asset);
                break;
            }
            cardX += 0.36f;
        }
    }
}
