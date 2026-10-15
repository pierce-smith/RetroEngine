package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

/**
 * InspectorInteractor handles Inspector panel clicks ([PICK] / [IMPORT]), property field interactions,
 * and launches native OS file dialogs for asset imports.
 */
public class InspectorInteractor {

    public static void processInspectorClick(float ndcX, float ndcY, EditorPanelManager manager) {
        SceneEntity selected = manager.getSelectedEntity();
        if (selected == null) return;

        // Mesh Component Row (ndcY: 0.36f to 0.52f - Higher up in Inspector)
        if (ndcY >= 0.36f && ndcY <= 0.52f) {
            if (ndcX >= 0.80f && ndcX <= 0.88f) {
                manager.openAssetPicker(AssetType.MESH_3D);
            } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                promptImportMesh(manager);
            }
        }
        // Material Texture Component Row (ndcY: 0.20f to 0.35f - Lower down in Inspector)
        else if (ndcY >= 0.20f && ndcY <= 0.35f) {
            if (ndcX >= 0.80f && ndcX <= 0.88f) {
                manager.openAssetPicker(AssetType.TEXTURE);
            } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                promptImportTexture(manager);
            }
        }
    }

    public static void promptImportTexture(EditorPanelManager manager) {
        try {
            String defaultDir = new File("Assets/Textures").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Select Texture Asset", defaultDir, "Texture Images (*.png, *.jpg, *.tga)", "png", "jpg", "tga"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                File file = new File(importedPath);
                AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.TEXTURE);
                manager.onFilesDropped(List.of(importedPath));
                manager.assignTextureToSelectedEntity(newAsset);
                System.out.println("[Inspector] Imported & Bound Texture: " + file.getName());
            }
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening texture dialog: " + e.getMessage());
        }
    }

    public static void promptImportMesh(EditorPanelManager manager) {
        try {
            String defaultDir = new File("Assets/Models").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Select 3D Mesh Asset", defaultDir, "3D Models (*.obj, *.fbx, *.gltf)", "obj", "fbx", "gltf", "glb"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                File file = new File(importedPath);
                AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.MESH_3D);
                manager.onFilesDropped(List.of(importedPath));
                manager.assignMeshToSelectedEntity(newAsset);
                System.out.println("[Inspector] Imported & Bound 3D Mesh: " + file.getName());
            }
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening mesh dialog: " + e.getMessage());
        }
    }

    public static void promptImportAssetForDirectory(EditorPanelManager manager) {
        try {
            String defaultDir = new File("Assets").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Import Asset to Directory", defaultDir, "Supported Assets (*.obj, *.fbx, *.gltf, *.png, *.jpg, *.tga, *.scene)", "obj", "fbx", "gltf", "glb", "png", "jpg", "tga", "scene"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                manager.onFilesDropped(List.of(importedPath));
                System.out.println("[AssetDirectory] Imported Asset: " + importedPath);
            }
        } catch (Exception e) {
            System.err.println("[AssetDirectory] Error opening file dialog: " + e.getMessage());
        }
    }
}
