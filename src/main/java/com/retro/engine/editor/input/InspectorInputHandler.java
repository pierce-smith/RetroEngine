package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

/**
 * InspectorInputHandler manages right-panel property field hit detection,
 * value scrubbing (POS, ROT, SCL fields), and asset picker modal triggers.
 */
public class InspectorInputHandler {

    public enum InspectorField { NONE, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, SCL_X, SCL_Y, SCL_Z }

    private InspectorField activeField = InspectorField.NONE;

    public boolean processInspectorClick(double mouseX, double mouseY, double rightMinX, EditorPanelManager manager, TransformUndoTracker undoTracker) {
        SceneEntity active = manager.getSelectedEntity();
        if (active == null) return false;

        // Mesh Renderer Row Clicks (Y: 190.0 to 225.0)
        if (mouseY >= 190.0 && mouseY <= 225.0) {
            if (mouseX >= 1150.0 && mouseX <= 1205.0) {
                manager.openAssetPicker(AssetType.MESH_3D);
                return true;
            }
            if (mouseX >= 1210.0 && mouseX <= 1270.0) {
                promptImportMesh(manager);
                return true;
            }
        }

        // Material Texture Row Clicks (Y: 255.0 to 295.0)
        if (mouseY >= 255.0 && mouseY <= 295.0) {
            if (mouseX >= 1150.0 && mouseX <= 1205.0) {
                manager.openAssetPicker(AssetType.TEXTURE);
                return true;
            }
            if (mouseX >= 1210.0 && mouseX <= 1270.0) {
                promptImportTexture(manager);
                return true;
            }
        }

        if (mouseX >= rightMinX + 20.0 && mouseX <= rightMinX + 250.0) {
            InspectorField hitField = getHitInspectorField(mouseX, mouseY, rightMinX);
            if (hitField != InspectorField.NONE) {
                this.activeField = hitField;
                undoTracker.captureStart(active);
                System.out.println("[Inspector] Grabbed Field: " + hitField);
                return true;
            }
        }
        return false;
    }

    public boolean handleFieldScrub(double deltaX, EditorPanelManager manager) {
        if (activeField == InspectorField.NONE) return false;

        SceneEntity active = manager.getSelectedEntity();
        if (active == null) return false;

        TransformComponent tc = active.getTransform();
        if (tc == null) return false;

        float scrubVal = (float) deltaX * 0.05f;

        switch (activeField) {
            case POS_X -> tc.posX += scrubVal;
            case POS_Y -> tc.posY += scrubVal;
            case POS_Z -> tc.posZ += scrubVal;
            case ROT_X -> tc.rotX = (tc.rotX + scrubVal * 2.0f) % 360.0f;
            case ROT_Y -> tc.rotY = (tc.rotY + scrubVal * 2.0f) % 360.0f;
            case ROT_Z -> tc.rotZ = (tc.rotZ + scrubVal * 2.0f) % 360.0f;
            case SCL_X -> tc.scaleX = Math.max(0.01f, tc.scaleX + scrubVal);
            case SCL_Y -> tc.scaleY = Math.max(0.01f, tc.scaleY + scrubVal);
            case SCL_Z -> tc.scaleZ = Math.max(0.01f, tc.scaleZ + scrubVal);
            default -> {}
        }
        return true;
    }

    public void releaseField() {
        this.activeField = InspectorField.NONE;
    }

    public InspectorField getActiveField() {
        return activeField;
    }

    public void promptImportTexture(EditorPanelManager manager) {
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
    }

    public void promptImportMesh(EditorPanelManager manager) {
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
    }

    private InspectorField getHitInspectorField(double mouseX, double mouseY, double rightMinX) {
        if (mouseX < rightMinX + 20.0 || mouseX > rightMinX + 260.0) return InspectorField.NONE;

        double relX = mouseX - (rightMinX + 20.0);
        int col = (int) (relX / 80.0);
        if (col < 0 || col > 2) return InspectorField.NONE;

        if (mouseY >= 115.0 && mouseY <= 132.0) {
            if (col == 0) return InspectorField.POS_X;
            if (col == 1) return InspectorField.POS_Y;
            if (col == 2) return InspectorField.POS_Z;
        } else if (mouseY >= 133.0 && mouseY <= 150.0) {
            if (col == 0) return InspectorField.ROT_X;
            if (col == 1) return InspectorField.ROT_Y;
            if (col == 2) return InspectorField.ROT_Z;
        } else if (mouseY >= 151.0 && mouseY <= 168.0) {
            if (col == 0) return InspectorField.SCL_X;
            if (col == 1) return InspectorField.SCL_Y;
            if (col == 2) return InspectorField.SCL_Z;
        }
        return InspectorField.NONE;
    }
}
