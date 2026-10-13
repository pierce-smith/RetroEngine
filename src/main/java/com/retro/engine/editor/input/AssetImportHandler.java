package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

/**
 * AssetImportHandler handles bottom-panel asset card clicks, spawning entities from mesh assets,
 * assigning textures, loading scene files, and triggering OS import file dialogs.
 */
public class AssetImportHandler {

    public boolean processAssetDirectoryClick(double mouseX, double mouseY, EditorPanelManager manager) {
        // "Import Asset" button on bottom panel right side
        if (mouseX >= 1030.0 && mouseX <= 1250.0 && mouseY >= 520.0 && mouseY <= 555.0) {
            promptImportAssetForDirectory(manager);
            return true;
        }

        List<AssetEntry> assets = manager.getAssetDirectory();
        if (assets.isEmpty()) {
            if (mouseY >= 560.0 && mouseY <= 690.0) {
                promptImportAssetForDirectory(manager);
                return true;
            }
            return false;
        }

        double startX = 20.0;
        double cardWidth = 230.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size() && mouseY >= 560.0 && mouseY <= 690.0) {
            AssetEntry clickedAsset = assets.get(index);
            if (clickedAsset.type == AssetType.MESH_3D) {
                manager.spawnEntityFromAsset(clickedAsset);
            } else if (clickedAsset.type == AssetType.TEXTURE) {
                manager.assignTextureToSelectedEntity(clickedAsset);
            } else if (clickedAsset.type == AssetType.SCENE) {
                manager.loadSceneFromFile(clickedAsset.absolutePath);
            }
            return true;
        }
        return false;
    }

    public void promptImportAssetForDirectory(EditorPanelManager manager) {
        try {
            String defaultDir = new File("Assets").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Import Asset to Directory", defaultDir, "Supported Assets (*.obj, *.fbx, *.gltf, *.png, *.jpg, *.tga, *.scene)", "obj", "fbx", "gltf", "glb", "png", "jpg", "tga", "scene"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                manager.onFilesDropped(List.of(importedPath));
            }
        } catch (Exception e) {
            System.err.println("[AssetDirectory] File import dialog error: " + e.getMessage());
        }
    }
}
