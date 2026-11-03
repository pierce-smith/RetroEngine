package com.retro.engine.editor.animator;

import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorTransition;
import com.retro.engine.animation.BlendTree;
import com.retro.engine.animation.KeyframeTrack;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SRP Delegate: Manages the Animator Inspector: state details and outgoing transitions on top,
 * and a dope sheet below — time ruler, per-bone Translation/Rotation/Scale keyframe tracks,
 * and a draggable playhead synced to the active animator's playback time.
 *
 * The playhead seeks the animator when the inspected state is the one currently playing;
 * for any other state it scrubs a local preview time that only drives this view. On the selected animator's
 * recorded clip (while EDITING) it is the record playhead instead: it sets the time 'K' / drag-release keys land
 * at, and the timeline runs past the last key so new keys can be placed later in time. While recording, the dope sheet
 * header offers [+ NEW CLIP] (start a blank recording clip) and [RENAME] (retitle the recording and its state).
 */
public class AnimatorInspectorDelegate {

    // --- Layout (design-space NDC of DockPanel.ANIMATOR_INSPECTOR); hit-tests share these values ---
    private static final float PANEL_X0 = 0.55f, PANEL_Y0 = -0.50f, PANEL_X1 = 1.00f, PANEL_Y1 = 0.86f;
    private static final float HEADER_Y0 = 0.81f;
    private static final float CX0 = 0.565f, CX1 = 0.985f;
    private static final float PROP_ROW_H = 0.045f;

    private static final float DOPE_TOP = 0.215f;
    private static final float DOPE_HEADER_Y0 = 0.175f;
    private static final float RULER_Y0 = 0.125f, RULER_Y1 = 0.165f;
    private static final float TRACK_TOP = RULER_Y0, TRACK_BOTTOM = -0.49f;
    private static final float TRACK_ROW_H = 0.038f;
    private static final float LABEL_X1 = 0.705f;
    private static final float TL_X0 = 0.718f, TL_X1 = 0.972f;
    private static final float DIAMOND_H = 0.010f;

    // Dope sheet header buttons (shown while recording), in place of the "DOPE SHEET" title
    private static final float HEADER_BTN_Y0 = DOPE_HEADER_Y0 + 0.006f, HEADER_BTN_Y1 = DOPE_TOP - 0.006f;
    private static final float NEW_CLIP_X0 = CX0 + 0.006f, NEW_CLIP_X1 = CX0 + 0.106f;
    private static final float RENAME_X0 = NEW_CLIP_X1 + 0.006f, RENAME_X1 = NEW_CLIP_X1 + 0.081f;

    // Record timeline: at least this long, and this far past the last key
    private static final float RECORD_MIN_LENGTH = 2.0f, RECORD_TAIL = 1.0f;

    private static final float MIN_TICK_GAP = 0.010f;
    private static final float MIN_LABEL_GAP = 0.07f;
    private static final int[] MINOR_FRAME_STEPS = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000};
    private static final float[] MAJOR_SECOND_STEPS = {0.05f, 0.1f, 0.2f, 0.25f, 0.5f, 1.0f, 2.0f, 5.0f, 10.0f, 30.0f, 60.0f};

    // Playhead / scrubbing
    private static boolean scrubbing = false;
    private static float previewTime = 0.0f;
    private static String previewStateName = null;

    // Track list scrolling (in rows)
    private static float scrollRows = 0.0f;
    private static int maxScrollRows = 0;

    // Sorted track cache, rebuilt when the displayed clip or its track count changes
    private static final List<KeyframeTrack> sortedTracks = new ArrayList<>();
    private static final Comparator<KeyframeTrack> BY_BONE = Comparator.comparing(t -> t.boneName == null ? "" : t.boneName);
    private static AnimationClip cachedClip = null;
    private static int cachedTrackCount = -1;

    private static final StringBuilder textScratch = new StringBuilder(64);

    private static float textTop(float midY, float size) { return midY + size * 0.009f; }

    /** Selected state, or the playing state when nothing is selected. Shared by render and input. */
    private static AnimatorState resolveInspectedState(AnimatorController controller, AnimatorComponent animator, String selectedStateName) {
        if (controller == null) return null;
        AnimatorState state = selectedStateName != null ? controller.getState(selectedStateName) : null;
        if (state == null && animator != null) state = animator.getCurrentState();
        return state != null ? state : controller.getEntryState();
    }

    /** Parameter values blend trees are evaluated with: the animator's live values, else the controller's defaults. */
    private static Map<String, AnimatorParameter> parameters(AnimatorController controller, AnimatorComponent animator) {
        return (animator != null) ? animator.getParameters() : controller.getParameters();
    }

    /** Clip shown on the dope sheet: the state's clip, or a blend tree's currently dominant motion. */
    private static AnimationClip displayClip(AnimatorState state, AnimatorController controller, AnimatorComponent animator) {
        if (state == null) return null;
        if (state.blendTree == null) return state.clip;
        List<BlendTree.Motion> motions = state.blendTree.getMotions();
        if (motions.isEmpty()) return null;
        float[] weights = state.blendTree.evaluateWeights(parameters(controller, animator));
        int best = 0;
        for (int i = 1; i < motions.size(); i++) {
            if (weights[i] > weights[best]) best = i;
        }
        return motions.get(best).clip;
    }

    /** Only the playing state is seekable, so only it shows (and scrubs) the animator's time. */
    private static boolean isLive(AnimatorState state, AnimatorComponent animator) {
        return animator != null && state == animator.getCurrentState();
    }

    /** Clip-local playhead time for the inspected state. */
    private static float playheadTime(AnimatorState state, AnimationClip clip, AnimatorComponent animator) {
        if (!isLive(state, animator)) {
            return state.name.equals(previewStateName) ? Math.min(previewTime, clip.durationSeconds) : 0.0f;
        }
        float stateTime = animator.currentTime;
        if (state.blendTree == null) return state.getClipSampleTime(stateTime);
        return state.getNormalizedTime(stateTime, animator.getParameters()) * clip.durationSeconds;
    }

    private static float frameRate(AnimationClip clip) {
        float fps = clip.ticksPerSecond;
        return (fps > 0.0f && fps <= 240.0f) ? fps : 30.0f;
    }

    /** True when the dope sheet shows {@code clip} as {@code recorder}'s record timeline. */
    private static boolean isRecording(AnimationClip clip, AnimatorComponent recorder) {
        return recorder != null && clip != null && clip == recorder.recordedPose;
    }

    private static float recordTimelineLength(AnimationClip clip, AnimatorComponent recorder) {
        return Math.max(Math.max(RECORD_MIN_LENGTH, clip.durationSeconds + RECORD_TAIL), recorder.recordTime);
    }

    private static float timeToX(float t, float duration) {
        return TL_X0 + (t / duration) * (TL_X1 - TL_X0);
    }

    // --- Rendering ---

    public static void renderInspectorSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, String selectedStateName,
                                              AnimatorTransition transition, AnimatorComponent recorder) {
        UIGeometryBatch.addQuad(buffer, PANEL_X0, PANEL_Y0, PANEL_X1, PANEL_Y1, EditorTheme.SURFACE, 1.0f);

        AnimatorState state = resolveInspectedState(controller, animator, selectedStateName);
        if (state == null) {
            centeredTextIn(buffer, "NOTHING TO INSPECT", PANEL_X0, PANEL_X1, 0.30f, 1.0f, EditorTheme.TEXT_MUTED);
            centeredTextIn(buffer, "SELECT A STATE NODE IN THE GRAPH", PANEL_X0, PANEL_X1, 0.24f, 0.72f, EditorTheme.TEXT_DISABLED);
        } else {
            renderStateDetails(buffer, controller, animator, state);
            renderDopeSheet(buffer, controller, animator, state, recorder);
        }

        // Header
        UIGeometryBatch.addQuad(buffer, PANEL_X0, HEADER_Y0, PANEL_X1, PANEL_Y1, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, PANEL_X0, HEADER_Y0, PANEL_X1, HEADER_Y0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "INSPECTOR", CX0, textTop(0.835f, 0.85f), CX1, 0.85f, EditorTheme.TEXT, 1.0f);
    }

    private static void renderStateDetails(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, AnimatorState state) {
        boolean active = isLive(state, animator);
        boolean entry = state == controller.getEntryState();

        // Title: status dot, name, status pills
        float mid = 0.775f;
        float dotH = 0.014f, dotW = UIGeometryBatch.squareWidth(buffer, dotH);
        UIGeometryBatch.addPill(buffer, CX0 + 0.004f, mid - dotH * 0.5f, CX0 + 0.004f + dotW, mid + dotH * 0.5f, active ? EditorTheme.ACCENT : EditorTheme.GRAPHITE, 1.0f);

        float pillsX = CX1;
        if (active) pillsX = statusPill(buffer, "PLAYING", pillsX, mid, EditorTheme.NODE_ACTIVE);
        if (entry) pillsX = statusPill(buffer, "ENTRY", pillsX, mid, EditorTheme.NODE_ENTRY);
        UIGeometryBatch.addTruncatedText(buffer, state.name, CX0 + dotW + 0.016f, textTop(mid, 1.0f), pillsX - 0.01f, 1.0f, EditorTheme.TEXT, 1.0f);

        // Properties
        float rowMid = 0.725f;
        textScratch.setLength(0);
        if (state.blendTree != null) {
            textScratch.append(state.blendTree.type == BlendTree.BlendType.SIMPLE_1D ? "BLEND 1D ON " : "BLEND 2D ON ").append(state.blendTree.parameterX);
        } else {
            textScratch.append(state.clip != null && state.clip.name != null ? state.clip.name : "NONE");
        }
        propertyRow(buffer, "MOTION", textScratch, rowMid);
        rowMid -= PROP_ROW_H;

        textScratch.setLength(0);
        AnimatorParametersDelegate.appendFixed2(textScratch, state.speed);
        textScratch.append('x');
        propertyRow(buffer, "SPEED", textScratch, rowMid);
        rowMid -= PROP_ROW_H;

        propertyRow(buffer, "LOOP", state.isLooping ? "YES" : "NO", rowMid);
        rowMid -= PROP_ROW_H;

        textScratch.setLength(0);
        AnimatorParametersDelegate.appendFixed2(textScratch, state.getCycleDuration(parameters(controller, animator)));
        textScratch.append('s');
        propertyRow(buffer, "LENGTH", textScratch, rowMid);
        rowMid -= PROP_ROW_H + 0.012f;

        // Outgoing transitions
        textScratch.setLength(0);
        textScratch.append("TRANSITIONS  ").append(state.transitions.size());
        sectionHeader(buffer, textScratch, rowMid);
        rowMid -= PROP_ROW_H + 0.004f;

        if (state.transitions.isEmpty()) {
            UIGeometryBatch.addBoundedText(buffer, "NO OUTGOING TRANSITIONS", CX0 + 0.008f, textTop(rowMid, 0.72f), CX1, 0.72f, EditorTheme.TEXT_DISABLED, 1.0f);
            return;
        }
        for (int i = 0; i < state.transitions.size(); i++) {
            if (rowMid - PROP_ROW_H * 0.5f < DOPE_TOP + 0.005f) {
                textScratch.setLength(0);
                textScratch.append('+').append(state.transitions.size() - i).append(" MORE");
                UIGeometryBatch.addRightAlignedText(buffer, textScratch, CX1, textTop(rowMid + PROP_ROW_H, 0.62f), 0.62f, EditorTheme.TEXT_DISABLED, 1.0f);
                break;
            }
            AnimatorTransition t = state.transitions.get(i);
            float arrowW = UIGeometryBatch.textWidth(buffer, 2, 0.75f);
            UIGeometryBatch.addBoundedText(buffer, "->", CX0 + 0.008f, textTop(rowMid, 0.75f), CX1, 0.75f, EditorTheme.ACCENT, 1.0f);

            textScratch.setLength(0);
            int count = t.conditions.size();
            if (count > 0) {
                textScratch.append(t.conditions.get(0).getFormattedRule());
                if (count > 1) textScratch.append(" +").append(count - 1);
            } else if (t.hasExitTime) {
                textScratch.append("EXIT ").append(Math.round(t.exitTime * 100.0f)).append('%');
            } else {
                textScratch.append("AUTO");
            }
            float condW = Math.min(UIGeometryBatch.textWidth(buffer, textScratch, 0.62f), (CX1 - CX0) * 0.5f);
            float condX0 = CX1 - condW;
            UIGeometryBatch.addTruncatedText(buffer, textScratch, condX0, textTop(rowMid, 0.62f), CX1, 0.62f, EditorTheme.TEXT_MUTED, 1.0f);
            UIGeometryBatch.addTruncatedText(buffer, t.destinationStateName, CX0 + 0.016f + arrowW, textTop(rowMid, 0.75f), condX0 - 0.012f, 0.75f, EditorTheme.TEXT, 1.0f);
            rowMid -= PROP_ROW_H;
        }
    }

    /** Right-aligned status pill ending at {@code x1}; returns its left edge. */
    private static float statusPill(UIGeometryBatch.Buffer buffer, String text, float x1, float mid, int color) {
        float size = 0.6f;
        float w = UIGeometryBatch.textWidth(buffer, text, size) + 0.016f;
        float h = 0.026f;
        float x0 = x1 - w;
        UIGeometryBatch.addPill(buffer, x0, mid - h * 0.5f, x1, mid + h * 0.5f, color, 1.0f);
        centeredTextIn(buffer, text, x0, x1, mid, size, EditorTheme.TEXT);
        return x0 - 0.008f;
    }

    private static void propertyRow(UIGeometryBatch.Buffer buffer, String label, CharSequence value, float mid) {
        float valueX0 = CX0 + 0.12f;
        UIGeometryBatch.addBoundedText(buffer, label, CX0 + 0.008f, textTop(mid, 0.68f), valueX0 - 0.01f, 0.68f, EditorTheme.TEXT_MUTED, 1.0f);
        UIGeometryBatch.addTruncatedText(buffer, value, valueX0, textTop(mid, 0.78f), CX1, 0.78f, EditorTheme.TEXT, 1.0f);
    }

    private static void sectionHeader(UIGeometryBatch.Buffer buffer, CharSequence title, float mid) {
        float h = 0.040f;
        UIGeometryBatch.addRoundedQuad(buffer, CX0, mid - h * 0.5f, CX1, mid + h * 0.5f, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, title, CX0 + 0.008f, textTop(mid, 0.75f), CX1 - 0.008f, 0.75f, EditorTheme.TEXT, 1.0f);
    }

    private static void renderDopeSheet(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, AnimatorState state, AnimatorComponent recorder) {
        AnimationClip clip = displayClip(state, controller, animator);
        float headerMid = (DOPE_HEADER_Y0 + DOPE_TOP) * 0.5f;

        textScratch.setLength(0);
        if (recorder == null) textScratch.append("DOPE SHEET");
        sectionHeader(buffer, textScratch, headerMid);
        if (recorder != null) {
            headerButton(buffer, "+ NEW CLIP", NEW_CLIP_X0, NEW_CLIP_X1, headerMid, EditorTheme.ACCENT);
            headerButton(buffer, "RENAME", RENAME_X0, RENAME_X1, headerMid, EditorTheme.GRAPHITE);
        }

        if (clip == null || clip.durationSeconds <= 0.0f) {
            centeredTextIn(buffer, "NO CLIP ON THIS STATE", PANEL_X0, PANEL_X1, 0.0f, 0.8f, EditorTheme.TEXT_DISABLED);
            return;
        }

        boolean recording = isRecording(clip, recorder);
        float duration = recording ? recordTimelineLength(clip, recorder) : clip.durationSeconds;
        float fps = frameRate(clip);
        float playTime = Math.max(0.0f, Math.min(duration, recording ? recorder.recordTime : playheadTime(state, clip, animator)));
        boolean live = recording || isLive(state, animator);

        // Header readout: [PREVIEW | REC]  0.45 / 2.00s (record mode shows the recorded clip's length)
        textScratch.setLength(0);
        AnimatorParametersDelegate.appendFixed2(textScratch, playTime);
        textScratch.append(" / ");
        AnimatorParametersDelegate.appendFixed2(textScratch, clip.durationSeconds);
        textScratch.append('s');
        float readoutX0 = CX1 - 0.008f - UIGeometryBatch.textWidth(buffer, textScratch, 0.68f);
        UIGeometryBatch.addBoundedText(buffer, textScratch, readoutX0, textTop(headerMid, 0.68f), CX1, 0.68f, live ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, 1.0f);
        if (recording) statusPill(buffer, "REC", readoutX0 - 0.008f, headerMid, EditorTheme.PLAYHEAD);
        else if (!live) statusPill(buffer, "PREVIEW", readoutX0 - 0.008f, headerMid, EditorTheme.GRAPHITE);

        // Track area backgrounds
        UIGeometryBatch.addQuad(buffer, TL_X0 - 0.008f, TRACK_BOTTOM, CX1, TRACK_TOP, EditorTheme.SURFACE_SUNKEN, 1.0f);
        UIGeometryBatch.addQuad(buffer, CX0, RULER_Y0, CX1, RULER_Y1, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, CX0, RULER_Y0 - EditorTheme.HAIRLINE * 0.5f, CX1, RULER_Y0 + EditorTheme.HAIRLINE * 0.5f, EditorTheme.BORDER, 1.0f);

        textScratch.setLength(0);
        textScratch.append(Math.round(fps)).append(" FPS");
        UIGeometryBatch.addBoundedText(buffer, textScratch, CX0 + 0.008f, textTop((RULER_Y0 + RULER_Y1) * 0.5f, 0.6f), LABEL_X1, 0.6f, EditorTheme.TEXT_MUTED, 1.0f);

        renderRuler(buffer, duration, fps);
        renderTracks(buffer, clip, duration, playTime, fps);
        renderPlayhead(buffer, playTime, duration, fps);
    }

    private static void headerButton(UIGeometryBatch.Buffer buffer, String label, float x0, float x1, float mid, int color) {
        UIGeometryBatch.addRoundedQuad(buffer, x0, HEADER_BTN_Y0, x1, HEADER_BTN_Y1, EditorTheme.RADIUS_SMALL, color, 1.0f);
        centeredTextIn(buffer, label, x0, x1, mid, 0.6f, EditorTheme.TEXT);
    }

    private static void renderRuler(UIGeometryBatch.Buffer buffer, float duration, float fps) {
        float secToX = (TL_X1 - TL_X0) / duration;
        float frameDur = 1.0f / fps;
        float tickHalf = UIGeometryBatch.squareWidth(buffer, 0.0015f) * 0.5f;

        // Minor ticks on frame multiples
        int minorStep = MINOR_FRAME_STEPS[MINOR_FRAME_STEPS.length - 1];
        for (int s : MINOR_FRAME_STEPS) {
            if (s * frameDur * secToX >= MIN_TICK_GAP) { minorStep = s; break; }
        }
        int totalFrames = (int) Math.floor(duration * fps + 0.0001f);
        for (int f = 0; f <= totalFrames; f += minorStep) {
            float x = timeToX(f * frameDur, duration);
            UIGeometryBatch.addQuad(buffer, x - tickHalf, RULER_Y0, x + tickHalf, RULER_Y0 + 0.008f, EditorTheme.TEXT_DISABLED, 1.0f);
        }

        // Labeled major ticks on round seconds, with faint guides down through the tracks
        float major = MAJOR_SECOND_STEPS[MAJOR_SECOND_STEPS.length - 1];
        for (float s : MAJOR_SECOND_STEPS) {
            if (s * secToX >= MIN_LABEL_GAP) { major = s; break; }
        }
        int decimals = major >= 1.0f ? 0 : (Math.abs(major * 10.0f - Math.round(major * 10.0f)) < 0.001f ? 1 : 2);
        int majorCount = (int) Math.floor(duration / major + 0.0001f);
        for (int i = 0; i <= majorCount; i++) {
            float t = i * major;
            float x = timeToX(t, duration);
            UIGeometryBatch.addQuad(buffer, x - tickHalf, RULER_Y0, x + tickHalf, RULER_Y0 + 0.018f, EditorTheme.TEXT_MUTED, 1.0f);
            UIGeometryBatch.addQuad(buffer, x - tickHalf, TRACK_BOTTOM, x + tickHalf, TRACK_TOP, EditorTheme.BORDER, 0.6f);

            textScratch.setLength(0);
            appendDecimal(textScratch, t, decimals);
            UIGeometryBatch.addBoundedText(buffer, textScratch, x + 0.004f, textTop(RULER_Y1 - 0.011f, 0.55f), TL_X1 + 0.012f, 0.55f, EditorTheme.TEXT_MUTED, 1.0f);
        }
        // Clip end marker
        float endX = timeToX(duration, duration);
        UIGeometryBatch.addQuad(buffer, endX - tickHalf, TRACK_BOTTOM, endX + tickHalf, RULER_Y1, EditorTheme.BORDER_STRONG, 1.0f);
    }

    private static void appendDecimal(StringBuilder sb, float v, int decimals) {
        if (decimals == 0) {
            sb.append(Math.round(v));
        } else if (decimals == 1) {
            long tenths = Math.round(v * 10.0f);
            sb.append(tenths / 10).append('.').append(tenths % 10);
        } else {
            AnimatorParametersDelegate.appendFixed2(sb, v);
        }
    }

    private static void refreshTrackCache(AnimationClip clip) {
        int count = clip.getTracks().size();
        if (clip == cachedClip && count == cachedTrackCount) return;
        sortedTracks.clear();
        for (KeyframeTrack track : clip.getTracks()) sortedTracks.add(track);
        sortedTracks.sort(BY_BONE);
        cachedClip = clip;
        cachedTrackCount = count;
        scrollRows = 0.0f;
    }

    /** One bone row (summary of all channels) followed by Translation / Rotation / Scale channel rows. */
    private static void renderTracks(UIGeometryBatch.Buffer buffer, AnimationClip clip, float duration, float playTime, float fps) {
        refreshTrackCache(clip);
        if (sortedTracks.isEmpty()) {
            maxScrollRows = 0;
            centeredTextIn(buffer, "NO KEYFRAME TRACKS", PANEL_X0, PANEL_X1, (TRACK_TOP + TRACK_BOTTOM) * 0.5f, 0.75f, EditorTheme.TEXT_DISABLED);
            return;
        }

        int totalRows = sortedTracks.size() * 4;
        int visibleRows = (int) ((TRACK_TOP - TRACK_BOTTOM) / TRACK_ROW_H);
        maxScrollRows = Math.max(0, totalRows - visibleRows);
        scrollRows = Math.max(0.0f, Math.min(scrollRows, maxScrollRows));
        int firstRow = (int) scrollRows;
        float halfFrame = 0.5f / fps;

        for (int v = 0; v < visibleRows && firstRow + v < totalRows; v++) {
            int row = firstRow + v;
            KeyframeTrack track = sortedTracks.get(row / 4);
            int channel = row % 4; // 0 = bone summary, 1 = T, 2 = R, 3 = S
            float top = TRACK_TOP - v * TRACK_ROW_H;
            float bottom = top - TRACK_ROW_H;
            float mid = (top + bottom) * 0.5f;

            if (channel == 0) {
                UIGeometryBatch.addQuad(buffer, CX0, bottom, CX1, top, EditorTheme.SURFACE_RAISED, 0.55f);
                // Disclosure triangle (expanded) + bone name
                float tw = UIGeometryBatch.squareWidth(buffer, 0.010f);
                float tx = CX0 + 0.006f;
                UIGeometryBatch.addTriangle(buffer, tx, mid + 0.004f, tx + tw, mid + 0.004f, tx + tw * 0.5f, mid - 0.005f, EditorTheme.TEXT_MUTED, 1.0f);
                UIGeometryBatch.addTruncatedText(buffer, track.boneName, tx + tw + 0.008f, textTop(mid, 0.62f), LABEL_X1, 0.62f, EditorTheme.TEXT, 1.0f);
                renderKeys(buffer, track.positionTimes, mid, duration, playTime, halfFrame, EditorTheme.TEXT_MUTED, 0.8f);
                renderKeys(buffer, track.rotationTimes, mid, duration, playTime, halfFrame, EditorTheme.TEXT_MUTED, 0.8f);
                renderKeys(buffer, track.scaleTimes, mid, duration, playTime, halfFrame, EditorTheme.TEXT_MUTED, 0.8f);
            } else {
                int color = channel == 1 ? EditorTheme.CHANNEL_TRANSLATION : (channel == 2 ? EditorTheme.CHANNEL_ROTATION : EditorTheme.CHANNEL_SCALE);
                String label = channel == 1 ? "POSITION" : (channel == 2 ? "ROTATION" : "SCALE");
                float[] times = channel == 1 ? track.positionTimes : (channel == 2 ? track.rotationTimes : track.scaleTimes);

                float chipH = 0.014f, chipW = UIGeometryBatch.squareWidth(buffer, chipH * 0.5f);
                float chipX = CX0 + 0.022f;
                UIGeometryBatch.addPill(buffer, chipX, mid - chipH * 0.5f, chipX + chipW, mid + chipH * 0.5f, color, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, label, chipX + chipW + 0.008f, textTop(mid, 0.55f), LABEL_X1, 0.55f,
                        times.length > 0 ? EditorTheme.TEXT_MUTED : EditorTheme.TEXT_DISABLED, 1.0f);
                renderKeys(buffer, times, mid, duration, playTime, halfFrame, color, 1.0f);
            }

            UIGeometryBatch.addQuad(buffer, CX0, bottom - EditorTheme.HAIRLINE * 0.25f, CX1, bottom + EditorTheme.HAIRLINE * 0.25f, EditorTheme.BORDER, 0.5f);
        }

        // Scroll indicator
        if (maxScrollRows > 0) {
            float trackH = TRACK_TOP - TRACK_BOTTOM;
            float thumbH = Math.max(0.04f, trackH * visibleRows / totalRows);
            float thumbTop = TRACK_TOP - (trackH - thumbH) * (scrollRows / maxScrollRows);
            float tw = UIGeometryBatch.squareWidth(buffer, 0.008f);
            UIGeometryBatch.addPill(buffer, PANEL_X1 - 0.004f - tw, thumbTop - thumbH, PANEL_X1 - 0.004f, thumbTop, EditorTheme.GRAPHITE, 0.85f);
        }
    }

    /** Keyframe diamonds along one row; keys under the playhead are highlighted, near-coincident keys drawn once. */
    private static void renderKeys(UIGeometryBatch.Buffer buffer, float[] times, float mid, float duration,
                                   float playTime, float halfFrame, int color, float alpha) {
        float lastX = -10.0f;
        float h = DIAMOND_H;
        float w = UIGeometryBatch.squareWidth(buffer, h);
        float ow = UIGeometryBatch.squareWidth(buffer, h + 0.003f);
        for (float t : times) {
            if (t < -0.0001f || t > duration + 0.0001f) continue;
            float x = timeToX(t, duration);
            if (x - lastX < w * 0.5f) continue;
            lastX = x;
            boolean current = Math.abs(t - playTime) <= halfFrame;
            diamond(buffer, x, mid, ow, h + 0.003f, EditorTheme.BACKGROUND, 1.0f);
            diamond(buffer, x, mid, w, h, current ? EditorTheme.TEXT : color, current ? 1.0f : alpha);
        }
    }

    private static void diamond(UIGeometryBatch.Buffer buffer, float cx, float cy, float halfW, float halfH, int rgb, float a) {
        UIGeometryBatch.addTriangle(buffer, cx - halfW, cy, cx + halfW, cy, cx, cy + halfH, rgb, a);
        UIGeometryBatch.addTriangle(buffer, cx - halfW, cy, cx, cy - halfH, cx + halfW, cy, rgb, a);
    }

    private static void renderPlayhead(UIGeometryBatch.Buffer buffer, float playTime, float duration, float fps) {
        float x = timeToX(playTime, duration);
        float lineHalf = UIGeometryBatch.squareWidth(buffer, 0.003f) * 0.5f;
        UIGeometryBatch.addQuad(buffer, x - lineHalf, TRACK_BOTTOM, x + lineHalf, RULER_Y0, EditorTheme.PLAYHEAD, 1.0f);

        // Head: frame-number tag in the ruler with a pointer down onto the tracks
        textScratch.setLength(0);
        textScratch.append(Math.round(playTime * fps));
        float size = 0.55f;
        float halfW = (UIGeometryBatch.textWidth(buffer, textScratch, size) + 0.012f) * 0.5f;
        float y0 = RULER_Y0 + 0.006f, y1 = RULER_Y1 - 0.004f;
        UIGeometryBatch.addRoundedQuad(buffer, x - halfW, y0, x + halfW, y1, EditorTheme.RADIUS_SMALL, EditorTheme.PLAYHEAD, 1.0f);
        float pw = UIGeometryBatch.squareWidth(buffer, 0.008f);
        UIGeometryBatch.addTriangle(buffer, x - pw, y0, x + pw, y0, x, RULER_Y0 - 0.004f, EditorTheme.PLAYHEAD, 1.0f);
        centeredTextIn(buffer, textScratch, x - halfW, x + halfW, (y0 + y1) * 0.5f, size, EditorTheme.TEXT);
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, CharSequence text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = Math.max(x0 + 0.002f, (x0 + x1 - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, textTop(midY, size), x1 + 0.001f, size, rgb, 1.0f);
    }

    // --- Input ---

    /** Left press: a dope sheet header button, else starts a playhead scrub when it lands on the ruler or the track area. */
    public static boolean handleInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return false;
        if (handleHeaderButtons(ndcX, ndcY, manager)) return true;
        if (ndcX < TL_X0 - 0.012f || ndcX > TL_X1 + 0.012f || ndcY < TRACK_BOTTOM || ndcY > RULER_Y1) return false;
        if (!seek(ndcX, manager)) return false;
        scrubbing = true;
        return true;
    }

    /** [+ NEW CLIP] / [RENAME], hit only while they are drawn (a recording animator and an inspected state). */
    private static boolean handleHeaderButtons(float ndcX, float ndcY, EditorWorkspace manager) {
        if (ndcY < HEADER_BTN_Y0 || ndcY > HEADER_BTN_Y1 || ndcX < NEW_CLIP_X0 || ndcX > RENAME_X1) return false;
        AnimatorComponent recorder = manager.getRecordingAnimator();
        AnimatorController controller = manager.getActiveAnimatorController();
        if (recorder == null || resolveInspectedState(controller, manager.getActiveAnimator(), manager.getSelectedStateName()) == null) return false;

        if (ndcX <= NEW_CLIP_X1) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String suggested = (recorder.controller != null) ? recorder.controller.uniqueStateName("NewClip") : "NewClip";
            String name = NativeFileDialog.promptInput("New Animation Clip", "Clip name:", suggested);
            if (name != null && !name.isBlank() && manager.createNewRecordedClip(name)) {
                // Fresh timeline: the record playhead is back at 0.00s and the empty clip's track list starts at the top
                scrubbing = false;
                scrollRows = 0.0f;
                UISoundPlayer.play(UISound.SPAWN);
            }
            return true;
        }
        if (ndcX >= RENAME_X0) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String current = (recorder.recordedPose != null) ? recorder.recordedPose.name : "NewClip";
            String name = NativeFileDialog.promptInput("Rename Clip", "Clip title:", current);
            if (name != null && !name.isBlank() && manager.renameActiveRecordedClip(name)) UISoundPlayer.play(UISound.CLICK);
            return true;
        }
        return true; // gap between the buttons
    }

    public static boolean handleCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        if (!scrubbing) return false;
        if (!seek(ndcX, manager)) scrubbing = false;
        return true;
    }

    public static boolean handleRelease() {
        if (!scrubbing) return false;
        scrubbing = false;
        return true;
    }

    public static boolean handleScroll(float ndcX, float ndcY, double yOffset) {
        if (ndcX < PANEL_X0 || ndcX > PANEL_X1 || ndcY < TRACK_BOTTOM || ndcY > TRACK_TOP) return false;
        scrollRows = Math.max(0.0f, Math.min(maxScrollRows, scrollRows - (float) yOffset * 1.5f));
        return true;
    }

    /** Moves the playhead to the time under {@code ndcX}; returns false when there is nothing to scrub. */
    private static boolean seek(float ndcX, EditorWorkspace manager) {
        AnimatorController controller = manager.getActiveAnimatorController();
        AnimatorComponent animator = manager.getActiveAnimator();
        AnimatorState state = resolveInspectedState(controller, animator, manager.getSelectedStateName());
        AnimationClip clip = displayClip(state, controller, animator);
        if (clip == null || clip.durationSeconds <= 0.0f) return false;

        AnimatorComponent recorder = manager.getRecordingAnimator();
        if (isRecording(clip, recorder)) {
            // Record playhead: frame-snapped time where the next keys land
            float length = recordTimelineLength(clip, recorder);
            float t = Math.max(0.0f, Math.min(length, (ndcX - TL_X0) / (TL_X1 - TL_X0) * length));
            float fps = frameRate(clip);
            manager.setRecordTime(Math.round(t * fps) / fps);
            return true;
        }

        float duration = clip.durationSeconds;
        float t = (ndcX - TL_X0) / (TL_X1 - TL_X0) * duration;
        // Looping states wrap at exactly the clip length, so stop just short of it
        t = Math.max(0.0f, Math.min(state.isLooping ? duration * 0.9999f : duration, t));

        if (isLive(state, animator)) {
            float stateTime = state.blendTree == null ? t : (t / duration) * state.getCycleDuration(animator.getParameters());
            animator.setCurrentTime(stateTime);
        } else {
            previewTime = t;
            previewStateName = state.name;
        }
        return true;
    }
}
