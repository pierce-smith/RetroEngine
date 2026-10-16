package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.scene.Entity;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.gizmos.ViewportCamera;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.core.WindowHost;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Encapsulates keyboard hotkeys, debouncing, and shortcuts for Editor operations.
 */
public class HotkeyRouter {

    private boolean saveKeyPressedLastFrame = false;
    private boolean loadKeyPressedLastFrame = false;
    private boolean undoKeyPressedLastFrame = false;
    private boolean duplicateKeyPressedLastFrame = false;
    private boolean deleteKeyPressedLastFrame = false;

    public void processInput(WindowHost window, EditorWorkspace editorManager, ViewportCamera cameraController, float deltaTime) {
        if (window == null || editorManager == null) return;

        boolean isCtrlDown = window.isKeyDown(GLFW_KEY_LEFT_CONTROL) || window.isKeyDown(GLFW_KEY_RIGHT_CONTROL) || window.isKeyDown(GLFW_KEY_LEFT_SUPER) || window.isKeyDown(GLFW_KEY_RIGHT_SUPER);
        boolean isShiftDown = window.isKeyDown(GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW_KEY_RIGHT_SHIFT);

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
        boolean isDelete = window.isKeyDown(GLFW_KEY_DELETE) || window.isKeyDown(GLFW_KEY_BACKSPACE);

        if (window.isKeyDown(GLFW_KEY_F) && cameraController != null) cameraController.reset();

        // Cmd+Z / Ctrl+Z: Undo | Cmd+Shift+Z / Ctrl+Shift+Z: Redo
        if (isCtrlDown && isZ) {
            if (!undoKeyPressedLastFrame) {
                if (isShiftDown) {
                    editorManager.redo();
                } else {
                    editorManager.undo();
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
                editorManager.promptSaveSceneWithNativeDialog();
                saveKeyPressedLastFrame = true;
            }
        } else {
            saveKeyPressedLastFrame = false;
        }

        // Cmd+O / Ctrl+O: Load Scene
        if (isCtrlDown && isO) {
            if (!loadKeyPressedLastFrame) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                editorManager.promptOpenSceneWithNativeDialog();
                loadKeyPressedLastFrame = true;
            }
        } else {
            loadKeyPressedLastFrame = false;
        }

        // Cmd+D / Ctrl+D: Duplicate Selected Entity
        if (isCtrlDown && isD) {
            if (!duplicateKeyPressedLastFrame) {
                editorManager.duplicateSelectedEntity(); // EditorWorkspace plays UISound.DUPLICATE
                duplicateKeyPressedLastFrame = true;
            }
        } else {
            duplicateKeyPressedLastFrame = false;
        }

        // Delete / Backspace: Remove Selected Entity
        if (isDelete) {
            if (!deleteKeyPressedLastFrame) {
                editorManager.deleteSelectedEntity(); // EditorWorkspace plays UISound.DELETE
                deleteKeyPressedLastFrame = true;
            }
        } else {
            deleteKeyPressedLastFrame = false;
        }

        if (cameraController != null && !isCtrlDown && !cameraController.isDraggingRmb() && !cameraController.isDraggingMmb()) {
            if (is1 || isW) editorManager.setCurrentGizmoMode(EditorWorkspace.GizmoMode.TRANSLATE);
            if (is2 || isE) editorManager.setCurrentGizmoMode(EditorWorkspace.GizmoMode.ROTATE);
            if (is3 || isR) editorManager.setCurrentGizmoMode(EditorWorkspace.GizmoMode.SCALE);
        }

        if (cameraController != null) {
            cameraController.update(deltaTime, isW, isS, isA, isD, isQ, isE);
        }
    }
}
