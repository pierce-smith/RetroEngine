package com.retro.engine.editor.input;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.CameraComponent;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class InspectorInteractor {

    public static void processInspectorClick(float ndcX, float ndcY, EditorPanelManager manager) {
        if (manager == null) return;
        SceneEntity entity = manager.getSelectedEntity();
        if (entity == null) return;

        float curY = 0.72f;

        for (Component comp : entity.getAllComponents()) {
            if (curY < -0.45f) break;

            // Header line
            curY -= 0.06f;

            if (comp instanceof TransformComponent) {
                // POS: curY -= 0.05f
                // ROT: curY -= 0.05f
                // SCL: curY -= 0.07f
                curY -= 0.17f;

            } else if (comp instanceof MeshRendererComponent) {
                // MESH Row: [curY - 0.04f, curY + 0.02f]
                if (ndcY >= curY - 0.04f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.80f && ndcX <= 0.88f) {
                        manager.openAssetPicker(AssetType.MESH_3D);
                        return;
                    } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                        promptImportMesh(manager);
                        return;
                    }
                }
                curY -= 0.08f;

            } else if (comp instanceof MaterialComponent) {
                curY -= 0.05f; // SHADER line
                // TEX Row: [curY - 0.04f, curY + 0.02f]
                if (ndcY >= curY - 0.04f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.80f && ndcX <= 0.88f) {
                        manager.openAssetPicker(AssetType.TEXTURE);
                        return;
                    } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                        promptImportTexture(manager);
                        return;
                    }
                }
                curY -= 0.09f;

            } else if (comp instanceof LightComponent lc) {
                // TYPE Row: [curY - 0.03f, curY + 0.02f]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f && ndcX >= 0.84f && ndcX <= 0.98f) {
                    lc.cycleType();
                    return;
                }
                curY -= 0.06f;

                // INTENSITY Row: [curY - 0.03f, curY + 0.02f]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) { lc.intensity = Math.max(0.0f, lc.intensity - 0.5f); return; }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) { lc.intensity += 0.5f; return; }
                }
                curY -= 0.06f;

                // COLOR Row: [curY - 0.03f, curY + 0.02f]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.88f) { lc.r = (lc.r > 0.5f) ? 0.2f : 1.0f; return; }
                    if (ndcX >= 0.89f && ndcX <= 0.93f) { lc.g = (lc.g > 0.5f) ? 0.2f : 1.0f; return; }
                    if (ndcX >= 0.94f && ndcX <= 0.98f) { lc.b = (lc.b > 0.5f) ? 0.2f : 1.0f; return; }
                }
                curY -= 0.08f;

            } else if (comp instanceof CameraComponent cc) {
                // FOV Row: [curY - 0.03f, curY + 0.02f]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) { cc.fov = Math.max(10.0f, cc.fov - 5.0f); return; }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) { cc.fov = Math.min(170.0f, cc.fov + 5.0f); return; }
                }
                curY -= 0.06f;

                // NEAR/FAR line
                curY -= 0.05f;

                // PRIMARY Row: [curY - 0.03f, curY + 0.02f]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f && ndcX >= 0.58f && ndcX <= 0.85f) {
                    cc.isPrimary = !cc.isPrimary;
                    return;
                }
                curY -= 0.08f;

            } else {
                curY -= 0.07f;
            }
        }
    }

    public static void promptImportTexture(EditorPanelManager manager) {
        String importedPath = NativeFileDialog.openFileDialog("Select Texture Asset", "", "Texture Images (*.png, *.jpg, *.tga)", "png", "jpg", "tga");
        if (importedPath != null && !importedPath.trim().isEmpty() && manager != null) {
            File file = new File(importedPath);
            manager.assignTextureToSelectedEntity(new AssetEntry(file.getName(), importedPath, AssetType.TEXTURE));
        }
    }

    public static void promptImportMesh(EditorPanelManager manager) {
        String importedPath = NativeFileDialog.openFileDialog("Select 3D Mesh Asset", "", "3D Models (*.obj, *.fbx, *.gltf)", "obj", "fbx", "gltf", "glb");
        if (importedPath != null && !importedPath.trim().isEmpty() && manager != null) {
            File file = new File(importedPath);
            manager.assignMeshToSelectedEntity(new AssetEntry(file.getName(), importedPath, AssetType.MESH_3D));
        }
    }

    public static void promptImportAssetForDirectory(EditorPanelManager manager) {
        String importedPath = NativeFileDialog.openFileDialog("Import Asset", "", "All Assets (*.obj, *.fbx, *.png, *.jpg, *.scene)", "obj", "fbx", "png", "jpg", "scene");
        if (importedPath != null && !importedPath.trim().isEmpty() && manager != null) {
            List<String> list = new ArrayList<>();
            list.add(importedPath);
            manager.onFilesDropped(list);
        }
    }
}
