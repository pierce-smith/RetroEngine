package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.ui.batch.UIGeometryBatch;
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

    public static void render(UIGeometryBatch.Buffer buffer, List<Entity> sceneEntities) {
        UIGeometryBatch.addQuad(buffer, -1.0f, -0.5f, -0.55f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addQuad(buffer, -1.0f, 0.88f, -0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -1.0f, -0.5f, -0.55f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "SCENE MANAGER", -0.96f, 0.95f, -0.57f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        List<HierarchyNode> visibleTree = buildFlattenedTree(sceneEntities);

        if (visibleTree.isEmpty()) {
            UIGeometryBatch.addBoundedText(buffer, "NO ENTITIES IN SCENE", -0.95f, 0.80f, -0.58f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "RIGHT-CLICK OR SPAWN", -0.95f, 0.72f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "3D MESH TO START", -0.95f, 0.65f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
        } else {
            float itemY = 0.80f;
            for (int i = 0; i < visibleTree.size(); i++) {
                HierarchyNode node = visibleTree.get(i);
                if (itemY < -0.45f) break;

                Entity entity = node.entity;
                if (entity == null) continue;

                float indentX = -0.96f + (node.depth * 0.04f);
                if (indentX > -0.65f) indentX = -0.65f;

                String prefix = "";
                if (node.hasChildren) {
                    prefix = entity.expandedInHierarchy ? "v " : "> ";
                } else if (node.depth > 0) {
                    prefix = "• ";
                } else {
                    prefix = "  ";
                }

                String label = prefix + entity.name;

                if (entity.selected) {
                    UIGeometryBatch.addQuad(buffer, -0.98f, itemY - 0.02f, -0.57f, itemY + 0.05f, 0.0f, 0.35f, 0.70f, 1.0f);
                    UIGeometryBatch.addBoundedText(buffer, label, indentX, itemY + 0.03f, -0.58f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);
                } else {
                    UIGeometryBatch.addBoundedText(buffer, label, indentX, itemY + 0.03f, -0.58f, 1.3f, 0.8f, 0.8f, 0.8f, 1.0f);
                }

                itemY -= 0.08f;
            }
        }
    }

    public static void render(List<Float> verts, List<Entity> sceneEntities) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, sceneEntities);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}