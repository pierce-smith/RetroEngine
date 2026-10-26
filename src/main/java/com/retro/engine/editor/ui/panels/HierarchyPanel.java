package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.entity.Entity;

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

    public static List<HierarchyNode> buildFlattenedTree(List<Entity> sceneEntities) {
        visibleTreeScratch.clear();
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
        poolIdx++;

        if (hasChildren && entity.expandedInHierarchy) {
            for (int i = 0; i < allEntities.size(); i++) {
                Entity child = allEntities.get(i);
                if (child != null && entity.id != null && entity.id.equalsIgnoreCase(child.parentId)) {
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
            if (e != null && parent.id.equalsIgnoreCase(e.parentId)) {
                return true;
            }
        }
        return false;
    }

    private static Entity findEntity(List<Entity> allEntities, String id) {
        if (id == null || allEntities == null) return null;
        for (int i = 0; i < allEntities.size(); i++) {
            Entity e = allEntities.get(i);
            if (e != null && e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    // --- Shared row layout (design-space NDC); AssetBrowserInputDelegate hit-tests with the same values ---
    public static final float ROW_TOP = 0.85f;
    public static final float ROW_HEIGHT = 0.08f;
    private static final float VIEW_BOTTOM = -0.48f;

    private static final float ROW_X0 = -0.985f, ROW_X1 = -0.565f;
    private static final float INDENT_X0 = -0.965f, INDENT = 0.04f, MAX_INDENT_X = -0.70f;
    private static final float GUIDE_OFFSET = 0.006f; // guides sit under the parent's disclosure triangle
    private static final float NAME_TEXT = 1.0f;

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

    public static void render(UIGeometryBatch.Buffer buffer, List<Entity> sceneEntities) {
        UIGeometryBatch.addQuad(buffer, -1.0f, -0.5f, -0.55f, 1.0f, EditorTheme.SURFACE, 1.0f);

        List<HierarchyNode> visibleTree = buildFlattenedTree(sceneEntities);

        if (visibleTree.isEmpty()) {
            centeredText(buffer, "NO ENTITIES", 0.30f, 1.15f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "RIGHT-CLICK THE VIEWPORT OR", 0.22f, 0.8f, EditorTheme.TEXT_DISABLED);
            centeredText(buffer, "USE ENTITY > SPAWN TO START", 0.17f, 0.8f, EditorTheme.TEXT_DISABLED);
            return;
        }

        for (int i = 0; i < visibleTree.size(); i++) {
            HierarchyNode node = visibleTree.get(i);
            float top = ROW_TOP - i * ROW_HEIGHT;
            float bottom = top - ROW_HEIGHT;
            if (bottom < VIEW_BOTTOM) break;

            Entity entity = node.entity;
            if (entity == null) continue;

            float mid = (top + bottom) * 0.5f;
            boolean selected = entity.selected;

            // Mac source-list selection: rounded accent row
            if (selected) {
                UIGeometryBatch.addRoundedQuad(buffer, ROW_X0, bottom + 0.008f, ROW_X1, top - 0.008f, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
            }

            // Indent guides: one hairline per ancestor level, plus an elbow into this node
            float guideHalf = UIGeometryBatch.squareWidth(buffer, 0.002f) * 0.5f;
            int guideColor = selected ? EditorTheme.TEXT : EditorTheme.BORDER;
            float guideAlpha = selected ? 0.35f : 1.0f;
            for (int d = 0; d < node.depth; d++) {
                float gx = guideX(d);
                if (gx > MAX_INDENT_X) break;
                UIGeometryBatch.addQuad(buffer, gx - guideHalf, bottom, gx + guideHalf, top, guideColor, guideAlpha);
            }

            float indentX = Math.min(MAX_INDENT_X, INDENT_X0 + node.depth * INDENT);
            if (node.depth > 0) {
                float gx = guideX(node.depth - 1);
                if (gx < indentX) {
                    UIGeometryBatch.addQuad(buffer, gx, mid - 0.001f, indentX - 0.004f, mid + 0.001f, guideColor, guideAlpha);
                }
            }

            // Disclosure triangle for nodes with children
            float triW = UIGeometryBatch.squareWidth(buffer, 0.016f);
            if (node.hasChildren) {
                int triColor = selected ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED;
                if (entity.expandedInHierarchy) {
                    UIGeometryBatch.addTriangle(buffer, indentX, mid + 0.007f, indentX + triW, mid + 0.007f, indentX + triW * 0.5f, mid - 0.008f, triColor, 1.0f);
                } else {
                    UIGeometryBatch.addTriangle(buffer, indentX + triW * 0.15f, mid + 0.008f, indentX + triW * 0.15f, mid - 0.008f, indentX + triW, mid, triColor, 1.0f);
                }
            }

            // Category badge
            Category cat = categoryOf(entity);
            float badgeH = 0.036f;
            float badgeX0 = indentX + triW + 0.008f;
            float badgeX1 = badgeX0 + Math.max(UIGeometryBatch.squareWidth(buffer, badgeH), UIGeometryBatch.textWidth(buffer, 1, 0.8f) + 0.008f);
            UIGeometryBatch.addRoundedQuad(buffer, badgeX0, mid - badgeH * 0.5f, badgeX1, mid + badgeH * 0.5f, 0.007f, cat.color, selected ? 1.0f : 0.9f);
            float gw = UIGeometryBatch.textWidth(buffer, 1, 0.8f);
            UIGeometryBatch.addBoundedText(buffer, cat.glyph, (badgeX0 + badgeX1 - gw) * 0.5f, mid + 0.8f * 0.009f, badgeX1, 0.8f, EditorTheme.BACKGROUND, 1.0f);

            UIGeometryBatch.addTruncatedText(buffer, entity.name, badgeX1 + 0.012f, mid + NAME_TEXT * 0.009f, ROW_X1 - 0.008f, NAME_TEXT, EditorTheme.TEXT, 1.0f);
        }
    }

    private static float guideX(int depth) {
        return INDENT_X0 + depth * INDENT + GUIDE_OFFSET;
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = -1.0f + Math.max(0.01f, (0.45f - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, -0.555f, size, rgb, 1.0f);
    }

    public static void render(List<Float> verts, List<Entity> sceneEntities) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, sceneEntities);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}