package com.retro.engine.editor.overlays;

import com.retro.engine.audio.AudioBus;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.core.ProjectSettings;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.panels.EngineConsolePanel;
import com.retro.engine.graphics.ShadingModel;
import com.retro.engine.graphics.TextureFiltering;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.io.File;

/**
 * "File > Project Settings..." modal with two tabs: RENDER (shading model, ground shadow blobs, distance fog, ambient
 * light) and AUDIO (Master / BGM / SFX / Voice bus volumes and mutes, BGM ducking, reverb zones). Edits apply live to the workspace's {@link ProjectSettings}, which
 * {@code SceneRenderPipeline} reads every frame; SAVE writes the project file and CANCEL restores the values the
 * modal opened with. Rendering and hit-testing share the layout constants below.
 *
 * <p>Sliders drag on press; the value well to the right of a slider opens a prompt for an exact number. The shading
 * model dropdown opens a list drawn over the rows below it; while it is open, clicks go to the list (a click elsewhere
 * just closes it). Rendering
 * formats numbers into a reused StringBuilder, so an open modal adds no per-frame allocation.
 */
public final class ProjectSettingsOverlay {

    private static final float CARD_X0 = -0.56f, CARD_X1 = 0.56f;
    private static final float CARD_Y0 = -0.64f, CARD_Y1 = 0.74f;

    private static final float LABEL_X = -0.52f;
    private static final float CONTROL_X0 = -0.16f;
    private static final float TRACK_X1 = 0.34f;
    private static final float VALUE_X0 = 0.36f, VALUE_X1 = 0.52f;
    private static final float ROW_H = 0.066f;
    private static final float CHANNEL_GAP = 0.016f;
    private static final float SWATCH_W = 0.07f;

    // Row centers, top to bottom
    private static final float SECTION_SHADING_Y = 0.59f;
    private static final float SHADING_MODEL_Y = 0.52f;
    private static final float BILINEAR_Y = 0.45f;
    private static final float SECTION_SHADOW_Y = 0.385f;
    private static final float BLOBS_Y = 0.325f;
    private static final float SECTION_FOG_Y = 0.255f;
    private static final float FOG_ENABLED_Y = 0.195f;
    private static final float FOG_START_Y = 0.11f;
    private static final float FOG_END_Y = 0.02f;
    private static final float FOG_DENSITY_Y = -0.07f;
    private static final float FOG_COLOR_Y = -0.16f;
    private static final float SECTION_AMBIENT_Y = -0.25f;
    private static final float AMBIENT_COLOR_Y = -0.32f;
    private static final float AMBIENT_INTENSITY_Y = -0.41f;

    // Audio tab rows
    private static final float SECTION_BUSES_Y = 0.59f;
    private static final float[] BUS_Y = { 0.52f, 0.44f, 0.36f, 0.28f }; // AudioBus ordinal order
    private static final float SECTION_DUCK_Y = 0.19f;
    private static final float DUCK_ENABLED_Y = 0.12f;
    private static final float DUCK_LEVEL_Y = 0.04f;
    private static final float DUCK_ATTACK_Y = -0.04f;
    private static final float DUCK_RELEASE_Y = -0.12f;
    private static final float SECTION_REVERB_Y = -0.21f;
    private static final float REVERB_ENABLED_Y = -0.28f;
    private static final float BUS_TRACK_X1 = 0.20f;
    private static final float MUTE_X0 = 0.22f, MUTE_X1 = 0.34f;
    private static final String[] BUS_LABELS = { "MASTER", "BGM (MUSIC)", "SFX", "VOICE" };

    // Header tabs
    private static final float TAB_Y0 = CARD_Y1 - 0.078f, TAB_Y1 = CARD_Y1 - 0.016f;
    private static final float TAB_RENDER_X0 = 0.03f, TAB_RENDER_X1 = 0.16f;
    private static final float TAB_AUDIO_X0 = 0.175f, TAB_AUDIO_X1 = 0.305f;
    private static final float TAB_RUNTIME_X0 = 0.32f, TAB_RUNTIME_X1 = 0.47f;
    private static final int TAB_RENDER_ID = 0, TAB_AUDIO_ID = 1, TAB_RUNTIME_ID = 2;

    // Runtime tab rows
    private static final float SECTION_MEMORY_Y = 0.59f;
    private static final float MAX_HEAP_Y = 0.52f;
    private static final float MAX_HEAP_HINT_Y = 0.46f;
    private static final float SECTION_PACING_Y = 0.37f;
    private static final float VSYNC_Y = 0.30f;
    private static final float FRAME_CAP_Y = 0.22f;
    private static final float FRAME_CAP_HINT_Y = 0.16f;

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
    private static final int BUS_FIRST = 10, BUS_LAST = 13; // BUS_FIRST + AudioBus ordinal (MASTER, BGM, SFX, VOICE)
    private static final int DUCK_LEVEL = 14, DUCK_ATTACK = 15, DUCK_RELEASE = 16;
    private static final int MAX_HEAP = 17;
    private static final int FRAME_CAP = 18;

    // UI slider ranges (exact entry through the value prompt is clamped by ProjectSettings.sanitize instead)
    private static final float FOG_DISTANCE_SLIDER_MAX = 500.0f;
    private static final float MAX_HEAP_SLIDER_MAX = 8192.0f;
    private static final int MAX_HEAP_STEP_MB = 64;
    // Dragging tops out here; typing a value reaches ProjectSettings.MAX_FRAME_RATE_CAP
    private static final float FRAME_CAP_SLIDER_MAX = 240.0f;

    private static final ProjectSettings openedWith = ProjectSettings.defaults();
    private static boolean dirty = false;
    private static String status = "";
    private static boolean statusIsError = false;

    private static int draggingSlider = NONE;
    private static int tab = TAB_RENDER_ID;

    private static final ShadingModel[] SHADING_MODELS = ShadingModel.values();
    private static final float DROPDOWN_X0 = CONTROL_X0, DROPDOWN_X1 = TRACK_X1;
    private static boolean shadingDropdownOpen = false;

    private static final StringBuilder valueSb = new StringBuilder(16);

    private ProjectSettingsOverlay() {}

    /** Opens the modal for the active project, remembering its current values for CANCEL. */
    public static void open(EditorWorkspace workspace) {
        if (workspace == null || !workspace.hasActiveProject()) return;
        openedWith.copyRenderSettingsFrom(workspace.getProjectSettings());
        openedWith.copyAudioSettingsFrom(workspace.getProjectSettings());
        openedWith.copyRuntimeSettingsFrom(workspace.getProjectSettings());
        dirty = false;
        draggingSlider = NONE;
        shadingDropdownOpen = false;
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
        UIGeometryBatch.addBoundedText(buffer, dirty ? "PROJECT SETTINGS *" : "PROJECT SETTINGS", CARD_X0 + 0.03f, CARD_Y1 - 0.035f,
            TAB_RENDER_X0 - 0.02f, 1.0f, EditorTheme.TEXT, 1.0f);
        tabButton(buffer, "RENDER", TAB_RENDER_X0, TAB_RENDER_X1, tab == TAB_RENDER_ID, hoverX, hoverY);
        tabButton(buffer, "AUDIO", TAB_AUDIO_X0, TAB_AUDIO_X1, tab == TAB_AUDIO_ID, hoverX, hoverY);
        tabButton(buffer, "RUNTIME", TAB_RUNTIME_X0, TAB_RUNTIME_X1, tab == TAB_RUNTIME_ID, hoverX, hoverY);

        UIGeometryBatch.addRoundedQuad(buffer, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 0.90f);
        centeredTextIn(buffer, "X", CLOSE_X0, CLOSE_X1, (CLOSE_Y0 + CLOSE_Y1) * 0.5f, 0.9f, EditorTheme.TEXT);

        if (tab == TAB_AUDIO_ID) renderAudioTab(buffer, s, hoverX, hoverY);
        else if (tab == TAB_RUNTIME_ID) renderRuntimeTab(buffer, s, hoverX, hoverY);
        else renderRenderTab(buffer, s, hoverX, hoverY);

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

        // Last, so the open list draws over the rows beneath it
        if (tab == TAB_RENDER_ID && shadingDropdownOpen) dropdownList(buffer, s.getShadingModel(), hoverX, hoverY);
    }

    private static void renderRenderTab(UIGeometryBatch.Buffer buffer, ProjectSettings s, float hoverX, float hoverY) {
        sectionHeader(buffer, "SHADING", SECTION_SHADING_Y);
        dropdownField(buffer, "GLOBAL SHADING MODEL", SHADING_MODEL_Y, s.getShadingModel(), hoverX, hoverY);
        toggleRow(buffer, "BILINEAR TEXTURES", BILINEAR_Y, s.textureFiltering == TextureFiltering.BILINEAR, hoverX, hoverY);

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
    }

    private static void renderAudioTab(UIGeometryBatch.Buffer buffer, ProjectSettings s, float hoverX, float hoverY) {
        sectionHeader(buffer, "MIXER BUSES", SECTION_BUSES_Y);
        boolean masterOn = !s.busMuted[AudioBus.MASTER.ordinal()];
        for (int b = 0; b < AudioBus.COUNT; b++) {
            float midY = BUS_Y[b];
            boolean audible = !s.busMuted[b] && (b == AudioBus.MASTER.ordinal() || masterOn);
            rowLabel(buffer, BUS_LABELS[b], midY, audible);
            drawTrack(buffer, CONTROL_X0, BUS_TRACK_X1, midY, s.busVolume[b], audible ? EditorTheme.ACCENT : EditorTheme.GRAPHITE);
            muteButton(buffer, midY, s.busMuted[b], hoverX, hoverY);
            valueWell(buffer, midY, BUS_FIRST + b, s, 2, audible);
        }

        sectionHeader(buffer, "BGM DUCKING  (VOICE / CRITICAL SFX)", SECTION_DUCK_Y);
        toggleRow(buffer, "DUCKING ENABLED", DUCK_ENABLED_Y, s.duckingEnabled, hoverX, hoverY);
        boolean duckOn = s.duckingEnabled;
        sliderRow(buffer, "DUCKED BGM LEVEL", DUCK_LEVEL_Y, DUCK_LEVEL, s, 2, duckOn);
        sliderRow(buffer, "ATTACK (SEC)", DUCK_ATTACK_Y, DUCK_ATTACK, s, 2, duckOn);
        sliderRow(buffer, "RELEASE (SEC)", DUCK_RELEASE_Y, DUCK_RELEASE, s, 2, duckOn);

        sectionHeader(buffer, "SPATIAL REVERB", SECTION_REVERB_Y);
        toggleRow(buffer, "REVERB ZONES", REVERB_ENABLED_Y, s.reverbZonesEnabled, hoverX, hoverY);
    }

    private static void renderRuntimeTab(UIGeometryBatch.Buffer buffer, ProjectSettings s, float hoverX, float hoverY) {
        sectionHeader(buffer, "MEMORY", SECTION_MEMORY_Y);
        sliderRow(buffer, "MAX JAVA HEAP (MB)", MAX_HEAP_Y, MAX_HEAP, s, 0, true);
        UIGeometryBatch.addTruncatedText(buffer, "USED BY EXPORTED GAMES. EXPORT AGAIN TO APPLY A CHANGE.", LABEL_X, MAX_HEAP_HINT_Y, VALUE_X1, 0.75f,
            EditorTheme.TEXT_MUTED, 1.0f);

        sectionHeader(buffer, "FRAME PACING", SECTION_PACING_Y);
        toggleRow(buffer, "VSYNC", VSYNC_Y, s.vsync, hoverX, hoverY);
        sliderRow(buffer, "FRAME RATE CAP (FPS)", FRAME_CAP_Y, FRAME_CAP, s, 0, true);
        UIGeometryBatch.addTruncatedText(buffer, "0 = NO CAP. APPLIES WHEN THE GAME RUNS, NOT IN THE EDITOR.", LABEL_X, FRAME_CAP_HINT_Y, VALUE_X1, 0.75f,
            EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void tabButton(UIGeometryBatch.Buffer buffer, String label, float x0, float x1, boolean selected, float hoverX, float hoverY) {
        boolean hot = inRect(hoverX, hoverY, x0, TAB_Y0, x1, TAB_Y1);
        if (selected) {
            UIGeometryBatch.addRoundedQuad(buffer, x0, TAB_Y0, x1, TAB_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        } else {
            UIGeometryBatch.addRoundedCard(buffer, x0, TAB_Y0, x1, TAB_Y1, EditorTheme.RADIUS_SMALL,
                hot ? EditorTheme.BORDER : EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 1.0f);
        }
        centeredTextIn(buffer, label, x0, x1, (TAB_Y0 + TAB_Y1) * 0.5f, 0.82f, selected ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED);
    }

    private static void muteButton(UIGeometryBatch.Buffer buffer, float midY, boolean muted, float hoverX, float hoverY) {
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        boolean hot = inRect(hoverX, hoverY, MUTE_X0, y0, MUTE_X1, y1);
        if (muted) {
            UIGeometryBatch.addRoundedQuad(buffer, MUTE_X0, y0, MUTE_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.RED, hot ? 0.8f : 0.95f);
        } else {
            UIGeometryBatch.addRoundedCard(buffer, MUTE_X0, y0, MUTE_X1, y1, EditorTheme.RADIUS_SMALL,
                hot ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        }
        centeredTextIn(buffer, "MUTE", MUTE_X0, MUTE_X1, midY, 0.75f, muted ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED);
    }

    private static void dropdownField(UIGeometryBatch.Buffer buffer, String label, float midY, ShadingModel current, float hoverX, float hoverY) {
        rowLabel(buffer, label, midY, true);
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        boolean hot = shadingDropdownOpen || inRect(hoverX, hoverY, DROPDOWN_X0, y0, DROPDOWN_X1, y1);
        UIGeometryBatch.addRoundedCard(buffer, DROPDOWN_X0, y0, DROPDOWN_X1, y1, EditorTheme.RADIUS_SMALL,
            EditorTheme.SURFACE_SUNKEN, hot ? EditorTheme.ACCENT : EditorTheme.BORDER_STRONG, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, current.displayName(), DROPDOWN_X0 + 0.016f, midY + 0.82f * 0.009f,
            DROPDOWN_X1 - 0.05f, 0.82f, EditorTheme.TEXT, 1.0f);
        centeredTextIn(buffer, shadingDropdownOpen ? "^" : "v", DROPDOWN_X1 - 0.045f, DROPDOWN_X1 - 0.005f, midY, 0.82f, EditorTheme.TEXT_MUTED);
    }

    private static void dropdownList(UIGeometryBatch.Buffer buffer, ShadingModel current, float hoverX, float hoverY) {
        float listY1 = dropdownOptionY1(0), listY0 = dropdownOptionY1(SHADING_MODELS.length);
        UIGeometryBatch.addRoundedCard(buffer, DROPDOWN_X0, listY0, DROPDOWN_X1, listY1, EditorTheme.RADIUS_SMALL,
            EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        for (int i = 0; i < SHADING_MODELS.length; i++) {
            ShadingModel m = SHADING_MODELS[i];
            float y1 = dropdownOptionY1(i), y0 = y1 - ROW_H, midY = (y0 + y1) * 0.5f;
            if (m == current) {
                UIGeometryBatch.addRoundedQuad(buffer, DROPDOWN_X0 + 0.004f, y0 + 0.004f, DROPDOWN_X1 - 0.004f, y1 - 0.004f,
                    EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 0.85f);
            } else if (inRect(hoverX, hoverY, DROPDOWN_X0, y0, DROPDOWN_X1, y1)) {
                UIGeometryBatch.addRoundedQuad(buffer, DROPDOWN_X0 + 0.004f, y0 + 0.004f, DROPDOWN_X1 - 0.004f, y1 - 0.004f,
                    EditorTheme.RADIUS_SMALL, EditorTheme.BORDER, 1.0f);
            }
            UIGeometryBatch.addBoundedText(buffer, m.displayName(), DROPDOWN_X0 + 0.016f, midY + 0.82f * 0.009f,
                DROPDOWN_X1 - 0.01f, 0.82f, EditorTheme.TEXT, 1.0f);
        }
    }

    /** Top edge of dropdown option {@code i}; the list hangs directly below the field. */
    private static float dropdownOptionY1(int i) {
        return SHADING_MODEL_Y - ROW_H * 0.5f - 0.006f - i * ROW_H;
    }

    /** Index of the dropdown option under the point, or NONE. */
    private static int dropdownOptionAt(float x, float y) {
        for (int i = 0; i < SHADING_MODELS.length; i++) {
            float y1 = dropdownOptionY1(i);
            if (inRect(x, y, DROPDOWN_X0, y1 - ROW_H, DROPDOWN_X1, y1)) return i;
        }
        return NONE;
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

        valueWell(buffer, midY, slider, s, decimals, enabled);
    }

    private static void valueWell(UIGeometryBatch.Buffer buffer, float midY, int slider, ProjectSettings s, int decimals, boolean enabled) {
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

        int clickedTab = tabAt(ndcX, ndcY);
        if (clickedTab != NONE) {
            if (clickedTab != tab) {
                UISoundPlayer.play(UISound.CLICK);
                tab = clickedTab;
                shadingDropdownOpen = false;
                draggingSlider = NONE;
            }
            return;
        }

        // An open list owns the click: pick an option, or close without acting on whatever lies beneath
        if (tab == TAB_RENDER_ID && shadingDropdownOpen) {
            shadingDropdownOpen = false;
            int option = dropdownOptionAt(ndcX, ndcY);
            if (option != NONE) {
                UISoundPlayer.play(UISound.CLICK);
                if (s.getShadingModel() != SHADING_MODELS[option]) {
                    s.setShadingModel(SHADING_MODELS[option]);
                    markDirty();
                }
            }
            return;
        }
        if (tab == TAB_RENDER_ID && inRect(ndcX, ndcY, DROPDOWN_X0, SHADING_MODEL_Y - ROW_H * 0.5f, DROPDOWN_X1, SHADING_MODEL_Y + ROW_H * 0.5f)) {
            UISoundPlayer.play(UISound.CLICK);
            shadingDropdownOpen = true;
            return;
        }

        if (inRect(ndcX, ndcY, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1) || inRect(ndcX, ndcY, CANCEL_X0, ACTION_Y0, CANCEL_X1, ACTION_Y1)) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            s.copyRenderSettingsFrom(openedWith);
            s.copyAudioSettingsFrom(openedWith);
            s.copyRuntimeSettingsFrom(openedWith);
            close(workspace);
            return;
        }
        if (inRect(ndcX, ndcY, SAVE_X0, ACTION_Y0, SAVE_X1, ACTION_Y1)) {
            save(workspace);
            return;
        }
        if (inRect(ndcX, ndcY, RESET_X0, ACTION_Y0, RESET_X1, ACTION_Y1)) {
            UISoundPlayer.play(UISound.CLICK);
            if (tab == TAB_AUDIO_ID) s.resetAudioSettings();
            else if (tab == TAB_RUNTIME_ID) s.resetRuntimeSettings();
            else s.resetRenderSettings();
            markDirty();
            return;
        }
        if (tab == TAB_AUDIO_ID) {
            if (handleAudioToggleClick(ndcX, ndcY, s)) return;
        } else if (tab == TAB_RENDER_ID && hitsToggle(ndcX, ndcY, BLOBS_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.shadowBlobsEnabled = !s.shadowBlobsEnabled;
            markDirty();
            return;
        }
        if (tab == TAB_RENDER_ID && hitsToggle(ndcX, ndcY, BILINEAR_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.textureFiltering = s.textureFiltering == TextureFiltering.BILINEAR ? TextureFiltering.NEAREST : TextureFiltering.BILINEAR;
            markDirty();
            return;
        }
        if (tab == TAB_RUNTIME_ID && hitsToggle(ndcX, ndcY, VSYNC_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.vsync = !s.vsync;
            markDirty();
            return;
        }
        if (tab == TAB_RENDER_ID && hitsToggle(ndcX, ndcY, FOG_ENABLED_Y)) {
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

    /** Mute buttons and ducking / reverb toggles on the AUDIO tab; true when the click was consumed. */
    private static boolean handleAudioToggleClick(float x, float y, ProjectSettings s) {
        for (int b = 0; b < AudioBus.COUNT; b++) {
            if (inRect(x, y, MUTE_X0, BUS_Y[b] - ROW_H * 0.5f, MUTE_X1, BUS_Y[b] + ROW_H * 0.5f)) {
                UISoundPlayer.play(UISound.TOGGLE);
                s.busMuted[b] = !s.busMuted[b];
                markDirty();
                return true;
            }
        }
        if (hitsToggle(x, y, DUCK_ENABLED_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.duckingEnabled = !s.duckingEnabled;
            markDirty();
            return true;
        }
        if (hitsToggle(x, y, REVERB_ENABLED_Y)) {
            UISoundPlayer.play(UISound.TOGGLE);
            s.reverbZonesEnabled = !s.reverbZonesEnabled;
            markDirty();
            return true;
        }
        return false;
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
        tab = TAB_RENDER_ID;
        shadingDropdownOpen = false;
        dirty = false;
        workspace.getOverlayManager().setActiveModal(ModalState.NONE);
    }

    private static void markDirty() {
        dirty = true;
        if (!statusIsError) setStatus("CHANGES PREVIEW LIVE. SAVE WRITES THE PROJECT FILE", false);
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
            case DUCK_LEVEL -> s.duckLevel;
            case DUCK_ATTACK -> s.duckAttack;
            case DUCK_RELEASE -> s.duckRelease;
            case MAX_HEAP -> s.maxHeapMb;
            case FRAME_CAP -> s.frameRateCap;
            default -> (slider >= BUS_FIRST && slider <= BUS_LAST) ? s.busVolume[slider - BUS_FIRST] : 0.0f;
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
            case DUCK_LEVEL -> s.duckLevel = value;
            case DUCK_ATTACK -> s.duckAttack = value;
            case DUCK_RELEASE -> s.duckRelease = value;
            case MAX_HEAP -> s.maxHeapMb = Math.round(value / MAX_HEAP_STEP_MB) * MAX_HEAP_STEP_MB;
            // The bottom of the track is "no cap"; anything above snaps into the supported range
            case FRAME_CAP -> s.frameRateCap = value < ProjectSettings.MIN_FRAME_RATE_CAP * 0.5f
                    ? 0 : ProjectSettings.sanitizeFrameRateCap(Math.round(value));
            default -> {
                if (slider >= BUS_FIRST && slider <= BUS_LAST) s.busVolume[slider - BUS_FIRST] = value;
            }
        }
        s.sanitize();
    }

    private static float sliderMax(int slider) {
        return switch (slider) {
            case FOG_START, FOG_END -> FOG_DISTANCE_SLIDER_MAX;
            case AMBIENT_INTENSITY -> ProjectSettings.AMBIENT_INTENSITY_MAX;
            case DUCK_ATTACK -> ProjectSettings.DUCK_ATTACK_MAX;
            case DUCK_RELEASE -> ProjectSettings.DUCK_RELEASE_MAX;
            case MAX_HEAP -> MAX_HEAP_SLIDER_MAX;
            case FRAME_CAP -> FRAME_CAP_SLIDER_MAX;
            default -> 1.0f;
        };
    }

    private static float normalized(int slider, ProjectSettings s) {
        return get(slider, s) / sliderMax(slider);
    }

    private static String sliderName(int slider) {
        return switch (slider) {
            case FOG_START -> "Fog start distance";
            case FOG_END -> "Fog end / draw distance";
            case FOG_DENSITY -> "Fog thickness (0-1)";
            case AMBIENT_INTENSITY -> "Ambient intensity (0-" + (int) ProjectSettings.AMBIENT_INTENSITY_MAX + ")";
            case DUCK_LEVEL -> "Ducked BGM level (0-1)";
            case DUCK_ATTACK -> "Duck attack seconds (0-" + (int) ProjectSettings.DUCK_ATTACK_MAX + ")";
            case DUCK_RELEASE -> "Duck release seconds (0-" + (int) ProjectSettings.DUCK_RELEASE_MAX + ")";
            case MAX_HEAP -> "Max Java heap in MB (" + ProjectSettings.MIN_MAX_HEAP_MB + "-" + ProjectSettings.MAX_MAX_HEAP_MB + ")";
            case FRAME_CAP -> "Frame rate cap in FPS (0 for none, or " + ProjectSettings.MIN_FRAME_RATE_CAP + "-" + ProjectSettings.MAX_FRAME_RATE_CAP + ")";
            case BUS_FIRST, BUS_FIRST + 1, BUS_FIRST + 2, BUS_FIRST + 3 -> BUS_LABELS[slider - BUS_FIRST] + " bus volume (0-1)";
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
            case DUCK_LEVEL -> DUCK_LEVEL_Y;
            case DUCK_ATTACK -> DUCK_ATTACK_Y;
            case DUCK_RELEASE -> DUCK_RELEASE_Y;
            case MAX_HEAP -> MAX_HEAP_Y;
            case FRAME_CAP -> FRAME_CAP_Y;
            default -> (slider >= BUS_FIRST && slider <= BUS_LAST) ? BUS_Y[slider - BUS_FIRST] : Float.NaN;
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
        if (slider >= BUS_FIRST && slider <= BUS_LAST) return BUS_TRACK_X1;
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
        int first = tab == TAB_RUNTIME_ID ? MAX_HEAP : tab == TAB_AUDIO_ID ? BUS_FIRST : FOG_START;
        int last = tab == TAB_RUNTIME_ID ? FRAME_CAP : tab == TAB_AUDIO_ID ? DUCK_RELEASE : AMBIENT_INTENSITY;
        for (int slider = first; slider <= last; slider++) {
            float midY = rowY(slider);
            if (inRect(x, y, sliderX0(slider), midY - ROW_H * 0.5f, sliderX1(slider), midY + ROW_H * 0.5f)) return slider;
        }
        return NONE;
    }

    private static final int[] SCALAR_SLIDERS = { FOG_START, FOG_END, FOG_DENSITY, AMBIENT_INTENSITY };
    private static final int[] AUDIO_SCALAR_SLIDERS = { BUS_FIRST, BUS_FIRST + 1, BUS_FIRST + 2, BUS_FIRST + 3, DUCK_LEVEL, DUCK_ATTACK, DUCK_RELEASE };
    private static final int[] RUNTIME_SCALAR_SLIDERS = { MAX_HEAP, FRAME_CAP };

    /** Scalar slider whose value well (right of the track) is under the point, or NONE. */
    private static int valueWellAt(float x, float y) {
        for (int slider : tab == TAB_RUNTIME_ID ? RUNTIME_SCALAR_SLIDERS : tab == TAB_AUDIO_ID ? AUDIO_SCALAR_SLIDERS : SCALAR_SLIDERS) {
            float midY = rowY(slider);
            if (inRect(x, y, VALUE_X0, midY - ROW_H * 0.5f, VALUE_X1, midY + ROW_H * 0.5f)) return slider;
        }
        return NONE;
    }

    private static int tabAt(float x, float y) {
        if (inRect(x, y, TAB_RENDER_X0, TAB_Y0, TAB_RENDER_X1, TAB_Y1)) return TAB_RENDER_ID;
        if (inRect(x, y, TAB_AUDIO_X0, TAB_Y0, TAB_AUDIO_X1, TAB_Y1)) return TAB_AUDIO_ID;
        if (inRect(x, y, TAB_RUNTIME_X0, TAB_Y0, TAB_RUNTIME_X1, TAB_Y1)) return TAB_RUNTIME_ID;
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
