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

    /** File extensions the editor panels accept from OS drag-and-drop. */
    public static final String[] DROP_EXTENSIONS = { ".png", ".obj", ".gltf", ".rscene" };

    public static boolean isSupportedDrop(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : DROP_EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }

    /**
     * Files dropped onto the Asset Browser: copies them into the folder currently shown (files already inside Assets/
     * are just referenced) and returns their resolved project paths.
     */
    public static List<String> handleFileDrop(com.retro.engine.editor.EditorWorkspace workspace, List<String> files) {
        if (workspace == null || files == null || files.isEmpty() || !workspace.hasActiveProject()) return List.of();
        List<String> resolved = workspace.getAssetDatabase().onFilesDropped(files);
        System.out.println("[Asset Browser] Imported " + resolved.size() + " dropped file(s) into /" + workspace.getCurrentFolderPath());
        return resolved;
    }

    // --- Responsive layout, in screen NDC relative to the docked tile (DockPanel.ASSETS.rect). ---
    // Everything below derives from the rect, and AssetBrowserInputDelegate hit-tests through the same accessors.
    // Nothing here measures text, so input code (which has no buffer) computes the identical layout.
    private static final float HEADER_ROW_H = 0.08f, HEADER_STACKED_ROW_H = 0.07f;
    private static final float PAD = 0.03f;
    private static final float BUTTON_GAP = 0.02f;
    private static final float FULL_NEW_FOLDER_W = 0.32f, FULL_IMPORT_W = 0.33f, SHORT_BUTTON_W = 0.16f;
    /** Tile widths at which the header drops to short button labels, then stacks the buttons under the path. */
    private static final float FULL_LABELS_MIN_W = 1.0f, SINGLE_ROW_MIN_W = 0.55f;

    private static final float CARD_MIN_W = 0.30f, CARD_MAX_W = 0.40f, CARD_GAP = 0.04f;
    private static final float CARD_H = 0.37f;
    private static final float ROW_PITCH = CARD_H + 0.04f;
    private static final float GRID_TOP_GAP = 0.03f;   // view top -> first card row
    private static final float SCROLL_GUTTER = 0.04f;  // grid keeps clear of the scrollbar hit band

    // Card interior, measured down from the card's top edge
    private static final float THUMB_BOTTOM_DY = 0.185f, NAME_DY = 0.215f, SUBTITLE_DY = 0.285f;

    // Scrollbar (pill thumb, Inspector style) along the right edge; the hit band is wider than the drawn pill
    private static final float SCROLLBAR_PILL_OUTER = 0.012f, SCROLLBAR_PILL_INNER = 0.021f;
    private static final float MIN_THUMB_H = 0.06f;

    private static float scrollOffsetY = 0.0f;
    private static float maxScrollY = 0.0f;
    private static String lastFolderPath = null;

    private static PanelRect rect() { return DockPanel.ASSETS.rect; }

    // Header
    private static boolean stackedHeader() { return rect().width() < SINGLE_ROW_MIN_W; }
    private static boolean fullLabels() { return rect().width() >= FULL_LABELS_MIN_W; }
    public static float headerY1() { return rect().y1; }
    public static float headerY0() { return rect().y1 - (stackedHeader() ? 2.0f * HEADER_STACKED_ROW_H : HEADER_ROW_H); }
    /** Vertical band of the breadcrumb path (the whole header unless stacked). */
    private static float crumbY0() { return stackedHeader() ? rect().y1 - HEADER_STACKED_ROW_H : headerY0(); }
    private static float buttonsY0() { return headerY0(); }
    private static float buttonsY1() { return stackedHeader() ? crumbY0() : headerY1(); }
    private static float importX1() { return rect().x1 - PAD; }
    private static float importX0() {
        if (stackedHeader()) return (rect().x0 + rect().x1) * 0.5f + BUTTON_GAP * 0.5f;
        return importX1() - (fullLabels() ? FULL_IMPORT_W : SHORT_BUTTON_W);
    }
    private static float newFolderX1() { return importX0() - BUTTON_GAP; }
    private static float newFolderX0() {
        if (stackedHeader()) return rect().x0 + PAD;
        return newFolderX1() - (fullLabels() ? FULL_NEW_FOLDER_W : SHORT_BUTTON_W);
    }
    private static float crumbX1() { return stackedHeader() ? rect().x1 - PAD : newFolderX0() - 0.03f; }

    private static boolean inButton(float x, float y, float x0, float x1) {
        return x >= x0 && x <= x1 && y >= buttonsY0() && y <= buttonsY1();
    }
    public static boolean inNewFolderButton(float x, float y) { return inButton(x, y, newFolderX0(), newFolderX1()); }
    public static boolean inImportButton(float x, float y) { return inButton(x, y, importX0(), importX1()); }

    // Grid
    public static float viewTop() { return headerY0() - 0.02f; }
    public static float viewBottom() { return rect().y0 + 0.01f; }
    private static float gridX0() { return rect().x0 + PAD; }
    private static float gridWidth() { return Math.max(CARD_MIN_W, rect().x1 - SCROLL_GUTTER - gridX0()); }

    public static int columns() {
        return Math.max(1, (int) ((gridWidth() + CARD_GAP) / (CARD_MIN_W + CARD_GAP)));
    }

    /** Cards share the row evenly, never wider than CARD_MAX_W (wide tiles leave the remainder empty). */
    public static float cardWidth() {
        int c = columns();
        return Math.min(CARD_MAX_W, (gridWidth() - (c - 1) * CARD_GAP) / c);
    }

    public static float getScrollOffsetY() { return scrollOffsetY; }
    public static float getMaxScrollY() { return maxScrollY; }
    public static void setScrollOffsetY(float offset) { scrollOffsetY = Math.max(0.0f, Math.min(maxScrollY, offset)); }
    public static void scroll(float delta) { setScrollOffsetY(scrollOffsetY + delta); }
    public static void resetScroll() { scrollOffsetY = 0.0f; }

    /** Recomputes the scroll bound for {@code slotCount} grid slots and re-clamps the offset. */
    public static void updateScrollBounds(int slotCount) {
        int c = columns();
        int rows = (slotCount + c - 1) / c;
        float contentH = rows > 0 ? CARD_H + (rows - 1) * ROW_PITCH + GRID_TOP_GAP : 0.0f;
        maxScrollY = Math.max(0.0f, contentH - (viewTop() - viewBottom()) + 0.01f);
        scrollOffsetY = Math.max(0.0f, Math.min(maxScrollY, scrollOffsetY));
    }

    public static float slotX(int slot) {
        return gridX0() + (slot % columns()) * (cardWidth() + CARD_GAP);
    }

    /** Top edge of a grid slot's card, scroll included. */
    public static float slotTop(int slot) {
        return viewTop() - GRID_TOP_GAP - (slot / columns()) * ROW_PITCH + scrollOffsetY;
    }

    /** True when any part of the slot's card intersects the view. */
    public static boolean slotVisible(int slot) {
        float top = slotTop(slot);
        return top - CARD_H < viewTop() && top > viewBottom();
    }

    /** Grid slot under the cursor, or -1 when none (clipped areas and gaps included). */
    public static int slotAt(float ndcX, float ndcY, int slotCount) {
        if (ndcY > viewTop() || ndcY < viewBottom()) return -1;
        float w = cardWidth();
        for (int slot = 0; slot < slotCount; slot++) {
            if (!slotVisible(slot)) continue;
            float x = slotX(slot), top = slotTop(slot);
            if (ndcX >= x && ndcX <= x + w && ndcY >= top - CARD_H && ndcY <= top) return slot;
        }
        return -1;
    }

    // --- Scrollbar geometry (shared with AssetBrowserInputDelegate) ---
    public static boolean hasScrollbar() { return maxScrollY > 0.001f; }

    public static float thumbHeight() {
        float trackH = viewTop() - viewBottom();
        return Math.max(MIN_THUMB_H, trackH * trackH / (trackH + maxScrollY));
    }

    public static float thumbTop() {
        float trackH = viewTop() - viewBottom();
        return viewTop() - (maxScrollY > 0.0f ? scrollOffsetY / maxScrollY : 0.0f) * (trackH - thumbHeight());
    }

    public static boolean inScrollbar(float ndcX, float ndcY) {
        float x1 = rect().x1;
        return hasScrollbar() && ndcX >= x1 - SCROLL_GUTTER && ndcX <= x1 && ndcY >= viewBottom() && ndcY <= viewTop();
    }

    /** Scrolls so the thumb's top edge sits at {@code thumbTopY} (used by thumb dragging). */
    public static void setThumbTop(float thumbTopY) {
        float travel = (viewTop() - viewBottom()) - thumbHeight();
        if (travel <= 0.0f) return;
        setScrollOffsetY((viewTop() - thumbTopY) / travel * maxScrollY);
    }

    private static final float CARD_RADIUS = 0.018f;

    private static final List<AssetMeta> visibleAssetsScratch = new ArrayList<>(128);
    private static final Map<String, String> EXTENSION_BADGES = new HashMap<>();
    private static final StringBuilder crumbSb = new StringBuilder(128);
    // Muted parent part ("ASSETS > MODELS > ") and bright current folder ("CHARS"), split so no substring is allocated
    private static final StringBuilder crumbParentSb = new StringBuilder(128);
    private static final StringBuilder crumbCurrentSb = new StringBuilder(64);

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

        PanelRect r = rect();
        UIGeometryBatch.addQuad(buffer, r.x0, r.y0, r.x1, headerY0(), EditorTheme.SURFACE, 1.0f);
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
            float y = viewTop() - Math.min(0.16f, (viewTop() - viewBottom()) * 0.35f);
            centeredText(buffer, "NO ASSETS IN THIS FOLDER", y, 1.1f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "DRAG & DROP FILES HERE OR CLICK + IMPORT", y - 0.08f, 0.85f, EditorTheme.TEXT_DISABLED);
            return;
        }

        buffer.pushClipY(viewBottom(), viewTop());
        float cardW = cardWidth();
        int slot = 0;
        if (hasUpLevel) {
            if (slotVisible(slot)) {
                renderCard(buffer, slotX(slot), slotTop(slot), cardW, TypeStyle.FOLDER, "..", "ENCLOSING FOLDER", "BACK", "UP", true);
            }
            slot++;
        }

        for (int i = 0; i < visibleAssetsScratch.size(); i++, slot++) {
            if (!slotVisible(slot)) continue;
            AssetMeta asset = visibleAssetsScratch.get(i);
            TypeStyle style = TypeStyle.of(asset.type);
            String badge = (style == TypeStyle.FOLDER) ? style.code : extensionBadge(asset.name, style);
            renderCard(buffer, slotX(slot), slotTop(slot), cardW, style, asset.name, style.displayName, style.hint, badge, false);
        }
        buffer.popClipY();

        renderScrollbar(buffer);
    }

    private static void renderScrollbar(UIGeometryBatch.Buffer buffer) {
        if (!hasScrollbar()) return;
        float top = thumbTop();
        float x1 = rect().x1;
        UIGeometryBatch.addPill(buffer, x1 - SCROLLBAR_PILL_INNER, top - thumbHeight(), x1 - SCROLLBAR_PILL_OUTER, top, EditorTheme.GRAPHITE, 0.85f);
    }

    // ------------------------------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------------------------------

    /** The browser is rooted at the project's Assets/ folder; nothing above it is ever shown. */
    private static String projectRootLabel(String projectRootPath) {
        return "ASSETS";
    }

    private static void renderHeader(UIGeometryBatch.Buffer buffer, String activePath, String rootLabel) {
        PanelRect r = rect();
        float hy0 = headerY0(), hy1 = headerY1();
        UIGeometryBatch.addQuad(buffer, r.x0, hy0, r.x1, hy1, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addQuad(buffer, r.x0, hy0, r.x1, hy0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        // Breadcrumb path: parents muted, current folder bright ("ASSETS > MODELS > CHARS")
        float midY = (crumbY0() + hy1) * 0.5f;
        float size = 1.0f;
        float textTop = midY + size * 0.009f;
        float maxX = crumbX1();

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

        float x = r.x0 + PAD;
        if (activePath.isEmpty()) {
            UIGeometryBatch.addTruncatedText(buffer, crumbSb, x, textTop, maxX, size, EditorTheme.TEXT, 1.0f);
        } else {
            crumbParentSb.setLength(0);
            crumbParentSb.append(crumbSb, 0, currentStart);
            crumbCurrentSb.setLength(0);
            crumbCurrentSb.append(crumbSb, currentStart, crumbSb.length());
            // Deep paths keep the current folder visible: when the whole crumb overflows, show only the current folder
            if (x + UIGeometryBatch.textWidth(buffer, crumbSb, size) > maxX) {
                UIGeometryBatch.addTruncatedText(buffer, crumbCurrentSb, x, textTop, maxX, size, EditorTheme.TEXT, 1.0f);
            } else {
                UIGeometryBatch.addBoundedText(buffer, crumbParentSb, x, textTop, maxX, size, EditorTheme.TEXT_MUTED, 1.0f);
                float curX = x + UIGeometryBatch.textWidth(buffer, crumbParentSb, size);
                UIGeometryBatch.addTruncatedText(buffer, crumbCurrentSb, curX, textTop, maxX, size, EditorTheme.TEXT, 1.0f);
            }
        }

        float by0 = buttonsY0() + 0.012f, by1 = buttonsY1() - 0.012f, bMid = (by0 + by1) * 0.5f;
        boolean full = fullLabels();
        float nx0 = newFolderX0(), nx1 = newFolderX1(), ix0 = importX0(), ix1 = importX1();
        UIGeometryBatch.addRoundedCard(buffer, nx0, by0, nx1, by1, EditorTheme.RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, full ? "+ NEW FOLDER" : "+ FOLDER", nx0, nx1, bMid, 0.95f, EditorTheme.TEXT);

        UIGeometryBatch.addRoundedQuad(buffer, ix0, by0, ix1, by1, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
        centeredTextIn(buffer, full ? "+ IMPORT ASSET" : "+ IMPORT", ix0, ix1, bMid, 0.95f, EditorTheme.TEXT);
    }

    // ------------------------------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------------------------------

    private static void renderCard(UIGeometryBatch.Buffer buffer, float cardX, float top, float cardW, TypeStyle style, String name,
                                   String subtitle, String hint, String badge, boolean upLevel) {
        float x0 = cardX, x1 = cardX + cardW;
        UIGeometryBatch.addRoundedCard(buffer, x0, top - CARD_H, x1, top, CARD_RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);

        // Thumbnail well tinted with the type color
        float tx0 = x0 + 0.014f, tx1 = x1 - 0.014f;
        float ty0 = top - THUMB_BOTTOM_DY, ty1 = top - 0.016f;
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
        UIGeometryBatch.addTruncatedText(buffer, name, textX0, top - NAME_DY, textX1, 1.0f, EditorTheme.TEXT, 1.0f);

        float subTop = top - SUBTITLE_DY;
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
        PanelRect r = rect();
        centeredTextIn(buffer, text, r.x0 + 0.01f, r.x1 - 0.01f, midY, size, rgb);
    }
}
