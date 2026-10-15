package com.retro.engine.editor;

/**
 * EditorSelectionController serves as the high-level input coordinator,
 * delegating regional hit-testing, gizmo dragging, and state updates to EditorPanelManager.
 */
public class EditorSelectionController {
    private final EditorPanelManager panelManager;
    private ViewportCameraController cameraController;

    public EditorSelectionController() { this(null); }

    public EditorSelectionController(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void setCameraController(ViewportCameraController cameraController) {
        this.cameraController = cameraController;
    }

    public ViewportCameraController getCameraController() { return cameraController; }
    public EditorPanelManager getPanelManager() { return panelManager; }

    public void handleMouseClick(double mouseX, double mouseY, int button, int action, EditorPanelManager manager) {
        EditorPanelManager activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            activeManager.handleMouseClick(mouseX, mouseY, button, action);
        }
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        EditorPanelManager activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            return activeManager.onCursorPos(mouseX, mouseY);
        }
        return false;
    }
}
