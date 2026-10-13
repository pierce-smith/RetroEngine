package com.retro.engine.editor.ui;

import com.retro.engine.ecs.SceneEntity;
import java.util.List;

/**
 * SceneHierarchyPanel handles rendering the Left Panel (Scene Manager & Entity Tree).
 * Target JDK: JDK 25 / macOS
 */
public class SceneHierarchyPanel {

    public static void render(List<Float> verts, List<SceneEntity> sceneEntities) {
        UIBatchBuilder.addQuad(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIBatchBuilder.addQuad(verts, -1.0f, 0.88f, -0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "SCENE MANAGER", -0.96f, 0.95f, -0.57f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (sceneEntities == null || sceneEntities.isEmpty()) {
            UIBatchBuilder.addBoundedText(verts, "NO ENTITIES IN SCENE", -0.95f, 0.80f, -0.58f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "CLICK 3D ASSET CARD", -0.95f, 0.72f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "BELOW TO SPAWN", -0.95f, 0.65f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
        } else {
            float itemY = 0.80f;
            for (SceneEntity entity : sceneEntities) {
                if (entity.selected) {
                    UIBatchBuilder.addQuad(verts, -0.98f, itemY - 0.02f, -0.57f, itemY + 0.05f, 0.0f, 0.35f, 0.70f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "> " + entity.name, -0.95f, itemY + 0.03f, -0.58f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);
                } else {
                    UIBatchBuilder.addBoundedText(verts, "  " + entity.name, -0.95f, itemY + 0.03f, -0.58f, 1.3f, 0.7f, 0.7f, 0.7f, 1.0f);
                }
                itemY -= 0.08f;
            }
        }
    }
}
