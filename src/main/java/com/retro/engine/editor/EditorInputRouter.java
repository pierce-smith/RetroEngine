package com.retro.engine.editor;

import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.ui.*;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.scene.Entity;

/**
 * Single Authority for GLFW/LWJGL Editor Input Event Routing.
 * Evaluates screen-to-NDC projections, panel region hit-testing,
 * viewport tab switching, 3D gizmo interactions, and viewport scrolling.
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
        return PanelRegion.getRegionAt(coords.ndcX, coords.ndcY, workspace.getOverlayManager()) == PanelRegion.VIEWPORT;
    }

    public boolean handleMouseClick(double rawX, double rawY, int button, int action) {
        if (workspace == null || (button != 0 && button != 1)) return false;

        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords == null) return false;

        float ndcX = coords.ndcX;
        float ndcY = coords.ndcY;
        double refX = coords.refX;
        double refY = coords.refY;

        OverlaySystem overlayManager = workspace.getOverlayManager();
        GizmoState gizmoStateController = workspace.getGizmoStateController();
        PanelRegion region = PanelRegion.getRegionAt(ndcX, ndcY, overlayManager);

        if (action == 1) { // Press
            if (button == 1) {
                overlayManager.onRightClickPress(
                    refX, refY, ndcX, ndcY, cameraController,
                    gizmoStateController.getCamPitch(),
                    gizmoStateController.getCamYaw(),
                    gizmoStateController.getCamPanX(),
                    gizmoStateController.getCamPanY(),
                    gizmoStateController.getCamZoom()
                );
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

            // Check Viewport Panel Header Tab Clicks (-0.55f to 0.55f, 0.86f to 0.92f)
            if (ndcY >= 0.86f && ndcY <= 0.92f && ndcX >= -0.55f && ndcX <= 0.55f) {
                if (ndcX >= -0.53f && ndcX <= -0.33f) {
                    UISoundPlayer.play(UISound.CLICK);
                    workspace.setActiveWorkspaceTab(EditorWorkspace.WorkspaceTab.SCENE_3D);
                    return true;
                } else if (ndcX >= -0.31f && ndcX <= -0.09f) {
                    UISoundPlayer.play(UISound.CLICK);
                    workspace.setActiveWorkspaceTab(EditorWorkspace.WorkspaceTab.ANIMATOR);
                    return true;
                }
            }

            if (ndcY >= 0.92f) {
                MainMenuBarInputDelegate.processTopBarClick(ndcX, workspace);
                return true;
            }

            if (workspace.getActiveWorkspaceTab() == EditorWorkspace.WorkspaceTab.ANIMATOR) {
                if (region == PanelRegion.ASSET_DIRECTORY) {
                    AssetBrowserInputDelegate.processAssetDirectoryClick(ndcX, ndcY, workspace);
                    return true;
                }
                return AnimatorGraphPanel.handleMouseClick(ndcX, ndcY, button, action, workspace);
            }

            switch (region) {
                case TOP_BAR -> {
                    MainMenuBarInputDelegate.processTopBarClick(ndcX, workspace);
                    return true;
                }
                case ASSET_DIRECTORY -> {
                    AssetBrowserInputDelegate.processAssetDirectoryClick(ndcX, ndcY, workspace);
                    return true;
                }
                case SCENE_HIERARCHY -> {
                    AssetBrowserInputDelegate.processHierarchyClick(ndcY, workspace);
                    return true;
                }
                case INSPECTOR -> {
                    InspectorInputDelegate.processInspectorClick(ndcX, ndcY, workspace);
                    return true;
                }
                case VIEWPORT -> {
                    if (gizmoStateController.processViewportToolbarClick(ndcX, ndcY)) {
                        return true;
                    }
                    gizmoStateController.processViewportGizmoClick(refX, refY, workspace);
                    return gizmoStateController.getActiveAxis() != EditorWorkspace.GizmoAxis.NONE;
                }
                default -> {
                    return false;
                }
            }
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

            if (workspace.getActiveWorkspaceTab() == EditorWorkspace.WorkspaceTab.ANIMATOR) {
                return AnimatorGraphPanel.handleMouseClick(ndcX, ndcY, button, action, workspace);
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

    public boolean onCursorPos(double rawX, double rawY) {
        if (workspace == null) return false;
        PointerCoordinates coords = calculateCoordinates(rawX, rawY);
        if (coords != null) {
            workspace.setLastNdcPos(coords.ndcX, coords.ndcY);

            if (workspace.getActiveWorkspaceTab() == EditorWorkspace.WorkspaceTab.ANIMATOR) {
                AnimatorGraphPanel.handleCursorPos(coords.ndcX, coords.ndcY, workspace);
            } else {
                InspectorInputDelegate.processInspectorDrag(coords.ndcX, coords.ndcY, workspace);
            }
        }
        return workspace.getGizmoStateController().onCursorPos(rawX, rawY, workspace, workspace.getOverlayManager());
    }

    public boolean handleScroll(double yOffset) {
        if (workspace == null) return false;
        float lastNdcX = workspace.getLastNdcX();
        float lastNdcY = workspace.getLastNdcY();

        if (lastNdcX >= 0.55f && lastNdcY >= -0.50f && lastNdcY <= 0.88f) {
            InspectorPanel.scroll((float) -yOffset * 0.18f);
            return true;
        }
        if (workspace.getActiveWorkspaceTab() == EditorWorkspace.WorkspaceTab.SCENE_3D &&
            lastNdcX >= -0.55f && lastNdcX <= 0.55f && lastNdcY >= -0.50f && lastNdcY <= 0.86f) {
            if (cameraController != null) {
                cameraController.onScroll(yOffset);
                return true;
            }
        }
        return false;
    }
}
