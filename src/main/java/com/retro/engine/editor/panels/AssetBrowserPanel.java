package com.retro.engine.editor.panels;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.UIContext;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AssetBrowserPanel handles rendering the Bottom Panel (Resource Cards & Nested Folders).
 * Hardened with zero-allocation asset list filtering via static scratch buffer.
 *
 * Header button and card geometry is shared with {@code AssetBrowserInputDelegate}.
 */
public class AssetBrowserPanel {

    // --- Shared layout (design-space NDC) ---
    public static final float HEADER_Y0 = -0.50f, HEADER_Y1 = -0.42f;
    public static final float NEW_FOLDER_X0 = 0.30f, NEW_FOLDER_X1 = 0.62f;
    public static final float IMPORT_X0 = 0.65f, IMPORT_X1 = 0.98f;

    public static final float CARD_START_X = -0.95f;
    public static final float CARD_W = 0.32f, CARD_PITCH = 0.36f;
    public static final float CARD_Y0 = -0.92f, CARD_Y1 = -0.55f;
    public static final float CARD_LIMIT_X = 0.95f;

    /** True when a card starting at cardX still fits in the row. */
    public static boolean cardFits(float cardX) {
        return cardX + CARD_PITCH - 0.01f <= CARD_LIMIT_X;
    }

    // --- Grid + vertical scroll (cards wrap into rows; rows scroll beneath the header, clipped to the view) ---
    public static final float VIEW_TOP = HEADER_Y0 - 0.02f, VIEW_BOTTOM = -0.99f;
    public static final float ROW_PITCH = (CARD_Y1 - CARD_Y0) + 0.04f;
    public static final int COLUMNS = computeColumns();

    // Scrollbar (pill thumb, Inspector style); the hit band is wider than the drawn pill
    public static final float SCROLLBAR_X0 = 0.979f, SCROLLBAR_X1 = 0.988f;
    public static final float SCROLLBAR_HIT_X0 = 0.960f, SCROLLBAR_HIT_X1 = 1.0f;
    private static final float MIN_THUMB_H = 0.06f;

    private static float scrollOffsetY = 0.0f;
    private static float maxScrollY = 0.0f;
    private static String lastFolderPath = null;

    private static int computeColumns() {
        int n = 0;
        for (float x = CARD_START_X; cardFits(x); x += CARD_PITCH) n++;
        return Math.max(1, n);
    }

    public static float getScrollOffsetY() { return scrollOffsetY; }
    public static float getMaxScrollY() { return maxScrollY; }
    public static void setScrollOffsetY(float offset) { scrollOffsetY = Math.max(0.0f, Math.min(maxScrollY, offset)); }
    public static void scroll(float delta) { setScrollOffsetY(scrollOffsetY + delta); }
    public static void resetScroll() { scrollOffsetY = 0.0f; }

    /** Recomputes the scroll bound for {@code slotCount} grid slots and re-clamps the offset. */
    public static void updateScrollBounds(int slotCount) {
        int rows = (slotCount + COLUMNS - 1) / COLUMNS;
        float contentH = rows > 0 ? (CARD_Y1 - CARD_Y0) + (rows - 1) * ROW_PITCH + (VIEW_TOP - CARD_Y1) : 0.0f;
        maxScrollY = Math.max(0.0f, contentH - (VIEW_TOP - VIEW_BOTTOM) + 0.01f);
        scrollOffsetY = Math.max(0.0f, Math.min(maxScrollY, scrollOffsetY));
    }

    public static float slotX(int slot) {
        return CARD_START_X + (slot % COLUMNS) * CARD_PITCH;
    }

    /** Vertical shift applied to the base card geometry (CARD_Y0..CARD_Y1) for a grid slot, scroll included. */
    public static float slotShiftY(int slot) {
        return -(slot / COLUMNS) * ROW_PITCH + scrollOffsetY;
    }

    /** True when any part of the slot's card intersects the view. */
    public static boolean slotVisible(int slot) {
        float dy = slotShiftY(slot);
        return CARD_Y0 + dy < VIEW_TOP && CARD_Y1 + dy > VIEW_BOTTOM;
    }

    /** Grid slot under the cursor, or -1 when none (clipped areas and gaps included). */
    public static int slotAt(float ndcX, float ndcY, int slotCount) {
        if (ndcY > VIEW_TOP || ndcY < VIEW_BOTTOM) return -1;
        for (int slot = 0; slot < slotCount; slot++) {
            if (!slotVisible(slot)) continue;
            float x = slotX(slot), dy = slotShiftY(slot);
            if (ndcX >= x && ndcX <= x + CARD_W && ndcY >= CARD_Y0 + dy && ndcY <= CARD_Y1 + dy) return slot;
        }
        return -1;
    }

    // --- Scrollbar geometry (shared with AssetBrowserInputDelegate) ---
    public static boolean hasScrollbar() { return maxScrollY > 0.001f; }

    public static float thumbHeight() {
        float trackH = VIEW_TOP - VIEW_BOTTOM;
        return Math.max(MIN_THUMB_H, trackH * trackH / (trackH + maxScrollY));
    }

    public static float thumbTop() {
        float trackH = VIEW_TOP - VIEW_BOTTOM;
        return VIEW_TOP - (maxScrollY > 0.0f ? scrollOffsetY / maxScrollY : 0.0f) * (trackH - thumbHeight());
    }

    public static boolean inScrollbar(float ndcX, float ndcY) {
        return hasScrollbar() && ndcX >= SCROLLBAR_HIT_X0 && ndcX <= SCROLLBAR_HIT_X1 && ndcY >= VIEW_BOTTOM && ndcY <= VIEW_TOP;
    }

    /** Scrolls so the thumb's top edge sits at {@code thumbTopY} (used by thumb dragging). */
    public static void setThumbTop(float thumbTopY) {
        float travel = (VIEW_TOP - VIEW_BOTTOM) - thumbHeight();
        if (travel <= 0.0f) return;
        setScrollOffsetY((VIEW_TOP - thumbTopY) / travel * maxScrollY);
    }

    private static final float CARD_RADIUS = 0.018f;

    private static final List<AssetMeta> visibleAssetsScratch = new ArrayList<>(128);
    private static final Map<String, String> EXTENSION_BADGES = new HashMap<>();
    private static final StringBuilder crumbSb = new StringBuilder(128);

    /** Visual identity per asset type: tint, short badge code, display name, click hint. */
    private enum TypeStyle {
        FOLDER(EditorTheme.TEAL, "DIR", "FOLDER", "OPEN"),
        PREFAB(EditorTheme.PURPLE, "PREFAB", "PREFAB", "SPAWN"),
        MESH(EditorTheme.ORANGE, "MESH", "3D MESH", "SPAWN"),
        TEXTURE(EditorTheme.GREEN, "TEX", "TEXTURE", "LINK"),
        AUDIO(EditorTheme.PINK, "AUDIO", "AUDIO CLIP", null),
        SCENE(EditorTheme.ACCENT, "SCENE", "SCENE", "OPEN"),
        SCRIPT(EditorTheme.YELLOW, "JAVA", "SCRIPT", "LINK"),
        MATERIAL(EditorTheme.RED, "MAT", "MATERIAL", null),
        OTHER(EditorTheme.GRAPHITE, "FILE", "FILE", null);

        final int color;
        final String code;
        final String displayName;
        final String hint;

        TypeStyle(int color, String code, String displayName, String hint) {
            this.color = color;
            this.code = code;
            this.displayName = displayName;
            this.hint = hint;
        }

        static TypeStyle of(AssetType type) {
            if (type == null) return OTHER;
            return switch (type) {
                case FOLDER -> FOLDER;
                case PREFAB -> PREFAB;
                case MESH_3D -> MESH;
                case TEXTURE -> TEXTURE;
                case AUDIO -> AUDIO;
                case SCENE -> SCENE;
                case SCRIPT -> SCRIPT;
                case MATERIAL -> MATERIAL;
                default -> OTHER;
            };
        }
    }

    /**
     * Renders the browser for the active project. The breadcrumb root is the project's Assets/ folder, and
     * only entries mirrored from that folder on disk are ever passed in.
     */
    public static void render(UIContext ui, List<AssetMeta> assetDirectory, String currentFolderPath, String projectRootPath) {
        UIGeometryBatch.Buffer buffer = ui.geometry();
        String activePath = (currentFolderPath != null) ? currentFolderPath : "";

        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, HEADER_Y0, EditorTheme.SURFACE, 1.0f);
        renderHeader(buffer, activePath, projectRootLabel(projectRootPath));

        // Zero-allocation loop replacing stream().filter().toList()
        visibleAssetsScratch.clear();
        if (assetDirectory != null) {
            for (int i = 0; i < assetDirectory.size(); i++) {
                AssetMeta a = assetDirectory.get(i);
                if (a != null && a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath)) {
                    visibleAssetsScratch.add(a);
                }
            }
        }

        boolean hasUpLevel = !activePath.isEmpty();

        if (!activePath.equalsIgnoreCase(lastFolderPath == null ? "\0" : lastFolderPath)) {
            lastFolderPath = activePath;
            resetScroll();
        }
        int slotCount = visibleAssetsScratch.size() + (hasUpLevel ? 1 : 0);
        updateScrollBounds(slotCount);

        if (visibleAssetsScratch.isEmpty() && !hasUpLevel) {
            centeredText(buffer, "NO ASSETS IN THIS FOLDER", -0.68f, 1.1f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "DRAG & DROP FILES HERE OR CLICK + IMPORT", -0.76f, 0.85f, EditorTheme.TEXT_DISABLED);
            return;
        }

        buffer.pushClipY(VIEW_BOTTOM, VIEW_TOP);
        int slot = 0;
        if (hasUpLevel) {
            if (slotVisible(slot)) {
                renderCard(buffer, slotX(slot), slotShiftY(slot), TypeStyle.FOLDER, "..", "ENCLOSING FOLDER", "BACK", "UP", true);
            }
            slot++;
        }

        for (int i = 0; i < visibleAssetsScratch.size(); i++, slot++) {
            if (!slotVisible(slot)) continue;
            AssetMeta asset = visibleAssetsScratch.get(i);
            TypeStyle style = TypeStyle.of(asset.type);
            String badge = (style == TypeStyle.FOLDER) ? style.code : extensionBadge(asset.name, style);
            renderCard(buffer, slotX(slot), slotShiftY(slot), style, asset.name, style.displayName, style.hint, badge, false);
        }
        buffer.popClipY();

        renderScrollbar(buffer);
    }

    private static void renderScrollbar(UIGeometryBatch.Buffer buffer) {
        if (!hasScrollbar()) return;
        float top = thumbTop();
        UIGeometryBatch.addPill(buffer, SCROLLBAR_X0, top - thumbHeight(), SCROLLBAR_X1, top, EditorTheme.GRAPHITE, 0.85f);
    }

    // ------------------------------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------------------------------

    /** The browser is rooted at the project's Assets/ folder; nothing above it is ever shown. */
    private static String projectRootLabel(String projectRootPath) {
        return "ASSETS";
    }

    private static void renderHeader(UIGeometryBatch.Buffer buffer, String activePath, String rootLabel) {
        UIGeometryBatch.addQuad(buffer, -1.0f, HEADER_Y0, 1.0f, HEADER_Y1, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addQuad(buffer, -1.0f, HEADER_Y0, 1.0f, HEADER_Y0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        // Breadcrumb path: parents muted, current folder bright ("ASSETS > MODELS > CHARS")
        float midY = (HEADER_Y0 + HEADER_Y1) * 0.5f;
        float size = 1.0f;
        float textTop = midY + size * 0.009f;
        float maxX = NEW_FOLDER_X0 - 0.03f;

        crumbSb.setLength(0);
        crumbSb.append(rootLabel);
        int lastSep = activePath.lastIndexOf('/');
        int currentStart = 0;
        if (!activePath.isEmpty()) {
            for (int i = 0; i < activePath.length(); i++) {
                if (i == 0 || activePath.charAt(i - 1) == '/') {
                    if (i == lastSep + 1) {
                        currentStart = crumbSb.length() + 3;
                    }
                    crumbSb.append(" > ");
                }
                char c = activePath.charAt(i);
                if (c != '/') crumbSb.append(c);
            }
        }

        float x = -0.96f;
        if (activePath.isEmpty()) {
            UIGeometryBatch.addTruncatedText(buffer, crumbSb, x, textTop, maxX, size, EditorTheme.TEXT, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, crumbSb.subSequence(0, currentStart), x, textTop, maxX, size, EditorTheme.TEXT_MUTED, 1.0f);
            float curX = x + UIGeometryBatch.textWidth(buffer, currentStart, size);
            if (curX < maxX) {
                UIGeometryBatch.addTruncatedText(buffer, crumbSb.subSequence(currentStart, crumbSb.length()), curX, textTop, maxX, size, EditorTheme.TEXT, 1.0f);
            }
        }

        float by0 = HEADER_Y0 + 0.012f, by1 = HEADER_Y1 - 0.012f;
        UIGeometryBatch.addRoundedCard(buffer, NEW_FOLDER_X0, by0, NEW_FOLDER_X1, by1, EditorTheme.RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "+ NEW FOLDER", NEW_FOLDER_X0, NEW_FOLDER_X1, midY, 0.95f, EditorTheme.TEXT);

        UIGeometryBatch.addRoundedQuad(buffer, IMPORT_X0, by0, IMPORT_X1, by1, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
        centeredTextIn(buffer, "+ IMPORT ASSET", IMPORT_X0, IMPORT_X1, midY, 0.95f, EditorTheme.TEXT);
    }

    // ------------------------------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------------------------------

    private static void renderCard(UIGeometryBatch.Buffer buffer, float cardX, float dy, TypeStyle style, String name, String subtitle,
                                   String hint, String badge, boolean upLevel) {
        float x0 = cardX, x1 = cardX + CARD_W;
        UIGeometryBatch.addRoundedCard(buffer, x0, CARD_Y0 + dy, x1, CARD_Y1 + dy, CARD_RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);

        // Thumbnail well tinted with the type color
        float tx0 = x0 + 0.014f, tx1 = x1 - 0.014f;
        float ty0 = -0.735f + dy, ty1 = CARD_Y1 - 0.016f + dy;
        UIGeometryBatch.addRoundedQuad(buffer, tx0, ty0, tx1, ty1, EditorTheme.RADIUS, EditorTheme.SURFACE_SUNKEN, 1.0f);
        UIGeometryBatch.addRoundedQuad(buffer, tx0, ty0, tx1, ty1, EditorTheme.RADIUS, style.color, 0.10f);

        float cx = (tx0 + tx1) * 0.5f, cy = (ty0 + ty1) * 0.5f;
        if (style == TypeStyle.FOLDER) {
            renderFolderIcon(buffer, cx, cy, upLevel ? EditorTheme.GRAPHITE : style.color);
        } else {
            centeredTextIn(buffer, style.code, tx0, tx1, cy, 1.6f, style.color);
        }

        // File-type indicator badge, pinned to the thumbnail's top-right corner
        if (badge != null && !badge.isEmpty()) {
            float bs = 0.75f;
            float bw = UIGeometryBatch.textWidth(buffer, badge, bs) + 0.016f;
            float bx1 = tx1 - 0.008f, bx0 = Math.max(tx0 + 0.008f, bx1 - bw);
            float by1 = ty1 - 0.010f, by0 = by1 - 0.028f;
            UIGeometryBatch.addPill(buffer, bx0, by0, bx1, by1, upLevel ? EditorTheme.GRAPHITE : style.color, 1.0f);
            centeredTextIn(buffer, badge, bx0, bx1, (by0 + by1) * 0.5f, bs, EditorTheme.BACKGROUND);
        }

        // Asset key (ellipsized) + type / click hint
        float textX0 = x0 + 0.016f, textX1 = x1 - 0.016f;
        UIGeometryBatch.addTruncatedText(buffer, name, textX0, -0.765f + dy, textX1, 1.0f, EditorTheme.TEXT, 1.0f);

        float subTop = -0.835f + dy;
        float hintW = 0.0f;
        if (hint != null) {
            hintW = UIGeometryBatch.textWidth(buffer, hint, 0.75f);
            UIGeometryBatch.addRightAlignedText(buffer, hint, textX1, subTop, 0.75f, EditorTheme.ACCENT, 1.0f);
        }
        UIGeometryBatch.addTruncatedText(buffer, subtitle, textX0, subTop, textX1 - hintW - 0.012f, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    /** Simple Finder-style folder glyph: tab + body, centered on (cx, cy). */
    private static void renderFolderIcon(UIGeometryBatch.Buffer buffer, float cx, float cy, int color) {
        float h = 0.09f;
        float w = UIGeometryBatch.squareWidth(buffer, h) * 1.35f;
        float x0 = cx - w * 0.5f, x1 = cx + w * 0.5f;
        float y0 = cy - h * 0.5f, y1 = cy + h * 0.38f;
        UIGeometryBatch.addRoundedQuad(buffer, x0, y1 - 0.02f, x0 + w * 0.42f, y1 + 0.016f, 0.008f, color, 0.85f);
        UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, 0.010f, color, 1.0f);
        UIGeometryBatch.addQuad(buffer, x0 + 0.004f, y1 - 0.016f, x1 - 0.004f, y1 - 0.013f, EditorTheme.TEXT, 0.25f);
    }

    /** ".PNG"-style extension badge from the asset key, falling back to the type code. Cached per name. */
    private static String extensionBadge(String name, TypeStyle style) {
        if (name == null) return style.code;
        return EXTENSION_BADGES.computeIfAbsent(name, n -> {
            int dot = n.lastIndexOf('.');
            if (dot < 0 || dot == n.length() - 1 || n.length() - dot > 6) return style.code;
            return n.substring(dot + 1).toUpperCase(Locale.ROOT);
        });
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        centeredTextIn(buffer, text, -1.0f, 1.0f, midY, size, rgb);
    }
}
