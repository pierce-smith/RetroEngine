package com.retro.engine.editor.animator;

import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorParameter.ParameterType;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.MenuItem;
import com.retro.engine.editor.panels.PanelRect;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SRP Delegate: Manages the Animator Parameters list: typed rows with inline value editors
 * (switches for Bools, numeric fields + steppers for Floats/Ints, fire buttons for Triggers)
 * and the [+ ADD] typed-parameter creation menu.
 */
public class AnimatorParametersDelegate {

    // --- Responsive layout against DockPanel.ANIMATOR_PARAMETERS.rect (screen NDC). layout() recomputes every edge;
    // render and handleParametersClick both call it first, so drawing and hit-testing share the same values.
    private static final float ROW_H = 0.065f;
    private static final float CTRL_HALF_H = 0.018f;
    /** Rows never grow wider than this, so the editor column stays next to the parameter name in wide tiles. */
    private static final float MAX_CONTENT_W = 0.8f;
    /** Editor column share of the row, clamped; the stepper keeps its nominal size unless the column is narrower. */
    private static final float EDITOR_SHARE = 0.47f, EDITOR_MIN_W = 0.12f, EDITOR_MAX_W = 0.30f, NOMINAL_EDITOR_W = 0.21f;

    private static float panelX0, panelY0, panelX1, panelY1;
    private static float headerY0, btnY0, btnY1, addX0, addX1;
    private static float listTop, rowX0, rowX1;
    private static float editorX0, editorX1, fieldX0, fieldX1, decX0, decX1, incX0, incX1, triggerX0;

    private static void layout() {
        PanelRect r = DockPanel.ANIMATOR_PARAMETERS.rect;
        panelX0 = r.x0; panelY0 = r.y0; panelX1 = r.x1; panelY1 = r.y1;
        rowX0 = r.x0 + 0.01f;
        rowX1 = Math.max(rowX0 + 0.05f, Math.min(r.x1 - 0.01f, rowX0 + MAX_CONTENT_W));
        headerY0 = r.y1 - 0.05f;
        btnY0 = r.y1 - 0.042f; btnY1 = r.y1 - 0.008f;
        addX1 = rowX1 - 0.005f; addX0 = addX1 - Math.min(0.08f, (rowX1 - rowX0) * 0.35f);
        listTop = r.y1 - 0.06f;

        float rowW = rowX1 - rowX0;
        float editorW = Math.max(EDITOR_MIN_W, Math.min(EDITOR_MAX_W, rowW * EDITOR_SHARE));
        editorW = Math.min(editorW, rowW * 0.6f);
        float k = Math.min(1.0f, editorW / NOMINAL_EDITOR_W);
        editorX1 = rowX1 - 0.005f; editorX0 = editorX1 - editorW;
        incX1 = editorX1; incX0 = incX1 - 0.0425f * k;
        decX1 = incX0; decX0 = decX1 - 0.0425f * k;
        fieldX0 = editorX0; fieldX1 = decX0 - 0.005f * k;
        triggerX0 = editorX1 - Math.min(editorW, 0.125f);
    }

    private static final float FLOAT_STEP = 0.1f;

    private static final StringBuilder valueScratch = new StringBuilder(32);

    private static float textTop(float midY, float size) { return midY + size * 0.009f; }

    private static float rowTop(int index) { return listTop - index * ROW_H; }

    private static int typeColor(ParameterType type) {
        return switch (type) {
            case FLOAT -> EditorTheme.TEAL;
            case INT -> EditorTheme.PURPLE;
            case BOOL -> EditorTheme.GREEN;
            case TRIGGER -> EditorTheme.ORANGE;
        };
    }

    private static String typeGlyph(ParameterType type) {
        return switch (type) {
            case FLOAT -> "F";
            case INT -> "I";
            case BOOL -> "B";
            case TRIGGER -> "T";
        };
    }

    /**
     * Values shown and edited: {@code animator}'s own live values,
     * else the controller's defaults.
     */
    private static Map<String, AnimatorParameter> parameters(AnimatorController controller, AnimatorComponent animator) {
        if (controller == null) return null;
        return (animator != null && animator.controller == controller) ? animator.getParameters() : controller.getParameters();
    }

    public static void renderParametersSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator) {
        layout();
        UIGeometryBatch.addQuad(buffer, panelX0, panelY0, panelX1, panelY1, EditorTheme.SURFACE, 1.0f);

        Map<String, AnimatorParameter> parameters = parameters(controller, animator);
        int count = parameters != null ? parameters.size() : 0;
        int index = 0;
        if (count > 0) {
            for (AnimatorParameter param : parameters.values()) {
                float top = rowTop(index);
                if (top - ROW_H < panelY0 + 0.04f) {
                    valueScratch.setLength(0);
                    valueScratch.append('+').append(count - index).append(" MORE");
                    UIGeometryBatch.addBoundedText(buffer, valueScratch, rowX0 + 0.01f, textTop(panelY0 + 0.02f, 0.7f), rowX1, 0.7f, EditorTheme.TEXT_DISABLED, 1.0f);
                    break;
                }
                renderParameterRow(buffer, param, top, index > 0);
                index++;
            }
        } else if (controller != null) {
            float y = panelY1 - Math.min(0.24f, (panelY1 - panelY0) * 0.35f);
            centeredText(buffer, "NO PARAMETERS", y, 1.0f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "USE + ADD TO CREATE ONE", y - 0.06f, 0.72f, EditorTheme.TEXT_DISABLED);
        }

        // Header
        UIGeometryBatch.addQuad(buffer, panelX0, headerY0, panelX1, panelY1, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, panelX0, headerY0, panelX1, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        float mid = (btnY0 + btnY1) * 0.5f;
        UIGeometryBatch.addTruncatedText(buffer, "PARAMETERS", panelX0 + 0.015f, textTop(mid, 0.85f), addX0 - 0.06f, 0.85f, EditorTheme.TEXT, 1.0f);
        if (count > 0) {
            valueScratch.setLength(0);
            valueScratch.append(count);
            float cx = panelX0 + 0.025f + UIGeometryBatch.textWidth(buffer, "PARAMETERS", 0.85f);
            UIGeometryBatch.addBoundedText(buffer, valueScratch, cx, textTop(mid, 0.75f), addX0 - 0.01f, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
        }
        if (controller != null) {
            UIGeometryBatch.addRoundedQuad(buffer, addX0, btnY0, addX1, btnY1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
            centeredTextIn(buffer, "+ ADD", addX0, addX1, mid, 0.72f, EditorTheme.TEXT);
        }
    }

    private static void renderParameterRow(UIGeometryBatch.Buffer buffer, AnimatorParameter param, float top, boolean separator) {
        float bottom = top - ROW_H;
        float mid = (top + bottom) * 0.5f;

        if (separator) {
            UIGeometryBatch.addQuad(buffer, rowX0 + 0.01f, top - EditorTheme.HAIRLINE * 0.5f, rowX1 - 0.01f, top + EditorTheme.HAIRLINE * 0.5f, EditorTheme.BORDER, 0.7f);
        }

        // Type badge
        float badgeH = 0.032f;
        float badgeX0 = rowX0 + 0.008f;
        float badgeX1 = badgeX0 + Math.max(UIGeometryBatch.squareWidth(buffer, badgeH), UIGeometryBatch.textWidth(buffer, 1, 0.75f) + 0.008f);
        UIGeometryBatch.addRoundedQuad(buffer, badgeX0, mid - badgeH * 0.5f, badgeX1, mid + badgeH * 0.5f, 0.007f, typeColor(param.type), 0.95f);
        centeredTextIn(buffer, typeGlyph(param.type), badgeX0, badgeX1, mid, 0.75f, EditorTheme.BACKGROUND);

        // Name
        float nameX1 = (param.type == ParameterType.TRIGGER ? triggerX0 : editorX0) - 0.01f;
        UIGeometryBatch.addTruncatedText(buffer, param.name, badgeX1 + 0.012f, textTop(mid, 0.85f), nameX1, 0.85f, EditorTheme.TEXT, 1.0f);

        // Inline editor
        switch (param.type) {
            case BOOL -> renderSwitch(buffer, param.boolValue, mid);
            case TRIGGER -> renderTriggerButton(buffer, param.boolValue, mid);
            case FLOAT -> {
                valueScratch.setLength(0);
                appendFixed2(valueScratch, param.floatValue);
                renderNumericField(buffer, valueScratch, mid);
            }
            case INT -> {
                valueScratch.setLength(0);
                valueScratch.append(param.intValue);
                renderNumericField(buffer, valueScratch, mid);
            }
        }
    }

    /** Appends {@code v} with two decimals without String.format allocation. */
    static void appendFixed2(StringBuilder sb, float v) {
        long scaled = Math.round(Math.abs(v) * 100.0);
        if (v < 0.0f && scaled != 0) sb.append('-');
        sb.append(scaled / 100).append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    /** Sunken value well plus a segmented [- | +] stepper. */
    private static void renderNumericField(UIGeometryBatch.Buffer buffer, CharSequence value, float mid) {
        float y0 = mid - CTRL_HALF_H, y1 = mid + CTRL_HALF_H;
        UIGeometryBatch.addRoundedCard(buffer, fieldX0, y0, fieldX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_SUNKEN, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addRightAlignedText(buffer, value, fieldX1 - 0.008f, textTop(mid, 0.78f), 0.78f, EditorTheme.TEXT, 1.0f);

        UIGeometryBatch.addRoundedCard(buffer, decX0, y0, incX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        UIGeometryBatch.addQuad(buffer, incX0 - 0.001f, y0 + 0.006f, incX0 + 0.001f, y1 - 0.006f, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "-", decX0, decX1, mid, 0.85f, EditorTheme.TEXT);
        centeredTextIn(buffer, "+", incX0, incX1, mid, 0.85f, EditorTheme.TEXT);
    }

    /** macOS-style switch, right-aligned in the editor column: accent track when on, graphite when off. */
    private static void renderSwitch(UIGeometryBatch.Buffer buffer, boolean on, float mid) {
        float h = 0.026f;
        float knobW = UIGeometryBatch.squareWidth(buffer, h - 0.006f);
        float trackW = Math.max(UIGeometryBatch.squareWidth(buffer, h) * 1.8f, knobW * 2.0f + 0.004f);
        float x1 = editorX1, x0 = x1 - trackW;
        UIGeometryBatch.addPill(buffer, x0, mid - h * 0.5f, x1, mid + h * 0.5f, on ? EditorTheme.ACCENT : EditorTheme.GRAPHITE, 1.0f);
        float pad = (trackW - knobW * 2.0f) * 0.25f + UIGeometryBatch.squareWidth(buffer, 0.003f);
        float kx0 = on ? x1 - pad - knobW : x0 + pad;
        UIGeometryBatch.addPill(buffer, kx0, mid - h * 0.5f + 0.003f, kx0 + knobW, mid + h * 0.5f - 0.003f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addRightAlignedText(buffer, on ? "TRUE" : "FALSE", x0 - 0.01f, textTop(mid, 0.68f), 0.68f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    /** Fire button; filled accent while the trigger is armed and waiting to be consumed by a transition. */
    private static void renderTriggerButton(UIGeometryBatch.Buffer buffer, boolean armed, float mid) {
        float y0 = mid - CTRL_HALF_H, y1 = mid + CTRL_HALF_H;
        if (armed) {
            UIGeometryBatch.addRoundedQuad(buffer, triggerX0, y0, editorX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        } else {
            UIGeometryBatch.addRoundedCard(buffer, triggerX0, y0, editorX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        }
        // Radio-style indicator dot
        float dotH = 0.012f, dotW = UIGeometryBatch.squareWidth(buffer, dotH);
        float dx0 = triggerX0 + 0.010f;
        UIGeometryBatch.addPill(buffer, dx0, mid - dotH * 0.5f, dx0 + dotW, mid + dotH * 0.5f, armed ? EditorTheme.TEXT : EditorTheme.GRAPHITE, 1.0f);
        centeredTextIn(buffer, armed ? "ARMED" : "FIRE", dx0 + dotW, editorX1, mid, 0.68f, EditorTheme.TEXT);
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, CharSequence text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = Math.max(x0 + 0.002f, (x0 + x1 - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, textTop(midY, size), x1, size, rgb, 1.0f);
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        centeredTextIn(buffer, text, panelX0, panelX1, midY, size, rgb);
    }

    // --- Input ---

    public static boolean handleParametersClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null || !DockPanel.ANIMATOR_PARAMETERS.rect.contains(ndcX, ndcY)) return false;
        AnimatorController controller = manager.getActiveAnimatorController();
        if (controller == null) return false;
        layout();

        // 1. [+ ADD] button
        if (ndcX >= addX0 && ndcX <= addX1 && ndcY >= btnY0 && ndcY <= btnY1) {
            openAddParameterMenu(ndcX, btnY0, controller, manager);
            return true;
        }

        // 2. Parameter rows
        if (ndcX >= rowX0 && ndcX <= rowX1 && ndcY >= panelY0 && ndcY < headerY0) {
            return processParameterClick(ndcX, ndcY, parameters(controller, manager.getActiveAnimator()));
        }
        return false;
    }

    private static boolean processParameterClick(float ndcX, float ndcY, Map<String, AnimatorParameter> parameters) {
        int index = 0;
        for (AnimatorParameter param : parameters.values()) {
            float top = rowTop(index);
            float bottom = top - ROW_H;
            if (bottom < panelY0 + 0.04f) break;
            if (ndcY >= bottom && ndcY <= top) {
                switch (param.type) {
                    case BOOL -> {
                        param.boolValue = !param.boolValue;
                        UISoundPlayer.play(UISound.TOGGLE);
                    }
                    case TRIGGER -> {
                        if (param.boolValue) param.resetTrigger();
                        else param.setTrigger();
                        UISoundPlayer.play(UISound.CLICK);
                    }
                    case FLOAT, INT -> {
                        boolean isFloat = param.type == ParameterType.FLOAT;
                        if (ndcX >= decX0 && ndcX < decX1) {
                            step(param, isFloat, -1);
                        } else if (ndcX >= incX0 && ndcX <= incX1) {
                            step(param, isFloat, 1);
                        } else {
                            promptNumericValue(param, isFloat);
                        }
                        UISoundPlayer.play(UISound.CLICK);
                    }
                }
                return true;
            }
            index++;
        }
        return false;
    }

    private static void step(AnimatorParameter param, boolean isFloat, int direction) {
        if (isFloat) {
            // Snap to the step grid so repeated clicks don't accumulate float drift
            param.floatValue = Math.round((param.floatValue + direction * FLOAT_STEP) / FLOAT_STEP) * FLOAT_STEP;
        } else {
            param.intValue += direction;
        }
    }

    private static void promptNumericValue(AnimatorParameter param, boolean isFloat) {
        String current = isFloat ? String.format("%.2f", param.floatValue) : String.valueOf(param.intValue);
        String input = NativeFileDialog.promptInput("Edit Parameter",
                "Enter " + (isFloat ? "float" : "int") + " value for '" + param.name + "':", current);
        if (input == null || input.trim().isEmpty()) return;
        try {
            if (isFloat) param.floatValue = Float.parseFloat(input.trim());
            else param.intValue = Integer.parseInt(input.trim());
        } catch (NumberFormatException ignored) {}
    }

    private static void openAddParameterMenu(float ndcX, float ndcY, AnimatorController controller, EditorWorkspace manager) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("Float", () -> promptAddParameter(controller, ParameterType.FLOAT)));
        items.add(new MenuItem("Int", () -> promptAddParameter(controller, ParameterType.INT)));
        items.add(new MenuItem("Bool", () -> promptAddParameter(controller, ParameterType.BOOL)));
        items.add(new MenuItem("Trigger", () -> promptAddParameter(controller, ParameterType.TRIGGER)));
        UISoundPlayer.play(UISound.CLICK);
        manager.getOverlayManager().openContextMenu(ndcX, ndcY, items);
    }

    private static void promptAddParameter(AnimatorController controller, ParameterType type) {
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String defaultName = switch (type) {
            case FLOAT -> "NewFloat";
            case INT -> "NewInt";
            case BOOL -> "NewBool";
            case TRIGGER -> "NewTrigger";
        };
        String name = NativeFileDialog.promptInput("Add Parameter", "Enter " + type.name().toLowerCase() + " parameter name:", defaultName);
        if (name == null || name.trim().isEmpty()) return;
        String trimmed = name.trim();
        if (controller.getParameters().containsKey(trimmed)) {
            // The typed setters would silently retarget the existing parameter
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            return;
        }
        switch (type) {
            case FLOAT -> controller.setFloat(trimmed, 0.0f);
            case INT -> controller.setInt(trimmed, 0);
            case BOOL -> controller.setBool(trimmed, false);
            case TRIGGER -> controller.getParameters().put(trimmed, new AnimatorParameter(trimmed, ParameterType.TRIGGER));
        }
        UISoundPlayer.play(UISound.SPAWN);
    }
}
