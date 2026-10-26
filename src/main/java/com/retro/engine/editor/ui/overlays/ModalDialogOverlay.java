package com.retro.engine.editor.ui.overlays;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.entity.Entity;

import java.util.List;

/**
 * ModalDialogOverlay encapsulates rendering and layout for modal dialog overlays.
 * Updated to match the macOS "Dark Graphite" design language with rounded cards,
 * type badges, and crisp button controls.
 */
public class ModalDialogOverlay {

    public static void renderAssetPickerModal(UIGeometryBatch.Buffer buffer, AssetType pickerFilterType, List<AssetMeta> assetDirectory) {
        if (pickerFilterType == null) return;

        // Fullscreen dim overlay
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0x000000, 0.65f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        // Modal Card Container
        UIGeometryBatch.addRoundedCard(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.016f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 0.98f);

        // Header Bar
        float headerY0 = cardMaxY - 0.09f;
        UIGeometryBatch.addRoundedQuad(buffer, cardMinX + 0.003f, headerY0, cardMaxX - 0.003f, cardMaxY - 0.003f, 0.014f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, cardMinX + 0.003f, headerY0, cardMaxX - 0.003f, cardMaxY - 0.02f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, cardMinX, headerY0, cardMaxX, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        String title = "SELECT " + pickerFilterType.name() + " ASSET";
        UIGeometryBatch.addBoundedText(buffer, title, cardMinX + 0.03f, cardMaxY - 0.035f, cardMaxX - 0.10f, 1.0f, EditorTheme.TEXT, 1.0f);

        // Close "X" Button (Red Badge with ASCII 'X')
        UIGeometryBatch.addRoundedQuad(buffer, cardMaxX - 0.075f, cardMaxY - 0.075f, cardMaxX - 0.02f, cardMaxY - 0.02f, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 0.90f);
        centeredTextIn(buffer, "X", cardMaxX - 0.075f, cardMaxX - 0.02f, cardMaxY - 0.0475f, 0.9f, EditorTheme.TEXT);

        // Top Action Buttons: [NONE / UNBIND] & [+ IMPORT DISK...]
        float actionY0 = 0.27f, actionY1 = 0.33f;
        float actionMidY = (actionY0 + actionY1) * 0.5f;

        // Unbind / Clear Button
        UIGeometryBatch.addRoundedCard(buffer, -0.42f, actionY0, -0.07f, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "NONE / UNBIND", -0.42f, -0.07f, actionMidY, 0.82f, EditorTheme.RED);

        // Import Disk Button
        UIGeometryBatch.addRoundedQuad(buffer, 0.07f, actionY0, 0.42f, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        centeredTextIn(buffer, "+ IMPORT DISK...", 0.07f, 0.42f, actionMidY, 0.82f, EditorTheme.TEXT);

        List<AssetMeta> filtered = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.type == pickerFilterType)
                .toList() : List.of();

        float listY = 0.20f;

        if (filtered.isEmpty()) {
            centeredTextIn(buffer, "NO " + pickerFilterType.name() + " ASSETS IN PROJECT", cardMinX, cardMaxX, listY - 0.02f, 0.95f, EditorTheme.TEXT_MUTED);
            centeredTextIn(buffer, "CLICK [+ IMPORT DISK...] TO BROWSE NATIVE FILES", cardMinX, cardMaxX, listY - 0.08f, 0.80f, EditorTheme.TEXT_DISABLED);
        } else {
            for (AssetMeta asset : filtered) {
                if (listY < -0.38f) break;

                float rowTop = listY + 0.03f;
                float rowBottom = listY - 0.05f;
                float rowMid = (rowTop + rowBottom) * 0.5f;

                // Asset Row Card
                UIGeometryBatch.addRoundedCard(buffer, -0.42f, rowBottom, 0.42f, rowTop, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);

                // Type Badge
                float badgeH = 0.032f;
                float badgeX0 = -0.40f;
                float badgeX1 = badgeX0 + Math.max(UIGeometryBatch.squareWidth(buffer, badgeH), UIGeometryBatch.textWidth(buffer, 1, 0.75f) + 0.008f);
                UIGeometryBatch.addRoundedQuad(buffer, badgeX0, rowMid - badgeH * 0.5f, badgeX1, rowMid + badgeH * 0.5f, 0.006f, typeColor(pickerFilterType), 0.95f);
                centeredTextIn(buffer, typeGlyph(pickerFilterType), badgeX0, badgeX1, rowMid, 0.75f, EditorTheme.BACKGROUND);

                // Asset Name
                UIGeometryBatch.addTruncatedText(buffer, asset.name, badgeX1 + 0.012f, rowMid + 0.009f, 0.22f, 0.90f, EditorTheme.TEXT, 1.0f);

                // SELECT Button
                float btnX0 = 0.24f, btnX1 = 0.40f;
                UIGeometryBatch.addRoundedQuad(buffer, btnX0, rowMid - 0.025f, btnX1, rowMid + 0.025f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
                centeredTextIn(buffer, "SELECT", btnX0, btnX1, rowMid, 0.80f, EditorTheme.TEXT);

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

        // Fullscreen dim overlay
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0x000000, 0.65f);

        float cardMinX = -0.45f, cardMaxX = 0.45f;
        float cardMinY = -0.45f, cardMaxY = 0.45f;

        // Modal Card Container
        UIGeometryBatch.addRoundedCard(buffer, cardMinX, cardMinY, cardMaxX, cardMaxY, 0.016f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 0.98f);

        // Header Bar
        float headerY0 = cardMaxY - 0.09f;
        UIGeometryBatch.addRoundedQuad(buffer, cardMinX + 0.003f, headerY0, cardMaxX - 0.003f, cardMaxY - 0.003f, 0.014f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, cardMinX + 0.003f, headerY0, cardMaxX - 0.003f, cardMaxY - 0.02f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, cardMinX, headerY0, cardMaxX, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        String title = "SET PARENT FOR '" + targetEntity.name.toUpperCase() + "'";
        UIGeometryBatch.addBoundedText(buffer, title, cardMinX + 0.03f, cardMaxY - 0.035f, cardMaxX - 0.10f, 1.0f, EditorTheme.TEXT, 1.0f);

        // Close "X" Button (Red Badge with ASCII 'X')
        UIGeometryBatch.addRoundedQuad(buffer, cardMaxX - 0.075f, cardMaxY - 0.075f, cardMaxX - 0.02f, cardMaxY - 0.02f, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 0.90f);
        centeredTextIn(buffer, "X", cardMaxX - 0.075f, cardMaxX - 0.02f, cardMaxY - 0.0475f, 0.9f, EditorTheme.TEXT);

        // Option 1: [UNPARENT / MOVE TO ROOT SCENE]
        float actionY0 = 0.27f, actionY1 = 0.33f;
        float actionMidY = (actionY0 + actionY1) * 0.5f;

        UIGeometryBatch.addRoundedCard(buffer, -0.42f, actionY0, 0.42f, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "UNPARENT / MOVE TO ROOT SCENE", -0.42f, 0.42f, actionMidY, 0.85f, EditorTheme.RED);

        // Filter valid candidate parent entities
        List<Entity> candidates = (sceneEntities != null) ? sceneEntities.stream()
                .filter(e -> e != null && !e.id.equalsIgnoreCase(targetEntity.id) && !e.isDescendantOf(targetEntity, sceneEntities))
                .toList() : List.of();

        float listY = 0.20f;

        if (candidates.isEmpty()) {
            centeredTextIn(buffer, "NO OTHER VALID PARENT CANDIDATES", cardMinX, cardMaxX, listY - 0.02f, 0.95f, EditorTheme.TEXT_MUTED);
        } else {
            for (Entity parentCandidate : candidates) {
                if (listY < -0.38f) break;

                float rowTop = listY + 0.03f;
                float rowBottom = listY - 0.05f;
                float rowMid = (rowTop + rowBottom) * 0.5f;

                UIGeometryBatch.addRoundedCard(buffer, -0.42f, rowBottom, 0.42f, rowTop, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);

                // Parent Entity Name
                UIGeometryBatch.addTruncatedText(buffer, "• " + parentCandidate.name, -0.39f, rowMid + 0.009f, 0.22f, 0.90f, EditorTheme.TEXT, 1.0f);

                // PARENT Button
                float btnX0 = 0.24f, btnX1 = 0.40f;
                UIGeometryBatch.addRoundedQuad(buffer, btnX0, rowMid - 0.025f, btnX1, rowMid + 0.025f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
                centeredTextIn(buffer, "PARENT", btnX0, btnX1, rowMid, 0.80f, EditorTheme.TEXT);

                listY -= 0.10f;
            }
        }
    }

    public static void renderEntityParentPickerModal(List<Float> verts, Entity targetEntity, List<Entity> sceneEntities) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderEntityParentPickerModal(tempBuf, targetEntity, sceneEntities);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    private static int typeColor(AssetType type) {
        if (type == null) return EditorTheme.GRAPHITE;
        return switch (type) {
            case FOLDER -> EditorTheme.TEAL;
            case PREFAB -> EditorTheme.PURPLE;
            case MESH_3D -> EditorTheme.ORANGE;
            case TEXTURE -> EditorTheme.GREEN;
            case AUDIO -> EditorTheme.PINK;
            case SCENE -> EditorTheme.ACCENT;
            case SCRIPT -> EditorTheme.YELLOW;
            default -> EditorTheme.GRAPHITE;
        };
    }

    private static String typeGlyph(AssetType type) {
        if (type == null) return "F";
        return switch (type) {
            case FOLDER -> "DIR";
            case PREFAB -> "P";
            case MESH_3D -> "M";
            case TEXTURE -> "T";
            case AUDIO -> "A";
            case SCENE -> "S";
            case SCRIPT -> "J";
            default -> "F";
        };
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }
}
