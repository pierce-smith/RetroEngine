package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.AudioSourceComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.audio.UISoundManager;
import com.retro.engine.editor.audio.UISoundManager.UISound;

import java.util.List;

public class TopBarInteractor {

    public static void processTopBarClick(float ndcX, EditorPanelManager manager) {
        if (ndcX >= -0.72f && ndcX <= -0.62f) {
            UISoundManager.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.FILE) ? DropdownMenu.NONE : DropdownMenu.FILE);
        } else if (ndcX >= -0.61f && ndcX <= -0.51f) {
            UISoundManager.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.EDIT) ? DropdownMenu.NONE : DropdownMenu.EDIT);
        } else if (ndcX >= -0.50f && ndcX <= -0.38f) {
            UISoundManager.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.ENTITY) ? DropdownMenu.NONE : DropdownMenu.ENTITY);
        } else if (ndcX >= -0.37f && ndcX <= -0.27f) {
            UISoundManager.play(UISound.CLICK);
            manager.setActiveDropdownMenu((manager.getActiveDropdownMenu() == DropdownMenu.VIEW) ? DropdownMenu.NONE : DropdownMenu.VIEW);
        } else if (ndcX >= -0.14f && ndcX <= -0.02f) {
            UISoundManager.play(UISound.TOGGLE);
            manager.play();
        } else if (ndcX >= -0.01f && ndcX <= 0.11f) {
            UISoundManager.play(UISound.TOGGLE);
            manager.pause();
        } else if (ndcX >= 0.12f && ndcX <= 0.23f) {
            UISoundManager.play(UISound.TOGGLE);
            manager.stop();
        }
    }

    public static boolean processDropdownOverlayClick(float ndcX, float ndcY, EditorPanelManager manager) {
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
                if (itemIndex == 0) { UISoundManager.play(UISound.CLICK); manager.newScene(); }
                else if (itemIndex == 1) { manager.promptOpenSceneWithNativeDialog(); }
                else if (itemIndex == 2) { manager.promptSaveSceneWithNativeDialog(); }
            } else if (activeDropdownMenu == DropdownMenu.EDIT) {
                if (itemIndex == 0) { UISoundManager.play(UISound.CLICK); manager.undo(); }
                else if (itemIndex == 1) { UISoundManager.play(UISound.CLICK); manager.redo(); }
                else if (itemIndex == 2) { UISoundManager.play(UISound.DELETE); manager.deleteSelectedEntity(); }
            } else if (activeDropdownMenu == DropdownMenu.ENTITY) {
                if (itemIndex == 0) { UISoundManager.play(UISound.SPAWN); manager.spawnDefaultCube(); }
                else if (itemIndex == 1) { UISoundManager.play(UISound.SPAWN); manager.spawnLightSource(); }
                else if (itemIndex == 2) { UISoundManager.play(UISound.SPAWN); manager.spawnCameraEntity(); }
            } else if (activeDropdownMenu == DropdownMenu.VIEW) {
                if (itemIndex == 1) { UISoundManager.play(UISound.TOGGLE); manager.toggle3DGrid(); }
            }
            return true;
        }
        return false;
    }

    public static void processModalClick(float ndcX, float ndcY, EditorPanelManager manager) {
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) {
            UISoundManager.play(UISound.CLOSE_MODAL);
            manager.closeModal();
            return;
        }
        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) {
            UISoundManager.play(UISound.CLOSE_MODAL);
            manager.closeModal();
            return;
        }

        AssetType pickerFilterType = manager.getPickerFilterType();

        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= 0.27f && ndcY <= 0.33f) {
            SceneEntity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (pickerFilterType == AssetType.TEXTURE) selected.setTextureAsset(null, "DEFAULT_CHECKER");
                else if (pickerFilterType == AssetType.MESH_3D) selected.setMeshAssetPath(null);
                else if (pickerFilterType == AssetType.AUDIO) {
                    AudioSourceComponent ac = selected.getComponent(AudioSourceComponent.class);
                    if (ac != null) ac.audioAssetPath = "";
                }
            }
            UISoundManager.play(UISound.DELETE);
            manager.closeModal();
            return;
        }

        if (ndcX >= 0.07f && ndcX <= 0.42f && ndcY >= 0.27f && ndcY <= 0.33f) {
            UISoundManager.play(UISound.OPEN_MODAL);
            manager.closeModal();
            if (pickerFilterType == AssetType.TEXTURE) InspectorInteractor.promptImportTexture(manager);
            else if (pickerFilterType == AssetType.MESH_3D) InspectorInteractor.promptImportMesh(manager);
            else if (pickerFilterType == AssetType.AUDIO) InspectorInteractor.promptImportAudio(manager);
            return;
        }

        List<AssetEntry> filtered = manager.getAssetDirectory().stream().filter(a -> a.type == pickerFilterType).toList();
        float listY = cardMaxY - 0.24f;
        for (AssetEntry asset : filtered) {
            if (listY < cardMinY + 0.05f) break;
            if (ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                UISoundManager.play(UISound.CLICK);
                if (pickerFilterType == AssetType.TEXTURE) manager.assignTextureToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.MESH_3D) manager.assignMeshToSelectedEntity(asset);
                else if (pickerFilterType == AssetType.AUDIO) manager.assignAudioToSelectedEntity(asset);
                manager.closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }
}
