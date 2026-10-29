package com.retro.engine.editor.input;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
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
import com.retro.engine.editor.ui.panels.InspectorPanel;

import java.lang.reflect.Field;
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
                } else if (itemIndex == 1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    String defaultDir = manager.getProjectScenesDir();
                    String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
                    if (importedPath != null && !importedPath.trim().isEmpty()) {
                        UISoundPlayer.play(UISound.SPAWN);
                        manager.getSceneManager().loadSceneFromFile(importedPath, manager.getAssetDatabase());
                    } else {
                        UISoundPlayer.play(UISound.CLOSE_MODAL);
                    }
                } else if (itemIndex == 2) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getSceneManager().promptSaveSceneWithNativeDialog(manager.getAssetDatabase().getMutableAssetDirectory(), manager.getAssetDatabase());
                } else if (itemIndex == 3) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    com.retro.engine.editor.ui.overlays.BuildExporterOverlay.open(manager);
                } else if (itemIndex == 4) {
                    // Unbind the project and return to the launcher, which clears and re-scans on the next open
                    UISoundPlayer.play(UISound.CLOSE_MODAL);
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    manager.closeProject();
                } else if (itemIndex == 5) {
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
                else if (itemIndex == 2) { UISoundPlayer.play(UISound.CLICK); manager.getDockSpace().resetLayout(); }
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
                if (assignMaterialChannel(manager, selected, null)) {
                    // Non-albedo Material channel cleared through the undoable property command
                } else if (pickerFilterType == AssetType.TEXTURE) {
                    selected.setTextureAsset(null, null);
                } else if (pickerFilterType == AssetType.MESH_3D) {
                    selected.setMeshAssetPath(null);
                } else if (pickerFilterType == AssetType.SCRIPT) {
                    ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                    String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                    String oldName = (sc != null) ? sc.scriptName : null;
                    EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, null, null);
                    manager.getCommandHistory().executeCommand(cmd);
                    InspectorPanel.resetScroll();
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
                    if (assignMaterialChannel(manager, selected, asset.absolutePath)) {
                        // Non-albedo Material channel assigned through the undoable property command
                    } else if (pickerFilterType == AssetType.TEXTURE) {
                        selected.setTextureAsset(asset.absolutePath, asset.name);
                    } else if (pickerFilterType == AssetType.MESH_3D) {
                        selected.setMeshAssetPath(asset.absolutePath);
                    } else if (pickerFilterType == AssetType.SCRIPT) {
                        ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                        String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                        String oldName = (sc != null) ? sc.scriptName : null;
                        EditorCommands.ScriptAssignCommand cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, asset.absolutePath, asset.name);
                        manager.getCommandHistory().executeCommand(cmd);
                        InspectorPanel.resetScroll();
                    }
                }
                manager.getOverlayManager().closeModal();
                return;
            }
            listY -= 0.10f;
        }
    }

    /**
     * Applies a picker result to the Material channel the picker was opened for. Returns false when the picker targets
     * the primary albedo texture (or no Material field), leaving the legacy Entity.setTextureAsset path to handle it.
     */
    private static boolean assignMaterialChannel(EditorWorkspace manager, Entity selected, String assetPath) {
        String targetField = manager.getOverlayManager().getPickerTargetField();
        if (targetField == null || "textureAssetPath".equals(targetField)) return false;
        Material mat = selected.getComponent(Material.class);
        if (mat == null) return false;
        try {
            Field field = Material.class.getDeclaredField(targetField);
            EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, field.get(mat), assetPath, "TEXTURE");
            manager.getCommandHistory().executeCommand(cmd);
            return true;
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
            return false;
        }
    }
}
