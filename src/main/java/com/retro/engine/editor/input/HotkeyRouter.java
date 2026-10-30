package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.core.GamepadManager;
import com.retro.engine.core.WindowHost;
import com.retro.engine.editor.gizmos.GizmoInteractor;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.world.SceneWorld.PlayState;


import static org.lwjgl.glfw.GLFW.*;

/**
 * Encapsulates keyboard hotkeys, debouncing, gizmo snap modifiers,
 * engine console shortcuts, and viewport camera controls.
 * Gamepad shortcuts: R3 = camera reset (F), D-Pad Left / Up / Right = Translate / Rotate / Scale gizmo.
 */
public class HotkeyRouter {

    private static final int CHORD_MODS = GLFW_MOD_CONTROL | GLFW_MOD_SUPER;

    // Raw key-down state from the previous frame, used to edge-trigger Ctrl/Cmd chords
    private boolean sDownLastFrame = false;
    private boolean oDownLastFrame = false;
    private boolean zDownLastFrame = false;
    private boolean dDownLastFrame = false;
    private boolean deleteKeyPressedLastFrame = false;
    private boolean graveKeyPressedLastFrame = false;

    public void processInput(WindowHost window, EditorWorkspace editorManager, ViewportCamera cameraController, float deltaTime) {
        if (window == null || editorManager == null) return;
        if (!editorManager.hasActiveProject()) return; // Project launcher owns input until a project is bound

        boolean isCtrlDown = window.isKeyDown(GLFW_KEY_LEFT_CONTROL) || window.isKeyDown(GLFW_KEY_RIGHT_CONTROL) || window.isKeyDown(GLFW_KEY_LEFT_SUPER) || window.isKeyDown(GLFW_KEY_RIGHT_SUPER);
        boolean isShiftDown = window.isKeyDown(GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW_KEY_RIGHT_SHIFT);

        // Snapping is DEFAULT (!isCtrlDown). Holding Cmd/Ctrl disables snapping for free movement.
        GizmoInteractor.setSnapEnabled(!isCtrlDown);

        // Chords fire only on the frame the letter goes down, and only if the OS reported Ctrl/Cmd on that press.
        // Polled modifier state can latch (e.g. a key-up swallowed by a native dialog), so a bare S/D held for
        // movement must never read as Save/Duplicate.
        boolean isSDown = window.isKeyDown(GLFW_KEY_S);
        boolean isODown = window.isKeyDown(GLFW_KEY_O);
        boolean isZDown = window.isKeyDown(GLFW_KEY_Z);
        boolean isDDown = window.isKeyDown(GLFW_KEY_D);
        boolean saveChord = isChordPressed(window, GLFW_KEY_S, isSDown, sDownLastFrame);
        boolean openChord = isChordPressed(window, GLFW_KEY_O, isODown, oDownLastFrame);
        boolean undoChord = isChordPressed(window, GLFW_KEY_Z, isZDown, zDownLastFrame);
        boolean duplicateChord = isChordPressed(window, GLFW_KEY_D, isDDown, dDownLastFrame);
        sDownLastFrame = isSDown;
        oDownLastFrame = isODown;
        zDownLastFrame = isZDown;
        dDownLastFrame = isDDown;

        // Scene-editing commands are disabled outside EDITING so gameplay keys can't save, open, duplicate, or delete
        boolean isEditing = editorManager.getPlayState() == PlayState.EDITING;

        // A focused Inspector text field owns the keyboard: no hotkeys (Backspace would delete the entity), no fly camera
        if (InspectorInputDelegate.isEditingText()) {
            if (cameraController != null) cameraController.update(deltaTime, false, false, false, false, false, false);
            return;
        }

        boolean is1 = window.isKeyDown(GLFW_KEY_1);
        boolean is2 = window.isKeyDown(GLFW_KEY_2);
        boolean is3 = window.isKeyDown(GLFW_KEY_3);
        boolean isW = window.isKeyDown(GLFW_KEY_W);
        boolean isS = isSDown;
        boolean isA = window.isKeyDown(GLFW_KEY_A);
        boolean isD = isDDown;
        boolean isQ = window.isKeyDown(GLFW_KEY_Q);
        boolean isE = window.isKeyDown(GLFW_KEY_E);
        boolean isR = window.isKeyDown(GLFW_KEY_R);
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
        if (undoChord) {
            if (isShiftDown) {
                editorManager.getCommandHistory().redo();
            } else {
                editorManager.getCommandHistory().undo();
            }
            UISoundPlayer.play(UISound.TOGGLE);
        }

        // Cmd+S / Ctrl+S: Save Scene
        if (isEditing && saveChord) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            editorManager.getSceneManager().promptSaveSceneWithNativeDialog(
                editorManager.getAssetDatabase().getMutableAssetDirectory(),
                editorManager.getAssetDatabase()
            );
        }

        // Cmd+O / Ctrl+O: Load Scene
        if (isEditing && openChord) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String defaultDir = editorManager.getProjectScenesDir();
            String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                UISoundPlayer.play(UISound.SPAWN);
                editorManager.getSceneManager().loadSceneFromFile(importedPath, editorManager.getAssetDatabase());
            } else {
                UISoundPlayer.play(UISound.CLOSE_MODAL);
            }
        }

        // Cmd+D / Ctrl+D: Duplicate Selected Entity
        if (isEditing && duplicateChord && editorManager.getSelectedEntity() != null) {
            editorManager.getSceneManager().duplicateSelectedEntity();
            Entity newlySpawned = editorManager.getSelectedEntity();
            if (newlySpawned != null) {
                EditorCommands.EntitySpawnCommand cmd = new EditorCommands.EntitySpawnCommand(editorManager, newlySpawned);
                editorManager.getCommandHistory().executeCommand(cmd);
            }
            UISoundPlayer.play(UISound.DUPLICATE);
        }

        // Delete / Backspace: Remove Selected Entity
        if (isEditing && isDelete) {
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

        // Joysticks report input even while another app is focused; only drive the editor when we have focus
        GamepadManager pad = window.getGamepadManager();
        if (pad != null && pad.isConnected() && window.isFocused()) {
            processGamepad(pad, editorManager, cameraController, deltaTime);
        }
    }

    private static boolean isChordPressed(WindowHost window, int key, boolean isDown, boolean wasDown) {
        return isDown && !wasDown && (window.getKeyPressMods(key) & CHORD_MODS) != 0;
    }

    private void processGamepad(GamepadManager pad, EditorWorkspace editorManager, ViewportCamera cameraController, float deltaTime) {
        if (cameraController == null || cameraController.isPlayLocked()) return;

        if (pad.isButtonJustPressed(GamepadManager.BUTTON_RIGHT_STICK)) cameraController.reset();

        if (pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_LEFT)) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.TRANSLATE);
        if (pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_UP)) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.ROTATE);
        if (pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_RIGHT)) editorManager.getGizmoStateController().setGizmoMode(EditorWorkspace.GizmoMode.SCALE);

        cameraController.applyGamepad(pad, deltaTime);
    }
}
