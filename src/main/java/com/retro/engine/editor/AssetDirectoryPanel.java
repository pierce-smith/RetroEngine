package com.retro.engine.editor;

import java.util.List;

/**
 * AssetDirectoryPanel handles rendering the Bottom Panel (Resource Cards & Drag-Drop Zone).
 * Target JDK: JDK 25 / macOS
 */
public class AssetDirectoryPanel {

    public static void render(List<Float> verts, List<EditorPanelManager.AssetEntry> assetDirectory) {
        UIBatchBuilder.addQuad(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.06f, 0.06f, 0.08f, 1.0f);
        UIBatchBuilder.addQuad(verts, -1.0f, -0.5f, 1.0f, -0.42f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "ASSET DIRECTORY (DROP FILES TO INGEST)", -0.96f, -0.45f, 0.95f, 1.4f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (assetDirectory == null || assetDirectory.isEmpty()) {
            UIBatchBuilder.addBoundedText(verts, "NO ASSETS LOADED - DRAG & DROP .OBJ / .FBX / .PNG FILES FROM FINDER", -0.80f, -0.70f, 0.90f, 1.2f, 0.4f, 0.4f, 0.4f, 1.0f);
        } else {
            float cardX = -0.95f;
            for (EditorPanelManager.AssetEntry asset : assetDirectory) {
                if (cardX + 0.35f > 0.95f) break;
                UIBatchBuilder.addQuad(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.10f, 0.10f, 0.13f, 1.0f);
                UIBatchBuilder.addFrameBorder(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.2f, 0.2f, 0.25f, 1.0f);

                UIBatchBuilder.addQuad(verts, cardX + 0.02f, -0.66f, cardX + 0.18f, -0.58f, 0.0f, 0.45f, 0.65f, 1.0f);
                UIBatchBuilder.addBoundedText(verts, "." + asset.type.name(), cardX + 0.03f, -0.60f, cardX + 0.17f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                UIBatchBuilder.addBoundedText(verts, asset.name, cardX + 0.02f, -0.75f, cardX + 0.30f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                UIBatchBuilder.addBoundedText(verts, asset.type == EditorPanelManager.AssetType.MESH_3D ? "[CLICK: SPAWN]" : "[CLICK: LINK]", cardX + 0.02f, -0.85f, cardX + 0.30f, 0.9f, 0.2f, 0.8f, 1.0f, 1.0f);
                cardX += 0.36f;
            }
        }
    }
}
