package com.retro.engine.editor.ui;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;

import java.util.List;

public class MainMenuBarInputDelegate {

    public static void processTopBarClick(float ndcX, EditorWorkspace manager) {
        if (ndcX >= -0.72f && ndcX <= -0.62f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.FILE) ? DropdownMenu.NONE : DropdownMenu.FILE);
        } else if (ndcX >= -0.61f && ndcX <= -0.51f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.EDIT) ? DropdownMenu.NONE : DropdownMenu.EDIT);
        } else if (ndcX >= -0.50f && ndcX <= -0.38f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.ENTITY) ? DropdownMenu.NONE : DropdownMenu.ENTITY);
        } else if (ndcX >= -0.37f && ndcX <= -0.27f) {
            UISoundPlayer.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.VIEW) ? DropdownMenu.NONE : DropdownMenu.VIEW);
        } else if (ndcX >= -0.14f && ndcX <= -0.02f) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.setActiveDropdownMenu(DropdownMenu.NONE);
            manager.play();
        } else if (ndcX >= -0.01f && ndcX <= 0.11f) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.setActiveDropdownMenu(DropdownMenu.NONE);
            manager.pause();
        } else if (ndcX >= 0.12f && ndcX <= 0.23f) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.setActiveDropdownMenu(DropdownMenu.NONE);
            manager.stop();
        } else {
            manager.setActiveDropdownMenu(DropdownMenu.NONE);
        }
    }

    public static boolean processDropdownOverlayClick(float ndcX, float ndcY, EditorWorkspace manager) {
        DropdownMenu activeDropdownMenu = manager.getActiveDropdownMenu();
        float minX = switch (activeDropdownMenu) {
            case FILE -> -0.72f;
            case EDIT -> -0.61f;
            case ENTITY -> -0.50f;
            case VIEW -> -0.37f;
            default -> -0.72f;
        };
        float maxX = minX + 0.32f;
        float minY = 0.58f;
        float maxY = 0.92f;

        if (ndcX >= minX && ndcX <= maxX && ndcY >= minY && ndcY <= maxY) {
            int itemIndex = (int) ((maxY - ndcY) / 0.09f);

            if (activeDropdownMenu == DropdownMenu.FILE) {
                if (itemIndex == 0) { UISoundPlayer.play(UISound.CLICK); manager.newScene(); }
                else if (itemIndex == 1) { manager.promptOpenSceneWithNativeDialog(); }
                else if (itemIndex == 2) { manager.promptSaveSceneWithNativeDialog(); }
            } else if (activeDropdownMenu == DropdownMenu.EDIT) {
                if (itemIndex == 0) { UISoundPlayer.play(UISound.CLICK); manager.undo(); }
                else if (itemIndex == 1) { UISoundPlayer.play(UISound.CLICK); manager.redo(); }
                else if (itemIndex == 2) { UISoundPlayer.play(UISound.DELETE); manager.deleteSelectedEntity(); }
            } else if (activeDropdownMenu == DropdownMenu.ENTITY) {
                if (itemIndex == 0) { UISoundPlayer.play(UISound.SPAWN); manager.spawnDefaultCube(); }
                else if (itemIndex == 1) { UISoundPlayer.play(UISound.SPAWN); manager.spawnLightSource(); }
                else if (itemIndex == 2) { UISoundPlayer.play(UISound.SPAWN); manager.spawnCameraEntity(); }
            } else if (activeDropdownMenu == DropdownMenu.VIEW) {
                if (itemIndex == 1) { UISoundPlayer.play(UISound.TOGGLE); manager.toggle3DGrid(); }
            }
            return true;
        }
        return false;
    }

    public static void processModalClick(float ndcX, float ndcY, EditorWorkspace manager) {
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            manager.closeModal();
            return;
        }
        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            manager.closeModal();
            return;
        }

        AssetType pickerFilterType = manager.getPickerFilterType();

        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= 0.27f && ndcY <= 0.33f) {
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (pickerFilterType == AssetType.TEXTURE) selected.setTextureAsset(null, "DEFAULT_CHECKER");
                else if (pickerFilterType == AssetType.MESH_3D) selected.setMeshAssetPath(null);
                else if (pickerFilterType == AssetType.AUDIO) {
                    AudioSource ac = selected.getComponent(AudioSource.class);
                    if (ac != null) ac.audioAssetPath = "";
                } else if (pickerFilterType == AssetType.SCRIPT) {
                    manager.assignScriptToSelectedEntity(null);
                }
            }
            UISoundPlayer.play(UISound.DELETE);
            manager.closeModal();
            return;
        }

        if (ndcX >= 0.07f && ndcX <= 0.42f && ndcY >= 0.27f && ndcY <= 0.33f) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.closeModal();
            if (pickerFilterType == AssetType.TEXTURE) InspectorInputDelegate.promptImportTexture(manager);
            else if (pickerFilterType == AssetType.MESH_3D) InspectorInputDelegate.promptImportMesh(manager);
            else if (pickerFilterType == AssetType.AUDIO) InspectorInputDelegate.promptImportAudio(manager);
            else if (pickerFilterType == AssetType.SCRIPT) InspectorInputDelegate.promptImportScript(manager);
            return;
        }

        List<AssetMeta> filtered = manager.getAssetDirectory().stream().filter(a -> a.type == pickerFilterType).toList();
        float listY = cardMaxY - 0.24f;
        for (AssetMeta asset : filtered) {
            if (listY < cardMinY + 0.05f) break;
            if (ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                UISoundPlayer.play(UISound.CLICK);
                if (pickerFilterType == AssetType.TEXTURE) manager.assignTextureToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.MESH_3D) manager.assignMeshToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.AUDIO) manager.assignAudioToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.SCRIPT) manager.assignScriptToSelectedEntity(asset);
                manager.closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }
}
