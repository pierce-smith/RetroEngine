package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.MenuItem;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.EntitySpawner;
import com.retro.engine.scene.components.*;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic reflection-driven input delegate for InspectorPanel UI controls.
 * Calculates exact element Y positions matching InspectorPanel layout math,
 * accounting for scrollOffset, and dispatches transactional Undo/Redo commands.
 */
public class InspectorInputDelegate {

    // Reflection Caches matching InspectorPanel
    private static final Map<Class<?>, Field[]> CACHED_FIELDS = new HashMap<>();
    private static final Map<Field, InspectorProperty> CACHED_ANNOTATIONS = new HashMap<>();

    private static boolean isDraggingSlider = false;
    private static String draggingFieldName = null;
    private static Component draggingComponent = null;

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity selected = manager.getSelectedEntity();
        if (selected == null || ndcX < 0.55f) return;

        // Top Header: "+ PREFAB" Button (0.82f to 0.98f, 0.800f to 0.845f)
        if (ndcX >= 0.82f && ndcX <= 0.98f && ndcY >= 0.800f && ndcY <= 0.845f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getSceneManager().promptSaveSelectedAsPrefab(
                manager.getAssetDatabase().getMutableAssetDirectory(),
                manager.getAssetDatabase()
            );
            return;
        }

        float scrollOffset = InspectorPanel.getScrollOffset();
        float curY = 0.74f;

        for (Component comp : selected.getAllComponents()) {
            if (comp == null) continue;
            curY -= 0.055f; // Header margin

            Class<?> compClass = comp.getClass();
            Field[] fields = CACHED_FIELDS.computeIfAbsent(compClass, Class::getDeclaredFields);

            for (Field field : fields) {
                InspectorProperty prop = CACHED_ANNOTATIONS.computeIfAbsent(field, f -> f.getAnnotation(InspectorProperty.class));
                if (prop == null) continue;

                field.setAccessible(true);
                String label = prop.label().isEmpty() ? field.getName().toUpperCase() : prop.label();
                float drawY = curY + scrollOffset;
                boolean isVisible = (drawY <= 0.77f && drawY - 0.045f >= -0.48f);

                try {
                    Object val = field.get(comp);

                    if (prop.type() == InspectorProperty.PropertyType.ASSET_PICKER) {
                        if (isVisible && ndcY >= drawY - 0.036f && ndcY <= drawY + 0.008f) {
                            // [PICK] Button (0.80f to 0.88f)
                            if (ndcX >= 0.80f && ndcX <= 0.88f) {
                                UISoundPlayer.play(UISound.OPEN_MODAL);
                                AssetType assetType = parseAssetType(prop.assetType());
                                manager.getOverlayManager().openAssetPicker(assetType);
                                return;
                            }
                            // [IMPORT] Button (0.89f to 0.98f)
                            if (ndcX >= 0.89f && ndcX <= 0.98f) {
                                UISoundPlayer.play(UISound.OPEN_MODAL);
                                promptImportAssetForType(manager, prop.assetType());
                                return;
                            }
                        }
                        curY -= 0.054f;

                    } else if (prop.type() == InspectorProperty.PropertyType.TOGGLE || field.getType() == boolean.class) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f) {
                            if (ndcX >= 0.58f && ndcX <= 0.85f) {
                                UISoundPlayer.play(UISound.CLICK);
                                boolean currentBool = (val != null) && (Boolean) val;
                                boolean newBool = !currentBool;
                                EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(comp, field, currentBool, newBool, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                        }
                        curY -= 0.048f;

                    } else if (prop.type() == InspectorProperty.PropertyType.BUTTON_GROUP || field.getType().isEnum()) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f) {
                            if (ndcX >= 0.84f && ndcX <= 0.98f) {
                                UISoundPlayer.play(UISound.CLICK);
                                Object[] enumConstants = field.getType().getEnumConstants();
                                if (enumConstants != null && enumConstants.length > 0) {
                                    int currentIdx = 0;
                                    for (int i = 0; i < enumConstants.length; i++) {
                                        if (enumConstants[i].equals(val)) {
                                            currentIdx = i;
                                            break;
                                        }
                                    }
                                    Object nextEnum = enumConstants[(currentIdx + 1) % enumConstants.length];
                                    EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(comp, field, val, nextEnum, label);
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                            }
                        }
                        curY -= 0.048f;

                    } else if (prop.type() == InspectorProperty.PropertyType.COLOR || field.getType() == float.class || field.getType() == Float.class) {
                        if (isVisible && ndcY >= drawY - 0.030f && ndcY <= drawY + 0.008f) {
                            float currentVal = (val != null) ? (Float) val : 0.0f;
                            float step = prop.step() > 0.0f ? prop.step() : 0.1f;

                            // [-] Button (0.84f to 0.90f)
                            if (ndcX >= 0.84f && ndcX <= 0.90f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.max(prop.min(), Math.min(prop.max(), currentVal - step));
                                EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(comp, field, currentVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                            // [+] Button (0.91f to 0.97f)
                            if (ndcX >= 0.91f && ndcX <= 0.97f) {
                                UISoundPlayer.play(UISound.CLICK);
                                float newVal = Math.max(prop.min(), Math.min(prop.max(), currentVal + step));
                                EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(comp, field, currentVal, newVal, label);
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                        }
                        curY -= 0.048f;

                    } else {
                        curY -= 0.044f;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            curY -= 0.020f;
        }

        // "+ ADD COMPONENT" Button
        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY <= 0.77f && drawAddBtnY - 0.04f >= -0.48f) {
            if (ndcX >= 0.58f && ndcX <= 0.98f && ndcY >= drawAddBtnY - 0.04f && ndcY <= drawAddBtnY + 0.01f) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                promptAddComponentMenu(manager, selected, ndcX, ndcY);
                return;
            }
        }
    }

    private static AssetType parseAssetType(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) return AssetType.TEXTURE;
        try {
            return AssetType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            if (typeStr.equalsIgnoreCase("MESH") || typeStr.equalsIgnoreCase("MESH_3D")) return AssetType.MESH_3D;
            if (typeStr.equalsIgnoreCase("AUDIO")) return AssetType.AUDIO;
            if (typeStr.equalsIgnoreCase("SCRIPT")) return AssetType.SCRIPT;
            return AssetType.TEXTURE;
        }
    }

    private static void promptImportAssetForType(EditorWorkspace manager, String typeStr) {
        if (typeStr == null) typeStr = "";
        String upper = typeStr.toUpperCase();
        if (upper.contains("MESH")) {
            promptImportMesh(manager);
        } else if (upper.contains("AUDIO")) {
            promptImportAudio(manager);
        } else if (upper.contains("SCRIPT")) {
            promptImportScript(manager);
        } else {
            promptImportTexture(manager);
        }
    }

    public static void processInspectorDrag(float ndcX, float ndcY, EditorWorkspace manager) {
        if (!isDraggingSlider || draggingComponent == null || draggingFieldName == null) return;
    }

    public static void onMouseRelease() {
        isDraggingSlider = false;
        draggingFieldName = null;
        draggingComponent = null;
    }

    public static void promptImportAssetForDirectory(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Import Workspace Asset(s)", "", "All Supported Assets (*.png, *.jpg, *.obj, *.fbx, *.wav, *.mp3, *.java)",
            "png", "jpg", "jpeg", "obj", "fbx", "gltf", "glb", "wav", "mp3", "java"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getAssetDatabase().onFilesDropped(importedPaths);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportAudio(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Audio Asset(s)", "", "Audio Files (*.wav, *.mp3, *.ogg)", "wav", "mp3", "ogg"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getAssetDatabase().onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                AudioSource ac = selected.getComponent(AudioSource.class);
                if (ac == null) {
                    ac = new AudioSource(primaryPath);
                    selected.addComponent(ac);
                } else {
                    try {
                        Field field = AudioSource.class.getDeclaredField("audioAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(ac, field, ac.audioAssetPath, primaryPath, "AUDIO");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                InspectorPanel.resetScroll();
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportScript(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Script Asset(s)", "", "Java Scripts (*.java)", "java"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getAssetDatabase().onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                String oldName = (sc != null) ? sc.scriptName : null;
                EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, primaryPath, file.getName());
                manager.getCommandHistory().executeCommand(cmd);
                InspectorPanel.resetScroll();
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportTexture(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Texture Asset(s)", "", "Image Files (*.png, *.jpg, *.jpeg, *.tga)", "png", "jpg", "jpeg", "tga"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getAssetDatabase().onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                Material mat = selected.getComponent(Material.class);
                if (mat != null) {
                    try {
                        Field field = Material.class.getDeclaredField("textureAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, mat.textureAssetPath, primaryPath, "TEXTURE");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
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
            manager.getAssetDatabase().onFilesDropped(importedPaths);
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                MeshFilter mc = selected.getComponent(MeshFilter.class);
                if (mc != null) {
                    try {
                        Field field = MeshFilter.class.getDeclaredField("meshAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mc, field, mc.meshAssetPath, primaryPath, "MESH_3D");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    EntitySpawner.attachAnimatorIfRigged(selected, primaryPath);
                }
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptAddComponentMenu(EditorWorkspace manager, Entity entity) {
        promptAddComponentMenu(manager, entity, 0.68f, 0.20f);
    }

    public static void promptAddComponentMenu(EditorWorkspace manager, Entity entity, float ndcX, float ndcY) {
        if (entity == null || manager == null) return;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("+ RigidBody", () -> {
            if (entity.getComponent(RigidBodyComponent.class) == null) {
                entity.addComponent(new RigidBodyComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Box Collider", () -> {
            if (entity.getComponent(BoxCollider.class) == null) {
                entity.addComponent(new BoxCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Sphere Collider", () -> {
            if (entity.getComponent(SphereCollider.class) == null) {
                entity.addComponent(new SphereCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Capsule Collider", () -> {
            if (entity.getComponent(CapsuleCollider.class) == null) {
                entity.addComponent(new CapsuleCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Light Source", () -> {
            if (entity.getComponent(Light.class) == null) {
                entity.addComponent(new Light());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Audio Source", () -> {
            if (entity.getComponent(AudioSource.class) == null) {
                entity.addComponent(new AudioSource());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Script Component", () -> {
            manager.getOverlayManager().openAssetPicker(AssetType.SCRIPT);
        }));
        items.add(new MenuItem("+ Character Controller", () -> {
            if (entity.getComponent(CharacterControllerComponent.class) == null) {
                entity.addComponent(new CharacterControllerComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Vehicle Controller", () -> {
            if (entity.getComponent(VehicleControllerComponent.class) == null) {
                entity.addComponent(new VehicleControllerComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));

        manager.getOverlayManager().openContextMenu(ndcX, ndcY, items);
    }
}
