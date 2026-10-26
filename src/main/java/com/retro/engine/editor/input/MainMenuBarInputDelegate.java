package com.retro.engine.editor.input;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.panels.MainMenuBar;

import java.io.File;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;

public class MainMenuBarInputDelegate {

    public static void processTopBarClick(float ndcX, EditorWorkspace manager) {
        if (manager == null) return;
        DropdownMenu current = manager.getOverlayManager().getActiveDropdownMenu();
        DropdownMenu title = MainMenuBar.titleAt(ndcX);
        if (title != DropdownMenu.NONE) {
            UISoundPlayer.play(UISound.CLICK);
            manager.getOverlayManager().setActiveDropdownMenu((current == title) ? DropdownMenu.NONE : title);
        } else if (ndcX >= MainMenuBar.PLAY_X0 && ndcX <= MainMenuBar.PLAY_X1) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
            manager.play();
        } else if (ndcX >= MainMenuBar.PAUSE_X0 && ndcX <= MainMenuBar.PAUSE_X1) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
            manager.pause();
        } else if (ndcX >= MainMenuBar.STOP_X0 && ndcX <= MainMenuBar.STOP_X1) {
            UISoundPlayer.play(UISound.TOGGLE);
            manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
            manager.stop();
        } else {
            manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
        }
    }

    public static boolean processDropdownOverlayClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return false;
        DropdownMenu activeDropdownMenu = manager.getOverlayManager().getActiveDropdownMenu();

        if (MainMenuBar.dropdownContains(activeDropdownMenu, ndcX, ndcY)) {
            int itemIndex = MainMenuBar.dropdownItemAt(activeDropdownMenu, ndcX, ndcY);

            if (activeDropdownMenu == DropdownMenu.FILE) {
                if (itemIndex == 0) {
                    UISoundPlayer.play(UISound.CLICK);
                    manager.getSceneManager().newScene();
                    manager.getAssetDatabase().clear();
                    manager.getAssetDatabase().scanWorkspaceDirectory("Assets");
                } else if (itemIndex == 1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    String defaultDir = new File("Assets/Scenes").getAbsolutePath();
                    String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
                    if (importedPath != null && !importedPath.trim().isEmpty()) {
                        UISoundPlayer.play(UISound.SPAWN);
                        manager.getAssetDatabase().clear();
                        manager.getAssetDatabase().scanWorkspaceDirectory("Assets");
                        var data = manager.getSceneManager().loadSceneFromFile(importedPath, manager.getAssetDatabase());
                        if (data != null && data.assetDirectory() != null && !data.assetDirectory().isEmpty()) {
                            manager.getAssetDatabase().restoreAssetDirectory(data.assetDirectory());
                        }
                    } else {
                        UISoundPlayer.play(UISound.CLOSE_MODAL);
                    }
                } else if (itemIndex == 2) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getSceneManager().promptSaveSceneWithNativeDialog(manager.getAssetDatabase().getMutableAssetDirectory(), manager.getAssetDatabase());
                } else if (itemIndex == 3) {
                    // Quit: same path as the OS-level ⌘Q / Alt + F4 (window close request)
                    UISoundPlayer.play(UISound.CLICK);
                    if (manager.getWindowHandle() != 0L) glfwSetWindowShouldClose(manager.getWindowHandle(), true);
                }
            } else if (activeDropdownMenu == DropdownMenu.EDIT) {
                if (itemIndex == 0) { UISoundPlayer.play(UISound.CLICK); manager.getCommandHistory().undo(); }
                else if (itemIndex == 1) { UISoundPlayer.play(UISound.CLICK); manager.getCommandHistory().redo(); }
                else if (itemIndex == 2) {
                    Entity sel = manager.getSelectedEntity();
                    if (sel != null) {
                        manager.getCommandHistory().executeCommand(new EditorCommands.EntityDeleteCommand(manager, sel));
                        UISoundPlayer.play(UISound.DELETE);
                    }
                }
            } else if (activeDropdownMenu == DropdownMenu.ENTITY) {
                if (itemIndex == 0) {
                    manager.getSceneManager().spawnDefaultCube();
                    Entity newly = manager.getSelectedEntity();
                    if (newly != null) manager.getCommandHistory().executeCommand(new EditorCommands.EntitySpawnCommand(manager, newly));
                    UISoundPlayer.play(UISound.SPAWN);
                } else if (itemIndex == 1) {
                    manager.getSceneManager().spawnLightSource();
                    Entity newly = manager.getSelectedEntity();
                    if (newly != null) manager.getCommandHistory().executeCommand(new EditorCommands.EntitySpawnCommand(manager, newly));
                    UISoundPlayer.play(UISound.SPAWN);
                } else if (itemIndex == 2) {
                    manager.getSceneManager().spawnCameraEntity();
                    Entity newly = manager.getSelectedEntity();
                    if (newly != null) manager.getCommandHistory().executeCommand(new EditorCommands.EntitySpawnCommand(manager, newly));
                    UISoundPlayer.play(UISound.SPAWN);
                } else if (itemIndex == 3) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getSceneManager().promptSaveSelectedAsPrefab(manager.getAssetDatabase().getMutableAssetDirectory(), manager.getAssetDatabase());
                }
            } else if (activeDropdownMenu == DropdownMenu.VIEW) {
                if (itemIndex == 0) { UISoundPlayer.play(UISound.CLICK); manager.resetViewportCamera(); }
                else if (itemIndex == 1) { UISoundPlayer.play(UISound.TOGGLE); manager.getGizmoStateController().toggle3DGrid(); }
                else if (itemIndex == 2) { UISoundPlayer.play(UISound.CLICK); DockSpace.resetLayout(); }
            }
            return true;
        }
        return false;
    }

    public static void processModalClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        if (ndcX < cardMinX || ndcX > cardMaxX || ndcY < cardMinY || ndcY > cardMaxY) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            manager.getOverlayManager().closeModal();
            return;
        }
        if (ndcX >= cardMaxX - 0.08f && ndcX <= cardMaxX - 0.02f && ndcY >= cardMaxY - 0.08f && ndcY <= cardMaxY - 0.02f) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            manager.getOverlayManager().closeModal();
            return;
        }

        AssetType pickerFilterType = manager.getOverlayManager().getPickerFilterType();

        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= 0.27f && ndcY <= 0.33f) {
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (pickerFilterType == AssetType.TEXTURE) {
                    selected.setTextureAsset(null, null);
                } else if (pickerFilterType == AssetType.MESH_3D) {
                    selected.setMeshAssetPath(null);
                }
            }
            UISoundPlayer.play(UISound.CLICK);
            manager.getOverlayManager().closeModal();
            return;
        }

        List<AssetMeta> allAssets = manager.getAssetDatabase().getAssetDirectory();
        List<AssetMeta> filteredAssets = (allAssets != null) ? allAssets.stream()
                .filter(a -> a.type == pickerFilterType)
                .toList() : List.of();

        float listY = 0.20f;
        for (AssetMeta asset : filteredAssets) {
            if (listY < -0.38f) break;
            if (ndcX >= -0.42f && ndcX <= 0.42f && ndcY >= listY - 0.05f && ndcY <= listY + 0.03f) {
                UISoundPlayer.play(UISound.CLICK);
                Entity selected = manager.getSelectedEntity();
                if (selected != null) {
                    if (pickerFilterType == AssetType.TEXTURE) {
                        selected.setTextureAsset(asset.absolutePath, asset.name);
                    } else if (pickerFilterType == AssetType.MESH_3D) {
                        selected.setMeshAssetPath(asset.absolutePath);
                    }
                }
                manager.getOverlayManager().closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }
}
