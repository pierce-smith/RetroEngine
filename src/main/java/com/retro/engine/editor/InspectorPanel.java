package com.retro.engine.editor;

import java.util.List;

/**
 * InspectorPanel handles rendering the Right Panel (Entity Transform & Material Readout).
 * Target JDK: JDK 25 / macOS
 */
public class InspectorPanel {

    public static void render(List<Float> verts, EditorPanelManager.SceneEntity selectedEntity) {
        render(verts, selectedEntity, null);
    }

    public static void render(List<Float> verts, EditorPanelManager.SceneEntity selectedEntity, List<EditorPanelManager.AssetEntry> assetDirectory) {
        UIBatchBuilder.addQuad(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIBatchBuilder.addQuad(verts, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "INSPECTOR", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            UIBatchBuilder.addBoundedText(verts, "ENTITY: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.3f, 1.0f, 0.8f, 0.2f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "--- TRANSFORM ---", 0.58f, 0.70f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, String.format("POS: X %.1f Y %.1f Z %.1f", selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ), 0.58f, 0.62f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, String.format("ROT: X %.1f Y %.1f Z %.1f", selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ), 0.58f, 0.54f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, String.format("SCL: X %.1f Y %.1f Z %.1f", selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ), 0.58f, 0.46f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);

            UIBatchBuilder.addBoundedText(verts, "--- MATERIAL & TEXTURE ---", 0.58f, 0.36f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "SHADER: RETRO_MSL_3D", 0.58f, 0.28f, 0.98f, 1.0f, 0.4f, 0.8f, 1.0f, 1.0f);

            UIBatchBuilder.addQuad(verts, 0.58f, 0.16f, 0.95f, 0.24f, 0.12f, 0.16f, 0.22f, 1.0f);
            UIBatchBuilder.addFrameBorder(verts, 0.58f, 0.16f, 0.95f, 0.24f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "TEX: " + selectedEntity.textureName, 0.60f, 0.21f, 0.93f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIBatchBuilder.addBoundedText(verts, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
        }
    }
}
