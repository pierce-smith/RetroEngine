package com.retro.engine.editor.ui.dock;

import com.retro.engine.editor.ui.batch.PanelRegion;

/**
 * Registry of dockable editor panels. Each panel keeps the fixed NDC rect its rendering and
 * hit-testing code was authored against (its "design rect"); the dock maps that rect onto
 * whatever tile the user docks it into, so panel internals need no layout changes.
 */
public enum DockPanel {
    SCENE("SCENE", -0.55f, -0.50f, 0.55f, 0.88f, PanelRegion.VIEWPORT),
    ANIMATOR("ANIMATOR", -0.55f, -0.50f, 0.55f, 0.86f, PanelRegion.ANIMATOR),
    HIERARCHY("HIERARCHY", -1.00f, -0.50f, -0.55f, 0.88f, PanelRegion.SCENE_HIERARCHY),
    INSPECTOR("INSPECTOR", 0.55f, -0.50f, 1.00f, 0.88f, PanelRegion.INSPECTOR),
    ANIMATOR_PARAMETERS("PARAMETERS", -1.00f, -0.50f, -0.55f, 0.86f, PanelRegion.ANIMATOR),
    ANIMATOR_INSPECTOR("ANIM INSPECTOR", 0.55f, -0.50f, 1.00f, 0.86f, PanelRegion.ANIMATOR),
    ASSETS("PROJECT", -1.00f, -1.00f, 1.00f, -0.42f, PanelRegion.ASSET_DIRECTORY),
    CONSOLE("CONSOLE", -0.85f, -0.85f, 0.85f, -0.20f, PanelRegion.CONSOLE);

    public final String title;
    public final float designX0, designY0, designX1, designY1;
    public final PanelRegion region;

    DockPanel(String title, float x0, float y0, float x1, float y1, PanelRegion region) {
        this.title = title;
        this.designX0 = x0;
        this.designY0 = y0;
        this.designX1 = x1;
        this.designY1 = y1;
        this.region = region;
    }
}
