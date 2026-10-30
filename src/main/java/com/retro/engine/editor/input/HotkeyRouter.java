package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.core.WindowHost;
import com.retro.engine.editor.gizmos.GizmoInteractor;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;


import static org.lwjgl.glfw.GLFW.*;

/**
 * Encapsulates keyboard hotkeys, debouncing, gizmo snap modifiers,
 * engine console shortcuts, and viewport camera controls.
 */
public class HotkeyRouter {

    private boolean saveKeyPressedLastFrame = false;
    private boolean loadKeyPressedLastFrame = false;
    private boolean undoKeyPressedLastFrame = false;
    private boolean duplicateKeyPressedLastFrame = false;
    private boolean deleteKeyPressedLastFrame = false;
    private boolean graveKeyPressedLastFrame = false;

    public void processInput(WindowHost window, EditorWorkspace editorManager, ViewportCamera cameraController, float deltaTime) {
        if (window == null || editorManager == null) return;
        if (!editorManager.hasActiveProject()) return; // Project launcher owns input until a project is bound

        boolean isCtrlDown = window.isKeyDown(GLFW_KEY_LEFT_CONTROL) || window.isKeyDown(GLFW_KEY_RIGHT_CONTROL) || window.isKeyDown(GLFW_KEY_LEFT_SUPER) || window.isKeyDown(GLFW_KEY_RIGHT_SUPER);
        boolean isShiftDown = window.isKeyDown(GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW_KEY_RIGHT_SHIFT);

        // Snapping is DEFAULT (!isCtrlDown). Holding Cmd/Ctrl disables snapping for free movement.
        GizmoInteractor.setSnapEnabled(!isCtrlDown);

        // A focused Inspector text field owns the keyboard: no hotkeys (Backspace would delete the entity), no fly camera
        if (InspectorInputDelegate.isEditingText()) {
            if (cameraController != null) cameraController.update(deltaTime, false, false, false, false, false, false);
            return;
        }

        boolean is1 = window.isKeyDown(GLFW_KEY_1);
        boolean is2 = window.isKeyDown(GLFW_KEY_2);
        boolean is3 = window.isKeyDown(GLFW_KEY_3);
        boolean isW = window.isKeyDown(GLFW_KEY_W);
        boolean isS = window.isKeyDown(GLFW_KEY_S);
        boolean isA = window.isKeyDown(GLFW_KEY_A);
        boolean isD = window.isKeyDown(GLFW_KEY_D);
        boolean isQ = window.isKeyDown(GLFW_KEY_Q);
        boolean isE = window.isKeyDown(GLFW_KEY_E);
        boolean isR = window.isKeyDown(GLFW_KEY_R);
        boolean isO = window.isKeyDown(GLFW_KEY_O);
        boolean isZ = window.isKeyDown(GLFW_KEY_Z);
        boolean isGrave = window.isKeyDown(GLFW_KEY_GRAVE_ACCENT); // Tilde key (~)
        boolean isDelete = window.isKeyDown(GLFW_KEY_DELETE) || window.isKeyDown(GLFW_KEY_BACKSPACE);

        // Cmd + ~ / Grave Accent: Toggle Engine Console & Profiler Overlay
        if (isGrave) {
            if (!graveKeyPressedLastFrame) {
                EngineConsolePanel.toggleConsole();
                UISoundPlayer.play(UISound.TOGGLE);
                graveKeyPressedLastFrame = true;
            }
        } else {
            graveKeyPressedLastFrame = false;
        }

        if (window.isKeyDown(GLFW_KEY_F) && cameraController != null) cameraController.reset();

        // Cmd+Z / Ctrl+Z: Undo | Cmd+Shift+Z / Ctrl+Shift+Z: Redo
        if (isCtrlDown && isZ) {
            if (!undoKeyPressedLastFrame) {
                if (isShiftDown) {
                    editorManager.getCommandHistory().redo();
                } else {
                    editorManager.getCommandHistory().undo();
                }
                UISoundPlayer.play(UISound.TOGGLE);
                undoKeyPressedLastFrame = true;
            }
        } else {
            undoKeyPressedLastFrame = false;
        }

        // Cmd+S / Ctrl+S: Save Scene
        if (isCtrlDown && isS) {
            if (!saveKeyPressedLastFrame) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                editorManager.getSceneManager().promptSaveSceneWithNativeDialog(
                    editorManager.getAssetDatabase().getMutableAssetDirectory(),
                    editorManager.getAssetDatabase()
                );
                saveKeyPressedLastFrame = true;
            }
        } else {
            saveKeyPressedLastFrame = false;
        }

        // Cmd+O / Ctrl+O: Load Scene
        if (isCtrlDown && isO) {
            if (!loadKeyPressedLastFrame) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                String defaultDir = editorManager.getProjectScenesDir();
                String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
                if (importedPath != null && !importedPath.trim().isEmpty()) {
                    UISoundPlayer.play(UISound.SPAWN);
                    editorManager.getSceneManager().loadSceneFromFile(importedPath, editorManager.getAssetDatabase());
                } else {
                    UISoundPlayer.play(UISound.CLOSE_MODAL);
                }
                loadKeyPressedLastFrame = true;
            }
        } else {
            loadKeyPressedLastFrame = false;
        }

        // Cmd+D / Ctrl+D: Duplicate Selected Entity
        if (isCtrlDown && isD) {
            if (!duplicateKeyPressedLastFrame) {
                if (editorManager.getSelectedEntity() != null) {
                    editorManager.getSceneManager().duplicateSelectedEntity();
                    Entity newlySpawned = editorManager.getSelectedEntity();
                    if (newlySpawned != null) {
                        EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(editorManager, newlySpawned);
                        editorManager.getCommandHistory().executeCommand(cmd);
                    }
                    UISoundPlayer.play(UISound.DUPLICATE);
                }
                duplicateKeyPressedLastFrame = true;
            }
        } else {
            duplicateKeyPressedLastFrame = false;
        }

        // Delete / Backspace: Remove Selected Entity
        if (isDelete) {
            if (!deleteKeyPressedLastFrame) {
                Entity selected = editorManager.getSelectedEntity();
                if (selected != null) {
                    String entityName = selected.name;
                    EditorCommands.EntityDeleteCommand cmd = new EditorCommands.EntityDeleteCommand(editorManager, selected);
                    editorManager.getCommandHistory().executeCommand(cmd);
                    UISoundPlayer.play(UISound.DELETE);
                    EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Scene] Deleted entity '" + entityName + "'.");
                }
                deleteKeyPressedLastFrame = true;
            }
        } else {
            deleteKeyPressedLastFrame = false;
        }

        if (cameraController != null && !isCtrlDown && !cameraController.isDraggingRmb() && !cameraController.isDraggingMmb()) {
            if (is1 || isW) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.TRANSLATE);
            if (is2 || isE) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.ROTATE);
            if (is3 || isR) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.SCALE);
        }

        if (cameraController != null) {
            cameraController.update(deltaTime, isW, isS, isA, isD, isQ, isE);
        }
    }
}
