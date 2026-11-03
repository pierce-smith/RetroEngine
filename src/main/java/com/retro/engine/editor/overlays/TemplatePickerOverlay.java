package com.retro.engine.editor.overlays;

import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.templates.TemplateManager;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.util.List;
import java.util.function.Consumer;

/**
 * In-engine single-choice list of New Project templates, drawn over the launcher or the editor. Click a row to
 * select it (double-click to create right away), then CREATE; CANCEL or a click outside the card closes it.
 * Rendering and hit-testing share the layout constants below.
 */
public final class TemplatePickerOverlay {

    private static final float CARD_X0 = -0.56f, CARD_X1 = 0.56f;
    private static final float ROW_X0 = CARD_X0 + 0.05f, ROW_X1 = CARD_X1 - 0.05f;
    private static final float ROW_H = 0.13f, ROW_GAP = 0.02f;
    private static final float TITLE_H = 0.20f, FOOTER_H = 0.17f;
    private static final float BTN_W = 0.22f, BTN_H = 0.085f;
    private static final long DOUBLE_CLICK_MS = 350;

    private static boolean open;
    private static int selected;
    private static Consumer<TemplateManager.ProjectTemplate> onCreate;
    private static int lastClickRow = -1;
    private static long lastClickAt;

    private TemplatePickerOverlay() {}

    public static boolean isOpen() { return open; }

    /** Opens the picker; {@code onCreate} runs on the main thread with the chosen template. */
    public static void show(Consumer<TemplateManager.ProjectTemplate> onCreateCallback) {
        onCreate = onCreateCallback;
        List<TemplateManager.ProjectTemplate> templates = TemplateManager.getTemplates();
        int idx = templates.indexOf(TemplateManager.FIRST_PERSON_EXAMPLE);
        selected = idx >= 0 ? idx : 0;
        lastClickRow = -1;
        open = true;
    }

    public static void close() {
        open = false;
        onCreate = null;
    }

    // --- Layout ---

    private static float cardHeight() {
        int n = TemplateManager.getTemplates().size();
        return TITLE_H + n * ROW_H + Math.max(0, n - 1) * ROW_GAP + FOOTER_H;
    }

    private static float cardTop() { return cardHeight() * 0.5f; }
    private static float cardBottom() { return -cardHeight() * 0.5f; }

    private static float rowTop(int i) { return cardTop() - TITLE_H - i * (ROW_H + ROW_GAP); }

    private static float btnY0() { return cardBottom() + (FOOTER_H - BTN_H) * 0.5f; }
    private static float createX1() { return ROW_X1; }
    private static float createX0() { return ROW_X1 - BTN_W; }
    private static float cancelX1() { return createX0() - 0.03f; }
    private static float cancelX0() { return cancelX1() - BTN_W; }

    private static int rowAt(float x, float y) {
        int n = TemplateManager.getTemplates().size();
        for (int i = 0; i < n; i++) {
            float y1 = rowTop(i);
            if (inRect(x, y, ROW_X0, y1 - ROW_H, ROW_X1, y1)) return i;
        }
        return -1;
    }

    // --- Rendering ---

    public static void render(UIGeometryBatch.Buffer buffer, float hoverX, float hoverY) {
        if (!open) return;
        List<TemplateManager.ProjectTemplate> templates = TemplateManager.getTemplates();
        float top = cardTop(), bottom = cardBottom();

        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, EditorTheme.SHADOW, 0.55f);
        UIGeometryBatch.addRoundedCard(buffer, CARD_X0, bottom, CARD_X1, top, 0.02f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "NEW PROJECT", ROW_X0, top - 0.07f + 1.4f * 0.009f, ROW_X1, 1.4f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "CHOOSE A TEMPLATE", ROW_X0, top - 0.135f + 0.8f * 0.009f, ROW_X1, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);

        for (int i = 0; i < templates.size(); i++) {
            TemplateManager.ProjectTemplate t = templates.get(i);
            float y1 = rowTop(i), y0 = y1 - ROW_H;
            boolean sel = i == selected;
            boolean hot = inRect(hoverX, hoverY, ROW_X0, y0, ROW_X1, y1);
            UIGeometryBatch.addRoundedCard(buffer, ROW_X0, y0, ROW_X1, y1, EditorTheme.RADIUS,
                sel ? EditorTheme.SURFACE_RAISED : (hot ? EditorTheme.BORDER : EditorTheme.SURFACE_SUNKEN),
                sel ? EditorTheme.ACCENT : EditorTheme.BORDER_STRONG, 1.0f);

            // Radio indicator
            float cx = ROW_X0 + 0.04f, cy = (y0 + y1) * 0.5f, r = 0.018f;
            UIGeometryBatch.addRoundedQuad(buffer, cx - r, cy - r, cx + r, cy + r, r, sel ? EditorTheme.ACCENT : EditorTheme.BORDER_STRONG, 1.0f);
            float ir = r * 0.45f;
            UIGeometryBatch.addRoundedQuad(buffer, cx - ir, cy - ir, cx + ir, cy + ir, ir, sel ? EditorTheme.TEXT : EditorTheme.SURFACE_SUNKEN, 1.0f);

            float textX = cx + 0.045f, textMax = ROW_X1 - 0.02f;
            UIGeometryBatch.addTruncatedText(buffer, t.displayName().toUpperCase(), textX, y1 - 0.042f + 1.05f * 0.009f, textMax, 1.05f,
                sel ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, 1.0f);
            UIGeometryBatch.addTruncatedText(buffer, t.description(), textX, y0 + 0.038f + 0.75f * 0.009f, textMax, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
        }

        float by0 = btnY0(), by1 = by0 + BTN_H;
        boolean hotCancel = inRect(hoverX, hoverY, cancelX0(), by0, cancelX1(), by1);
        UIGeometryBatch.addRoundedCard(buffer, cancelX0(), by0, cancelX1(), by1, EditorTheme.RADIUS,
            hotCancel ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "CANCEL", cancelX0(), cancelX1(), (by0 + by1) * 0.5f, 1.0f, EditorTheme.TEXT);
        boolean hotCreate = inRect(hoverX, hoverY, createX0(), by0, createX1(), by1);
        UIGeometryBatch.addRoundedQuad(buffer, createX0(), by0, createX1(), by1, EditorTheme.RADIUS, EditorTheme.ACCENT, hotCreate ? 1.0f : 0.85f);
        centeredTextIn(buffer, "CREATE...", createX0(), createX1(), (by0 + by1) * 0.5f, 1.0f, EditorTheme.TEXT);
    }

    // --- Input ---

    /** Left-button press while open. Always consumes the click (the picker is modal). */
    public static void handleClick(float x, float y) {
        if (!open) return;
        float by0 = btnY0(), by1 = by0 + BTN_H;
        int row = rowAt(x, y);
        if (row >= 0) {
            long now = System.currentTimeMillis();
            boolean dbl = row == lastClickRow && now - lastClickAt <= DOUBLE_CLICK_MS;
            selected = row;
            lastClickRow = row;
            lastClickAt = now;
            UISoundPlayer.play(UISound.CLICK);
            if (dbl) confirm();
        } else if (inRect(x, y, createX0(), by0, createX1(), by1)) {
            confirm();
        } else if (inRect(x, y, cancelX0(), by0, cancelX1(), by1) || !inRect(x, y, CARD_X0, cardBottom(), CARD_X1, cardTop())) {
            close();
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    private static void confirm() {
        List<TemplateManager.ProjectTemplate> templates = TemplateManager.getTemplates();
        if (selected < 0 || selected >= templates.size()) return;
        Consumer<TemplateManager.ProjectTemplate> callback = onCreate;
        TemplateManager.ProjectTemplate chosen = templates.get(selected);
        close();
        if (callback != null) callback.accept(chosen);
    }

    private static boolean inRect(float x, float y, float x0, float y0, float x1, float y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }
}
