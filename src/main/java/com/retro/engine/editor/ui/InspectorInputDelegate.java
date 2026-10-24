package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.EntitySpawner;
import com.retro.engine.scene.components.*;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class InspectorInputDelegate {

    private static boolean isDraggingSlider = false;
    private static String draggingFieldName = null;
    private static Component draggingComponent = null;

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity selected = manager.getSelectedEntity();
        if (selected == null || ndcX < 0.55f) return;

        // Header Buttons: Add Component (+) Button
        if (ndcX >= 0.82f && ndcX <= 0.98f && ndcY >= 0.82f && ndcY <= 0.88f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            promptAddComponentMenu(manager, selected, ndcX, ndcY);
            return;
        }

        // Save As Prefab Button
        if (ndcX >= 0.58f && ndcX <= 0.80f && ndcY >= 0.82f && ndcY <= 0.88f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getSceneManager().promptSaveSelectedAsPrefab(
                manager.getAssetDatabase().getMutableAssetDirectory(),
                manager.getAssetDatabase()
            );
            return;
        }

        // Texture Asset Field Picker Click
        Material mat = selected.getComponent(Material.class);
        if (mat != null && ndcX >= 0.70f && ndcX <= 0.95f && ndcY >= 0.40f && ndcY <= 0.48f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getOverlayManager().openAssetPicker(AssetType.TEXTURE);
            return;
        }

        // Mesh Asset Field Picker Click
        MeshFilter mesh = selected.getComponent(MeshFilter.class);
        if (mesh != null && ndcX >= 0.70f && ndcX <= 0.95f && ndcY >= 0.25f && ndcY <= 0.33f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getOverlayManager().openAssetPicker(AssetType.MESH_3D);
            return;
        }

        // Audio Asset Field Picker Click
        AudioSource audio = selected.getComponent(AudioSource.class);
        if (audio != null && ndcX >= 0.70f && ndcX <= 0.95f && ndcY >= 0.10f && ndcY <= 0.18f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getOverlayManager().openAssetPicker(AssetType.AUDIO);
            return;
        }

        // Script Asset Field Picker Click
        ScriptComponent script = selected.getComponent(ScriptComponent.class);
        if (script != null && ndcX >= 0.70f && ndcX <= 0.95f && ndcY >= -0.05f && ndcY <= 0.03f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getOverlayManager().openAssetPicker(AssetType.SCRIPT);
            return;
        }
    }

    public static void processInspectorDrag(float ndcX, float ndcY, EditorWorkspace manager) {
        if (!isDraggingSlider || draggingComponent == null || draggingFieldName == null) return;
        // Drag processing logic
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
            File file = new File(primaryPath);
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
            File file = new File(primaryPath);
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
            File file = new File(primaryPath);
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
