package com.retro.engine.editor.panels;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.UIContext;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * HierarchyPanel handles rendering the Left Panel (Scene Manager & Indented Scene Graph Tree).
 * Zero-allocation in-place flattened tree building and node pooling.
 */
public class HierarchyPanel {

    public static class HierarchyNode {
        public Entity entity;
        public int depth;
        public boolean hasChildren;

        public HierarchyNode() {
            this.entity = null;
            this.depth = 0;
            this.hasChildren = false;
        }

        public void set(Entity entity, int depth, boolean hasChildren) {
            this.entity = entity;
            this.depth = depth;
            this.hasChildren = hasChildren;
        }
    }

    private static final List<HierarchyNode> nodePool = new ArrayList<>();
    private static final List<HierarchyNode> visibleTreeScratch = new ArrayList<>();
    private static int lastMaxDepth = 0;

    /** Deepest level in the most recent {@link #buildFlattenedTree} result (0 = roots only). */
    public static int getLastMaxDepth() { return lastMaxDepth; }

    /**
     * Flattens the scene graph into display order: each root, then (when expanded) its children recursively to any
     * depth. Sibling order is scene-list order. Entities whose parent is missing are shown as roots.
     */
    public static List<HierarchyNode> buildFlattenedTree(List<Entity> sceneEntities) {
        visibleTreeScratch.clear();
        lastMaxDepth = 0;
        if (sceneEntities == null || sceneEntities.isEmpty()) return visibleTreeScratch;

        int poolIdx = 0;
        // Collect top-level entities
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity entity = sceneEntities.get(i);
            if (entity == null) continue;
            if (entity.parentId == null || entity.parentId.trim().isEmpty() || findEntity(sceneEntities, entity.parentId) == null) {
                poolIdx = appendNodeAndVisibleChildren(entity, 0, sceneEntities, poolIdx);
            }
        }
        return visibleTreeScratch;
    }

    private static int appendNodeAndVisibleChildren(Entity entity, int depth, List<Entity> allEntities, int poolIdx) {
        boolean hasChildren = hasAnyChild(entity, allEntities);

        HierarchyNode node;
        if (poolIdx < nodePool.size()) {
            node = nodePool.get(poolIdx);
        } else {
            node = new HierarchyNode();
            nodePool.add(node);
        }
        node.set(entity, depth, hasChildren);
        visibleTreeScratch.add(node);
        if (depth > lastMaxDepth) lastMaxDepth = depth;
        poolIdx++;

        // A node can only be reached through its single parent, so recursion always terminates (a parent loop is
        // never reachable from a root); the depth bound is a backstop against a pathological stack depth
        if (hasChildren && entity.expandedInHierarchy && depth < MAX_TREE_DEPTH) {
            for (int i = 0; i < allEntities.size(); i++) {
                Entity child = allEntities.get(i);
                if (child != null && child != entity && entity.id != null && entity.id.equalsIgnoreCase(child.parentId)) {
                    poolIdx = appendNodeAndVisibleChildren(child, depth + 1, allEntities, poolIdx);
                }
            }
        }
        return poolIdx;
    }

    private static boolean hasAnyChild(Entity parent, List<Entity> allEntities) {
        if (parent == null || parent.id == null || allEntities == null) return false;
        for (int i = 0; i < allEntities.size(); i++) {
            Entity e = allEntities.get(i);
            if (e != null && e != parent && parent.id.equalsIgnoreCase(e.parentId)) {
                return true;
            }
        }
        return false;
    }

    public static Entity findEntity(List<Entity> allEntities, String id) {
        if (id == null || allEntities == null) return null;
        for (int i = 0; i < allEntities.size(); i++) {
            Entity e = allEntities.get(i);
            if (e != null && e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    private static final int MAX_TREE_DEPTH = 512;

    // --- Shared row layout, in screen NDC relative to the docked tile (DockPanel.HIERARCHY.rect).
    // AssetBrowserInputDelegate hit-tests through the same accessors, so render and input always agree.
    public static final float ROW_HEIGHT = 0.075f;
    private static final float TOP_PAD = 0.025f, BOTTOM_PAD = 0.02f;
    private static final float ROW_INSET = 0.015f;
    private static final float INDENT_INSET = 0.035f, INDENT = 0.04f;
    /** Share of the tile width deep trees may spend on indentation, and its absolute cap for wide tiles. */
    private static final float INDENT_BUDGET_FRACTION = 0.6f, INDENT_BUDGET_MAX = 0.5f;
    private static final float GUIDE_OFFSET = 0.006f; // guides sit under the parent's disclosure triangle
    private static final float NAME_TEXT = 1.0f;

    // Scrollbar (pill thumb, Asset Browser style) along the panel's right edge; the hit band is wider than the pill
    private static final float SCROLLBAR_PILL_OUTER = 0.0045f, SCROLLBAR_PILL_INNER = 0.0115f, SCROLLBAR_HIT_W = 0.025f;
    private static final float MIN_THUMB_H = 0.06f;

    private static PanelRect rect() { return DockPanel.HIERARCHY.rect; }
    public static float rowTopEdge() { return rect().y1 - TOP_PAD; }
    public static float viewTop() { return rowTopEdge(); }
    public static float viewBottom() { return rect().y0 + BOTTOM_PAD; }
    private static float rowX0() { return rect().x0 + ROW_INSET; }
    private static float indentX0() { return rect().x0 + INDENT_INSET; }
    private static float indentBudget() { return Math.min(INDENT_BUDGET_MAX, rect().width() * INDENT_BUDGET_FRACTION); }

    private static float scrollY = 0.0f;
    private static float maxScrollY = 0.0f;

    // --- Vertical scroll ---
    public static float getScrollY() { return scrollY; }
    public static void setScrollY(float offset) { scrollY = Math.max(0.0f, Math.min(maxScrollY, offset)); }
    public static void scroll(float delta) { setScrollY(scrollY + delta); }
    public static void resetScroll() { scrollY = 0.0f; }

    /** Recomputes the scroll bound for {@code rowCount} visible rows and re-clamps the offset. */
    public static void updateScrollBounds(int rowCount) {
        maxScrollY = Math.max(0.0f, rowCount * ROW_HEIGHT - (viewTop() - viewBottom()));
        scrollY = Math.max(0.0f, Math.min(maxScrollY, scrollY));
    }

    /** Top edge of flattened-tree row {@code i}, scroll offset applied. */
    public static float rowTop(int i) {
        return rowTopEdge() - i * ROW_HEIGHT + scrollY;
    }

    /**
     * Unclamped row index under {@code ndcY} (may be negative or past the last row), or Integer.MIN_VALUE when the
     * point is outside the clipped view band.
     */
    private static int rawRowAt(float ndcY) {
        if (ndcY > viewTop() || ndcY < viewBottom()) return Integer.MIN_VALUE;
        return (int) Math.floor((rowTopEdge() + scrollY - ndcY) / ROW_HEIGHT);
    }

    /** Flattened-tree row under {@code ndcY}, or -1 when none (clipped areas and empty space included). */
    public static int rowAt(float ndcY, int rowCount) {
        int row = rawRowAt(ndcY);
        return (row >= 0 && row < rowCount) ? row : -1;
    }

    // --- Scrollbar geometry (shared with AssetBrowserInputDelegate) ---
    public static boolean hasScrollbar() { return maxScrollY > 0.001f; }

    public static float thumbHeight() {
        float trackH = viewTop() - viewBottom();
        return Math.max(MIN_THUMB_H, trackH * trackH / (trackH + maxScrollY));
    }

    public static float thumbTop() {
        float trackH = viewTop() - viewBottom();
        return viewTop() - (maxScrollY > 0.0f ? scrollY / maxScrollY : 0.0f) * (trackH - thumbHeight());
    }

    public static boolean inScrollbar(float ndcX, float ndcY) {
        float x1 = rect().x1;
        return hasScrollbar() && ndcX >= x1 - SCROLLBAR_HIT_W && ndcX <= x1 && ndcY >= viewBottom() && ndcY <= viewTop();
    }

    /** Scrolls so the thumb's top edge sits at {@code thumbTopY} (used by thumb dragging). */
    public static void setThumbTop(float thumbTopY) {
        float travel = (viewTop() - viewBottom()) - thumbHeight();
        if (travel <= 0.0f) return;
        setScrollY((viewTop() - thumbTopY) / travel * maxScrollY);
    }

    private static float rowX1() {
        return rect().x1 - (hasScrollbar() ? SCROLLBAR_HIT_W : ROW_INSET);
    }

    // --- Drag-and-drop (state is driven by AssetBrowserInputDelegate; drawn as feedback here) ---
    public enum DropKind {
        NONE,
        /** Re-parent onto the target row's entity. */
        INTO,
        /** Become the target's sibling, ordered just before it. */
        BEFORE,
        /** Become the target's sibling, ordered just after it. */
        AFTER,
        /** Empty space below the last row: move to the end of the scene root. */
        ROOT_END
    }

    public static final class DropTarget {
        public DropKind kind = DropKind.NONE;
        public int row = -1;
        public Entity target;
        public Entity newParent;
        public boolean valid;

        void set(DropKind kind, int row, Entity target, Entity newParent, boolean valid) {
            this.kind = kind;
            this.row = row;
            this.target = target;
            this.newParent = newParent;
            this.valid = valid;
        }
    }

    private static final DropTarget dropScratch = new DropTarget();
    private static Entity dragSource = null;
    private static float dragY = 0.0f;

    public static void setDrag(Entity source, float ndcY) {
        dragSource = source;
        dragY = ndcY;
    }

    public static void clearDrag() { dragSource = null; }
    public static Entity getDragSource() { return dragSource; }

    /**
     * Resolves where {@code source} would land if dropped at {@code ndcY}: the middle half of a row re-parents onto it,
     * the outer quarters order it as a sibling in the gap above/below, and empty space below the list moves it to the
     * scene root. Every candidate parent is cycle-checked against the source's subtree. Returns a shared scratch.
     */
    public static DropTarget computeDrop(float ndcY, Entity source, List<HierarchyNode> visibleTree, List<Entity> sceneEntities) {
        DropTarget drop = dropScratch;
        drop.set(DropKind.NONE, -1, null, null, false);
        if (source == null || visibleTree == null || visibleTree.isEmpty()) return drop;

        int row = rawRowAt(ndcY);
        if (row == Integer.MIN_VALUE || row < 0) return drop;
        if (row >= visibleTree.size()) {
            drop.set(DropKind.ROOT_END, visibleTree.size() - 1, null, null, true);
            return drop;
        }

        float f = (rowTop(row) - ndcY) / ROW_HEIGHT;
        DropKind kind = f < 0.25f ? DropKind.BEFORE : (f > 0.75f ? DropKind.AFTER : DropKind.INTO);

        // The gap under an expanded parent visually opens its child list: treat it as "before its first child"
        if (kind == DropKind.AFTER && row + 1 < visibleTree.size() && visibleTree.get(row + 1).depth > visibleTree.get(row).depth) {
            kind = DropKind.BEFORE;
            row++;
        }

        Entity target = visibleTree.get(row).entity;
        Entity newParent = (kind == DropKind.INTO) ? target : findEntity(sceneEntities, target.parentId);
        boolean valid = target != source && isCycleSafeParent(source, newParent, sceneEntities);
        drop.set(kind, row, target, newParent, valid);
        return drop;
    }

    /** True when {@code parent} (null = root) can adopt {@code child} without creating a parent loop at any depth. */
    public static boolean isCycleSafeParent(Entity child, Entity parent, List<Entity> sceneEntities) {
        if (child == null) return false;
        if (parent == null) return true;
        return parent != child && !parent.isDescendantOf(child, sceneEntities);
    }

    /** Entity categories shown as colored badges, in priority order. */
    public enum Category {
        CAMERA("C", EditorTheme.PURPLE),
        LIGHT("L", EditorTheme.YELLOW),
        AUDIO("A", EditorTheme.PINK),
        MESH("M", EditorTheme.TEAL),
        EMPTY("E", EditorTheme.GRAPHITE);

        public final String glyph;
        public final int color;

        Category(String glyph, int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    public static Category categoryOf(Entity e) {
        if (e.getComponent(Camera.class) != null) return Category.CAMERA;
        if (e.getComponent(Light.class) != null) return Category.LIGHT;
        if (e.getComponent(AudioSource.class) != null) return Category.AUDIO;
        if (e.getComponent(MeshFilter.class) != null || e.getMeshAssetPath() != null) return Category.MESH;
        return Category.EMPTY;
    }

    public static void render(UIContext ui, List<Entity> sceneEntities) {
        UIGeometryBatch.Buffer buffer = ui.geometry();
        PanelRect r = rect();
        UIGeometryBatch.addQuad(buffer, r.x0, r.y0, r.x1, r.y1, EditorTheme.SURFACE, 1.0f);

        List<HierarchyNode> visibleTree = buildFlattenedTree(sceneEntities);
        updateScrollBounds(visibleTree.size());

        if (visibleTree.isEmpty()) {
            // Empty state sits in the upper part of the tile, whatever its height
            float y = r.y1 - Math.min(0.57f, r.height() * 0.4f);
            centeredText(buffer, "NO ENTITIES", y, 1.15f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "RIGHT-CLICK THE VIEWPORT OR", y - 0.08f, 0.8f, EditorTheme.TEXT_DISABLED);
            centeredText(buffer, "USE ENTITY > SPAWN TO START", y - 0.13f, 0.8f, EditorTheme.TEXT_DISABLED);
            return;
        }

        // Every level stays visibly distinct: deep trees shrink the per-level step to fit the indent budget
        float indentStep = lastMaxDepth > 0 ? Math.min(INDENT, indentBudget() / lastMaxDepth) : INDENT;
        float rowX0 = rowX0(), rowX1 = rowX1(), indentX0 = indentX0();
        float viewTop = viewTop(), viewBottom = viewBottom();

        buffer.pushClipY(viewBottom, viewTop);
        for (int i = 0; i < visibleTree.size(); i++) {
            HierarchyNode node = visibleTree.get(i);
            float top = rowTop(i);
            float bottom = top - ROW_HEIGHT;
            if (bottom > viewTop) continue;
            if (top < viewBottom) break;

            Entity entity = node.entity;
            if (entity == null) continue;

            float mid = (top + bottom) * 0.5f;
            boolean selected = entity.selected;
            float rowAlpha = (entity == dragSource) ? 0.45f : 1.0f;

            // Mac source-list selection: rounded accent row
            if (selected) {
                UIGeometryBatch.addRoundedQuad(buffer, rowX0, bottom + 0.008f, rowX1, top - 0.008f, EditorTheme.RADIUS, EditorTheme.ACCENT, rowAlpha);
            }

            // Indent guides: one hairline per ancestor level, plus an elbow into this node
            float guideHalf = UIGeometryBatch.squareWidth(buffer, 0.002f) * 0.5f;
            int guideColor = selected ? EditorTheme.TEXT : EditorTheme.BORDER;
            float guideAlpha = selected ? 0.35f : 1.0f;
            for (int d = 0; d < node.depth; d++) {
                float gx = guideX(d, indentStep);
                UIGeometryBatch.addQuad(buffer, gx - guideHalf, bottom, gx + guideHalf, top, guideColor, guideAlpha);
            }

            float indentX = indentX0 + node.depth * indentStep;
            if (node.depth > 0) {
                float gx = guideX(node.depth - 1, indentStep);
                if (gx < indentX - 0.004f) {
                    UIGeometryBatch.addQuad(buffer, gx, mid - 0.001f, indentX - 0.004f, mid + 0.001f, guideColor, guideAlpha);
                }
            }

            // Disclosure triangle for nodes with children
            float triW = UIGeometryBatch.squareWidth(buffer, 0.016f);
            if (node.hasChildren) {
                int triColor = selected ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED;
                if (entity.expandedInHierarchy) {
                    UIGeometryBatch.addTriangle(buffer, indentX, mid + 0.007f, indentX + triW, mid + 0.007f, indentX + triW * 0.5f, mid - 0.008f, triColor, rowAlpha);
                } else {
                    UIGeometryBatch.addTriangle(buffer, indentX + triW * 0.15f, mid + 0.008f, indentX + triW * 0.15f, mid - 0.008f, indentX + triW, mid, triColor, rowAlpha);
                }
            }

            // Category badge
            Category cat = categoryOf(entity);
            float badgeH = 0.036f;
            float badgeX0 = indentX + triW + 0.008f;
            float badgeX1 = badgeX0 + Math.max(UIGeometryBatch.squareWidth(buffer, badgeH), UIGeometryBatch.textWidth(buffer, 1, 0.8f) + 0.008f);
            UIGeometryBatch.addRoundedQuad(buffer, badgeX0, mid - badgeH * 0.5f, badgeX1, mid + badgeH * 0.5f, 0.007f, cat.color, (selected ? 1.0f : 0.9f) * rowAlpha);
            float gw = UIGeometryBatch.textWidth(buffer, 1, 0.8f);
            UIGeometryBatch.addBoundedText(buffer, cat.glyph, (badgeX0 + badgeX1 - gw) * 0.5f, mid + 0.8f * 0.009f, badgeX1, 0.8f, EditorTheme.BACKGROUND, rowAlpha);

            UIGeometryBatch.addTruncatedText(buffer, entity.name, badgeX1 + 0.012f, mid + NAME_TEXT * 0.009f, rowX1 - 0.008f, NAME_TEXT, EditorTheme.TEXT, rowAlpha);
        }

        if (dragSource != null) {
            renderDropFeedback(buffer, computeDrop(dragY, dragSource, visibleTree, sceneEntities), visibleTree, indentStep, rowX1);
        }
        buffer.popClipY();

        renderScrollbar(buffer);
    }

    private static void renderDropFeedback(UIGeometryBatch.Buffer buffer, DropTarget drop, List<HierarchyNode> visibleTree, float indentStep, float rowX1) {
        if (drop.kind == DropKind.NONE) return;
        int color = drop.valid ? EditorTheme.ACCENT : EditorTheme.RED;
        float t = 0.004f;

        if (drop.kind == DropKind.INTO) {
            float top = rowTop(drop.row);
            UIGeometryBatch.addFrameBorder(buffer, rowX0(), top - ROW_HEIGHT + 0.006f, rowX1, top - 0.006f, t, color, 1.0f);
            return;
        }

        // Insertion line in the gap, indented to the depth the entity will land at
        int depth;
        float lineY;
        if (drop.kind == DropKind.ROOT_END) {
            depth = 0;
            lineY = rowTop(drop.row) - ROW_HEIGHT;
        } else {
            depth = visibleTree.get(drop.row).depth;
            lineY = (drop.kind == DropKind.BEFORE) ? rowTop(drop.row) : rowTop(drop.row) - ROW_HEIGHT;
        }
        float x0 = indentX0() + depth * indentStep;
        UIGeometryBatch.addQuad(buffer, x0, lineY - t * 0.5f, rowX1, lineY + t * 0.5f, color, 1.0f);
        float dotH = 0.014f, dotW = UIGeometryBatch.squareWidth(buffer, dotH);
        UIGeometryBatch.addPill(buffer, x0 - dotW * 0.5f, lineY - dotH * 0.5f, x0 + dotW * 0.5f, lineY + dotH * 0.5f, color, 1.0f);
    }

    private static void renderScrollbar(UIGeometryBatch.Buffer buffer) {
        if (!hasScrollbar()) return;
        float top = thumbTop();
        float x1 = rect().x1;
        UIGeometryBatch.addPill(buffer, x1 - SCROLLBAR_PILL_INNER, top - thumbHeight(), x1 - SCROLLBAR_PILL_OUTER, top, EditorTheme.GRAPHITE, 0.85f);
    }

    private static float guideX(int depth, float indentStep) {
        return indentX0() + depth * indentStep + GUIDE_OFFSET;
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        PanelRect r = rect();
        float x = r.x0 + Math.max(0.01f, (r.width() - w) * 0.5f);
        UIGeometryBatch.addTruncatedText(buffer, text, x, midY + size * 0.009f, r.x1 - 0.005f, size, rgb, 1.0f);
    }
}