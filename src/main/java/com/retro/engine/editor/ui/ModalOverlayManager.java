package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.AssetType;

import java.util.List;

/**
 * ModalOverlayManager encapsulates rendering and layout for modal dialog overlays.
 */
public class ModalOverlayManager {

    public static void renderAssetPickerModal(List<Float> verts, AssetType pickerFilterType, List<AssetEntry> assetDirectory) {
        if (pickerFilterType == null) return;

        UIBatchBuilder.addQuad(verts, -1.0f, -1.0f, 1.0f, 1.0f, 0.02f, 0.02f, 0.04f, 0.75f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        UIBatchBuilder.addQuad(verts, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.10f, 0.11f, 0.15f, 0.98f);
        UIBatchBuilder.addFrameBorder(verts, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.004f, 0.0f, 0.55f, 0.90f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMinX, cardMaxY - 0.10f, cardMaxX, cardMaxY, 0.16f, 0.18f, 0.24f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "SELECT " + pickerFilterType.name() + " ASSET", cardMinX + 0.03f, cardMaxY - 0.04f, cardMaxX - 0.10f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMaxX - 0.08f, cardMaxY - 0.08f, cardMaxX - 0.02f, cardMaxY - 0.02f, 0.65f, 0.15f, 0.15f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "X", cardMaxX - 0.065f, cardMaxY - 0.035f, cardMaxX - 0.03f, 1.2f, 1.0f, 1.0f, 1.0f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.25f, 0.15f, 0.15f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, cardMinX + 0.03f, cardMaxY - 0.18f, cardMinX + 0.38f, cardMaxY - 0.12f, 0.002f, 0.8f, 0.2f, 0.2f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "[NONE / UNBIND]", cardMinX + 0.05f, cardMaxY - 0.14f, cardMinX + 0.36f, 1.0f, 1.0f, 0.6f, 0.6f, 1.0f);

        UIBatchBuilder.addQuad(verts, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.15f, 0.45f, 0.30f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, cardMaxX - 0.38f, cardMaxY - 0.18f, cardMaxX - 0.03f, cardMaxY - 0.12f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "[IMPORT DISK...]", cardMaxX - 0.36f, cardMaxY - 0.14f, cardMaxX - 0.05f, 1.0f, 0.4f, 1.0f, 0.6f, 1.0f);

        List<AssetEntry> filtered = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.type == pickerFilterType)
                .toList() : List.of();

        float listY = cardMaxY - 0.24f;

        if (filtered.isEmpty()) {
            UIBatchBuilder.addBoundedText(verts, "NO " + pickerFilterType.name() + " ASSETS IN PROJECT", cardMinX + 0.05f, listY, cardMaxX - 0.05f, 1.1f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "CLICK [IMPORT DISK...] TO BROWSE NATIVE FILES", cardMinX + 0.05f, listY - 0.08f, cardMaxX - 0.05f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
        } else {
            for (AssetEntry asset : filtered) {
                if (listY < cardMinY + 0.05f) break;

                UIBatchBuilder.addQuad(verts, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.15f, 0.18f, 0.24f, 1.0f);
                UIBatchBuilder.addFrameBorder(verts, cardMinX + 0.03f, listY - 0.05f, cardMaxX - 0.03f, listY + 0.03f, 0.001f, 0.25f, 0.30f, 0.40f, 1.0f);

                UIBatchBuilder.addBoundedText(verts, "• " + asset.name, cardMinX + 0.05f, listY - 0.01f, cardMaxX - 0.22f, 1.1f, 0.95f, 0.95f, 0.95f, 1.0f);

                UIBatchBuilder.addQuad(verts, cardMaxX - 0.20f, listY - 0.04f, cardMaxX - 0.05f, listY + 0.02f, 0.0f, 0.45f, 0.70f, 1.0f);
                UIBatchBuilder.addBoundedText(verts, "SELECT", cardMaxX - 0.18f, listY - 0.01f, cardMaxX - 0.06f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                listY -= 0.10f;
            }
        }
    }
}
