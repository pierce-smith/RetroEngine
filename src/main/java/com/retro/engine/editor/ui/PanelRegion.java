package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.EditorWorkspace.ModalState;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.OverlaySystem;

/**
 * PanelRegion defines explicit screen-space NDC bounds for all editor panels,
 * providing clean regional hit-testing and preventing click/drag bleed
 * between UI panels and the 3D viewport canvas.
 */
public enum PanelRegion {
    TOP_BAR,
    SCENE_HIERARCHY,
    INSPECTOR,
    ASSET_DIRECTORY,
    VIEWPORT,
    OVERLAY_MODAL;

    /**
     * Determines which panel region contains the given NDC coordinate (X, Y in [-1.0, 1.0]).
     */
    public static PanelRegion getRegionAt(float ndcX, float ndcY, OverlaySystem overlayManager) {
        if (overlayManager != null) {
            if (overlayManager.getActiveModal() != EditorWorkspace.ModalState.NONE ||
                overlayManager.getActiveDropdownMenu() != EditorWorkspace.DropdownMenu.NONE ||
                overlayManager.isContextMenuVisible()) {
                return OVERLAY_MODAL;
            }
        }

        if (ndcY >= 0.92f) {
            return TOP_BAR;
        }
        if (ndcY <= -0.42f) {
            return ASSET_DIRECTORY;
        }
        if (ndcX <= -0.55f) {
            return SCENE_HIERARCHY;
        }
        if (ndcX >= 0.55f) {
            return INSPECTOR;
        }
        return VIEWPORT;
    }
}
