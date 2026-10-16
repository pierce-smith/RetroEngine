package com.retro.engine.editor.ui;

import com.retro.engine.editor.audio.UISoundManager;
import com.retro.engine.editor.audio.UISoundManager.UISound;

import java.util.List;

public class ContextMenuOverlayManager {

    public static void render(List<Float> verts, float rawNdcX, float rawNdcY, List<ContextMenuItem> items) {
        if (items == null || items.isEmpty()) return;

        float width = 0.38f;
        float itemHeight = 0.06f;
        float totalHeight = items.size() * itemHeight + 0.02f;

        float minX = rawNdcX;
        float maxX = minX + width;
        if (maxX > 0.95f) {
            maxX = 0.95f;
            minX = maxX - width;
        }

        float maxY = rawNdcY;
        float minY = maxY - totalHeight;
        if (minY < -0.95f) {
            minY = -0.95f;
            maxY = minY + totalHeight;
        }

        // Dark background quad with border
        UIBatchBuilder.addQuad(verts, minX, minY, maxX, maxY, 0.10f, 0.11f, 0.15f, 0.98f);
        UIBatchBuilder.addFrameBorder(verts, minX, minY, maxX, maxY, 0.003f, 0.0f, 0.55f, 0.90f, 1.0f);

        float currentY = maxY - 0.01f;

        for (int i = 0; i < items.size(); i++) {
            ContextMenuItem item = items.get(i);
            float rowTop = currentY;
            float rowBottom = currentY - itemHeight;

            UIBatchBuilder.addQuad(verts, minX + 0.005f, rowBottom + 0.005f, maxX - 0.005f, rowTop - 0.005f, 0.16f, 0.18f, 0.24f, 1.0f);
            UIBatchBuilder.addFrameBorder(verts, minX + 0.005f, rowBottom + 0.005f, maxX - 0.005f, rowTop - 0.005f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);

            UIBatchBuilder.addBoundedText(
                verts,
                item.label(),
                minX + 0.02f,
                rowBottom + 0.015f,
                maxX - 0.01f,
                1.0f,
                0.95f, 0.95f, 0.95f, 1.0f
            );

            currentY -= itemHeight;
        }
    }

    public static boolean processClick(float ndcX, float ndcY, float rawNdcX, float rawNdcY, List<ContextMenuItem> items) {
        if (items == null || items.isEmpty()) return false;

        float width = 0.38f;
        float itemHeight = 0.06f;
        float totalHeight = items.size() * itemHeight + 0.02f;

        float minX = rawNdcX;
        float maxX = minX + width;
        if (maxX > 0.95f) {
            maxX = 0.95f;
            minX = maxX - width;
        }

        float maxY = rawNdcY;
        float minY = maxY - totalHeight;
        if (minY < -0.95f) {
            minY = -0.95f;
            maxY = minY + totalHeight;
        }

        if (ndcX >= minX && ndcX <= maxX && ndcY >= minY && ndcY <= maxY) {
            float relativeY = maxY - 0.01f - ndcY;
            int index = (int) (relativeY / itemHeight);
            if (index >= 0 && index < items.size()) {
                ContextMenuItem clicked = items.get(index);
                if (clicked.action() != null) {
                    String label = (clicked.label() != null) ? clicked.label().toLowerCase() : "";
                    if (!label.contains("spawn") && !label.contains("delete") && !label.contains("duplicate") &&
                        !label.contains("open scene") && !label.contains("save scene") && !label.contains("import") &&
                        !label.contains("folder") && !label.contains("parent")) {
                        UISoundManager.play(UISound.CLICK);
                    }
                    clicked.action().run();
                }
            }
            return true;
        }

        return false;
    }
}
