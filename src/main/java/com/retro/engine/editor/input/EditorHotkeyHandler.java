package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.host.GlfwWindow;

import static org.lwjgl.glfw.GLFW.*;

/**
 * EditorHotkeyHandler encapsulates keyboard shortcut routing,
 * input debouncing, gizmo mode toggles, and camera navigation key polling.
 */
public class EditorHotkeyHandler {

    private boolean saveKeyPressedLastFrame = false;
    private boolean loadKeyPressedLastFrame = false;
    private boolean undoKeyPressedLastFrame = false;
    private boolean duplicateKeyPressedLastFrame = false;
    private boolean deleteKeyPressedLastFrame = false;

    public void processInput(GlfwWindow window, EditorPanelManager editorManager, ViewportCameraController cameraController, float deltaTime) {
        if (window == null || editorManager == null || cameraController == null) return;

        boolean isCtrlDown = window.isKeyDown(GLFW_KEY_LEFT_CONTROL) || window.isKeyDown(GLFW_KEY_RIGHT_CONTROL) ||
                             window.isKeyDown(GLFW_KEY_LEFT_SUPER) || window.isKeyDown(GLFW_KEY_RIGHT_SUPER);
        boolean isShiftDown = window.isKeyDown(GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW_KEY_RIGHT_SHIFT);

        // Update Gizmo Snapping State
        GizmoInteractor.setSnapEnabled(isCtrlDown);

        // Navigation / Mode Keys
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

        if (window.isKeyDown(GLFW_KEY_F)) {
            cameraController.reset();
        }

        // Cmd+Z / Ctrl+Z: Undo | Cmd+Shift+Z / Ctrl+Shift+Z: Redo
        if (isCtrlDown && isZ) {
            if (!undoKeyPressedLastFrame) {
                if (isShiftDown) {
                    editorManager.redo();
                } else {
                    editorManager.undo();
                }
                undoKeyPressedLastFrame = true;
            }
        } else {
            undoKeyPressedLastFrame = false;
        }

        // Cmd+S / Ctrl+S: Save Scene
        if (isCtrlDown && isS) {
            if (!saveKeyPressedLastFrame) {
                editorManager.promptSaveSceneWithNativeDialog();
                saveKeyPressedLastFrame = true;
            }
        } else {
            saveKeyPressedLastFrame = false;
        }

        // Cmd+O / Ctrl+O: Load Scene
        if (isCtrlDown && isO) {
            if (!loadKeyPressedLastFrame) {
                editorManager.promptOpenSceneWithNativeDialog();
                loadKeyPressedLastFrame = true;
            }
        } else {
            loadKeyPressedLastFrame = false;
        }

        // Cmd+D / Ctrl+D: Duplicate Selected Entity (Custom Model / Primitive / Light)
        if (isCtrlDown && isD) {
            if (!duplicateKeyPressedLastFrame) {
                editorManager.duplicateSelectedEntity();
                duplicateKeyPressedLastFrame = true;
            }
        } else {
            duplicateKeyPressedLastFrame = false;
        }

        // Delete / Backspace: Remove Selected Entity
        if (isDelete) {
            if (!deleteKeyPressedLastFrame) {
                editorManager.deleteSelectedEntity();
                deleteKeyPressedLastFrame = true;
            }
        } else {
            deleteKeyPressedLastFrame = false;
        }

        // Gizmo Mode Switches (1/W = Translate, 2/E = Rotate, 3/R = Scale)
        if (!isCtrlDown && !cameraController.isDraggingRmb() && !cameraController.isDraggingMmb()) {
            if (is1 || isW) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.TRANSLATE);
            if (is2 || isE) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.ROTATE);
            if (is3 || isR) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.SCALE);
        }

        // Update Camera Flying Controller
        cameraController.update(deltaTime, isW, isS, isA, isD, isQ, isE);
    }
}
