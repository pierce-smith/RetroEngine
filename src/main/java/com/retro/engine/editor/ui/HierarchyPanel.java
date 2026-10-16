package com.retro.engine.editor.ui;

import com.retro.engine.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * HierarchyPanel handles rendering the Left Panel (Scene Manager & Indented Scene Graph Tree).
 */
public class HierarchyPanel {

    public static class HierarchyNode {
        public final Entity entity;
        public final int depth;
        public final boolean hasChildren;

        public HierarchyNode(Entity entity, int depth, boolean hasChildren) {
            this.entity = entity;
            this.depth = depth;
            this.hasChildren = hasChildren;
        }
    }

    public static List<HierarchyNode> buildFlattenedTree(List<Entity> sceneEntities) {
        List<HierarchyNode> visibleTree = new ArrayList<>();
        if (sceneEntities == null || sceneEntities.isEmpty()) return visibleTree;

        // Collect top-level entities
        for (Entity entity : sceneEntities) {
            if (entity.parentId == null || entity.parentId.trim().isEmpty() || findEntity(sceneEntities, entity.parentId) == null) {
                appendNodeAndVisibleChildren(entity, 0, sceneEntities, visibleTree);
            }
        }
        return visibleTree;
    }

    private static void appendNodeAndVisibleChildren(Entity entity, int depth, List<Entity> allEntities, List<HierarchyNode> result) {
        List<Entity> children = getChildrenOf(entity, allEntities);
        boolean hasChildren = !children.isEmpty();

        result.add(new HierarchyNode(entity, depth, hasChildren));

        if (hasChildren && entity.expandedInHierarchy) {
            for (Entity child : children) {
                appendNodeAndVisibleChildren(child, depth + 1, allEntities, result);
            }
        }
    }

    private static List<Entity> getChildrenOf(Entity parent, List<Entity> allEntities) {
        List<Entity> list = new ArrayList<>();
        for (Entity e : allEntities) {
            if (parent.id.equalsIgnoreCase(e.parentId)) {
                list.add(e);
            }
        }
        return list;
    }

    private static Entity findEntity(List<Entity> allEntities, String id) {
        for (Entity e : allEntities) {
            if (e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    public static void render(List<Float> verts, List<Entity> sceneEntities) {
        UIGeometryBatch.addQuad(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addQuad(verts, -1.0f, 0.88f, -0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(verts, "SCENE MANAGER", -0.96f, 0.95f, -0.57f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        List<HierarchyNode> visibleTree = buildFlattenedTree(sceneEntities);

        if (visibleTree.isEmpty()) {
            UIGeometryBatch.addBoundedText(verts, "NO ENTITIES IN SCENE", -0.95f, 0.80f, -0.58f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(verts, "RIGHT-CLICK OR SPAWN", -0.95f, 0.72f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
            UIGeometryBatch.addBoundedText(verts, "3D MESH TO START", -0.95f, 0.65f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
        } else {
            float itemY = 0.80f;
            for (HierarchyNode node : visibleTree) {
                if (itemY < -0.45f) break;

                Entity entity = node.entity;
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
                    UIGeometryBatch.addQuad(verts, -0.98f, itemY - 0.02f, -0.57f, itemY + 0.05f, 0.0f, 0.35f, 0.70f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, label, indentX, itemY + 0.03f, -0.58f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);
                } else {
                    UIGeometryBatch.addBoundedText(verts, label, indentX, itemY + 0.03f, -0.58f, 1.3f, 0.8f, 0.8f, 0.8f, 1.0f);
                }

                itemY -= 0.08f;
            }
        }
    }
}
