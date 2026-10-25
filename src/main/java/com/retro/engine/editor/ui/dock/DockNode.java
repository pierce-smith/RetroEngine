package com.retro.engine.editor.ui.dock;

import java.util.ArrayList;
import java.util.List;

/**
 * Node of the dock layout tree: either a split (two children side by side or stacked, divided at
 * a ratio) or a leaf holding a stack of tabbed panels with one active tab.
 */
final class DockNode {

    final boolean leaf;

    // Split node
    boolean horizontal;   // true: first | second side by side; false: first above second
    float ratio;          // fraction of the extent given to the first child
    DockNode first;
    DockNode second;

    // Leaf node
    final List<DockPanel> tabs;
    int activeTab;

    // Layout cache (NDC), rebuilt every frame
    float x0, y0, x1, y1;

    private DockNode(boolean leaf) {
        this.leaf = leaf;
        this.tabs = leaf ? new ArrayList<>(4) : null;
    }

    static DockNode leaf(DockPanel... panels) {
        DockNode node = new DockNode(true);
        for (DockPanel p : panels) node.tabs.add(p);
        return node;
    }

    static DockNode split(boolean horizontal, float ratio, DockNode first, DockNode second) {
        DockNode node = new DockNode(false);
        node.horizontal = horizontal;
        node.ratio = ratio;
        node.first = first;
        node.second = second;
        return node;
    }

    DockPanel activePanel() {
        if (!leaf || tabs.isEmpty()) return null;
        if (activeTab < 0 || activeTab >= tabs.size()) activeTab = 0;
        return tabs.get(activeTab);
    }

    float tabBarBottom() {
        return y1 - DockSpace.TAB_BAR_H;
    }
}
