package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.MenuItem;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntitySpawner;
import com.retro.engine.scene.components.*;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scripting.ScriptEngine;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dynamic reflection-driven input delegate for InspectorPanel UI controls.
 */
public class InspectorInputDelegate {

    private static boolean isDraggingSlider = false;
    private static String draggingFieldName = null;
    private static Component draggingComponent = null;

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity selected = manager.getSelectedEntity();
        if (selected == null || ndcX < 0.55f) return;

        // Top Header: "+ PREFAB" Button
        if (ndcX >= InspectorPanel.PREFAB_X0 && ndcX <= InspectorPanel.PREFAB_X1 && ndcY >= InspectorPanel.PREFAB_Y0 && ndcY <= InspectorPanel.PREFAB_Y1) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getSceneManager().promptSaveSelectedAsPrefab(
                manager.getAssetDatabase().getMutableAssetDirectory(),
                manager.getAssetDatabase()
            );
            return;
        }

        float scrollOffset = InspectorPanel.getScrollOffset();
        float curY = InspectorPanel.CONTENT_TOP;

        for (Component comp : selected.getAllComponents()) {
            if (comp == null) continue;

            // Section header: "REMOVE" button
            float drawHeaderY = curY + scrollOffset;
            if (InspectorPanel.isRemovable(comp) && InspectorPanel.isRowVisible(drawHeaderY)
                    && InspectorPanel.isOverRemoveButton(ndcX, ndcY, drawHeaderY)) {
                UISoundPlayer.play(UISound.DELETE);
                EditorCommands.RemoveComponentCommand cmd = new EditorCommands.RemoveComponentCommand(manager, selected, comp);
                manager.getCommandHistory().executeCommand(cmd);
                return;
            }
            curY -= InspectorPanel.HEADER_ADVANCE;

            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).inspectorProperties()) {
                float drawY = curY + scrollOffset;
                curY -= InspectorPanel.rowAdvance(p.control);
                if (!InspectorPanel.isRowVisible(drawY) || ndcY < drawY - InspectorPanel.CTRL_BELOW || ndcY > drawY + InspectorPanel.CTRL_ABOVE) continue;

                try {
                    if (handlePropertyClick(manager, comp, p, p.get(comp), ndcX)) return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Pillar 3: User Script Reflected Custom Field Interactions
            if (comp instanceof ScriptComponent sc) {
                Class<?> scriptClass = ScriptEngine.resolveScriptClass(sc);
                if (scriptClass != null) {
                    List<Field> userFields = InspectorPanel.getUserScriptFields(scriptClass);
                    for (Field f : userFields) {
                        f.setAccessible(true);
                        String fName = f.getName();
                        InspectorProperty prop = f.getAnnotation(InspectorProperty.class);
                        String label = (prop != null && !prop.label().isEmpty()) ? prop.label() : fName.toUpperCase();

                        float drawY = curY + scrollOffset;
                        boolean isVisible = InspectorPanel.isRowVisible(drawY);
                        boolean inRow = isVisible && ndcY >= drawY - InspectorPanel.CTRL_BELOW && ndcY <= drawY + InspectorPanel.CTRL_ABOVE;

                        String valStr = null;
                        if (sc.propertyOverrides != null && sc.propertyOverrides.containsKey(fName)) {
                            valStr = sc.propertyOverrides.get(fName);
                        } else if (sc.activeScriptInstance != null) {
                            try {
                                Object o = f.get(sc.activeScriptInstance);
                                valStr = (o != null) ? o.toString() : "";
                            } catch (Exception ignored) {}
                        }
                        if (valStr == null) {
                            try {
                                Object dummy = f.get(scriptClass.getDeclaredConstructor().newInstance());
                                valStr = (dummy != null) ? dummy.toString() : "0";
                            } catch (Exception e) {
                                valStr = "0";
                            }
                        }

                        Class<?> fieldType = f.getType();

                        if (fieldType == boolean.class || fieldType == Boolean.class) {
                            if (inRow && ndcX >= InspectorPanel.LABEL_X && ndcX <= InspectorPanel.VALUE_X1) {
                                UISoundPlayer.play(UISound.CLICK);
                                boolean currentBool = Boolean.parseBoolean(valStr);
                                boolean newBool = !currentBool;
                                EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, String.valueOf(newBool));
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType == float.class || fieldType == Float.class || fieldType == double.class || fieldType == Double.class || fieldType == int.class || fieldType == Integer.class) {
                            if (inRow) {
                                float currentVal = 0.0f;
                                try { currentVal = Float.parseFloat(valStr); } catch (Exception ignored) {}
                                float step = (prop != null && prop.step() > 0.0f) ? prop.step() : 0.1f;

                                if (ndcX >= InspectorPanel.DEC_X0 && ndcX < InspectorPanel.DEC_X1) {
                                    UISoundPlayer.play(UISound.CLICK);
                                    float newVal = currentVal - step;
                                    String newStr = (fieldType == int.class || fieldType == Integer.class) ? String.valueOf((int) newVal) : String.valueOf(newVal);
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, newStr);
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                                if (ndcX >= InspectorPanel.INC_X0 && ndcX <= InspectorPanel.INC_X1) {
                                    UISoundPlayer.play(UISound.CLICK);
                                    float newVal = currentVal + step;
                                    String newStr = (fieldType == int.class || fieldType == Integer.class) ? String.valueOf((int) newVal) : String.valueOf(newVal);
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, newStr);
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType.isEnum()) {
                            if (inRow && ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                                UISoundPlayer.play(UISound.CLICK);
                                Object[] enumConstants = fieldType.getEnumConstants();
                                if (enumConstants != null && enumConstants.length > 0) {
                                    int currentIdx = 0;
                                    for (int i = 0; i < enumConstants.length; i++) {
                                        if (enumConstants[i].toString().equalsIgnoreCase(valStr)) {
                                            currentIdx = i;
                                            break;
                                        }
                                    }
                                    Object nextEnum = enumConstants[(currentIdx + 1) % enumConstants.length];
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, nextEnum.toString());
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType == String.class) {
                            if (inRow && ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                                UISoundPlayer.play(UISound.OPEN_MODAL);
                                String input = NativeFileDialog.promptInput("Edit " + label, label + ":", valStr);
                                if (input != null && !input.equals(valStr)) {
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, input);
                                    manager.getCommandHistory().executeCommand(cmd);
                                } else {
                                    UISoundPlayer.play(UISound.CLOSE_MODAL);
                                }
                                return;
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else {
                            curY -= InspectorPanel.TEXT_ROW_ADVANCE;
                        }
                    }
                }
            }

            curY -= InspectorPanel.COMPONENT_GAP;
        }

        // "+ ADD COMPONENT" Button
        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY + InspectorPanel.ADD_ABOVE <= InspectorPanel.VIEW_TOP && drawAddBtnY - InspectorPanel.ADD_BELOW >= InspectorPanel.VIEW_BOTTOM) {
            if (ndcX >= InspectorPanel.ADD_X0 && ndcX <= InspectorPanel.ADD_X1
                    && ndcY >= drawAddBtnY - InspectorPanel.ADD_BELOW && ndcY <= drawAddBtnY + InspectorPanel.ADD_ABOVE) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                promptAddComponentMenu(manager, selected, ndcX, ndcY);
                return;
            }
        }
    }

    /** Applies a click inside a property row; returns true when the click was consumed. */
    private static boolean handlePropertyClick(EditorWorkspace manager, Component comp, ComponentSchema.Property p, Object val, float ndcX) {
        Field field = p.field;
        InspectorProperty prop = p.meta;
        String label = p.label;

        switch (p.control) {
            case ASSET -> {
                if (ndcX >= InspectorPanel.PICK_X0 && ndcX <= InspectorPanel.PICK_X1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    AssetType assetType = parseAssetType(prop.assetType());
                    manager.getOverlayManager().openAssetPicker(assetType, (comp instanceof Material) ? field.getName() : null);
                    return true;
                }
                if (ndcX >= InspectorPanel.IMPORT_X0 && ndcX <= InspectorPanel.IMPORT_X1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    if (comp instanceof Material && "TEXTURE".equalsIgnoreCase(prop.assetType())) {
                        promptImportTexture(manager, field.getName());
                    } else {
                        promptImportAssetForType(manager, prop.assetType());
                    }
                    return true;
                }
            }
            case TOGGLE -> {
                if (ndcX >= InspectorPanel.LABEL_X && ndcX <= InspectorPanel.VALUE_X1) {
                    UISoundPlayer.play(UISound.CLICK);
                    boolean currentBool = (val != null) && (Boolean) val;
                    execute(manager, comp, field, currentBool, !currentBool, label);
                    return true;
                }
            }
            case CHOICE -> {
                Object[] enumConstants = field.getType().getEnumConstants();
                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1 && enumConstants != null && enumConstants.length > 0) {
                    UISoundPlayer.play(UISound.CLICK);
                    int currentIdx = Math.max(0, Arrays.asList(enumConstants).indexOf(val));
                    execute(manager, comp, field, val, enumConstants[(currentIdx + 1) % enumConstants.length], label);
                    return true;
                }
            }
            case NUMBER -> {
                float currentVal = (val != null) ? (Float) val : 0.0f;
                float step = prop.step() > 0.0f ? prop.step() : 0.1f;
                float delta = 0.0f;
                if (ndcX >= InspectorPanel.DEC_X0 && ndcX < InspectorPanel.DEC_X1) delta = -step;
                else if (ndcX >= InspectorPanel.INC_X0 && ndcX <= InspectorPanel.INC_X1) delta = step;
                if (delta != 0.0f) {
                    UISoundPlayer.play(UISound.CLICK);
                    float newVal = Math.max(prop.min(), Math.min(prop.max(), currentVal + delta));
                    execute(manager, comp, field, currentVal, newVal, label);
                    return true;
                }
            }
            case STRING -> {
                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    String current = (val != null) ? (String) val : "";
                    String input = NativeFileDialog.promptInput("Edit " + label, label + ":", current);
                    if (input != null && !input.equals(current)) {
                        execute(manager, comp, field, val, input, label);
                    } else {
                        UISoundPlayer.play(UISound.CLOSE_MODAL);
                    }
                    return true;
                }
            }
            case READ_ONLY -> {}
        }
        return false;
    }

    private static void execute(EditorWorkspace manager, Component comp, Field field, Object oldVal, Object newVal, String label) {
        manager.getCommandHistory().executeCommand(new EditorCommands.ModifyPropertyCommand(comp, field, oldVal, newVal, label));
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
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
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
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
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
        promptImportTexture(manager, "textureAssetPath");
    }

    public static void promptImportTexture(EditorWorkspace manager, String materialFieldName) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Texture Asset(s)", "", "Image Files (*.png, *.jpg, *.jpeg, *.tga)", "png", "jpg", "jpeg", "tga"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                Material mat = selected.getComponent(Material.class);
                if (mat != null) {
                    try {
                        Field field = Material.class.getDeclaredField(materialFieldName);
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, field.get(mat), primaryPath, "TEXTURE");
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
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
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
