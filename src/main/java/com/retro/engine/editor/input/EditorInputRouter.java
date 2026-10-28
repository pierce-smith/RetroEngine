package com.retro.engine.editor.input;

import com.retro.engine.editor.ui.overlays.ProjectLauncherOverlay;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.ui.animator.AnimatorGraphPanel;
import com.retro.engine.editor.ui.batch.PanelRegion;
import com.retro.engine.editor.ui.dock.DockPanel;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.overlays.ContextMenuOverlay;
import com.retro.engine.editor.ui.overlays.OverlaySystem;
import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;

/**
 * Single Authority for GLFW/LWJGL Editor Input Event Routing.
 * Evaluates screen-to-NDC projections, panel region hit-testing,
 * dock chrome (tab activation, tab re-docking, splitter resizing), 3D gizmo interactions,
 * and viewport scrolling. Panel input is dispatched in each docked panel's design space.
 * Hardened with explicit dispose() unbind hooks.
 */
public class EditorInputRouter {

    private final EditorWorkspace workspace;
    private ViewportCamera cameraController;

    public EditorInputRouter(EditorWorkspace workspace) {
        this.workspace = workspace;
    }

    public void setCameraController(ViewportCamera cameraController) {
        this.cameraController = cameraController;
    }

    public ViewportCamera getCameraController() {
        return cameraController;
    }

    public void dispose() {
        this.cameraController = null;
    }

    private static class PointerCoordinates {
        final float ndcX;
        final float ndcY;
        final double refX;
        final double refY;

        PointerCoordinates(float ndcX, float ndcY, double refX, double refY) {
            this.ndcX = ndcX;
            this.ndcY = ndcY;
            this.refX = refX;
            this.refY = refY;
        }
    }

    private PointerCoordinates calculateCoordinates(double rawX, double rawY) {
        if (workspace == null) return null;
        int winW = workspace.getWindowWidth();
        int winH = workspace.getWindowHeight();
        if (winW <= 0 || winH <= 0) return null;

        float ndcX = (float) ((rawX / (double) winW) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (rawY / (double) winH) * 2.0);
        double refX = (rawX / (double) winW) * 1280.0;
        double refY = (rawY / (double) winH) * 720.0;

        return new PointerCoordinates(ndcX, ndcY, refX, refY);
    }

    public boolean isCursorInViewport(double rawX, double rawY) {
        if (workspace == null) return false;
        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords == null) return false;
        if (DockSpace.isDragging() || !workspace.hasActiveProject()) return false;
        return PanelRegion.getRegionAt(coords.ndcX, coords.ndcY, workspace.getOverlayManager()) == PanelRegion.VIEWPORT;
    }

    public boolean handleMouseClick(double rawX, double rawY, int button, int action) {
        if (workspace == null || (button != 0 && button != 1)) return false;

        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords == null) return false;

        float ndcX = coords.ndcX;
        float ndcY = coords.ndcY;

        // Project launcher is modal over the whole window until a project is bound
        if (!workspace.hasActiveProject()) {
            if (button == 0 && action == 1) ProjectLauncherOverlay.handleClick(ndcX, ndcY, workspace);
            return true;
        }

        double refX = coords.refX;
        double refY = coords.refY;

        OverlaySystem overlayManager = workspace.getOverlayManager();
        GizmoState gizmoStateController = workspace.getGizmoStateController();
        PanelRegion region = PanelRegion.getRegionAt(ndcX, ndcY, overlayManager);

        if (action == 1) { // Press
            if (button == 1) {
                DockSpace.cancelDrag();
                overlayManager.onRightClickPress(
                    refX, refY, ndcX, ndcY, cameraController,
                    gizmoStateController.getCamPitch(),
                    gizmoStateController.getCamYaw(),
                    gizmoStateController.getCamPanX(),
                    gizmoStateController.getCamPanY(),
                    gizmoStateController.getCamZoom()
                );
                // Animator canvas opens its graph context menu on press; OverlaySystem's release-time
                // menu builder then sees the open menu (OVERLAY_MODAL) and leaves it alone
                if (region == PanelRegion.ANIMATOR && DockSpace.panelAt(ndcX, ndcY) == DockPanel.ANIMATOR) {
                    dispatchToPanel(DockPanel.ANIMATOR, ndcX, ndcY, button, action);
                }
                return true;
            }

            if (overlayManager.isContextMenuVisible()) {
                boolean consumed = ContextMenuOverlay.processClick(
                    ndcX, ndcY,
                    overlayManager.getContextMenuNdcX(),
                    overlayManager.getContextMenuNdcY(),
                    overlayManager.getContextMenuItems()
                );
                overlayManager.closeContextMenu();
                return true;
            }

            if (overlayManager.getActiveModal() == EditorWorkspace.ModalState.ASSET_PICKER) {
                MainMenuBarInputDelegate.processModalClick(ndcX, ndcY, workspace);
                return true;
            }

            if (overlayManager.getActiveModal() == EditorWorkspace.ModalState.PARENT_PICKER) {
                overlayManager.processParentPickerModalClick(ndcX, ndcY, workspace);
                return true;
            }

            if (overlayManager.getActiveDropdownMenu() != EditorWorkspace.DropdownMenu.NONE) {
                if (ndcY >= 0.92f) {
                    MainMenuBarInputDelegate.processTopBarClick(ndcX, workspace);
                } else {
                    MainMenuBarInputDelegate.processDropdownOverlayClick(ndcX, ndcY, workspace);
                    overlayManager.setActiveDropdownMenu(EditorWorkspace.DropdownMenu.NONE);
                }
                return true;
            }

            if (ndcY >= DockSpace.AREA_Y1) {
                MainMenuBarInputDelegate.processTopBarClick(ndcX, workspace);
                return true;
            }

            // Dock chrome (tab bars, splitters) takes priority over panel content
            if (DockSpace.onPress(ndcX, ndcY)) {
                return true;
            }

            DockPanel panel = DockSpace.panelAt(ndcX, ndcY);
            if (panel == null) return false;
            return dispatchToPanel(panel, ndcX, ndcY, button, action);
        } else if (action == 0) { // Release
            InspectorInputDelegate.onMouseRelease();
            if (button == 1) {
                overlayManager.onRightClickRelease(
                    refX, refY, workspace, cameraController,
                    gizmoStateController.getCamPitch(),
                    gizmoStateController.getCamYaw(),
                    gizmoStateController.getCamPanX(),
                    gizmoStateController.getCamPanY(),
                    gizmoStateController.getCamZoom(),
                    gizmoStateController.getLastOrbitTime()
                );
                return true;
            }

            if (DockSpace.onRelease(ndcX, ndcY)) {
                return true;
            }

            // Playhead scrubs and node drags may end outside their tiles, so they always see the release
            if (button == 0 && AnimatorGraphPanel.handleInspectorRelease()) {
                return true;
            }
            if (DockSpace.isVisible(DockPanel.ANIMATOR)) {
                DockSpace.beginInput(DockPanel.ANIMATOR);
                try {
                    if (AnimatorGraphPanel.handleCanvasClick(DockSpace.toDesignX(DockPanel.ANIMATOR, ndcX), DockSpace.toDesignY(DockPanel.ANIMATOR, ndcY), button, action, workspace)) {
                        return true;
                    }
                } finally {
                    DockSpace.endInput();
                }
            }

            if (button == 0 && gizmoStateController.getActiveAxis() != EditorWorkspace.GizmoAxis.NONE && workspace.getSelectedEntity() != null) {
                Entity selected = workspace.getSelectedEntity();
                Transform tc = selected.getTransform();
                if (tc != null) {
                    if (tc.posX != gizmoStateController.getDragStartPx() || tc.posY != gizmoStateController.getDragStartPy() || tc.posZ != gizmoStateController.getDragStartPz() ||
                        tc.rotX != gizmoStateController.getDragStartRx() || tc.rotY != gizmoStateController.getDragStartRy() || tc.rotZ != gizmoStateController.getDragStartRz() ||
                        tc.scaleX != gizmoStateController.getDragStartSx() || tc.scaleY != gizmoStateController.getDragStartSy() || tc.scaleZ != gizmoStateController.getDragStartSz()) {

                        EditorCommands.TransformCommand cmd = new EditorCommands.TransformCommand(tc,
                            gizmoStateController.getDragStartPx(), gizmoStateController.getDragStartPy(), gizmoStateController.getDragStartPz(),
                            gizmoStateController.getDragStartRx(), gizmoStateController.getDragStartRy(), gizmoStateController.getDragStartRz(),
                            gizmoStateController.getDragStartSx(), gizmoStateController.getDragStartSy(), gizmoStateController.getDragStartSz(),
                            tc.posX, tc.posY, tc.posZ,
                            tc.rotX, tc.rotY, tc.rotZ,
                            tc.scaleX, tc.scaleY, tc.scaleZ
                        );
                        workspace.getCommandHistory().executeCommand(cmd);
                    }
                }
                gizmoStateController.setActiveAxis(EditorWorkspace.GizmoAxis.NONE);
                return true;
            }
        }
        return false;
    }

    /**
     * Dispatches a press to a docked panel in that panel's authored design space, so every existing
     * hit-test keeps working wherever (and at whatever size) the panel is docked.
     */
    private boolean dispatchToPanel(DockPanel panel, float screenX, float screenY, int button, int action) {
        float dx = DockSpace.toDesignX(panel, screenX);
        float dy = DockSpace.toDesignY(panel, screenY);
        GizmoState gizmoStateController = workspace.getGizmoStateController();

        DockSpace.beginInput(panel);
        try {
            switch (panel) {
                case HIERARCHY -> {
                    AssetBrowserInputDelegate.processHierarchyClick(dy, workspace);
                    return true;
                }
                case INSPECTOR -> {
                    InspectorInputDelegate.processInspectorClick(dx, dy, workspace);
                    return true;
                }
                case ASSETS -> {
                    AssetBrowserInputDelegate.processAssetDirectoryClick(dx, dy, workspace);
                    return true;
                }
                case SCENE -> {
                    if (gizmoStateController.processViewportToolbarClick(dx, dy)) {
                        return true;
                    }
                    gizmoStateController.processViewportGizmoClick((dx + 1.0) * 640.0, (1.0 - dy) * 360.0, workspace);
                    return gizmoStateController.getActiveAxis() != EditorWorkspace.GizmoAxis.NONE;
                }
                case ANIMATOR -> {
                    return AnimatorGraphPanel.handleCanvasClick(dx, dy, button, action, workspace);
                }
                case ANIMATOR_PARAMETERS, ANIMATOR_INSPECTOR -> {
                    // Same routing as the classic full-screen animator: parameters sidebar, then canvas
                    // (which cancels a pending transition link when clicked outside the graph)
                    return AnimatorGraphPanel.handleMouseClick(dx, dy, button, action, workspace);
                }
                case CONSOLE -> {
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } finally {
            DockSpace.endInput();
        }
    }

    public boolean onCursorPos(double rawX, double rawY) {
        if (workspace == null) return false;
        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords != null) {
            workspace.setLastNdcPos(coords.ndcX, coords.ndcY);
            if (!workspace.hasActiveProject()) return true;

            if (DockSpace.onCursor(coords.ndcX, coords.ndcY)) {
                return true;
            }

            if (DockSpace.isVisible(DockPanel.ANIMATOR)) {
                AnimatorGraphPanel.handleCursorPos(DockSpace.toDesignX(DockPanel.ANIMATOR, coords.ndcX), DockSpace.toDesignY(DockPanel.ANIMATOR, coords.ndcY), workspace);
            }
            if (DockSpace.isVisible(DockPanel.ANIMATOR_INSPECTOR)) {
                AnimatorGraphPanel.handleInspectorCursorPos(DockSpace.toDesignX(DockPanel.ANIMATOR_INSPECTOR, coords.ndcX), DockSpace.toDesignY(DockPanel.ANIMATOR_INSPECTOR, coords.ndcY), workspace);
            }
            if (DockSpace.isVisible(DockPanel.INSPECTOR)) {
                InspectorInputDelegate.processInspectorDrag(DockSpace.toDesignX(DockPanel.INSPECTOR, coords.ndcX), DockSpace.toDesignY(DockPanel.INSPECTOR, coords.ndcY), workspace);
            }
        }
        return workspace.getGizmoStateController().onCursorPos(rawX, rawY, workspace, workspace.getOverlayManager());
    }

    public boolean handleScroll(double yOffset) {
        if (workspace == null) return false;
        if (!workspace.hasActiveProject()) return true;
        DockPanel panel = DockSpace.panelAt(workspace.getLastNdcX(), workspace.getLastNdcY());

        if (panel == DockPanel.INSPECTOR) {
            InspectorPanel.scroll((float) -yOffset * 0.18f);
            return true;
        }
        if (panel == DockPanel.ANIMATOR || panel == DockPanel.ANIMATOR_INSPECTOR) {
            float dx = DockSpace.toDesignX(panel, workspace.getLastNdcX());
            float dy = DockSpace.toDesignY(panel, workspace.getLastNdcY());
            return panel == DockPanel.ANIMATOR
                    ? AnimatorGraphPanel.handleCanvasScroll(dx, dy, yOffset)
                    : AnimatorGraphPanel.handleInspectorScroll(dx, dy, yOffset);
        }
        if (panel == DockPanel.SCENE && !workspace.getOverlayManager().isContextMenuVisible()) {
            if (cameraController != null) {
                cameraController.onScroll(yOffset);
                return true;
            }
        }
        return false;
    }
}
