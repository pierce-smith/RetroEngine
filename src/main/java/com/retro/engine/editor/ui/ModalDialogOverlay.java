package com.retro.engine.editor.ui;

import com.retro.engine.scene.Entity;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;

import java.util.List;

/**
 * ModalDialogOverlay encapsulates rendering and layout for modal dialog overlays.
 */
public class ModalDialogOverlay {

    public static void renderAssetPickerModal(UIGeometryBatch.Buffer buffer, AssetType pickerFilterType, List<AssetMeta> assetDirectory) {
        if (pickerFilterType == null) return;

        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0.02f, 0.02f, 0.04f, 0.75f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        UIGeometryBatch.addQuad(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.10f, 0.11f, 0.15f, 0.98f);
        UIGeometryBatch.addFrameBorder(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.004f, 0.0f, 0.55f, 0.90f, 1.0f);

        UIGeometryBatch.addQuad(buffer, cardMinX, cardMaxY - 0.10f, cardMaxX, cardMaxY, 0.16f, 0.18f, 0.24f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "SELECT " + pickerFilterType.name() + " ASSET", cardMinX + 0.03f, cardMaxY - 0.04f, cardMaxX - 0.10f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIGeometryBatch.addQuad(buffer, cardMaxX - 0.08f, cardMaxY - 0.08f, cardMaxX - 0.02f, cardMaxY - 0.02f, 0.65f, 0.15f, 0.15f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "X", cardMaxX - 0.065f, cardMaxY - 0.035f, cardMaxX - 0.03f, 1.2f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIGeometryBatch.addQuad(buffer, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.25f, 0.15f, 0.15f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.002f, 0.8f, 0.2f, 0.2f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "[NONE / UNBIND]", cardMinX + 0.05f, cardMaxY - 0.14f, cardMinX + 0.36f, 1.0f, 1.0f, 0.6f, 0.6f, 1.0f);

        UIGeometryBatch.addQuad(buffer, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.15f, 0.45f, 0.30f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "[IMPORT DISK...]", cardMaxX - 0.36f, cardMaxY - 0.14f, cardMaxX - 0.05f, 1.0f, 0.4f, 1.0f, 0.6f, 1.0f);

        List<AssetMeta> filtered = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.type == pickerFilterType)
                .toList() : List.of();

        float listY = cardMaxY - 0.24f;

        if (filtered.isEmpty()) {
            UIGeometryBatch.addBoundedText(buffer, "NO " + pickerFilterType.name() + " ASSETS IN PROJECT", cardMinX + 0.05f, listY, cardMaxX - 0.05f, 1.1f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "CLICK [IMPORT DISK...] TO BROWSE NATIVE FILES", cardMinX + 0.05f, listY - 0.08f, cardMaxX - 0.05f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
        } else {
            for (AssetMeta asset : filtered) {
                if (listY < cardMinY + 0.05f) break;

                UIGeometryBatch.addQuad(buffer, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.15f, 0.18f, 0.24f, 1.0f);
                UIGeometryBatch.addFrameBorder(buffer, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.001f, 0.25f, 0.30f, 0.40f, 1.0f);

                UIGeometryBatch.addBoundedText(buffer, "• " + asset.name, cardMinX + 0.05f, listY - 0.01f, cardMaxX - 0.22f, 1.1f, 0.95f, 0.95f, 0.95f, 1.0f);

                UIGeometryBatch.addQuad(buffer, cardMaxX - 0.20f, listY - 0.04f, cardMaxX - 0.05f, listY + 0.02f, 0.0f, 0.45f, 0.70f, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, "SELECT", cardMaxX - 0.18f, listY - 0.01f, cardMaxX - 0.06f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                listY -= 0.10f;
            }
        }
    }
    public static void renderAssetPickerModal(List<Float> verts, AssetType pickerFilterType, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderAssetPickerModal(tempBuf, pickerFilterType, assetDirectory);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void renderEntityParentPickerModal(UIGeometryBatch.Buffer buffer, Entity targetEntity, List<Entity> sceneEntities) {
        if (targetEntity == null) return;

        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0.02f, 0.02f, 0.04f, 0.75f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        UIGeometryBatch.addQuad(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.10f, 0.11f, 0.15f, 0.98f);
        UIGeometryBatch.addFrameBorder(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.004f, 0.0f, 0.55f, 0.90f, 1.0f);

        UIGeometryBatch.addQuad(buffer, cardMinX, cardMaxY - 0.10f, cardMaxX, cardMaxY, 0.16f, 0.18f, 0.24f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "SET PARENT FOR '" + targetEntity.name.toUpperCase() + "'", cardMinX + 0.03f, cardMaxY - 0.04f, cardMaxX - 0.10f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Close X Button
        UIGeometryBatch.addQuad(buffer, cardMaxX - 0.08f, cardMaxY - 0.08f, cardMaxX - 0.02f, cardMaxY - 0.02f, 0.65f, 0.15f, 0.15f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "X", cardMaxX - 0.065f, cardMaxY - 0.035f, cardMaxX - 0.03f, 1.2f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Option 1: [NO PARENT / ROOT]
        UIGeometryBatch.addQuad(buffer, cardMinX + 0.03f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.11f, 0.25f, 0.15f, 0.15f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, cardMinX + 0.03f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.11f, 0.002f, 0.8f, 0.2f, 0.2f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "[UNPARENT / MOVE TO ROOT SCENE]", cardMinX + 0.05f, cardMaxY - 0.13f, cardMaxX - 0.05f, 1.1f, 1.0f, 0.8f, 0.8f, 1.0f);

        // Filter valid candidate parent entities
        List<Entity> candidates = (sceneEntities != null) ? sceneEntities.stream()
                .filter(e -> !e.id.equalsIgnoreCase(targetEntity.id) && !e.isDescendantOf(targetEntity, sceneEntities))
                .toList() : List.of();

        float listY = cardMaxY - 0.24f;

        if (candidates.isEmpty()) {
            UIGeometryBatch.addBoundedText(buffer, "NO OTHER VALID PARENT CANDIDATES", cardMinX + 0.05f, listY, cardMaxX - 0.05f, 1.1f, 0.5f, 0.5f, 0.5f, 1.0f);
        } else {
            for (Entity parentCandidate : candidates) {
                if (listY < cardMinY + 0.05f) break;

                UIGeometryBatch.addQuad(buffer, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.15f, 0.18f, 0.24f, 1.0f);
                UIGeometryBatch.addFrameBorder(buffer, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.001f, 0.25f, 0.30f, 0.40f, 1.0f);

                UIGeometryBatch.addBoundedText(buffer, "• " + parentCandidate.name, cardMinX + 0.05f, listY - 0.01f, cardMaxX - 0.22f, 1.1f, 0.95f, 0.95f, 0.95f, 1.0f);

                UIGeometryBatch.addQuad(buffer, cardMaxX - 0.20f, listY - 0.04f, cardMaxX - 0.05f, listY + 0.02f, 0.0f, 0.45f, 0.70f, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, "PARENT", cardMaxX - 0.18f, listY - 0.01f, cardMaxX - 0.06f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                listY -= 0.10f;
            }
        }
    }
    public static void renderEntityParentPickerModal(List<Float> verts, Entity targetEntity, List<Entity> sceneEntities) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderEntityParentPickerModal(tempBuf, targetEntity, sceneEntities);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}