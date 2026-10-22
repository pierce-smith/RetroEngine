package com.retro.engine.editor.ui;

import com.retro.engine.editor.ui.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.commands.ModifyPropertyCommand;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

public class InspectorInputDelegate {

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity entity = manager.getSelectedEntity();
        if (entity == null) return;

        // Check top header + PREFAB button
        if (ndcX >= 0.82f && ndcX <= 0.98f && ndcY >= 0.800f && ndcY <= 0.845f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.promptSaveSelectedAsPrefab();
            return;
        }

        float scrollOffset = InspectorPanel.getScrollOffset();
        float curY = 0.74f;

        for (Component comp : entity.getAllComponents()) {
            curY -= 0.055f;

            Field[] fields = comp.getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(InspectorProperty.class)) continue;

                InspectorProperty prop = field.getAnnotation(InspectorProperty.class);
                field.setAccessible(true);
                String label = prop.label().isEmpty() ? field.getName() : prop.label();

                float drawY = curY + scrollOffset;
                boolean isVisible = (drawY <= 0.77f && drawY - 0.045f >= -0.48f);

                try {
                    Object val = field.get(comp);

                    if (prop.type() == PropertyType.ASSET_PICKER) {
                        if (isVisible && ndcY >= drawY - 0.036f && ndcY <= drawY + 0.008f) {
                            if (ndcX >= 0.80f && ndcX <= 0.88f) {
                                UISoundPlayer.play(UISound.OPEN_MODAL);
                                AssetType targetType = parseAssetType(prop.assetType());
                                manager.openAssetPicker(targetType);
                                return;
                            } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                                UISoundPlayer.play(UISound.OPEN_MODAL);
                                promptImportAssetByKind(manager, prop.assetType());
                                return;
                            }
                        }
                        curY -= 0.054f;

                    } else if (prop.type() == PropertyType.TOGGLE || field.getType() == boolean.class) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f && ndcX >= 0.58f && ndcX <= 0.85f) {
                            UISoundPlayer.play(UISound.TOGGLE);
                            boolean oldBool = (Boolean) val;
                            boolean newBool = !oldBool;
                            ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, oldBool, newBool, label);
                            manager.getCommandHistory().executeCommand(cmd);
                            return;
                        }
                        curY -= 0.048f;

                    } else if (prop.type() == PropertyType.BUTTON_GROUP || field.getType().isEnum()) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f && ndcX >= 0.84f && ndcX <= 0.98f) {
                            UISoundPlayer.play(UISound.TOGGLE);
                            Object[] constants = field.getType().getEnumConstants();
                            if (constants != null && constants.length > 0) {
                                int idx = 0;
                                for (int i = 0; i < constants.length; i++) {
                                    if (constants[i].equals(val)) { idx = i; break; }
                                }
                                Object nextEnum = constants[(idx + 1) % constants.length];
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, val, nextEnum, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                        }
                        curY -= 0.048f;

                    } else if (prop.type() == PropertyType.COLOR) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f) {
                            float floatVal = (Float) val;
                            if (ndcX >= 0.84f && ndcX <= 0.90f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.max(prop.min(), floatVal - prop.step());
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, floatVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                            if (ndcX >= 0.91f && ndcX <= 0.97f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.min(prop.max(), floatVal + prop.step());
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, floatVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                        }
                        curY -= 0.048f;

                    } else if (field.getType() == float.class || field.getType() == Float.class) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f) {
                            float floatVal = (Float) val;
                            if (ndcX >= 0.84f && ndcX <= 0.90f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.max(prop.min(), floatVal - prop.step());
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, floatVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                            if (ndcX >= 0.91f && ndcX <= 0.97f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.min(prop.max(), floatVal + prop.step());
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, floatVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                        }
                        curY -= 0.048f;

                    } else if (field.getType() == String.class || prop.type() == PropertyType.TEXT) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f && ndcX >= 0.58f && ndcX <= 0.98f) {
                            UISoundPlayer.play(UISound.OPEN_MODAL);
                            String currentStr = (val != null) ? val.toString() : "";
                            String newStr = NativeFileDialog.promptInput("Edit Property: " + label, "Enter text value:", currentStr);
                            if (newStr != null && !newStr.equals(currentStr)) {
                                UISoundPlayer.play(UISound.CLICK);
                                ModifyPropertyCommand cmd = new ModifyPropertyCommand(comp, field, currentStr, newStr, label);
                                manager.getCommandHistory().executeCommand(cmd);
                            } else {
                                UISoundPlayer.play(UISound.CLOSE_MODAL);
                            }
                            return;
                        }
                        curY -= 0.044f;
                    } else {
                        curY -= 0.044f;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            curY -= 0.020f;
        }

        // Check + ADD COMPONENT button click
        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY <= 0.77f && drawAddBtnY - 0.04f >= -0.48f) {
            if (ndcX >= 0.58f && ndcX <= 0.98f && ndcY >= drawAddBtnY - 0.04f && ndcY <= drawAddBtnY + 0.01f) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                manager.openAssetPicker(AssetType.SCRIPT);
            }
        }
    }

    private static AssetType parseAssetType(String assetTypeStr) {
        if (assetTypeStr == null) return AssetType.MESH_3D;
        return switch (assetTypeStr.toUpperCase()) {
            case "TEXTURE" -> AssetType.TEXTURE;
            case "AUDIO" -> AssetType.AUDIO;
            case "SCRIPT" -> AssetType.SCRIPT;
            default -> AssetType.MESH_3D;
        };
    }

    private static void promptImportAssetByKind(EditorWorkspace manager, String assetKind) {
        if ("TEXTURE".equalsIgnoreCase(assetKind)) {
            promptImportTexture(manager);
        } else if ("AUDIO".equalsIgnoreCase(assetKind)) {
            promptImportAudio(manager);
        } else if ("SCRIPT".equalsIgnoreCase(assetKind)) {
            promptImportScript(manager);
        } else {
            promptImportMesh(manager);
        }
    }

    public static void promptImportAssetForDirectory(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Import Asset(s) into Project", "", "All Supported Assets (*.obj, *.fbx, *.gltf, *.glb, *.png, *.jpg, *.jpeg, *.tga, *.scene, *.java, *.prefab.bin)",
            "obj", "fbx", "gltf", "glb", "png", "jpg", "jpeg", "tga", "scene", "java", "prefab.bin", "prefab"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportScript(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Gameplay Script(s)", "", "Java Scripts (*.java)", "java"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            manager.assignScriptToSelectedEntity(new AssetMeta(file.getName(), primaryPath, AssetType.SCRIPT));
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportAudio(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Audio Clip(s)", "", "Audio Clips (*.wav, *.mp3, *.ogg)", "wav", "mp3", "ogg"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            manager.assignAudioToSelectedEntity(new AssetMeta(file.getName(), primaryPath, AssetType.AUDIO));
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportTexture(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Texture Asset(s)", "", "Texture Images (*.png, *.jpg, *.jpeg, *.tga)", "png", "jpg", "jpeg", "tga"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            manager.assignTextureToSelectedEntity(new AssetMeta(file.getName(), primaryPath, AssetType.TEXTURE));
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportMesh(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select 3D Mesh Asset(s)", "", "3D Models (*.obj, *.fbx, *.gltf, *.glb)", "obj", "fbx", "gltf", "glb"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            manager.assignMeshToSelectedEntity(new AssetMeta(file.getName(), primaryPath, AssetType.MESH_3D));
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }
}
