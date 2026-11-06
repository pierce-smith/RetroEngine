package com.retro.engine.ui;

import com.retro.engine.core.FrameTimings;

/**
 * Draws {@link FrameTimings} over the top-left of the game viewport. F3 toggles it in the editor. In a standalone game
 * it needs {@code --frame-stats}, which also turns F3 on there, because F3 otherwise belongs to the game.
 */
public final class FrameStatsOverlay {

    private static final int TEXT_RGB = 0xE8E8E8, GOOD_RGB = 0x66D97A, WARN_RGB = 0xE8C24A, BAD_RGB = 0xE86A5A;
    private static final float BUDGET_MS = 16.67f, WARN_MS = 33.3f;
    private static final float WIDTH = 0.46f, ROW = 0.05f, PAD = 0.015f, TEXT_SIZE = 0.9f;

    private static boolean visible, hotkeyEnabled;
    private static final StringBuilder line = new StringBuilder(48);

    private FrameStatsOverlay() {}

    public static boolean isVisible() { return visible; }
    public static void setVisible(boolean show) { visible = show; }
    public static void setHotkeyEnabled(boolean enabled) { hotkeyEnabled = enabled; }

    /** The F3 handler. */
    public static void onHotkey() {
        if (hotkeyEnabled) visible = !visible;
    }

    /** Appends the overlay to {@code buffer}, inside the viewport rect in window NDC. Does nothing while hidden. */
    public static void draw(UIGeometryBatch.Buffer buffer, FrameTimings timings, float minX, float minY, float maxX, float maxY,
                            int windowWidth, int windowHeight) {
        if (!visible || maxX <= minX || maxY <= minY) return;
        buffer.setWindowSize(windowWidth, windowHeight);
        buffer.setPanelMapping(minX, minY, maxX, maxY, minX, minY, maxX, maxY);
        try {
            int rows = 2 + FrameTimings.PHASE_COUNT;
            float x0 = minX + PAD, x1 = Math.min(maxX - PAD, x0 + WIDTH), top = maxY - PAD;
            UIGeometryBatch.addQuad(buffer, x0, top - rows * ROW - PAD, x1, top, 0x000000, 0.7f);

            float textX = x0 + PAD, textMaxX = x1 - PAD, y = top - ROW * 0.75f;
            float frameMs = timings.getAverageFrameMs();
            line.setLength(0);
            line.append("FRAME ");
            appendMs(line, frameMs);
            line.append("  ").append(frameMs > 0.0f ? Math.round(1000.0f / frameMs) : 0).append(" FPS");
            UIGeometryBatch.addBoundedText(buffer, line, textX, y, textMaxX, TEXT_SIZE, colorFor(frameMs), 1.0f);

            y -= ROW;
            line.setLength(0);
            line.append("MEDIAN ");
            appendMs(line, timings.getMedianMs());
            line.append("  P99 ");
            appendMs(line, timings.getP99Ms());
            UIGeometryBatch.addBoundedText(buffer, line, textX, y, textMaxX, TEXT_SIZE, colorFor(timings.getP99Ms()), 1.0f);

            for (int phase = 0; phase < FrameTimings.PHASE_COUNT; phase++) {
                y -= ROW;
                line.setLength(0);
                line.append(FrameTimings.phaseName(phase)).append(' ');
                appendMs(line, timings.getPhaseAverageMs(phase));
                UIGeometryBatch.addBoundedText(buffer, line, textX, y, textMaxX, TEXT_SIZE, TEXT_RGB, 1.0f);
            }
        } finally {
            buffer.clearPanelMapping();
        }
    }

    private static int colorFor(float ms) {
        return ms <= BUDGET_MS * 1.05f ? GOOD_RGB : ms <= WARN_MS ? WARN_RGB : BAD_RGB;
    }

    /** One decimal place, without String.format's allocation. */
    private static void appendMs(StringBuilder sb, float ms) {
        int tenths = Math.round(ms * 10.0f);
        sb.append(tenths / 10).append('.').append(tenths % 10);
    }
}
