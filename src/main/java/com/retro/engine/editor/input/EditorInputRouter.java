package com.retro.engine.editor.input;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorCommands;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.animator.AnimatorGraphPanel;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.overlays.BuildExporterOverlay;
import com.retro.engine.editor.overlays.ContextMenuOverlay;
import com.retro.engine.editor.overlays.OverlaySystem;
import com.retro.engine.editor.overlays.ProjectLauncherOverlay;
import com.retro.engine.editor.overlays.ProjectSettingsOverlay;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.InspectorPanel;
import com.retro.engine.editor.panels.PanelRegion;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

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
        if (workspace.getDockSpace().isDragging() || !workspace.hasActiveProject()) return false;
        return PanelRegion.getRegionAt(coords.ndcX, coords.ndcY, workspace.getOverlayManager(), workspace.getDockSpace()) == PanelRegion.VIEWPORT;
    }

    public boolean handleMouseClick(double rawX, double rawY, int button, int action) {
        if (workspace == null || (button != 0 && button != 1)) return false;

        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords == null) return false;

        float ndcX = coords.ndcX;
        float ndcY = coords.ndcY;

        // Template picker is modal over everything (launcher or editor) while open
        if (com.retro.engine.editor.overlays.TemplatePickerOverlay.isOpen()) {
            if (button == 0 && action == 1 && !workspace.isNativeDialogOpen()) {
                com.retro.engine.editor.overlays.TemplatePickerOverlay.handleClick(ndcX, ndcY);
            }
            return true;
        }

        // Project launcher is modal over the whole window until a project is bound
        if (!workspace.hasActiveProject()) {
            if (button == 0 && action == 1) ProjectLauncherOverlay.handleClick(ndcX, ndcY, workspace);
            return true;
        }

        double refX = coords.refX;
        double refY = coords.refY;

        OverlaySystem overlayManager = workspace.getOverlayManager();
        GizmoState gizmoStateController = workspace.getGizmoStateController();
        PanelRegion region = PanelRegion.getRegionAt(ndcX, ndcY, overlayManager, workspace.getDockSpace());

        if (action == 1) { // Press
            // Pressing outside the Inspector is focus loss for an inline text field: commit it
            if (workspace.getDockSpace().panelAt(ndcX, ndcY) != DockPanel.INSPECTOR) {
                InspectorInputDelegate.commitInlineEdit(workspace);
            }
            if (button == 1) {
                workspace.getDockSpace().cancelDrag();
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
                if (region == PanelRegion.ANIMATOR && workspace.getDockSpace().panelAt(ndcX, ndcY) == DockPanel.ANIMATOR) {
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

            if (overlayManager.getActiveModal() == EditorWorkspace.ModalState.BUILD_EXPORTER) {
                if (button == 0) BuildExporterOverlay.handleClick(ndcX, ndcY, workspace);
                return true;
            }

            if (overlayManager.getActiveModal() == EditorWorkspace.ModalState.PROJECT_SETTINGS) {
                if (button == 0) ProjectSettingsOverlay.handleClick(ndcX, ndcY, workspace);
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
                    boolean keepOpen = MainMenuBarInputDelegate.keepsDropdownOpen(ndcX, ndcY, workspace);
                    MainMenuBarInputDelegate.processDropdownOverlayClick(ndcX, ndcY, workspace);
                    if (!keepOpen) overlayManager.setActiveDropdownMenu(EditorWorkspace.DropdownMenu.NONE);
                }
                return true;
            }

            if (ndcY >= DockSpace.AREA_Y1) {
                MainMenuBarInputDelegate.processTopBarClick(ndcX, workspace);
                return true;
            }

            // Dock chrome (tab bars, splitters) takes priority over panel content
            if (workspace.getDockSpace().onPress(ndcX, ndcY)) {
                return true;
            }

            DockPanel panel = workspace.getDockSpace().panelAt(ndcX, ndcY);
            if (panel == null) return false;
            return dispatchToPanel(panel, ndcX, ndcY, button, action);
        } else if (action == 0) { // Release
            if (button == 0) gizmoStateController.onPrimaryRelease(workspace);
            InspectorInputDelegate.onMouseRelease(workspace);
            AssetBrowserInputDelegate.onMouseRelease(workspace);
            overlayManager.onModalMouseRelease();
            ProjectSettingsOverlay.onMouseRelease();
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

            if (workspace.getDockSpace().onRelease(ndcX, ndcY)) {
                return true;
            }

            // Playhead scrubs and node drags may end outside their tiles, so they always see the release
            if (button == 0 && AnimatorGraphPanel.handleInspectorRelease()) {
                return true;
            }
            if (workspace.getDockSpace().isVisible(DockPanel.ANIMATOR)) {
                workspace.getDockSpace().beginInput(DockPanel.ANIMATOR);
                try {
                    if (AnimatorGraphPanel.handleCanvasClick(workspace.getDockSpace().toDesignX(DockPanel.ANIMATOR, ndcX), workspace.getDockSpace().toDesignY(DockPanel.ANIMATOR, ndcY), button, action, workspace)) {
                        return true;
                    }
                } finally {
                    workspace.getDockSpace().endInput();
                }
            }

            if (button == 0 && gizmoStateController.isBoneDragActive()) {
                gizmoStateController.finishBoneDrag(workspace);
                return true;
            }

            if (button == 0 && gizmoStateController.isGroupDragActive()) {
                gizmoStateController.finishGroupDrag(workspace);
                return true;
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
        float dx = workspace.getDockSpace().toDesignX(panel, screenX);
        float dy = workspace.getDockSpace().toDesignY(panel, screenY);
        GizmoState gizmoStateController = workspace.getGizmoStateController();

        workspace.getDockSpace().beginInput(panel);
        try {
            switch (panel) {
                case HIERARCHY -> {
                    AssetBrowserInputDelegate.processHierarchyClick(dx, dy, workspace);
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
            workspace.getDockSpace().endInput();
        }
    }

    public boolean onCursorPos(double rawX, double rawY) {
        if (workspace == null) return false;
        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords != null) {
            workspace.setLastNdcPos(coords.ndcX, coords.ndcY);
            if (!workspace.hasActiveProject()) return true;

            if (workspace.getDockSpace().onCursor(coords.ndcX, coords.ndcY)) {
                return true;
            }

            if (workspace.getDockSpace().isVisible(DockPanel.ANIMATOR)) {
                AnimatorGraphPanel.handleCursorPos(workspace.getDockSpace().toDesignX(DockPanel.ANIMATOR, coords.ndcX), workspace.getDockSpace().toDesignY(DockPanel.ANIMATOR, coords.ndcY), workspace);
            }
            if (workspace.getDockSpace().isVisible(DockPanel.ANIMATOR_INSPECTOR)) {
                AnimatorGraphPanel.handleInspectorCursorPos(workspace.getDockSpace().toDesignX(DockPanel.ANIMATOR_INSPECTOR, coords.ndcX), workspace.getDockSpace().toDesignY(DockPanel.ANIMATOR_INSPECTOR, coords.ndcY), workspace);
            }
            if (workspace.getOverlayManager().processModalDrag(coords.ndcY)) {
                return true;
            }
            if (ProjectSettingsOverlay.handleDrag(coords.ndcX, workspace)) {
                return true;
            }
            if (workspace.getDockSpace().isVisible(DockPanel.HIERARCHY)
                    && AssetBrowserInputDelegate.processHierarchyDrag(workspace.getDockSpace().toDesignY(DockPanel.HIERARCHY, coords.ndcY))) {
                return true;
            }
            if (workspace.getDockSpace().isVisible(DockPanel.ASSETS)
                    && AssetBrowserInputDelegate.processDrag(workspace.getDockSpace().toDesignY(DockPanel.ASSETS, coords.ndcY))) {
                return true;
            }
            if (workspace.getDockSpace().isVisible(DockPanel.INSPECTOR)) {
                InspectorInputDelegate.processInspectorDrag(workspace.getDockSpace().toDesignX(DockPanel.INSPECTOR, coords.ndcX), workspace.getDockSpace().toDesignY(DockPanel.INSPECTOR, coords.ndcY), workspace);
            }
        }
        return workspace.getGizmoStateController().onCursorPos(rawX, rawY, workspace, workspace.getOverlayManager());
    }

    public boolean handleScroll(double yOffset) {
        if (workspace == null) return false;
        if (!workspace.hasActiveProject()) return true;
        if (workspace.getOverlayManager().handlePickerScroll(yOffset)) {
            return true;
        }
        DockPanel panel = workspace.getDockSpace().panelAt(workspace.getLastNdcX(), workspace.getLastNdcY());

        if (panel == DockPanel.HIERARCHY) {
            return AssetBrowserInputDelegate.handleHierarchyScroll(yOffset);
        }
        if (panel == DockPanel.INSPECTOR) {
            InspectorPanel.scroll((float) -yOffset * 0.18f);
            return true;
        }
        if (panel == DockPanel.ASSETS) {
            return AssetBrowserInputDelegate.handleScroll(yOffset);
        }
        if (panel == DockPanel.ANIMATOR || panel == DockPanel.ANIMATOR_INSPECTOR) {
            float dx = workspace.getDockSpace().toDesignX(panel, workspace.getLastNdcX());
            float dy = workspace.getDockSpace().toDesignY(panel, workspace.getLastNdcY());
            return panel == DockPanel.ANIMATOR
                    ? AnimatorGraphPanel.handleCanvasScroll(dx, dy, yOffset)
                    : AnimatorGraphPanel.handleInspectorScroll(dx, dy, yOffset);
        }
        if (panel == DockPanel.SCENE && !workspace.getOverlayManager().isContextMenuVisible()) {
            // Play mode: the Main Camera owns the view, so zoom is not consumed here
            if (cameraController != null && !cameraController.isPlayLocked()) {
                cameraController.onScroll(yOffset);
                return true;
            }
        }
        return false;
    }
}
