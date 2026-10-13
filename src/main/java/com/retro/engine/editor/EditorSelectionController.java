package com.retro.engine.editor;

import com.retro.engine.editor.input.EditorInputRouter;

/**
 * EditorSelectionController serves as the high-level input coordinator,
 * delegating regional hit-testing, gizmo dragging, field scrubbing,
 * and asset ingestion to the modular EditorInputRouter pipeline.
 */
public class EditorSelectionController {

    private final EditorPanelManager panelManager;
    private final EditorInputRouter inputRouter;
    private ViewportCameraController cameraController;

    public EditorSelectionController() {
        this(null);
    }

    public EditorSelectionController(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
        this.inputRouter = new EditorInputRouter();
    }

    public void setCameraController(ViewportCameraController cameraController) {
        this.cameraController = cameraController;
    }

    public ViewportCameraController getCameraController() {
        return cameraController;
    }

    public EditorPanelManager getPanelManager() {
        return panelManager;
    }

    public EditorInputRouter getInputRouter() {
        return inputRouter;
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, int action, EditorPanelManager manager) {
        EditorPanelManager activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            inputRouter.handleMouseClick(mouseX, mouseY, button, action, activeManager);
        }
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        EditorPanelManager activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            return inputRouter.onCursorPos(mouseX, mouseY, activeManager);
        }
        return false;
    }
}
