package com.retro.engine.editor.panels;

import com.retro.engine.editor.EditorTheme;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Master docking workspace covering the full editor viewport below the main menu bar.
 * Owns the dock tree (splits + tabbed leaves), computes every panel's on-screen tile, renders tab
 * bars / splitters / drop previews, and handles tab activation, tab drag-and-drop re-docking
 * (stack, or split left/right/top/bottom) and splitter resizing.
 *
 * One instance per editor UI, owned through {@link com.retro.engine.editor.UIContext}; not thread-safe.
 * Panels keep rendering and hit-testing in their authored design-space NDC: rendering goes through
 * {@link #beginPanel}/{@link #endPanel} (a batch transform + clip), and input is mapped back with
 * {@link #toDesignX}/{@link #toDesignY}.
 */
public final class DockSpace {

    public static final float AREA_X0 = -1.0f, AREA_Y0 = -1.0f, AREA_X1 = 1.0f, AREA_Y1 = 0.92f;
    static final float TAB_BAR_H = 0.05f;

    private static final float GAP = 0.006f;
    private static final float SPLITTER_GRAB = 0.014f;
    private static final float MIN_EXTENT = 0.12f;
    private static final float DRAG_THRESHOLD = 0.02f;
    private static final float TAB_TEXT_SIZE = 0.95f;
    private static final float MIN_RENDERABLE = 0.04f;

    public enum DropZone { CENTER, LEFT, RIGHT, TOP, BOTTOM }

    private static final int PANEL_COUNT = DockPanel.values().length;
    private final boolean[] visible = new boolean[PANEL_COUNT];
    private final float[] contentX0 = new float[PANEL_COUNT];
    private final float[] contentY0 = new float[PANEL_COUNT];
    private final float[] contentX1 = new float[PANEL_COUNT];
    private final float[] contentY1 = new float[PANEL_COUNT];

    private final List<DockNode> leaves = new ArrayList<>();
    private final List<DockNode> splits = new ArrayList<>();

    private DockNode root;
    private int windowW = 1280;
    private int windowH = 720;

    // Interaction state
    private DockNode draggingSplit = null;
    private DockPanel draggingTab = null;
    private boolean tabDragActive = false;
    private float pressX, pressY;
    private float cursorX, cursorY;

    // Scene <-> Animator focus, console tab sync, and input-space context
    private DockPanel focusedWorkspace = DockPanel.SCENE;
    private boolean lastConsoleFlag = false;
    private DockPanel consoleReturnPanel = DockPanel.ASSETS;
    private DockPanel inputPanel = null;

    // Measures tab captions with the window metrics of the buffer the dock chrome is drawn into
    private final UIGeometryBatch.Buffer measureBuffer;

    public DockSpace(UIGeometryBatch.Buffer measureBuffer) {
        this.measureBuffer = measureBuffer;
        for (DockPanel p : DockPanel.values()) {
            int i = p.ordinal();
            contentX0[i] = p.designX0; contentY0[i] = p.designY0;
            contentX1[i] = p.designX1; contentY1[i] = p.designY1;
        }
        root = buildDefaultLayout();
    }

    /** Default layout reproduces the classic editor arrangement, with every panel now dockable. */
    private DockNode buildDefaultLayout() {
        float areaH = AREA_Y1 - AREA_Y0;
        DockNode left = DockNode.leaf(DockPanel.HIERARCHY, DockPanel.ANIMATOR_PARAMETERS);
        DockNode center = DockNode.leaf(DockPanel.SCENE, DockPanel.ANIMATOR);
        DockNode right = DockNode.leaf(DockPanel.INSPECTOR, DockPanel.ANIMATOR_INSPECTOR);
        DockNode bottom = DockNode.leaf(DockPanel.ASSETS, DockPanel.CONSOLE);

        DockNode centerRight = DockNode.split(true, 1.10f / 1.55f, center, right);
        DockNode top = DockNode.split(true, 0.45f / 2.0f, left, centerRight);
        return DockNode.split(false, (AREA_Y1 - (-0.42f)) / areaH, top, bottom);
    }

    public void resetLayout() {
        root = buildDefaultLayout();
        draggingSplit = null;
        draggingTab = null;
        tabDragActive = false;
        focusedWorkspace = DockPanel.SCENE;
        consoleReturnPanel = DockPanel.ASSETS;
        EngineConsolePanel.setConsoleVisible(false);
        lastConsoleFlag = false;
    }

    // ------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------

    /** Recomputes every tile for this frame. Call once per frame before rendering or routing input. */
    public void layout(int winW, int winH) {
        if (winW > 0) windowW = winW;
        if (winH > 0) windowH = winH;

        syncConsoleVisibility();

        leaves.clear();
        splits.clear();
        Arrays.fill(visible, false);
        layoutNode(root, AREA_X0, AREA_Y0, AREA_X1, AREA_Y1);

        // Keep Scene/Animator focus on a panel the user can actually see
        DockPanel other = (focusedWorkspace == DockPanel.SCENE) ? DockPanel.ANIMATOR : DockPanel.SCENE;
        if (!visible[focusedWorkspace.ordinal()] && visible[other.ordinal()]) focusedWorkspace = other;
    }

    private void layoutNode(DockNode n, float x0, float y0, float x1, float y1) {
        n.x0 = x0; n.y0 = y0; n.x1 = x1; n.y1 = y1;
        if (n.leaf) {
            leaves.add(n);
            DockPanel p = n.activePanel();
            if (p != null) {
                int i = p.ordinal();
                visible[i] = true;
                contentX0[i] = x0; contentY0[i] = y0;
                contentX1[i] = x1; contentY1[i] = Math.max(y0, y1 - TAB_BAR_H);
            }
            return;
        }
        splits.add(n);
        if (n.horizontal) {
            float line = x0 + (x1 - x0) * n.ratio;
            layoutNode(n.first, x0, y0, line - GAP * 0.5f, y1);
            layoutNode(n.second, line + GAP * 0.5f, y0, x1, y1);
        } else {
            float line = y1 - (y1 - y0) * n.ratio;
            layoutNode(n.first, x0, line + GAP * 0.5f, x1, y1);
            layoutNode(n.second, x0, y0, x1, line - GAP * 0.5f);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Panel queries & coordinate mapping
    // ------------------------------------------------------------------------------------------

    public boolean isVisible(DockPanel p) {
        return visible[p.ordinal()];
    }

    /** Visible and large enough to draw meaningful content. */
    public boolean isRenderable(DockPanel p) {
        int i = p.ordinal();
        return visible[i] && (contentX1[i] - contentX0[i]) > MIN_RENDERABLE && (contentY1[i] - contentY0[i]) > MIN_RENDERABLE;
    }

    public float getContentX0(DockPanel p) { return contentX0[p.ordinal()]; }
    public float getContentY0(DockPanel p) { return contentY0[p.ordinal()]; }
    public float getContentX1(DockPanel p) { return contentX1[p.ordinal()]; }
    public float getContentY1(DockPanel p) { return contentY1[p.ordinal()]; }

    public float toDesignX(DockPanel p, float screenX) {
        int i = p.ordinal();
        float w = contentX1[i] - contentX0[i];
        if (w <= 0.0001f) return p.designX0;
        return p.designX0 + (screenX - contentX0[i]) * (p.designX1 - p.designX0) / w;
    }

    public float toDesignY(DockPanel p, float screenY) {
        int i = p.ordinal();
        float h = contentY1[i] - contentY0[i];
        if (h <= 0.0001f) return p.designY0;
        return p.designY0 + (screenY - contentY0[i]) * (p.designY1 - p.designY0) / h;
    }

    public float toScreenX(DockPanel p, float designX) {
        int i = p.ordinal();
        return contentX0[i] + (designX - p.designX0) * (contentX1[i] - contentX0[i]) / (p.designX1 - p.designX0);
    }

    public float toScreenY(DockPanel p, float designY) {
        int i = p.ordinal();
        return contentY0[i] + (designY - p.designY0) * (contentY1[i] - contentY0[i]) / (p.designY1 - p.designY0);
    }

    /** Panel whose content area (excluding tab bar) contains the point, or null. */
    public DockPanel panelAt(float x, float y) {
        for (int i = 0; i < leaves.size(); i++) {
            DockNode leaf = leaves.get(i);
            DockPanel p = leaf.activePanel();
            if (p == null) continue;
            int k = p.ordinal();
            if (x >= contentX0[k] && x <= contentX1[k] && y >= contentY0[k] && y <= contentY1[k]) return p;
        }
        return null;
    }

    public void beginPanel(UIGeometryBatch.Buffer buffer, DockPanel p) {
        int i = p.ordinal();
        buffer.setPanelMapping(p.designX0, p.designY0, p.designX1, p.designY1,
                contentX0[i], contentY0[i], contentX1[i], contentY1[i]);
    }

    public void endPanel(UIGeometryBatch.Buffer buffer) {
        buffer.clearPanelMapping();
    }

    /**
     * Marks input as being dispatched in a panel's design space, so code that opens screen-space
     * overlays (context menus) from inside a panel handler can convert back to screen coordinates.
     */
    public void beginInput(DockPanel p) { inputPanel = p; }
    public void endInput() { inputPanel = null; }
    public float inputToScreenX(float x) { return inputPanel == null ? x : toScreenX(inputPanel, x); }
    public float inputToScreenY(float y) { return inputPanel == null ? y : toScreenY(inputPanel, y); }

    // ------------------------------------------------------------------------------------------
    // Scene viewport (3D offscreen image placement)
    // ------------------------------------------------------------------------------------------

    public boolean isSceneViewportVisible() {
        return isRenderable(DockPanel.SCENE);
    }

    /** Pixel aspect of the docked Scene tile; the projection uses it so the 3D image is never distorted. */
    public float getSceneAspect() {
        int i = DockPanel.SCENE.ordinal();
        float w = (contentX1[i] - contentX0[i]) * 0.5f * windowW;
        float h = (contentY1[i] - contentY0[i]) * 0.5f * windowH;
        return (w > 1.0f && h > 1.0f) ? (w / h) : (712.0f / 488.0f);
    }

    // ------------------------------------------------------------------------------------------
    // Activation, workspace focus, console sync
    // ------------------------------------------------------------------------------------------

    public DockPanel getFocusedWorkspace() {
        return focusedWorkspace;
    }

    /**
     * Activates a panel's tab. Scene and Animator also swap their stacked companions, matching the
     * classic editor: Scene shows Hierarchy/Inspector, Animator shows Parameters/Anim Inspector.
     */
    public void activate(DockPanel p) {
        if (!activateTab(p)) return;
        if (p == DockPanel.SCENE) {
            focusedWorkspace = DockPanel.SCENE;
            swapCompanion(DockPanel.ANIMATOR_PARAMETERS, DockPanel.HIERARCHY);
            swapCompanion(DockPanel.ANIMATOR_INSPECTOR, DockPanel.INSPECTOR);
        } else if (p == DockPanel.ANIMATOR) {
            focusedWorkspace = DockPanel.ANIMATOR;
            swapCompanion(DockPanel.HIERARCHY, DockPanel.ANIMATOR_PARAMETERS);
            swapCompanion(DockPanel.INSPECTOR, DockPanel.ANIMATOR_INSPECTOR);
        }
    }

    /** If 'from' is the shown tab of its stack, show 'to' instead (only when they share that stack). */
    private void swapCompanion(DockPanel from, DockPanel to) {
        DockNode leaf = findLeaf(from);
        if (leaf != null && leaf.activePanel() == from && leaf.tabs.contains(to)) {
            leaf.activeTab = leaf.tabs.indexOf(to);
        }
    }

    private boolean activateTab(DockPanel p) {
        DockNode leaf = findLeaf(p);
        if (leaf == null) return false;
        if (p == DockPanel.CONSOLE && leaf.activePanel() != DockPanel.CONSOLE && leaf.activePanel() != null) {
            consoleReturnPanel = leaf.activePanel();
        }
        leaf.activeTab = leaf.tabs.indexOf(p);
        return true;
    }

    /**
     * Keeps EngineConsolePanel's visibility flag (toggled by the Cmd+~ hotkey) and the Console tab in
     * agreement: a hotkey toggle shows/hides the tab, and tab clicks update the flag.
     */
    private void syncConsoleVisibility() {
        DockNode leaf = findLeaf(DockPanel.CONSOLE);
        if (leaf == null) return;
        boolean flag = EngineConsolePanel.isConsoleVisible();
        boolean shown = leaf.activePanel() == DockPanel.CONSOLE;

        if (flag != lastConsoleFlag) {
            if (flag && !shown) {
                activateTab(DockPanel.CONSOLE);
            } else if (!flag && shown && leaf.tabs.size() > 1) {
                DockPanel back = leaf.tabs.contains(consoleReturnPanel) && consoleReturnPanel != DockPanel.CONSOLE
                        ? consoleReturnPanel
                        : leaf.tabs.get(leaf.tabs.get(0) == DockPanel.CONSOLE ? 1 : 0);
                leaf.activeTab = leaf.tabs.indexOf(back);
            }
            shown = leaf.activePanel() == DockPanel.CONSOLE;
        }
        EngineConsolePanel.setConsoleVisible(shown);
        lastConsoleFlag = shown;
    }

    // ------------------------------------------------------------------------------------------
    // Input: tab clicks, tab drag & drop, splitter resize
    // ------------------------------------------------------------------------------------------

    public boolean isDragging() {
        return draggingSplit != null || (draggingTab != null && tabDragActive);
    }

    /** Left press on dock chrome (tab bars, splitters). Returns true if consumed. */
    public boolean onPress(float x, float y) {
        if (x < AREA_X0 || x > AREA_X1 || y < AREA_Y0 || y > AREA_Y1) return false;

        DockNode split = splitterAt(x, y);
        if (split != null) {
            draggingSplit = split;
            return true;
        }

        DockNode leaf = leafAt(x, y);
        if (leaf != null && y >= leaf.tabBarBottom()) {
            int tab = tabIndexAt(leaf, x);
            if (tab >= 0) {
                DockPanel p = leaf.tabs.get(tab);
                if (leaf.activePanel() != p) UISoundPlayer.play(UISound.CLICK);
                activate(p);
                draggingTab = p;
                tabDragActive = false;
                pressX = x;
                pressY = y;
            }
            return true; // empty tab-bar space is still chrome
        }
        return false;
    }

    /** Cursor move; returns true while a dock drag owns the pointer. */
    public boolean onCursor(float x, float y) {
        cursorX = x;
        cursorY = y;
        if (draggingSplit != null) {
            resizeSplit(draggingSplit, x, y);
            return true;
        }
        if (draggingTab != null) {
            if (!tabDragActive && Math.hypot(x - pressX, y - pressY) > DRAG_THRESHOLD) tabDragActive = true;
            return true;
        }
        return false;
    }

    /** Left release; completes splitter drags and tab drops. Returns true if a dock interaction ended. */
    public boolean onRelease(float x, float y) {
        if (draggingSplit != null) {
            draggingSplit = null;
            return true;
        }
        if (draggingTab != null) {
            DockPanel panel = draggingTab;
            boolean wasDragging = tabDragActive;
            draggingTab = null;
            tabDragActive = false;
            if (wasDragging) {
                DockNode target = leafAt(x, y);
                if (target != null) {
                    dropPanel(panel, target, dropZoneFor(target, x, y));
                    UISoundPlayer.play(UISound.SPAWN);
                }
            }
            return true;
        }
        return false;
    }

    /** Cancels any in-progress dock drag (e.g. when a modal opens). */
    public void cancelDrag() {
        draggingSplit = null;
        draggingTab = null;
        tabDragActive = false;
    }

    private void resizeSplit(DockNode n, float x, float y) {
        float extent = n.horizontal ? (n.x1 - n.x0) : (n.y1 - n.y0);
        if (extent <= 0.0001f) return;
        float ratio = n.horizontal ? (x - n.x0) / extent : (n.y1 - y) / extent;
        float min = Math.min(0.5f, MIN_EXTENT / extent);
        n.ratio = Math.max(min, Math.min(1.0f - min, ratio));
    }

    private DockNode splitterAt(float x, float y) {
        float half = SPLITTER_GRAB * 0.5f;
        for (int i = 0; i < splits.size(); i++) {
            DockNode n = splits.get(i);
            if (n.horizontal) {
                float line = n.x0 + (n.x1 - n.x0) * n.ratio;
                if (Math.abs(x - line) <= half && y >= n.y0 && y <= n.y1) return n;
            } else {
                float line = n.y1 - (n.y1 - n.y0) * n.ratio;
                if (Math.abs(y - line) <= half && x >= n.x0 && x <= n.x1) return n;
            }
        }
        return null;
    }

    private DockNode leafAt(float x, float y) {
        for (int i = 0; i < leaves.size(); i++) {
            DockNode leaf = leaves.get(i);
            if (x >= leaf.x0 && x <= leaf.x1 && y >= leaf.y0 && y <= leaf.y1) return leaf;
        }
        return null;
    }

    private DropZone dropZoneFor(DockNode leaf, float x, float y) {
        if (y >= leaf.tabBarBottom()) return DropZone.CENTER;
        float w = leaf.x1 - leaf.x0;
        float h = leaf.tabBarBottom() - leaf.y0;
        if (w <= 0.0001f || h <= 0.0001f) return DropZone.CENTER;
        float u = (x - leaf.x0) / w;
        float v = (y - leaf.y0) / h;
        float left = u, right = 1.0f - u, bottom = v, top = 1.0f - v;
        float nearest = Math.min(Math.min(left, right), Math.min(bottom, top));
        if (nearest >= 0.25f) return DropZone.CENTER;
        if (nearest == left) return DropZone.LEFT;
        if (nearest == right) return DropZone.RIGHT;
        if (nearest == top) return DropZone.TOP;
        return DropZone.BOTTOM;
    }

    // ------------------------------------------------------------------------------------------
    // Tree mutation
    // ------------------------------------------------------------------------------------------

    private void dropPanel(DockPanel p, DockNode target, DropZone zone) {
        DockNode source = findLeaf(p);
        if (source == null || target == null || !target.leaf) return;

        if (zone == DropZone.CENTER) {
            if (source != target) {
                removePanel(p);
                target.tabs.add(p);
            }
            activate(p);
            return;
        }

        // Splitting a single-tab stack against itself would leave an empty side
        if (source == target && source.tabs.size() == 1) return;

        removePanel(p);
        DockNode newLeaf = DockNode.leaf(p);
        boolean horizontal = (zone == DropZone.LEFT || zone == DropZone.RIGHT);
        boolean newFirst = (zone == DropZone.LEFT || zone == DropZone.TOP);
        DockNode split = DockNode.split(horizontal, 0.5f, newFirst ? newLeaf : target, newFirst ? target : newLeaf);
        replaceNode(target, split);
        activate(p);
    }

    private void removePanel(DockPanel p) {
        DockNode leaf = findLeaf(p);
        if (leaf == null) return;
        int idx = leaf.tabs.indexOf(p);
        leaf.tabs.remove(idx);
        if (idx < leaf.activeTab || leaf.activeTab >= leaf.tabs.size()) {
            leaf.activeTab = Math.max(0, leaf.activeTab - 1);
        }
        if (leaf.tabs.isEmpty() && leaf != root) {
            DockNode parent = findParent(root, leaf);
            if (parent != null) {
                replaceNode(parent, parent.first == leaf ? parent.second : parent.first);
            }
        }
    }

    private void replaceNode(DockNode old, DockNode replacement) {
        if (old == root) {
            root = replacement;
            return;
        }
        DockNode parent = findParent(root, old);
        if (parent == null) return;
        if (parent.first == old) parent.first = replacement; else parent.second = replacement;
    }

    private DockNode findParent(DockNode node, DockNode child) {
        if (node == null || node.leaf) return null;
        if (node.first == child || node.second == child) return node;
        DockNode found = findParent(node.first, child);
        return (found != null) ? found : findParent(node.second, child);
    }

    private DockNode findLeaf(DockPanel p) {
        return findLeaf(root, p);
    }

    private DockNode findLeaf(DockNode node, DockPanel p) {
        if (node == null) return null;
        if (node.leaf) return node.tabs.contains(p) ? node : null;
        DockNode found = findLeaf(node.first, p);
        return (found != null) ? found : findLeaf(node.second, p);
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    private float naturalTabWidth(DockPanel p) {
        return 0.035f + UIGeometryBatch.screenTextWidth(measureBuffer, p.title, TAB_TEXT_SIZE);
    }

    private float tabScale(DockNode leaf) {
        float total = 0.0f;
        for (int i = 0; i < leaf.tabs.size(); i++) total += naturalTabWidth(leaf.tabs.get(i));
        float avail = (leaf.x1 - leaf.x0) - 0.01f;
        return (total > avail && total > 0.0f) ? Math.max(0.0f, avail / total) : 1.0f;
    }

    private int tabIndexAt(DockNode leaf, float x) {
        float scale = tabScale(leaf);
        float tx = leaf.x0 + 0.005f;
        for (int i = 0; i < leaf.tabs.size(); i++) {
            float w = naturalTabWidth(leaf.tabs.get(i)) * scale;
            if (x >= tx && x <= tx + w) return i;
            tx += w;
        }
        return -1;
    }

    /** Opaque backing for every non-scene tile, drawn before panel content. */
    public void renderBackgrounds(UIGeometryBatch.Buffer buffer) {
        for (int i = 0; i < leaves.size(); i++) {
            DockNode leaf = leaves.get(i);
            if (leaf.activePanel() == DockPanel.SCENE) continue;
            UIGeometryBatch.addQuad(buffer, leaf.x0, leaf.y0, leaf.x1, leaf.tabBarBottom(), EditorTheme.SURFACE, 1.0f);
        }
    }

    /** Tab bars, tile frames and splitters, drawn after panel content. */
    public void renderChrome(UIGeometryBatch.Buffer buffer) {
        DockNode hoverSplit = (draggingSplit != null) ? draggingSplit : (draggingTab == null ? splitterAt(cursorX, cursorY) : null);

        // Splitters read as the charcoal window background showing between tiles; accent while grabbed/hovered
        for (int i = 0; i < splits.size(); i++) {
            DockNode n = splits.get(i);
            boolean hot = (n == hoverSplit);
            int color = hot ? EditorTheme.ACCENT : EditorTheme.BACKGROUND;
            if (n.horizontal) {
                float line = n.x0 + (n.x1 - n.x0) * n.ratio;
                UIGeometryBatch.addQuad(buffer, line - GAP * 0.5f, n.y0, line + GAP * 0.5f, n.y1, color, 1.0f);
            } else {
                float line = n.y1 - (n.y1 - n.y0) * n.ratio;
                UIGeometryBatch.addQuad(buffer, n.x0, line - GAP * 0.5f, n.x1, line + GAP * 0.5f, color, 1.0f);
            }
        }

        DockNode hoverLeaf = (draggingTab == null && hoverSplit == null) ? leafAt(cursorX, cursorY) : null;

        for (int i = 0; i < leaves.size(); i++) {
            DockNode leaf = leaves.get(i);
            float barY0 = leaf.tabBarBottom(), barY1 = leaf.y1;

            // Toolbar-style tab strip with a hairline separating it from the panel surface
            UIGeometryBatch.addQuad(buffer, leaf.x0, barY0, leaf.x1, barY1, EditorTheme.BACKGROUND, 1.0f);
            UIGeometryBatch.addQuad(buffer, leaf.x0, barY0, leaf.x1, barY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

            int hoverTab = (leaf == hoverLeaf && cursorY >= barY0) ? tabIndexAt(leaf, cursorX) : -1;
            float scale = tabScale(leaf);
            float tx = leaf.x0 + 0.005f;
            for (int t = 0; t < leaf.tabs.size(); t++) {
                DockPanel p = leaf.tabs.get(t);
                float w = naturalTabWidth(p) * scale;
                boolean active = (t == leaf.activeTab);
                boolean beingDragged = (p == draggingTab && tabDragActive);
                float alpha = beingDragged ? 0.4f : 1.0f;

                float px0 = tx + 0.003f, px1 = tx + w - 0.003f;
                float py0 = barY0 + 0.009f, py1 = barY1 - 0.007f;
                if (px1 > px0) {
                    if (active) {
                        UIGeometryBatch.addPill(buffer, px0, py0, px1, py1, EditorTheme.BORDER, alpha);
                        // High-contrast accent indicator under the active pill
                        float inset = Math.min(0.012f, (px1 - px0) * 0.25f);
                        UIGeometryBatch.addPill(buffer, px0 + inset, barY0 + 0.003f, px1 - inset, barY0 + 0.0065f, EditorTheme.ACCENT, alpha);
                    } else if (t == hoverTab) {
                        UIGeometryBatch.addPill(buffer, px0, py0, px1, py1, EditorTheme.SURFACE_RAISED, alpha);
                    }
                }
                int tc = active ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED;
                UIGeometryBatch.addBoundedText(buffer, p.title, tx + 0.015f, barY1 - 0.0165f, tx + w - 0.006f, TAB_TEXT_SIZE, tc, alpha);
                tx += w;
            }

            // Right-aligned caption for the active panel (e.g. which RHI backend renders the Scene)
            String caption = captionFor(leaf.activePanel());
            if (caption != null) {
                float capSize = 0.85f;
                float capW = UIGeometryBatch.screenTextWidth(measureBuffer, caption, capSize);
                float capX = Math.max(tx + 0.02f, leaf.x1 - 0.012f - capW);
                if (capX + 0.05f < leaf.x1) {
                    UIGeometryBatch.addBoundedText(buffer, caption, capX, barY1 - 0.019f, leaf.x1 - 0.008f, capSize, EditorTheme.TEXT_MUTED, 1.0f);
                }
            }

            UIGeometryBatch.addFrameBorder(buffer, leaf.x0, leaf.y0, leaf.x1, barY1, EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        }
    }

    private String captionFor(DockPanel p) {
        if (p == DockPanel.SCENE) return ViewportPanel.getRhiCanvasTitle();
        if (p == DockPanel.ANIMATOR) return "STATE MACHINE";
        return null;
    }

    /** Drop-zone preview and dragged-tab ghost, drawn above everything docked. */
    public void renderDragOverlay(UIGeometryBatch.Buffer buffer) {
        if (draggingTab == null || !tabDragActive) return;

        DockNode target = leafAt(cursorX, cursorY);
        if (target != null) {
            DropZone zone = dropZoneFor(target, cursorX, cursorY);
            float x0 = target.x0, y0 = target.y0, x1 = target.x1, y1 = target.y1;
            float midX = (x0 + x1) * 0.5f, midY = (y0 + target.tabBarBottom()) * 0.5f;
            switch (zone) {
                case LEFT -> x1 = midX;
                case RIGHT -> x0 = midX;
                case TOP -> y0 = midY;
                case BOTTOM -> y1 = midY;
                default -> {}
            }
            float inset = 0.006f;
            UIGeometryBatch.addRoundedQuad(buffer, x0 + inset, y0 + inset, x1 - inset, y1 - inset, EditorTheme.RADIUS * 1.5f, EditorTheme.ACCENT, 0.22f);
            UIGeometryBatch.addFrameBorder(buffer, x0 + inset, y0 + inset, x1 - inset, y1 - inset, 0.003f, EditorTheme.ACCENT, 0.9f);
        }

        float gw = naturalTabWidth(draggingTab);
        float gx = cursorX + 0.01f, gy = cursorY - 0.01f;
        UIGeometryBatch.addPill(buffer, gx, gy - TAB_BAR_H + 0.012f, gx + gw, gy, EditorTheme.ACCENT, 0.95f);
        UIGeometryBatch.addBoundedText(buffer, draggingTab.title, gx + 0.015f, gy - 0.011f, gx + gw - 0.006f, TAB_TEXT_SIZE, EditorTheme.TEXT, 1.0f);
    }
}
