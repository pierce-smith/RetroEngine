package com.retro.engine.editor.ui.overlays;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.panels.HierarchyPanel;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.entity.Entity;

import java.io.File;
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
        float actionY0 = PICKER_ACTION_Y0, actionY1 = PICKER_ACTION_Y1;
        float actionMidY = (actionY0 + actionY1) * 0.5f;

        // Unbind / Clear Button
        UIGeometryBatch.addRoundedCard(buffer, -0.42f, actionY0, -0.07f, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "NONE / UNBIND", -0.42f, -0.07f, actionMidY, 0.82f, EditorTheme.RED);

        // Import Disk Button
        UIGeometryBatch.addRoundedQuad(buffer, PICKER_IMPORT_X0, actionY0, PICKER_IMPORT_X1, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        centeredTextIn(buffer, "+ IMPORT DISK...", PICKER_IMPORT_X0, PICKER_IMPORT_X1, actionMidY, 0.82f, EditorTheme.TEXT);

        List<AssetMeta> filtered = assetPickerEntries(pickerFilterType, assetDirectory);
        updatePickerScrollBounds(filtered.size());

        if (filtered.isEmpty()) {
            centeredTextIn(buffer, "NO " + pickerFilterType.name() + " ASSETS IN PROJECT", cardMinX, cardMaxX, 0.18f, 0.95f, EditorTheme.TEXT_MUTED);
            centeredTextIn(buffer, "CLICK [+ IMPORT DISK...] TO BROWSE NATIVE FILES", cardMinX, cardMaxX, 0.12f, 0.80f, EditorTheme.TEXT_DISABLED);
            return;
        }

        // Rows scroll beneath the action buttons and are clipped to the list band
        buffer.beginScreenClip(cardMinX, PICKER_LIST_BOTTOM, cardMaxX, PICKER_LIST_TOP);
        for (int i = 0; i < filtered.size(); i++) {
            float rowTop = pickerRowTop(i);
            float rowBottom = rowTop - PICKER_ROW_H;
            if (rowBottom > PICKER_LIST_TOP) continue;
            if (rowTop < PICKER_LIST_BOTTOM) break;
            float rowMid = (rowTop + rowBottom) * 0.5f;
            AssetMeta asset = filtered.get(i);

            // Asset Row Card
            UIGeometryBatch.addRoundedCard(buffer, PICKER_ROW_X0, rowBottom, PICKER_ROW_X1, rowTop, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);

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
        }
        buffer.endScreenClip();

        renderPickerScrollbar(buffer);
    }

    /** Rows the asset picker lists for {@code filterType}, alphabetical by name; MainMenuBarInputDelegate hit-tests the same list. */
    public static List<AssetMeta> assetPickerEntries(AssetType filterType, List<AssetMeta> assetDirectory) {
        return AssetDatabase.filterByTypeSorted(assetDirectory, filterType);
    }

    /** True when (ndcX, ndcY) lies on the asset picker's [+ IMPORT DISK...] button. */
    public static boolean isOverPickerImportButton(float ndcX, float ndcY) {
        return ndcX >= PICKER_IMPORT_X0 && ndcX <= PICKER_IMPORT_X1 && ndcY >= PICKER_ACTION_Y0 && ndcY <= PICKER_ACTION_Y1;
    }

    /**
     * [+ IMPORT DISK...]: opens the OS file dialog filtered to {@code filterType}, imports the chosen file into the
     * project's Assets/ folder and returns its registered asset (so the caller can bind it as the picker's choice).
     * Returns null when the dialog is cancelled, the type has no importable files, or the import did not register.
     */
    public static AssetMeta importPickerAssetFromDisk(AssetType filterType, AssetDatabase assetDb) {
        if (filterType == null || assetDb == null) return null;
        String[] extensions = importExtensions(filterType);
        if (extensions.length == 0) return null;

        String chosen = NativeFileDialog.openFileDialog("Import " + filterType.name() + " Asset", "",
                importFilterDescription(filterType, extensions), extensions);
        if (chosen == null || chosen.trim().isEmpty()) return null;

        // Copies outside files into the current Assets/ folder and rescans, so the picker list refreshes on the next frame
        List<String> imported = assetDb.onFilesDropped(List.of(chosen));
        if (imported.isEmpty()) return null;
        File importedFile = new File(imported.get(0)).getAbsoluteFile();
        for (AssetMeta meta : assetPickerEntries(filterType, assetDb.getAssetDirectory())) {
            if (meta.absolutePath != null && new File(meta.absolutePath).getAbsoluteFile().equals(importedFile)) {
                return meta;
            }
        }
        return null;
    }

    /** File extensions the importer accepts for each picker type (same sets as the Inspector IMPORT buttons). */
    private static String[] importExtensions(AssetType type) {
        return switch (type) {
            case TEXTURE -> new String[] {"png", "jpg", "jpeg", "tga"};
            case MESH_3D -> new String[] {"obj", "fbx", "gltf", "glb"};
            case AUDIO -> new String[] {"wav", "mp3", "ogg"};
            case SCRIPT -> new String[] {"java"};
            default -> new String[0];
        };
    }

    private static String importFilterDescription(AssetType type, String[] extensions) {
        StringBuilder sb = new StringBuilder(switch (type) {
            case TEXTURE -> "Image Files";
            case MESH_3D -> "3D Models";
            case AUDIO -> "Audio Files";
            case SCRIPT -> "Java Scripts";
            default -> "Supported Files";
        }).append(" (");
        for (int i = 0; i < extensions.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("*.").append(extensions[i]);
        }
        return sb.append(')').toString();
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
        float actionY0 = PICKER_ACTION_Y0, actionY1 = PICKER_ACTION_Y1;
        float actionMidY = (actionY0 + actionY1) * 0.5f;

        UIGeometryBatch.addRoundedCard(buffer, -0.42f, actionY0, 0.42f, actionY1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "UNPARENT / MOVE TO ROOT SCENE", -0.42f, 0.42f, actionMidY, 0.85f, EditorTheme.RED);

        // Same flattened tree (and expand/collapse state) as the Hierarchy panel
        List<HierarchyPanel.HierarchyNode> tree = HierarchyPanel.buildFlattenedTree(sceneEntities);
        updatePickerScrollBounds(tree.size());

        if (tree.isEmpty()) {
            centeredTextIn(buffer, "NO OTHER VALID PARENT CANDIDATES", cardMinX, cardMaxX, 0.18f, 0.95f, EditorTheme.TEXT_MUTED);
            return;
        }

        float indentStep = parentPickerIndentStep(HierarchyPanel.getLastMaxDepth());
        float guideHalf = UIGeometryBatch.squareWidth(buffer, 0.002f) * 0.5f;
        float triW = UIGeometryBatch.squareWidth(buffer, 0.016f);
        float gapHalf = (PICKER_ROW_PITCH - PICKER_ROW_H) * 0.5f;

        // Rows scroll beneath the action button and are clipped to the list band
        buffer.beginScreenClip(cardMinX, PICKER_LIST_BOTTOM, cardMaxX, PICKER_LIST_TOP);
        for (int i = 0; i < tree.size(); i++) {
            float rowTop = pickerRowTop(i);
            float rowBottom = rowTop - PICKER_ROW_H;
            if (rowBottom > PICKER_LIST_TOP) continue;
            if (rowTop < PICKER_LIST_BOTTOM) break;
            float rowMid = (rowTop + rowBottom) * 0.5f;

            HierarchyPanel.HierarchyNode node = tree.get(i);
            Entity entity = node.entity;
            if (entity == null) continue;
            boolean selectable = isSelectableParent(targetEntity, entity, sceneEntities);
            boolean currentParent = selectable && entity.id != null && entity.id.equalsIgnoreCase(targetEntity.parentId);
            float alpha = selectable ? 1.0f : 0.40f;

            UIGeometryBatch.addRoundedCard(buffer, PICKER_ROW_X0, rowBottom, PICKER_ROW_X1, rowTop, EditorTheme.RADIUS_SMALL,
                    selectable ? EditorTheme.SURFACE_RAISED : EditorTheme.SURFACE, EditorTheme.BORDER, 1.0f);

            // Indent guides through the row gaps (so levels read as continuous branches), plus an elbow into this node
            for (int d = 0; d < node.depth; d++) {
                float gx = parentPickerIndentX(d, indentStep) + GUIDE_OFFSET;
                UIGeometryBatch.addQuad(buffer, gx - guideHalf, rowBottom - gapHalf, gx + guideHalf, rowTop + gapHalf, EditorTheme.BORDER_STRONG, 1.0f);
            }
            float indentX = parentPickerIndentX(node.depth, indentStep);
            if (node.depth > 0) {
                float gx = parentPickerIndentX(node.depth - 1, indentStep) + GUIDE_OFFSET;
                if (gx < indentX - 0.004f) {
                    UIGeometryBatch.addQuad(buffer, gx, rowMid - 0.001f, indentX - 0.004f, rowMid + 0.001f, EditorTheme.BORDER_STRONG, 1.0f);
                }
            }

            // Disclosure triangle (clickable here too; shares entity.expandedInHierarchy with the panel)
            if (node.hasChildren) {
                if (entity.expandedInHierarchy) {
                    UIGeometryBatch.addTriangle(buffer, indentX, rowMid + 0.007f, indentX + triW, rowMid + 0.007f, indentX + triW * 0.5f, rowMid - 0.008f, EditorTheme.TEXT_MUTED, 1.0f);
                } else {
                    UIGeometryBatch.addTriangle(buffer, indentX + triW * 0.15f, rowMid + 0.008f, indentX + triW * 0.15f, rowMid - 0.008f, indentX + triW, rowMid, EditorTheme.TEXT_MUTED, 1.0f);
                }
            }

            // Category badge, as in the Hierarchy
            HierarchyPanel.Category cat = HierarchyPanel.categoryOf(entity);
            float badgeH = 0.032f;
            float badgeX0 = indentX + triW + 0.008f;
            float badgeX1 = badgeX0 + Math.max(UIGeometryBatch.squareWidth(buffer, badgeH), UIGeometryBatch.textWidth(buffer, 1, 0.75f) + 0.008f);
            UIGeometryBatch.addRoundedQuad(buffer, badgeX0, rowMid - badgeH * 0.5f, badgeX1, rowMid + badgeH * 0.5f, 0.006f, cat.color, 0.9f * alpha);
            centeredTextIn(buffer, cat.glyph, badgeX0, badgeX1, rowMid, 0.75f, EditorTheme.BACKGROUND);

            UIGeometryBatch.addTruncatedText(buffer, entity.name, badgeX1 + 0.012f, rowMid + 0.009f, 0.22f, 0.90f,
                    selectable ? EditorTheme.TEXT : EditorTheme.TEXT_DISABLED, 1.0f);

            // PARENT button for valid targets; a status tag for the current parent and for the loop-forming rows
            float btnX0 = 0.24f, btnX1 = 0.40f;
            if (currentParent) {
                centeredTextIn(buffer, "CURRENT", btnX0, btnX1, rowMid, 0.80f, EditorTheme.TEXT_MUTED);
            } else if (selectable) {
                UIGeometryBatch.addRoundedQuad(buffer, btnX0, rowMid - 0.025f, btnX1, rowMid + 0.025f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
                centeredTextIn(buffer, "PARENT", btnX0, btnX1, rowMid, 0.80f, EditorTheme.TEXT);
            } else {
                centeredTextIn(buffer, entity == targetEntity ? "SELF" : "CHILD", btnX0, btnX1, rowMid, 0.80f, EditorTheme.TEXT_DISABLED);
            }
        }
        buffer.endScreenClip();

        renderPickerScrollbar(buffer);
    }

    // --- Parent picker tree layout (OverlaySystem hit-tests the disclosure triangles with the same values) ---
    private static final float PICKER_INDENT_X0 = -0.40f, PICKER_INDENT = 0.04f, PICKER_MAX_INDENT_X = -0.12f;
    private static final float GUIDE_OFFSET = 0.006f; // guides sit under the parent's disclosure triangle
    private static final float PICKER_DISCLOSURE_HIT_W = 0.035f;

    /** Per-level indent: the Hierarchy's step, shrunk for deep trees so every level stays distinct. */
    public static float parentPickerIndentStep(int maxDepth) {
        return maxDepth > 0 ? Math.min(PICKER_INDENT, (PICKER_MAX_INDENT_X - PICKER_INDENT_X0) / maxDepth) : PICKER_INDENT;
    }

    public static float parentPickerIndentX(int depth, float indentStep) {
        return PICKER_INDENT_X0 + depth * indentStep;
    }

    /** True when {@code ndcX} lands on the disclosure triangle of a parent-picker row at {@code depth}. */
    public static boolean hitsParentPickerDisclosure(float ndcX, int depth, int maxDepth) {
        float x0 = parentPickerIndentX(depth, parentPickerIndentStep(maxDepth));
        return ndcX >= x0 - 0.008f && ndcX <= x0 + PICKER_DISCLOSURE_HIT_W;
    }

    /**
     * True when {@code candidate} may adopt {@code target}: anything except the target itself and its own subtree at
     * any depth, which would form a parent loop.
     */
    public static boolean isSelectableParent(Entity target, Entity candidate, List<Entity> sceneEntities) {
        if (target == null || candidate == null || candidate == target) return false;
        if (candidate.id != null && candidate.id.equalsIgnoreCase(target.id)) return false;
        return !candidate.isDescendantOf(target, sceneEntities);
    }

    // --- Shared picker list layout + scroll (screen-space NDC). Only one picker modal is open at a time, so the
    // asset and parent pickers share one scroll state; MainMenuBarInputDelegate / OverlaySystem hit-test these values ---
    public static final float PICKER_ACTION_Y0 = 0.27f, PICKER_ACTION_Y1 = 0.33f;
    public static final float PICKER_IMPORT_X0 = 0.07f, PICKER_IMPORT_X1 = 0.42f;
    public static final float PICKER_LIST_TOP = 0.23f, PICKER_LIST_BOTTOM = -0.43f;
    public static final float PICKER_ROW_H = 0.08f, PICKER_ROW_PITCH = 0.10f;
    public static final float PICKER_ROW_X0 = -0.42f, PICKER_ROW_X1 = 0.42f;
    public static final float PICKER_SCROLLBAR_X0 = 0.428f, PICKER_SCROLLBAR_X1 = 0.437f;
    public static final float PICKER_SCROLLBAR_HIT_X0 = 0.421f, PICKER_SCROLLBAR_HIT_X1 = 0.45f;
    private static final float PICKER_MIN_THUMB_H = 0.06f;

    private static float pickerScrollY = 0.0f;
    private static float pickerMaxScrollY = 0.0f;

    public static void resetPickerScroll() { pickerScrollY = 0.0f; }
    public static void setPickerScrollY(float offset) { pickerScrollY = Math.max(0.0f, Math.min(pickerMaxScrollY, offset)); }
    public static void scrollPicker(float delta) { setPickerScrollY(pickerScrollY + delta); }

    public static void updatePickerScrollBounds(int rowCount) {
        float contentH = rowCount > 0 ? (rowCount - 1) * PICKER_ROW_PITCH + PICKER_ROW_H : 0.0f;
        pickerMaxScrollY = Math.max(0.0f, contentH - (PICKER_LIST_TOP - PICKER_LIST_BOTTOM));
        pickerScrollY = Math.max(0.0f, Math.min(pickerMaxScrollY, pickerScrollY));
    }

    public static float pickerRowTop(int i) {
        return PICKER_LIST_TOP - i * PICKER_ROW_PITCH + pickerScrollY;
    }

    /** List row under the cursor, or -1 (outside the clipped list band, in a gap, or past the last row). */
    public static int pickerRowAt(float ndcX, float ndcY, int rowCount) {
        if (ndcX < PICKER_ROW_X0 || ndcX > PICKER_ROW_X1 || ndcY > PICKER_LIST_TOP || ndcY < PICKER_LIST_BOTTOM) return -1;
        float fromTop = PICKER_LIST_TOP + pickerScrollY - ndcY;
        int row = (int) Math.floor(fromTop / PICKER_ROW_PITCH);
        if (row < 0 || row >= rowCount || fromTop - row * PICKER_ROW_PITCH > PICKER_ROW_H) return -1;
        return row;
    }

    public static boolean pickerHasScrollbar() { return pickerMaxScrollY > 0.001f; }

    public static float pickerThumbHeight() {
        float trackH = PICKER_LIST_TOP - PICKER_LIST_BOTTOM;
        return Math.max(PICKER_MIN_THUMB_H, trackH * trackH / (trackH + pickerMaxScrollY));
    }

    public static float pickerThumbTop() {
        float trackH = PICKER_LIST_TOP - PICKER_LIST_BOTTOM;
        return PICKER_LIST_TOP - (pickerMaxScrollY > 0.0f ? pickerScrollY / pickerMaxScrollY : 0.0f) * (trackH - pickerThumbHeight());
    }

    public static boolean inPickerScrollbar(float ndcX, float ndcY) {
        return pickerHasScrollbar() && ndcX >= PICKER_SCROLLBAR_HIT_X0 && ndcX <= PICKER_SCROLLBAR_HIT_X1
                && ndcY >= PICKER_LIST_BOTTOM && ndcY <= PICKER_LIST_TOP;
    }

    /** Scrolls so the thumb's top edge sits at {@code thumbTopY} (used by thumb dragging). */
    public static void setPickerThumbTop(float thumbTopY) {
        float travel = (PICKER_LIST_TOP - PICKER_LIST_BOTTOM) - pickerThumbHeight();
        if (travel <= 0.0f) return;
        setPickerScrollY((PICKER_LIST_TOP - thumbTopY) / travel * pickerMaxScrollY);
    }

    private static void renderPickerScrollbar(UIGeometryBatch.Buffer buffer) {
        if (!pickerHasScrollbar()) return;
        float top = pickerThumbTop();
        UIGeometryBatch.addPill(buffer, PICKER_SCROLLBAR_X0, top - pickerThumbHeight(), PICKER_SCROLLBAR_X1, top, EditorTheme.GRAPHITE, 0.85f);
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
