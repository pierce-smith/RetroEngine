package com.retro.engine.editor.ui.overlays;

import com.retro.engine.core.ProjectSettings;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.panels.EngineConsolePanel;
import com.retro.engine.editor.ui.theme.EditorTheme;

import java.io.File;

/**
 * "File > Project Settings..." modal: global render environment for the active project (ground shadow blobs,
 * distance fog, ambient light). Edits apply live to the workspace's {@link ProjectSettings}, which
 * {@code SceneRenderPipeline} reads every frame; SAVE writes ProjectSettings.bin and CANCEL restores the values the
 * modal opened with. Rendering and hit-testing share the layout constants below.
 *
 * <p>Sliders drag on press; the value well to the right of a slider opens a prompt for an exact number. Rendering
 * formats numbers into a reused StringBuilder, so an open modal adds no per-frame allocation.
 */
public final class ProjectSettingsOverlay {

    private static final float CARD_X0 = -0.56f, CARD_X1 = 0.56f;
    private static final float CARD_Y0 = -0.64f, CARD_Y1 = 0.58f;

    private static final float LABEL_X = -0.52f;
    private static final float CONTROL_X0 = -0.16f;
    private static final float TRACK_X1 = 0.34f;
    private static final float VALUE_X0 = 0.36f, VALUE_X1 = 0.52f;
    private static final float ROW_H = 0.066f;
    private static final float CHANNEL_GAP = 0.016f;
    private static final float SWATCH_W = 0.07f;

    // Row centers, top to bottom
    private static final float SECTION_SHADOW_Y = 0.43f;
    private static final float BLOBS_Y = 0.36f;
    private static final float SECTION_FOG_Y = 0.27f;
    private static final float FOG_ENABLED_Y = 0.20f;
    private static final float FOG_START_Y = 0.11f;
    private static final float FOG_END_Y = 0.02f;
    private static final float FOG_DENSITY_Y = -0.07f;
    private static final float FOG_COLOR_Y = -0.16f;
    private static final float SECTION_AMBIENT_Y = -0.25f;
    private static final float AMBIENT_COLOR_Y = -0.32f;
    private static final float AMBIENT_INTENSITY_Y = -0.41f;

    private static final float ACTION_Y0 = -0.60f, ACTION_Y1 = -0.52f;
    private static final float RESET_X0 = -0.52f, RESET_X1 = -0.24f;
    private static final float CANCEL_X0 = 0.04f, CANCEL_X1 = 0.26f;
    private static final float SAVE_X0 = 0.29f, SAVE_X1 = 0.52f;
    private static final float CLOSE_X0 = CARD_X1 - 0.075f, CLOSE_X1 = CARD_X1 - 0.02f;
    private static final float CLOSE_Y0 = CARD_Y1 - 0.075f, CLOSE_Y1 = CARD_Y1 - 0.02f;

    // Slider ids
    private static final int NONE = -1;
    private static final int FOG_START = 0, FOG_END = 1, FOG_DENSITY = 2;
    private static final int FOG_R = 3, FOG_G = 4, FOG_B = 5;
    private static final int AMBIENT_R = 6, AMBIENT_G = 7, AMBIENT_B = 8;
    private static final int AMBIENT_INTENSITY = 9;

    // UI slider ranges (exact entry through the value prompt is clamped by ProjectSettings.sanitize instead)
    private static final float FOG_DISTANCE_SLIDER_MAX = 500.0f;

    private static final ProjectSettings openedWith = ProjectSettings.defaults();
    private static boolean dirty = false;
    private static String status = "";
    private static boolean statusIsError = false;

    private static int draggingSlider = NONE;

    private static final StringBuilder valueSb = new StringBuilder(16);

    private ProjectSettingsOverlay() {}

    /** Opens the modal for the active project, remembering its current values for CANCEL. */
    public static void open(EditorWorkspace workspace) {
        if (workspace == null || !workspace.hasActiveProject()) return;
        openedWith.copyRenderSettingsFrom(workspace.getProjectSettings());
        dirty = false;
        draggingSlider = NONE;
        setStatus("", false);
        workspace.getOverlayManager().setActiveModal(ModalState.PROJECT_SETTINGS);
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    public static void render(UIGeometryBatch.Buffer buffer, ProjectSettings s, float hoverX, float hoverY) {
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0x000000, 0.65f);
        UIGeometryBatch.addRoundedCard(buffer, CARD_X0, CARD_Y0, CARD_X1, CARD_Y1, 0.016f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 0.98f);

        float headerY0 = CARD_Y1 - 0.09f;
        UIGeometryBatch.addRoundedQuad(buffer, CARD_X0 + 0.003f, headerY0, CARD_X1 - 0.003f, CARD_Y1 - 0.003f, 0.014f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, CARD_X0 + 0.003f, headerY0, CARD_X1 - 0.003f, CARD_Y1 - 0.02f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, CARD_X0, headerY0, CARD_X1, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, dirty ? "PROJECT SETTINGS  (UNSAVED)" : "PROJECT SETTINGS", CARD_X0 + 0.03f, CARD_Y1 - 0.035f,
            CLOSE_X0 - 0.02f, 1.0f, EditorTheme.TEXT, 1.0f);

        UIGeometryBatch.addRoundedQuad(buffer, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 0.90f);
        centeredTextIn(buffer, "X", CLOSE_X0, CLOSE_X1, (CLOSE_Y0 + CLOSE_Y1) * 0.5f, 0.9f, EditorTheme.TEXT);

        sectionHeader(buffer, "SHADOWS", SECTION_SHADOW_Y);
        toggleRow(buffer, "GROUND SHADOW BLOBS", BLOBS_Y, s.shadowBlobsEnabled, hoverX, hoverY);

        sectionHeader(buffer, "DISTANCE FOG", SECTION_FOG_Y);
        toggleRow(buffer, "FOG ENABLED", FOG_ENABLED_Y, s.fogEnabled, hoverX, hoverY);
        boolean fogOn = s.fogEnabled;
        sliderRow(buffer, "FOG START", FOG_START_Y, FOG_START, s, 1, fogOn);
        sliderRow(buffer, "FOG END", FOG_END_Y, FOG_END, s, 1, fogOn);
        sliderRow(buffer, "FOG DENSITY", FOG_DENSITY_Y, FOG_DENSITY, s, 2, fogOn);
        colorRow(buffer, "FOG COLOR", FOG_COLOR_Y, FOG_R, s, s.fogColor, fogOn);

        sectionHeader(buffer, "AMBIENT LIGHT", SECTION_AMBIENT_Y);
        colorRow(buffer, "AMBIENT COLOR", AMBIENT_COLOR_Y, AMBIENT_R, s, s.ambientColor, true);
        sliderRow(buffer, "AMBIENT INTENSITY", AMBIENT_INTENSITY_Y, AMBIENT_INTENSITY, s, 2, true);

        if (!status.isEmpty()) {
            UIGeometryBatch.addTruncatedText(buffer, status, LABEL_X, ACTION_Y1 + 0.05f, VALUE_X1, 0.75f,
                statusIsError ? EditorTheme.RED : EditorTheme.TEXT_MUTED, 1.0f);
        }

        UIGeometryBatch.addRoundedCard(buffer, RESET_X0, ACTION_Y0, RESET_X1, ACTION_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "RESET DEFAULTS", RESET_X0, RESET_X1, (ACTION_Y0 + ACTION_Y1) * 0.5f, 0.85f, EditorTheme.TEXT);
        UIGeometryBatch.addRoundedCard(buffer, CANCEL_X0, ACTION_Y0, CANCEL_X1, ACTION_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "CANCEL", CANCEL_X0, CANCEL_X1, (ACTION_Y0 + ACTION_Y1) * 0.5f, 0.9f, EditorTheme.TEXT);
        UIGeometryBatch.addRoundedQuad(buffer, SAVE_X0, ACTION_Y0, SAVE_X1, ACTION_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        centeredTextIn(buffer, "SAVE", SAVE_X0, SAVE_X1, (ACTION_Y0 + ACTION_Y1) * 0.5f, 0.9f, EditorTheme.TEXT);
    }

    private static void sectionHeader(UIGeometryBatch.Buffer buffer, String title, float midY) {
        UIGeometryBatch.addBoundedText(buffer, title, LABEL_X, midY + 0.78f * 0.009f, VALUE_X1, 0.78f, EditorTheme.ACCENT, 1.0f);
        UIGeometryBatch.addQuad(buffer, LABEL_X, midY - 0.022f, VALUE_X1, midY - 0.022f + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
    }

    private static void rowLabel(UIGeometryBatch.Buffer buffer, String label, float midY, boolean enabled) {
        UIGeometryBatch.addBoundedText(buffer, label, LABEL_X, midY + 0.82f * 0.009f, CONTROL_X0 - 0.02f, 0.82f,
            enabled ? EditorTheme.TEXT_MUTED : EditorTheme.TEXT_DISABLED, 1.0f);
    }

    private static void toggleRow(UIGeometryBatch.Buffer buffer, String label, float midY, boolean on, float hoverX, float hoverY) {
        rowLabel(buffer, label, midY, true);
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        float x1 = CONTROL_X0 + 0.16f;
        boolean hot = inRect(hoverX, hoverY, CONTROL_X0, y0, x1, y1);
        if (on) {
            UIGeometryBatch.addRoundedQuad(buffer, CONTROL_X0, y0, x1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, hot ? 0.85f : 1.0f);
        } else {
            UIGeometryBatch.addRoundedCard(buffer, CONTROL_X0, y0, x1, y1, EditorTheme.RADIUS_SMALL,
                hot ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        }
        centeredTextIn(buffer, on ? "ON" : "OFF", CONTROL_X0, x1, midY, 0.85f, on ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED);
    }

    private static void sliderRow(UIGeometryBatch.Buffer buffer, String label, float midY, int slider, ProjectSettings s, int decimals, boolean enabled) {
        rowLabel(buffer, label, midY, enabled);
        drawTrack(buffer, CONTROL_X0, TRACK_X1, midY, normalized(slider, s), enabled ? EditorTheme.ACCENT : EditorTheme.GRAPHITE);

        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        UIGeometryBatch.addRoundedQuad(buffer, VALUE_X0, y0, VALUE_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_SUNKEN, 1.0f);
        valueSb.setLength(0);
        appendFixed(valueSb, get(slider, s), decimals);
        UIGeometryBatch.addRightAlignedText(buffer, valueSb, VALUE_X1 - 0.014f, midY + 0.8f * 0.009f, 0.8f,
            enabled ? EditorTheme.TEXT : EditorTheme.TEXT_DISABLED, 1.0f);
    }

    /** Swatch + three R / G / B channel sliders; {@code firstSlider} is the R channel's id (G and B follow). */
    private static void colorRow(UIGeometryBatch.Buffer buffer, String label, float midY, int firstSlider, ProjectSettings s, float[] rgb, boolean enabled) {
        rowLabel(buffer, label, midY, enabled);
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        UIGeometryBatch.addRoundedCard(buffer, CONTROL_X0, y0, CONTROL_X0 + SWATCH_W, y1, EditorTheme.RADIUS_SMALL,
            packRgb(rgb[0], rgb[1], rgb[2]), EditorTheme.BORDER_STRONG, enabled ? 1.0f : 0.4f);

        for (int c = 0; c < 3; c++) {
            float x0 = channelX0(c), x1 = channelX1(c);
            drawTrack(buffer, x0, x1, midY, rgb[c], enabled ? CHANNEL_COLORS[c] : EditorTheme.GRAPHITE);
            valueSb.setLength(0);
            valueSb.append(CHANNEL_NAMES[c]).append(' ');
            appendFixed(valueSb, get(firstSlider + c, s), 2);
            UIGeometryBatch.addBoundedText(buffer, valueSb, x0 + 0.01f, midY + 0.7f * 0.009f, x1 - 0.004f, 0.7f,
                enabled ? EditorTheme.TEXT : EditorTheme.TEXT_DISABLED, 1.0f);
        }
    }

    private static final int[] CHANNEL_COLORS = { EditorTheme.RED, EditorTheme.GREEN, EditorTheme.ACCENT };
    private static final char[] CHANNEL_NAMES = { 'R', 'G', 'B' };

    private static void drawTrack(UIGeometryBatch.Buffer buffer, float x0, float x1, float midY, float t, int fillRgb) {
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_SUNKEN, 1.0f);
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        if (clamped > 0.0f) {
            UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x0 + (x1 - x0) * clamped, y1, EditorTheme.RADIUS_SMALL, fillRgb, 0.55f);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    /** Left-button press while the modal is open. Clicks outside the card are swallowed. */
    public static void handleClick(float ndcX, float ndcY, EditorWorkspace workspace) {
        if (workspace == null) return;
        ProjectSettings s = workspace.getProjectSettings();

        if (inRect(ndcX, ndcY, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1) || inRect(ndcX, ndcY, CANCEL_X0, ACTION_Y0, CANCEL_X1, ACTION_Y1)) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            s.copyRenderSettingsFrom(openedWith);
            close(workspace);
            return;
        }
        if (inRect(ndcX, ndcY, SAVE_X0, ACTION_Y0, SAVE_X1, ACTION_Y1)) {
            save(workspace);
            return;
        }
        if (inRect(ndcX, ndcY, RESET_X0, ACTION_Y0, RESET_X1, ACTION_Y1)) {
            UISoundPlayer.play(UISound.CLICK);
            s.resetRenderSettings();
            markDirty();
            return;
        }
        if (hitsToggle(ndcX, ndcY, BLOBS_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.shadowBlobsEnabled = !s.shadowBlobsEnabled;
            markDirty();
            return;
        }
        if (hitsToggle(ndcX, ndcY, FOG_ENABLED_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.fogEnabled = !s.fogEnabled;
            markDirty();
            return;
        }

        int valueSlider = valueWellAt(ndcX, ndcY);
        if (valueSlider != NONE) {
            promptExactValue(valueSlider, s);
            return;
        }

        int slider = sliderAt(ndcX, ndcY);
        if (slider != NONE) {
            draggingSlider = slider;
            dragTo(ndcX, s);
        }
    }

    /** Cursor move in screen space; drives an active slider drag. Returns false when no slider is held. */
    public static boolean handleDrag(float ndcX, EditorWorkspace workspace) {
        if (draggingSlider == NONE) return false;
        if (workspace == null || workspace.getOverlayManager().getActiveModal() != ModalState.PROJECT_SETTINGS) {
            draggingSlider = NONE;
            return false;
        }
        dragTo(ndcX, workspace.getProjectSettings());
        return true;
    }

    public static void onMouseRelease() {
        draggingSlider = NONE;
    }

    private static void dragTo(float ndcX, ProjectSettings s) {
        float x0 = sliderX0(draggingSlider), x1 = sliderX1(draggingSlider);
        float t = Math.max(0.0f, Math.min(1.0f, (ndcX - x0) / (x1 - x0)));
        set(draggingSlider, s, t * sliderMax(draggingSlider));
        markDirty();
    }

    private static void promptExactValue(int slider, ProjectSettings s) {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String input = NativeFileDialog.promptInput("Project Settings", sliderName(slider) + ":", Float.toString(get(slider, s)));
        if (input == null) return;
        try {
            set(slider, s, Float.parseFloat(input.trim()));
            markDirty();
        } catch (NumberFormatException e) {
            setStatus("NOT A NUMBER: " + input.trim(), true);
        }
    }

    private static void save(EditorWorkspace workspace) {
        ProjectSettings s = workspace.getProjectSettings();
        s.sanitize();
        try {
            s.write(new File(workspace.getActiveProjectPath()));
            UISoundPlayer.play(UISound.CLICK);
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Project] Saved project settings");
            close(workspace);
        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            setStatus("SAVE FAILED: " + msg, true);
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.ERROR, "[Project] Could not save project settings: " + msg);
        }
    }

    private static void close(EditorWorkspace workspace) {
        draggingSlider = NONE;
        dirty = false;
        workspace.getOverlayManager().setActiveModal(ModalState.NONE);
    }

    private static void markDirty() {
        dirty = true;
        if (!statusIsError) setStatus("CHANGES PREVIEW LIVE. SAVE WRITES PROJECTSETTINGS.BIN", false);
    }

    private static void setStatus(String message, boolean isError) {
        status = (message != null) ? message : "";
        statusIsError = isError;
    }

    // ------------------------------------------------------------------------------------------
    // Slider model
    // ------------------------------------------------------------------------------------------

    private static float get(int slider, ProjectSettings s) {
        return switch (slider) {
            case FOG_START -> s.fogStart;
            case FOG_END -> s.fogEnd;
            case FOG_DENSITY -> s.fogDensity;
            case FOG_R, FOG_G, FOG_B -> s.fogColor[slider - FOG_R];
            case AMBIENT_R, AMBIENT_G, AMBIENT_B -> s.ambientColor[slider - AMBIENT_R];
            case AMBIENT_INTENSITY -> s.ambientIntensity;
            default -> 0.0f;
        };
    }

    /** Sets one value and re-clamps the whole set (dragging fog start past fog end pushes the end along). */
    private static void set(int slider, ProjectSettings s, float value) {
        switch (slider) {
            case FOG_START -> {
                s.fogStart = value;
                if (s.fogEnd <= value) s.fogEnd = value + 0.01f;
            }
            case FOG_END -> s.fogEnd = Math.max(value, s.fogStart + 0.01f);
            case FOG_DENSITY -> s.fogDensity = value;
            case FOG_R, FOG_G, FOG_B -> s.fogColor[slider - FOG_R] = value;
            case AMBIENT_R, AMBIENT_G, AMBIENT_B -> s.ambientColor[slider - AMBIENT_R] = value;
            case AMBIENT_INTENSITY -> s.ambientIntensity = value;
            default -> {}
        }
        s.sanitize();
    }

    private static float sliderMax(int slider) {
        return switch (slider) {
            case FOG_START, FOG_END -> FOG_DISTANCE_SLIDER_MAX;
            case AMBIENT_INTENSITY -> ProjectSettings.AMBIENT_INTENSITY_MAX;
            default -> 1.0f;
        };
    }

    private static float normalized(int slider, ProjectSettings s) {
        return get(slider, s) / sliderMax(slider);
    }

    private static String sliderName(int slider) {
        return switch (slider) {
            case FOG_START -> "Fog start distance";
            case FOG_END -> "Fog end distance";
            case FOG_DENSITY -> "Fog density (0-1)";
            case AMBIENT_INTENSITY -> "Ambient intensity (0-" + (int) ProjectSettings.AMBIENT_INTENSITY_MAX + ")";
            default -> "Value (0-1)";
        };
    }

    private static float rowY(int slider) {
        return switch (slider) {
            case FOG_START -> FOG_START_Y;
            case FOG_END -> FOG_END_Y;
            case FOG_DENSITY -> FOG_DENSITY_Y;
            case FOG_R, FOG_G, FOG_B -> FOG_COLOR_Y;
            case AMBIENT_R, AMBIENT_G, AMBIENT_B -> AMBIENT_COLOR_Y;
            case AMBIENT_INTENSITY -> AMBIENT_INTENSITY_Y;
            default -> Float.NaN;
        };
    }

    private static boolean isColorChannel(int slider) {
        return slider >= FOG_R && slider <= AMBIENT_B;
    }

    private static float sliderX0(int slider) {
        if (!isColorChannel(slider)) return CONTROL_X0;
        return channelX0((slider - FOG_R) % 3);
    }

    private static float sliderX1(int slider) {
        if (!isColorChannel(slider)) return TRACK_X1;
        return channelX1((slider - FOG_R) % 3);
    }

    private static float channelX0(int channel) {
        float start = CONTROL_X0 + SWATCH_W + CHANNEL_GAP;
        float w = (VALUE_X1 - start - CHANNEL_GAP * 2.0f) / 3.0f;
        return start + channel * (w + CHANNEL_GAP);
    }

    private static float channelX1(int channel) {
        float start = CONTROL_X0 + SWATCH_W + CHANNEL_GAP;
        float w = (VALUE_X1 - start - CHANNEL_GAP * 2.0f) / 3.0f;
        return channelX0(channel) + w;
    }

    private static int sliderAt(float x, float y) {
        for (int slider = FOG_START; slider <= AMBIENT_INTENSITY; slider++) {
            float midY = rowY(slider);
            if (inRect(x, y, sliderX0(slider), midY - ROW_H * 0.5f, sliderX1(slider), midY + ROW_H * 0.5f)) return slider;
        }
        return NONE;
    }

    private static final int[] SCALAR_SLIDERS = { FOG_START, FOG_END, FOG_DENSITY, AMBIENT_INTENSITY };

    /** Scalar slider whose value well (right of the track) is under the point, or NONE. */
    private static int valueWellAt(float x, float y) {
        for (int slider : SCALAR_SLIDERS) {
            float midY = rowY(slider);
            if (inRect(x, y, VALUE_X0, midY - ROW_H * 0.5f, VALUE_X1, midY + ROW_H * 0.5f)) return slider;
        }
        return NONE;
    }

    private static boolean hitsToggle(float x, float y, float midY) {
        return inRect(x, y, CONTROL_X0, midY - ROW_H * 0.5f, CONTROL_X0 + 0.16f, midY + ROW_H * 0.5f);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Appends {@code v} with a fixed number of decimals without the boxing / formatting of String.format. */
    private static void appendFixed(StringBuilder sb, float v, int decimals) {
        if (v < 0.0f) { sb.append('-'); v = -v; }
        int scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        long scaled = Math.round((double) v * scale);
        sb.append(scaled / scale);
        if (decimals > 0) {
            sb.append('.');
            long frac = scaled % scale;
            for (int div = scale / 10; div > 0; div /= 10) {
                sb.append((char) ('0' + (frac / div) % 10));
            }
        }
    }

    private static int packRgb(float r, float g, float b) {
        int ri = Math.round(Math.max(0.0f, Math.min(1.0f, r)) * 255.0f);
        int gi = Math.round(Math.max(0.0f, Math.min(1.0f, g)) * 255.0f);
        int bi = Math.round(Math.max(0.0f, Math.min(1.0f, b)) * 255.0f);
        return (ri << 16) | (gi << 8) | bi;
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
