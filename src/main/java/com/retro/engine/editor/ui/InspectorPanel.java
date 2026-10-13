package com.retro.engine.editor.ui;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;

import java.util.List;

/**
 * InspectorPanel handles rendering the Right Panel (Dynamic Component Readout & Inspector Mutation).
 * Overhauled with in-context Asset Pickers [PICK] and Native File Importers [IMPORT].
 * Target JDK: JDK 21+ / JDK 25 / macOS
 */
public class InspectorPanel {

    public static void render(List<Float> verts, SceneEntity selectedEntity) {
        render(verts, selectedEntity, null);
    }

    public static void render(List<Float> verts, SceneEntity selectedEntity, List<AssetEntry> assetDirectory) {
        UIBatchBuilder.addQuad(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIBatchBuilder.addQuad(verts, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "INSPECTOR [ECS COMPONENTS]", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            UIBatchBuilder.addBoundedText(verts, "ENTITY: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.3f, 1.0f, 0.8f, 0.2f, 1.0f);

            float curY = 0.72f;

            for (Component comp : selectedEntity.getAllComponents()) {
                if (curY < -0.45f) break;

                UIBatchBuilder.addBoundedText(verts, "--- " + comp.getComponentName().toUpperCase() + " ---", 0.58f, curY, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
                curY -= 0.06f;

                if (comp instanceof TransformComponent tc) {
                    UIBatchBuilder.addBoundedText(verts, String.format("POS: X %.1f Y %.1f Z %.1f", tc.posX, tc.posY, tc.posZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.05f;
                    UIBatchBuilder.addBoundedText(verts, String.format("ROT: X %.1f Y %.1f Z %.1f", tc.rotX, tc.rotY, tc.rotZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.05f;
                    UIBatchBuilder.addBoundedText(verts, String.format("SCL: X %.1f Y %.1f Z %.1f", tc.scaleX, tc.scaleY, tc.scaleZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.07f;

                } else if (comp instanceof MeshRendererComponent mc) {
                    String meshName = mc.meshAssetPath != null ? new java.io.File(mc.meshAssetPath).getName() : "NONE";

                    UIBatchBuilder.addQuad(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "MESH: " + meshName, 0.59f, curY - 0.01f, 0.78f, 0.95f, 0.4f, 0.8f, 1.0f, 1.0f);

                    UIBatchBuilder.addQuad(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIBatchBuilder.addQuad(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);

                    curY -= 0.08f;

                } else if (comp instanceof MaterialComponent mat) {
                    UIBatchBuilder.addBoundedText(verts, "SHADER: " + mat.shaderName, 0.58f, curY, 0.98f, 1.0f, 0.4f, 0.8f, 1.0f, 1.0f);
                    curY -= 0.05f;

                    UIBatchBuilder.addQuad(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "TEX: " + mat.textureName, 0.59f, curY - 0.01f, 0.78f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIBatchBuilder.addQuad(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIBatchBuilder.addQuad(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                    UIBatchBuilder.addFrameBorder(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                    UIBatchBuilder.addBoundedText(verts, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);

                    curY -= 0.09f;

                } else if (comp instanceof LightComponent lc) {
                    UIBatchBuilder.addBoundedText(verts, "TYPE: " + lc.type.name(), 0.58f, curY, 0.98f, 1.0f, 1.0f, 0.9f, 0.3f, 1.0f);
                    curY -= 0.05f;
                    UIBatchBuilder.addBoundedText(verts, String.format("INTENSITY: %.1f", lc.intensity), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.07f;
                } else {
                    UIBatchBuilder.addBoundedText(verts, "DATA: " + comp.toString(), 0.58f, curY, 0.98f, 1.0f, 0.7f, 0.7f, 0.7f, 1.0f);
                    curY -= 0.07f;
                }
            }
        } else {
            UIBatchBuilder.addBoundedText(verts, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "CLICK AN ENTITY IN SCENE HIERARCHY", 0.58f, 0.72f, 0.98f, 0.95f, 0.4f, 0.4f, 0.4f, 1.0f);
        }
    }
}
