package com.retro.engine.editor;

import com.retro.engine.editor.gizmos.ViewportCamera;

import com.retro.engine.editor.ui.PanelRegion;

/**
 * EditorInputRouter serves as the high-level input coordinator,
 * delegating regional hit-testing, gizmo dragging, and state updates to EditorWorkspace.
 */
public class EditorInputRouter {
    private final EditorWorkspace panelManager;
    private ViewportCamera cameraController;

    public EditorInputRouter() { this(null); }

    public EditorInputRouter(EditorWorkspace panelManager) {
        this.panelManager = panelManager;
    }

    public void setCameraController(ViewportCamera cameraController) {
        this.cameraController = cameraController;
    }

    public ViewportCamera getCameraController() { return cameraController; }
    public EditorWorkspace getPanelManager() { return panelManager; }

    public boolean handleMouseClick(double mouseX, double mouseY, int button, int action, EditorWorkspace manager) {
        EditorWorkspace activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            return activeManager.handleMouseClick(mouseX, mouseY, button, action);
        }
        return false;
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorWorkspace manager) {
        EditorWorkspace activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager != null) {
            return activeManager.onCursorPos(mouseX, mouseY);
        }
        return false;
    }

    public boolean isCursorInViewport(double mouseX, double mouseY, EditorWorkspace manager) {
        EditorWorkspace activeManager = (manager != null) ? manager : this.panelManager;
        if (activeManager == null) return false;

        int w = activeManager.getWindowWidth();
        int h = activeManager.getWindowHeight();
        if (w <= 0 || h <= 0) return false;

        float ndcX = (float) ((mouseX / (double) w) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (mouseY / (double) h) * 2.0);

        return activeManager.getRegionAt(ndcX, ndcY) == PanelRegion.VIEWPORT;
    }
}
