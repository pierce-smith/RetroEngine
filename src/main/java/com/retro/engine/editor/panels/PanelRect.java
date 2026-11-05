package com.retro.engine.editor.panels;

/**
 * The tile a docked panel currently occupies, in screen NDC (below its tab bar). {@link DockSpace#layout} rewrites it
 * every frame before rendering and input, so a panel that lays itself out from this rect (rather than from fixed
 * coordinates) adapts to any dock position: narrow side columns, shallow bottom strips, or large center tiles.
 * Render and hit-test code must derive geometry from the same rect so they always agree.
 */
public final class PanelRect {

    public float x0, y0, x1, y1;
    /** False while the panel is not the shown tab of any dock leaf; its coordinates are then stale. */
    public boolean visible = true;

    PanelRect(float x0, float y0, float x1, float y1) {
        set(x0, y0, x1, y1);
    }

    void set(float x0, float y0, float x1, float y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    public float width() { return x1 - x0; }
    public float height() { return y1 - y0; }
    public float centerX() { return (x0 + x1) * 0.5f; }
    public float centerY() { return (y0 + y1) * 0.5f; }

    /** Point inside the tile; always false for a hidden panel, so stale rects never capture input. */
    public boolean contains(float x, float y) {
        return visible && x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }
}
