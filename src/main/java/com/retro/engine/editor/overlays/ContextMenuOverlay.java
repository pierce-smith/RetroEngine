package com.retro.engine.editor.overlays;

import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.panels.MenuItem;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.util.List;

/**
 * ContextMenuOverlay encapsulates rendering and layout for right-click popup context menus.
 * Updated to match the macOS Dark Graphite aesthetic with rounded cards, metallic borders,
 * and subtle drop shadows while maintaining exact hit-testing boundaries.
 */
public class ContextMenuOverlay {

    public static void render(UIGeometryBatch.Buffer buffer, float rawNdcX, float rawNdcY, List<MenuItem> items) {
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

        // Soft drop shadow
        UIGeometryBatch.addRoundedQuad(buffer, minX + 0.004f, minY - 0.008f, maxX + 0.004f, maxY - 0.002f, EditorTheme.RADIUS * 1.4f, 0x000000, 0.35f);

        // Dark Graphite popup card container
        UIGeometryBatch.addRoundedCard(buffer, minX, minY, maxX, maxY, EditorTheme.RADIUS * 1.4f, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 0.98f);

        float currentY = maxY - 0.01f;

        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            float rowTop = currentY;
            float rowBottom = currentY - itemHeight;

            // Row item surface
            UIGeometryBatch.addRoundedCard(buffer, minX + 0.005f, rowBottom + 0.003f, maxX - 0.005f, rowTop - 0.003f, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE, EditorTheme.BORDER, 1.0f);

            // Item text
            float textMidY = (rowTop + rowBottom) * 0.5f;
            UIGeometryBatch.addTruncatedText(
                    buffer,
                    item.label(),
                    minX + 0.02f,
                    textMidY + 1.0f * 0.009f,
                    maxX - 0.015f,
                    1.0f,
                    EditorTheme.TEXT, 1.0f
            );

            currentY -= itemHeight;
        }
    }

    public static void render(List<Float> verts, float rawNdcX, float rawNdcY, List<MenuItem> items) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, rawNdcX, rawNdcY, items);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static boolean processClick(float ndcX, float ndcY, float rawNdcX, float rawNdcY, List<MenuItem> items) {
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
                MenuItem clicked = items.get(index);
                if (clicked.action() != null) {
                    String label = (clicked.label() != null) ? clicked.label().toLowerCase() : "";
                    if (!label.contains("spawn") && !label.contains("delete") && !label.contains("duplicate") &&
                            !label.contains("open scene") && !label.contains("save scene") && !label.contains("import") &&
                            !label.contains("folder") && !label.contains("parent")) {
                        UISoundPlayer.play(UISound.CLICK);
                    }
                    clicked.action().run();
                }
            }
            return true;
        }

        return false;
    }
}
