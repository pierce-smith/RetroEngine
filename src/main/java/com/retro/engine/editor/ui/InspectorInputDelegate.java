package com.retro.engine.editor.ui;

import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.commands.AudioPropertyCommand;
import com.retro.engine.editor.commands.LightPropertyCommand;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

public class InspectorInputDelegate {

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity entity = manager.getSelectedEntity();
        if (entity == null) return;

        float curY = 0.72f;

        for (Component comp : entity.getAllComponents()) {
            if (curY < -0.45f) break;

            curY -= 0.06f; // Header line

            if (comp instanceof Transform) {
                curY -= 0.17f;

            } else if (comp instanceof MeshFilter) {
                if (ndcY >= curY - 0.04f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.80f && ndcX <= 0.88f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        manager.openAssetPicker(AssetType.MESH_3D);
                        return;
                    } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        promptImportMesh(manager);
                        return;
                    }
                }
                curY -= 0.08f;

            } else if (comp instanceof Material) {
                curY -= 0.05f; // SHADER line
                if (ndcY >= curY - 0.04f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.80f && ndcX <= 0.88f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        manager.openAssetPicker(AssetType.TEXTURE);
                        return;
                    } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        promptImportTexture(manager);
                        return;
                    }
                }
                curY -= 0.09f;

            } else if (comp instanceof Light lc) {
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f && ndcX >= 0.84f && ndcX <= 0.98f) {
                    UISoundPlayer.play(UISound.TOGGLE);
                    Light.LightType oldType = lc.type;
                    Light.LightType nextType = switch (oldType) {
                        case DIRECTIONAL -> Light.LightType.POINT;
                        case POINT -> Light.LightType.SPOT;
                        case SPOT -> Light.LightType.DIRECTIONAL;
                    };
                    LightPropertyCommand cmd = new LightPropertyCommand(entity, oldType, nextType, lc.intensity, lc.intensity, lc.r, lc.r, lc.g, lc.g, lc.b, lc.b);
                    manager.getCommandHistory().executeCommand(cmd);
                    return;
                }
                curY -= 0.06f;

                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldVal = lc.intensity;
                        float newVal = Math.max(0.0f, oldVal - 0.5f);
                        LightPropertyCommand cmd = new LightPropertyCommand(entity, lc.type, lc.type, oldVal, newVal, lc.r, lc.r, lc.g, lc.g, lc.b, lc.b);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldVal = lc.intensity;
                        float newVal = oldVal + 0.5f;
                        LightPropertyCommand cmd = new LightPropertyCommand(entity, lc.type, lc.type, oldVal, newVal, lc.r, lc.r, lc.g, lc.g, lc.b, lc.b);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                }
                curY -= 0.06f;

                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.88f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldR = lc.r;
                        float newR = (oldR > 0.5f) ? 0.2f : 1.0f;
                        LightPropertyCommand cmd = new LightPropertyCommand(entity, lc.type, lc.type, lc.intensity, lc.intensity, oldR, newR, lc.g, lc.g, lc.b, lc.b);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                    if (ndcX >= 0.89f && ndcX <= 0.93f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldG = lc.g;
                        float newG = (oldG > 0.5f) ? 0.2f : 1.0f;
                        LightPropertyCommand cmd = new LightPropertyCommand(entity, lc.type, lc.type, lc.intensity, lc.intensity, lc.r, lc.r, oldG, newG, lc.b, lc.b);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                    if (ndcX >= 0.94f && ndcX <= 0.98f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldB = lc.b;
                        float newB = (oldB > 0.5f) ? 0.2f : 1.0f;
                        LightPropertyCommand cmd = new LightPropertyCommand(entity, lc.type, lc.type, lc.intensity, lc.intensity, lc.r, lc.r, lc.g, lc.g, oldB, newB);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                }
                curY -= 0.08f;

            } else if (comp instanceof Camera cc) {
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) { UISoundPlayer.play(UISound.CLICK); cc.fov = Math.max(10.0f, cc.fov - 5.0f); return; }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) { UISoundPlayer.play(UISound.CLICK); cc.fov = Math.min(170.0f, cc.fov + 5.0f); return; }
                }
                curY -= 0.06f;

                curY -= 0.05f; // NEAR/FAR line

                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f && ndcX >= 0.58f && ndcX <= 0.85f) {
                    UISoundPlayer.play(UISound.TOGGLE);
                    cc.isPrimary = !cc.isPrimary;
                    return;
                }
                curY -= 0.08f;

            } else if (comp instanceof AudioSource ac) {
                // Line 1: Clip Picker / Import
                if (ndcY >= curY - 0.04f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.80f && ndcX <= 0.88f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        manager.openAssetPicker(AssetType.AUDIO);
                        return;
                    } else if (ndcX >= 0.89f && ndcX <= 0.98f) {
                        UISoundPlayer.play(UISound.OPEN_MODAL);
                        promptImportAudio(manager);
                        return;
                    }
                }
                curY -= 0.08f;

                // Line 2: Volume Adjust [-] [+]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldVol = ac.volume;
                        float newVol = Math.max(0.0f, oldVol - 0.1f);
                        AudioPropertyCommand cmd = new AudioPropertyCommand(entity, ac.audioAssetPath, ac.audioAssetPath, oldVol, newVol, ac.pitch, ac.pitch, ac.loop, ac.loop);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldVol = ac.volume;
                        float newVol = Math.min(2.0f, oldVol + 0.1f);
                        AudioPropertyCommand cmd = new AudioPropertyCommand(entity, ac.audioAssetPath, ac.audioAssetPath, oldVol, newVol, ac.pitch, ac.pitch, ac.loop, ac.loop);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                }
                curY -= 0.06f;

                // Line 3: Pitch Adjust [-] [+]
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.84f && ndcX <= 0.90f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldPitch = ac.pitch;
                        float newPitch = Math.max(0.1f, oldPitch - 0.1f);
                        AudioPropertyCommand cmd = new AudioPropertyCommand(entity, ac.audioAssetPath, ac.audioAssetPath, ac.volume, ac.volume, oldPitch, newPitch, ac.loop, ac.loop);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                    if (ndcX >= 0.91f && ndcX <= 0.97f) {
                        UISoundPlayer.play(UISound.CLICK);
                        float oldPitch = ac.pitch;
                        float newPitch = Math.min(3.0f, oldPitch + 0.1f);
                        AudioPropertyCommand cmd = new AudioPropertyCommand(entity, ac.audioAssetPath, ac.audioAssetPath, ac.volume, ac.volume, oldPitch, newPitch, ac.loop, ac.loop);
                        manager.getCommandHistory().executeCommand(cmd);
                        return;
                    }
                }
                curY -= 0.06f;

                // Line 4: Transport Play / Pause / Stop
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f) {
                    if (ndcX >= 0.58f && ndcX <= 0.70f) { UISoundPlayer.play(UISound.TOGGLE); ac.play(); return; }
                    if (ndcX >= 0.72f && ndcX <= 0.84f) { UISoundPlayer.play(UISound.TOGGLE); ac.pause(); return; }
                    if (ndcX >= 0.86f && ndcX <= 0.98f) { UISoundPlayer.play(UISound.TOGGLE); ac.stop(); return; }
                }
                curY -= 0.06f;

                // Line 5: Loop Toggle
                if (ndcY >= curY - 0.03f && ndcY <= curY + 0.02f && ndcX >= 0.58f && ndcX <= 0.85f) {
                    UISoundPlayer.play(UISound.TOGGLE);
                    boolean oldLoop = ac.loop;
                    boolean newLoop = !oldLoop;
                    AudioPropertyCommand cmd = new AudioPropertyCommand(entity, ac.audioAssetPath, ac.audioAssetPath, ac.volume, ac.volume, ac.pitch, ac.pitch, oldLoop, newLoop);
                    manager.getCommandHistory().executeCommand(cmd);
                    return;
                }
                curY -= 0.08f;

            } else {
                curY -= 0.07f;
            }
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

    public static void promptImportAssetForDirectory(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Import Asset(s) into Project", "", "All Supported Assets (*.obj, *.fbx, *.gltf, *.glb, *.png, *.jpg, *.jpeg, *.tga, *.scene)",
            "obj", "fbx", "gltf", "glb", "png", "jpg", "jpeg", "tga", "scene"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.onFilesDropped(importedPaths);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }
}
