package com.retro.engine.editor.input;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.editor.EditorCommands;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.overlays.ModalDialogOverlay;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.InspectorPanel;
import com.retro.engine.editor.panels.MainMenuBar;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

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
                    com.retro.engine.editor.overlays.ProjectSettingsOverlay.open(manager);
                } else if (itemIndex == 4) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    com.retro.engine.editor.overlays.ProjectLauncherOverlay.toggleModWorkspace(manager);
                } else if (itemIndex == 5) {
                    // Straight to the folder picker; packaging runs in the background and reports with a native dialog
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    com.retro.engine.editor.overlays.BuildExporterOverlay.promptPackageMod(manager);
                } else if (itemIndex == 6) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    com.retro.engine.editor.overlays.BuildExporterOverlay.open(manager);
                } else if (itemIndex == 7) {
                    // Unbind the project and return to the launcher, which clears and re-scans on the next open
                    UISoundPlayer.play(UISound.CLOSE_MODAL);
                    manager.getOverlayManager().setActiveDropdownMenu(DropdownMenu.NONE);
                    manager.closeProject();
                } else if (itemIndex == 8) {
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

        if (manager.getOverlayManager().beginPickerScrollbarDrag(ndcX, ndcY)) return;

        if (ndcX >= -0.42f && ndcX <= -0.07f && ndcY >= ModalDialogOverlay.PICKER_ACTION_Y0 && ndcY <= ModalDialogOverlay.PICKER_ACTION_Y1) {
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (assignMaterialChannel(manager, selected, null)) {
                    // Non-albedo Material channel cleared through the undoable property command
                } else if (pickerFilterType == AssetType.TEXTURE) {
                    selected.setTextureAsset(null, null);
                } else if (pickerFilterType == AssetType.MESH_3D) {
                    selected.setMeshAssetPath(null);
                } else if (pickerFilterType == AssetType.SCRIPT) {
                    manager.getCommandHistory().executeCommand(new EditorCommands.ScriptAssignCommand(
                            selected, pickerTargetScript(manager, selected), null, null));
                    InspectorPanel.resetScroll();
                } else if (pickerFilterType == AssetType.AUDIO) {
                    AudioSource audio = pickerTargetAudio(manager, selected);
                    if (audio != null) {
                        manager.getCommandHistory().executeCommand(new EditorCommands.AudioClipAssignCommand(selected, audio, null));
                    }
                }
            }
            UISoundPlayer.play(UISound.CLICK);
            manager.getOverlayManager().closeModal();
            return;
        }

        // [+ IMPORT DISK...]: import a file from disk, then bind it exactly as if its row had been clicked
        if (ModalDialogOverlay.isOverPickerImportButton(ndcX, ndcY)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            AssetMeta imported = ModalDialogOverlay.importPickerAssetFromDisk(pickerFilterType, manager.getAssetDatabase());
            if (imported == null) {
                // Cancelled or not importable: keep the picker open
                UISoundPlayer.play(UISound.CLOSE_MODAL);
                return;
            }
            UISoundPlayer.play(UISound.SPAWN);
            applyPickedAsset(manager, pickerFilterType, imported);
            manager.getOverlayManager().closeModal();
            return;
        }

        // Same alphabetical list and scroll-aware row layout the modal draws
        List<AssetMeta> filteredAssets = ModalDialogOverlay.assetPickerEntries(pickerFilterType, manager.getAssetDatabase().getAssetDirectory());
        ModalDialogOverlay.updatePickerScrollBounds(filteredAssets.size());
        int row = ModalDialogOverlay.pickerRowAt(ndcX, ndcY, filteredAssets.size());
        if (row < 0) return;

        UISoundPlayer.play(UISound.CLICK);
        applyPickedAsset(manager, pickerFilterType, filteredAssets.get(row));
        manager.getOverlayManager().closeModal();
    }

    /** Binds a picker choice to the selected entity: Material channel, texture, mesh, or the targeted ScriptComponent. */
    private static void applyPickedAsset(EditorWorkspace manager, AssetType pickerFilterType, AssetMeta asset) {
        Entity selected = manager.getSelectedEntity();
        // Only an asset of the picker's own type may be bound, so e.g. an audio clip can never land in a mesh or texture slot
        if (selected != null && asset != null && asset.type == pickerFilterType) {
            if (assignMaterialChannel(manager, selected, asset.absolutePath)) {
                // Non-albedo Material channel assigned through the undoable property command
            } else if (pickerFilterType == AssetType.TEXTURE) {
                selected.setTextureAsset(asset.absolutePath, asset.name);
            } else if (pickerFilterType == AssetType.MESH_3D) {
                selected.setMeshAssetPath(asset.absolutePath);
            } else if (pickerFilterType == AssetType.SCRIPT) {
                // Bound to a script card: reassign that instance; otherwise attach an additional ScriptComponent
                manager.getCommandHistory().executeCommand(new EditorCommands.ScriptAssignCommand(
                        selected, pickerTargetScript(manager, selected), asset.absolutePath, asset.name));
                InspectorPanel.resetScroll();
            } else if (pickerFilterType == AssetType.AUDIO) {
                // Bound to an Audio Source card: set that clip; otherwise the first AudioSource, adding one if none exists
                manager.getCommandHistory().executeCommand(new EditorCommands.AudioClipAssignCommand(
                        selected, pickerTargetAudio(manager, selected), asset.absolutePath));
            }
        }
    }

    /** AudioSource the picker was opened from if still attached to {@code selected}, else its first AudioSource (or null). */
    private static AudioSource pickerTargetAudio(EditorWorkspace manager, Entity selected) {
        Component target = manager.getOverlayManager().getPickerTargetComponent();
        if (target instanceof AudioSource audio && selected.getComponents(AudioSource.class).contains(audio)) return audio;
        return selected.getComponent(AudioSource.class);
    }

    /** ScriptComponent the picker was opened from, if it is still attached to {@code selected}; null means add a new one. */
    private static ScriptComponent pickerTargetScript(EditorWorkspace manager, Entity selected) {
        Component target = manager.getOverlayManager().getPickerTargetComponent();
        if (!(target instanceof ScriptComponent sc)) return null;
        return selected.getComponents(ScriptComponent.class).contains(sc) ? sc : null;
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
