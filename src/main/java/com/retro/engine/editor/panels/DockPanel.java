package com.retro.engine.editor.panels;


/**
 * Registry of dockable editor panels. Panels lay themselves out from {@link #rect}, the tile they currently occupy,
 * so they reflow to any dock position and are drawn and hit-tested in plain screen NDC. The design rect is each
 * panel's home in the classic layout (the initial rect) and, for the Scene viewport only, the space its renderer
 * is authored in and stretched from.
 */
public enum DockPanel {
    SCENE("SCENE", -0.55f, -0.50f, 0.55f, 0.88f, PanelRegion.VIEWPORT, false),
    ANIMATOR("ANIMATOR", -0.55f, -0.50f, 0.55f, 0.86f, PanelRegion.ANIMATOR),
    HIERARCHY("HIERARCHY", -1.00f, -0.50f, -0.55f, 0.88f, PanelRegion.SCENE_HIERARCHY),
    INSPECTOR("INSPECTOR", 0.55f, -0.50f, 1.00f, 0.88f, PanelRegion.INSPECTOR),
    ANIMATOR_PARAMETERS("PARAMETERS", -1.00f, -0.50f, -0.55f, 0.86f, PanelRegion.ANIMATOR),
    ANIMATOR_INSPECTOR("ANIM INSPECTOR", 0.55f, -0.50f, 1.00f, 0.86f, PanelRegion.ANIMATOR),
    ASSETS("PROJECT", -1.00f, -1.00f, 1.00f, -0.42f, PanelRegion.ASSET_DIRECTORY),
    CONSOLE("CONSOLE", -0.85f, -0.85f, 0.85f, -0.20f, PanelRegion.CONSOLE),
    NAVIGATION("NAVIGATION", 0.55f, -0.50f, 1.00f, 0.88f, PanelRegion.NAVIGATION);

    public final String title;
    public final float designX0, designY0, designX1, designY1;
    public final PanelRegion region;
    /** True when the panel lays itself out from {@link #rect}; false keeps the legacy stretch from the design rect. */
    public final boolean reflows;
    /** Current tile in screen NDC, rewritten by {@link DockSpace#layout} every frame. */
    public final PanelRect rect;

    DockPanel(String title, float x0, float y0, float x1, float y1, PanelRegion region) {
        this(title, x0, y0, x1, y1, region, true);
    }

    DockPanel(String title, float x0, float y0, float x1, float y1, PanelRegion region, boolean reflows) {
        this.reflows = reflows;
        this.rect = new PanelRect(x0, y0, x1, y1);
        this.title = title;
        this.designX0 = x0;
        this.designY0 = y0;
        this.designX1 = x1;
        this.designY1 = y1;
        this.region = region;
    }
}
