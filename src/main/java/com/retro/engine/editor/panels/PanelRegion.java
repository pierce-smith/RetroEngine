package com.retro.engine.editor.panels;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.overlays.OverlaySystem;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;

/**
 * PanelRegion classifies screen-space NDC points by the editor panel under them,
 * providing clean regional hit-testing and preventing click/drag bleed
 * between UI panels and the 3D viewport canvas.
 */
public enum PanelRegion {
    TOP_BAR,
    SCENE_HIERARCHY,
    INSPECTOR,
    ASSET_DIRECTORY,
    VIEWPORT,
    ANIMATOR,
    CONSOLE,
    DOCK_CHROME,
    OVERLAY_MODAL;

    /**
     * Determines which panel region contains the given screen NDC coordinate (X, Y in [-1.0, 1.0]).
     * Panels are resolved through the dock layout, so regions follow panels wherever they are docked;
     * tab bars and splitters report DOCK_CHROME.
     */
    public static PanelRegion getRegionAt(float ndcX, float ndcY, OverlaySystem overlayManager, DockSpace dock) {
        if (overlayManager != null) {
            if (overlayManager.getActiveModal() != EditorWorkspace.ModalState.NONE ||
                overlayManager.getActiveDropdownMenu() != EditorWorkspace.DropdownMenu.NONE ||
                overlayManager.isContextMenuVisible()) {
                return OVERLAY_MODAL;
            }
        }

        if (ndcY >= DockSpace.AREA_Y1) {
            return TOP_BAR;
        }
        DockPanel panel = dock.panelAt(ndcX, ndcY);
        return (panel != null) ? panel.region : DOCK_CHROME;
    }
}
