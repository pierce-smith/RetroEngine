package com.retro.engine.editor.animator;

import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorTransition;
import com.retro.engine.animation.BlendTree;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
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
import java.util.Collection;
import java.util.List;

/**
 * SRP Delegate: Manages 2D Animator Graph Canvas rendering, the pannable/zoomable grid,
 * node card rendering, state transitions, and node drag interactions.
 *
 * State positions ({@link AnimatorState#graphX}/{@link AnimatorState#graphY}, top-left corner) live in
 * graph space; the view maps graph space onto the canvas through a pan offset and a zoom about the
 * canvas center, so hit-testing always goes through {@link #toViewX}/{@link #toGraphX}.
 */
public class AnimatorCanvasDelegate {

    // --- Layout against DockPanel.ANIMATOR.rect (screen NDC). layout() recomputes the canvas, header and button
    // edges; render and every input entry point call it first, so drawing and hit-testing share the same values.
    private static float canvasX0, canvasY0, canvasX1, canvasY1;
    private static float headerY0, btnY0, btnY1, addX0, addX1, resetX0, resetX1;
    /** On-screen center of the graph view (middle of the canvas area below the header). */
    private static float viewCx, viewCy;

    /** Graph-space point shown at the view center at zero pan; keeps existing graphs where they were authored. */
    private static final float GRAPH_CX = 0.0f, GRAPH_CY = 0.155f;
    private static final float MIN_ZOOM = 0.4f, MAX_ZOOM = 2.0f;

    private static void layout() {
        PanelRect r = DockPanel.ANIMATOR.rect;
        canvasX0 = r.x0; canvasY0 = r.y0; canvasX1 = r.x1; canvasY1 = r.y1;
        headerY0 = r.y1 - 0.05f;
        btnY0 = r.y1 - 0.042f; btnY1 = r.y1 - 0.008f;
        // Buttons hug the right edge and narrow with the tile so the layer breadcrumb keeps room
        float k = Math.max(0.6f, Math.min(1.0f, r.width() / 1.1f));
        addX1 = r.x1 - 0.01f; addX0 = addX1 - 0.125f * k;
        resetX1 = addX0 - 0.01f; resetX0 = resetX1 - 0.105f * k;
        viewCx = r.centerX();
        viewCy = (r.y0 + headerY0) * 0.5f;
    }

    private static final float NODE_W = 0.22f;
    private static final float NODE_H = 0.12f;
    private static final float HEADER_H = 0.042f;
    private static final float GRID_STEP = 0.05f;
    private static final int GRID_MAJOR_EVERY = 5;

    // Entry / Any State are not controller states; they sit at fixed graph positions
    private static final float ENTRY_X = -0.48f, ENTRY_Y = 0.65f;
    private static final float ANY_X = -0.48f, ANY_Y = 0.35f;
    private static final float SPECIAL_W = 0.18f, SPECIAL_H = 0.09f;

    // View
    private static float panX = 0.0f, panY = 0.0f, zoom = 1.0f;

    private static boolean isDraggingNode = false;
    private static String draggedStateName = null;
    private static float dragStartNdcX = 0.0f;
    private static float dragStartNdcY = 0.0f;
    private static float nodeStartGraphX = 0.0f;
    private static float nodeStartGraphY = 0.0f;

    private static boolean isPanning = false;
    private static float panStartPanX = 0.0f, panStartPanY = 0.0f;

    // Transition link mode: set by the "Create Transition" menu item, resolved by the next left press
    private static String pendingTransitionSource = null;
    private static float cursorNdcX = 0.0f;
    private static float cursorNdcY = 0.0f;

    private static final StringBuilder labelScratch = new StringBuilder(64);

    // --- View transform ---
    private static float toViewX(float graphX) { return (graphX + panX - GRAPH_CX) * zoom + viewCx; }
    private static float toViewY(float graphY) { return (graphY + panY - GRAPH_CY) * zoom + viewCy; }
    private static float toGraphX(float viewX) { return (viewX - viewCx) / zoom + GRAPH_CX - panX; }
    private static float toGraphY(float viewY) { return (viewY - viewCy) / zoom + GRAPH_CY - panY; }

    /** Text scale that tracks zoom without becoming unreadable or oversized. */
    private static float textZoom() { return Math.max(0.6f, Math.min(1.3f, zoom)); }

    private static float textTop(float midY, float size) { return midY + size * 0.009f; }

    /** {@code animator}: the instance whose playing / crossfading states are highlighted (null shows none live). */
    public static void renderCanvas(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, String selectedStateName) {
        layout();
        UIGeometryBatch.addQuad(buffer, canvasX0, canvasY0, canvasX1, canvasY1, EditorTheme.BACKGROUND, 1.0f);
        renderCanvasGrid(buffer);

        if (controller == null) {
            centeredText(buffer, "NO ANIMATOR CONTROLLER", viewCy + 0.03f, 1.1f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "SELECT A RIGGED ENTITY TO EDIT ITS STATE MACHINE", viewCy - 0.03f, 0.75f, EditorTheme.TEXT_DISABLED);
            renderHeader(buffer, null);
            return;
        }

        AnimatorState activeState = (animator != null) ? animator.getCurrentState() : null;
        AnimatorState targetState = (animator != null) ? animator.getTargetState() : null;
        AnimatorState entryState = controller.getEntryState();
        AnimatorState selectedState = selectedStateName != null ? controller.getState(selectedStateName) : null;
        Collection<AnimatorState> allStates = controller.getStates().values();

        // 1. Transition arrows (under the nodes)
        for (AnimatorState srcState : allStates) {
            for (AnimatorTransition transition : srcState.transitions) {
                AnimatorState dstState = controller.getState(transition.destinationStateName);
                if (dstState == null) continue;

                boolean isBlending = targetState != null && activeState == srcState && targetState == dstState;
                boolean touchesSelection = selectedState != null && (srcState == selectedState || dstState == selectedState);
                int color = isBlending ? EditorTheme.ORANGE : (touchesSelection ? EditorTheme.ACCENT : EditorTheme.TEXT_MUTED);
                renderTransition(buffer,
                        srcState.graphX, srcState.graphY, NODE_W, NODE_H,
                        dstState.graphX, dstState.graphY, NODE_W, NODE_H,
                        conditionLabel(transition), color, isBlending ? 1.0f : 0.85f, isBlending);
            }
        }

        if (entryState != null) {
            renderTransition(buffer, ENTRY_X, ENTRY_Y, SPECIAL_W, SPECIAL_H,
                    entryState.graphX, entryState.graphY, NODE_W, NODE_H, null, EditorTheme.NODE_ENTRY, 1.0f, false);
        }

        // Rubber-band preview while choosing a transition destination
        AnimatorState linkSource = (pendingTransitionSource != null) ? controller.getState(pendingTransitionSource) : null;
        if (linkSource != null) {
            float sx = toViewX(linkSource.graphX + NODE_W * 0.5f), sy = toViewY(linkSource.graphY - NODE_H * 0.5f);
            float t = Math.max(0.002f, 0.004f * zoom);
            UIGeometryBatch.addLineSegment(buffer, sx, sy, cursorNdcX, cursorNdcY, t, EditorTheme.YELLOW, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, cursorNdcX, cursorNdcY, cursorNdcX - sx, cursorNdcY - sy, 0.026f * zoom, 0.014f * zoom, EditorTheme.YELLOW, 1.0f);
        }

        // 2. Entry & Any State nodes
        labelScratch.setLength(0);
        labelScratch.append("-> ").append(entryState != null ? entryState.name : "NONE");
        renderNode(buffer, ENTRY_X, ENTRY_Y, SPECIAL_W, SPECIAL_H, "ENTRY", labelScratch, EditorTheme.NODE_ENTRY, EditorTheme.TEXT_MUTED, false, -1, -1.0f);
        renderNode(buffer, ANY_X, ANY_Y, SPECIAL_W, SPECIAL_H, "ANY STATE", "ALL STATES", EditorTheme.NODE_ANY_STATE, EditorTheme.TEXT_MUTED, false, -1, -1.0f);

        // 3. State nodes (later = on top, matching findStateAt)
        for (AnimatorState state : allStates) {
            boolean isActive = state == activeState;
            boolean isTarget = state == targetState;
            boolean isSelected = state == selectedState;

            CharSequence subtitle;
            int subtitleColor = EditorTheme.TEXT_MUTED;
            if (state.blendTree != null) {
                labelScratch.setLength(0);
                labelScratch.append(state.blendTree.type == BlendTree.BlendType.SIMPLE_1D ? "BLEND 1D • " : "BLEND 2D • ").append(state.blendTree.parameterX);
                subtitle = labelScratch;
            } else if (state.clip != null && state.clip.name != null) {
                subtitle = state.clip.name;
            } else {
                subtitle = "NO MOTION";
                subtitleColor = EditorTheme.TEXT_DISABLED;
            }

            float progress = -1.0f;
            if (isActive) progress = state.getNormalizedTime(animator.currentTime, animator.getParameters());
            else if (isTarget) progress = state.getNormalizedTime(animator.targetTime, animator.getParameters());

            int ring = -1;
            if (isSelected) ring = isActive ? EditorTheme.TEXT : EditorTheme.ACCENT;
            else if (isTarget) ring = EditorTheme.ORANGE;

            int header = isActive ? EditorTheme.NODE_ACTIVE : EditorTheme.SURFACE_RAISED;
            renderNode(buffer, state.graphX, state.graphY, NODE_W, NODE_H, state.name, subtitle, header, subtitleColor, isActive, ring, progress);
        }

        if (linkSource != null) {
            renderHintPill(buffer, "CLICK A STATE TO CONNECT  •  RIGHT-CLICK TO CANCEL");
        }

        // 4. Header last, so nodes panned beneath it are covered
        renderHeader(buffer, controller);
    }

    private static CharSequence conditionLabel(AnimatorTransition transition) {
        labelScratch.setLength(0);
        int count = transition.conditions.size();
        if (count > 0) {
            labelScratch.append(transition.conditions.get(0).getFormattedRule());
            if (count > 1) labelScratch.append(" +").append(count - 1);
        } else if (transition.hasExitTime) {
            labelScratch.append("EXIT ").append(Math.round(transition.exitTime * 100.0f)).append('%');
        } else {
            labelScratch.append("AUTO");
        }
        return labelScratch;
    }

    // --- Rendering ---

    private static void renderHeader(UIGeometryBatch.Buffer buffer, AnimatorController controller) {
        UIGeometryBatch.addQuad(buffer, canvasX0, headerY0, canvasX1, canvasY1, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, canvasX0, headerY0, canvasX1, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        float mid = (btnY0 + btnY1) * 0.5f;
        float x = canvasX0 + 0.015f;
        // Zoom readout sits left of RESET VIEW when there is room; the breadcrumb takes what remains
        labelScratch.setLength(0);
        labelScratch.append(Math.round(zoom * 100.0f)).append('%');
        float zoomW = UIGeometryBatch.textWidth(buffer, labelScratch, 0.72f);
        boolean showZoom = resetX0 - 0.012f - zoomW - 0.03f > x + 0.08f;
        float crumbX1 = showZoom ? resetX0 - 0.012f - zoomW - 0.03f : resetX0 - 0.012f;
        UIGeometryBatch.addTruncatedText(buffer, "BASE LAYER", x, textTop(mid, 0.8f), crumbX1, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
        if (controller != null) {
            x += UIGeometryBatch.textWidth(buffer, "BASE LAYER", 0.8f) + 0.012f;
            float nameX = x + UIGeometryBatch.textWidth(buffer, 1, 0.8f) + 0.012f;
            if (nameX + 0.04f < crumbX1) {
                UIGeometryBatch.addBoundedText(buffer, ">", x, textTop(mid, 0.8f), crumbX1, 0.8f, EditorTheme.TEXT_DISABLED, 1.0f);
                String name = controller.name != null ? controller.name : "CONTROLLER";
                UIGeometryBatch.addTruncatedText(buffer, name, nameX, textTop(mid, 0.8f), crumbX1, 0.8f, EditorTheme.TEXT, 1.0f);
            }
        }
        if (showZoom) {
            UIGeometryBatch.addRightAlignedText(buffer, labelScratch, resetX0 - 0.012f, textTop(mid, 0.72f), 0.72f, EditorTheme.TEXT_MUTED, 1.0f);
        }

        UIGeometryBatch.addRoundedCard(buffer, resetX0, btnY0, resetX1, btnY1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "RESET VIEW", resetX0, resetX1, mid, 0.62f, EditorTheme.TEXT);

        if (controller != null) {
            UIGeometryBatch.addRoundedQuad(buffer, addX0, btnY0, addX1, btnY1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
            centeredTextIn(buffer, "+ STATE", addX0, addX1, mid, 0.72f, EditorTheme.TEXT);
        }
    }

    /** Dot-grid style line grid: hairline minor lines every GRID_STEP, stronger major lines; both follow pan & zoom. */
    private static void renderCanvasGrid(UIGeometryBatch.Buffer buffer) {
        boolean showMinor = GRID_STEP * zoom >= 0.025f;
        float halfX = UIGeometryBatch.squareWidth(buffer, 0.0015f) * 0.5f;
        float halfY = 0.00075f;

        int k0 = (int) Math.ceil(toGraphX(canvasX0) / GRID_STEP);
        int k1 = (int) Math.floor(toGraphX(canvasX1) / GRID_STEP);
        for (int k = k0; k <= k1; k++) {
            boolean major = Math.floorMod(k, GRID_MAJOR_EVERY) == 0;
            if (!major && !showMinor) continue;
            float vx = toViewX(k * GRID_STEP);
            UIGeometryBatch.addQuad(buffer, vx - halfX, canvasY0, vx + halfX, headerY0, EditorTheme.BORDER, major ? 0.9f : 0.35f);
        }

        k0 = (int) Math.ceil(toGraphY(canvasY0) / GRID_STEP);
        k1 = (int) Math.floor(toGraphY(headerY0) / GRID_STEP);
        for (int k = k0; k <= k1; k++) {
            boolean major = Math.floorMod(k, GRID_MAJOR_EVERY) == 0;
            if (!major && !showMinor) continue;
            float vy = toViewY(k * GRID_STEP);
            UIGeometryBatch.addQuad(buffer, canvasX0, vy - halfY, canvasX1, vy + halfY, EditorTheme.BORDER, major ? 0.9f : 0.35f);
        }
    }

    /**
     * Node card: soft drop shadow, optional selection ring, charcoal body with a hairline border,
     * colored rounded header bar, a subtitle line, and a playback progress bar when progress >= 0.
     */
    private static void renderNode(UIGeometryBatch.Buffer buffer, float graphX, float graphY, float graphW, float graphH,
                                   CharSequence title, CharSequence subtitle, int headerColor, int subtitleColor,
                                   boolean glow, int ringColor, float progress) {
        float x1 = toViewX(graphX), y2 = toViewY(graphY);
        float x2 = x1 + graphW * zoom, y1 = y2 - graphH * zoom;
        if (x2 < canvasX0 || x1 > canvasX1 || y2 < canvasY0 || y1 > headerY0) return;

        float r = EditorTheme.RADIUS * zoom;
        float tz = textZoom();

        // Drop shadow: wide faint spread plus a tight offset core
        float spread = 0.010f * zoom, spreadX = UIGeometryBatch.squareWidth(buffer, spread);
        float offY = 0.008f * zoom, offX = UIGeometryBatch.squareWidth(buffer, offY) * 0.5f;
        UIGeometryBatch.addRoundedQuad(buffer, x1 - spreadX + offX, y1 - spread - offY, x2 + spreadX + offX, y2 + spread - offY, r + spread, EditorTheme.SHADOW, 0.18f);
        UIGeometryBatch.addRoundedQuad(buffer, x1 + offX, y1 - offY, x2 + offX, y2 - offY, r, EditorTheme.SHADOW, 0.35f);

        // Active glow / selection ring sit just outside the card
        if (glow) {
            float g = 0.010f * zoom, gx = UIGeometryBatch.squareWidth(buffer, g);
            UIGeometryBatch.addRoundedQuad(buffer, x1 - gx, y1 - g, x2 + gx, y2 + g, r + g, EditorTheme.NODE_ACTIVE, 0.22f);
        }
        if (ringColor >= 0) {
            float t = Math.max(0.003f, 0.0045f * zoom), tx = UIGeometryBatch.squareWidth(buffer, t);
            UIGeometryBatch.addRoundedQuad(buffer, x1 - tx, y1 - t, x2 + tx, y2 + t, r + t, ringColor, 1.0f);
        }

        UIGeometryBatch.addRoundedCard(buffer, x1, y1, x2, y2, r, EditorTheme.NODE_INACTIVE, EditorTheme.BORDER_STRONG, 1.0f);

        // Header bar: rounded top corners, squared-off bottom edge
        float hy = 0.0025f, hx = UIGeometryBatch.squareWidth(buffer, hy);
        float headerBottom = y2 - Math.min(HEADER_H * zoom, (y2 - y1) * 0.5f);
        float hr = Math.max(0.0f, r - hy);
        UIGeometryBatch.addRoundedQuad(buffer, x1 + hx, headerBottom, x2 - hx, y2 - hy, hr, headerColor, 1.0f);
        UIGeometryBatch.addQuad(buffer, x1 + hx, headerBottom, x2 - hx, Math.min(headerBottom + hr, y2 - hy), headerColor, 1.0f);

        float padX = 0.012f * zoom;
        float headerMid = (headerBottom + y2) * 0.5f;
        UIGeometryBatch.addTruncatedText(buffer, title, x1 + padX, textTop(headerMid, 0.82f * tz), x2 - padX, 0.82f * tz, EditorTheme.TEXT, 1.0f);

        float bodyMid = (y1 + headerBottom) * 0.5f + (progress >= 0.0f ? 0.006f * zoom : 0.0f);
        if (subtitle != null && zoom >= 0.55f) {
            UIGeometryBatch.addTruncatedText(buffer, subtitle, x1 + padX, textTop(bodyMid, 0.68f * tz), x2 - padX, 0.68f * tz, subtitleColor, 1.0f);
        }

        if (progress >= 0.0f) {
            float barH = 0.007f * zoom;
            float by0 = y1 + 0.010f * zoom;
            UIGeometryBatch.addPill(buffer, x1 + padX, by0, x2 - padX, by0 + barH, EditorTheme.SURFACE_SUNKEN, 1.0f);
            float fill = (x2 - x1 - 2.0f * padX) * Math.max(0.0f, Math.min(1.0f, progress));
            if (fill > 0.0f) {
                UIGeometryBatch.addPill(buffer, x1 + padX, by0, x1 + padX + fill, by0 + barH, headerColor == EditorTheme.NODE_ACTIVE ? EditorTheme.ACCENT : EditorTheme.ORANGE, 1.0f);
            }
        }
    }

    /**
     * Directed link between two node rects (graph-space top-left + size): offset to the right of its
     * direction so A->B and B->A run side by side, clipped to both card edges, with a midpoint
     * arrowhead and an optional condition badge.
     */
    private static void renderTransition(UIGeometryBatch.Buffer buffer,
                                         float sgx, float sgy, float sgw, float sgh,
                                         float dgx, float dgy, float dgw, float dgh,
                                         CharSequence label, int color, float alpha, boolean emphasized) {
        float scx = toViewX(sgx + sgw * 0.5f), scy = toViewY(sgy - sgh * 0.5f);
        float dcx = toViewX(dgx + dgw * 0.5f), dcy = toViewY(dgy - dgh * 0.5f);
        float dx = dcx - scx, dy = dcy - scy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;
        float ux = dx / len, uy = dy / len;

        // Perpendicular offset with equal on-screen length whichever way the link points
        float off = 0.014f * zoom;
        float px = -uy * UIGeometryBatch.squareWidth(buffer, off), py = ux * off;

        float ts = boxExit(ux, uy, sgw * zoom * 0.5f, sgh * zoom * 0.5f) + 0.006f * zoom;
        float td = boxExit(ux, uy, dgw * zoom * 0.5f, dgh * zoom * 0.5f) + 0.010f * zoom;
        if (ts + td >= len) return; // Overlapping cards

        float x1 = scx + px + ux * ts, y1 = scy + py + uy * ts;
        float x2 = dcx + px - ux * td, y2 = dcy + py - uy * td;

        float thick = Math.max(0.002f, (emphasized ? 0.0065f : 0.0035f) * zoom);
        UIGeometryBatch.addLineSegment(buffer, x1, y1, x2, y2, thick, color, alpha);

        float midX = (x1 + x2) * 0.5f, midY = (y1 + y2) * 0.5f;
        float arrowLen = 0.028f * zoom;
        UIGeometryBatch.addArrowhead(buffer, midX + ux * arrowLen * 0.5f, midY + uy * arrowLen * 0.5f, ux, uy, arrowLen, 0.015f * zoom, color, 1.0f);

        if (label == null || label.length() == 0 || zoom < 0.6f) return;

        // Condition badge beside the arrowhead, pushed off the line along its perpendicular
        float size = 0.6f * textZoom();
        float textW = Math.min(UIGeometryBatch.textWidth(buffer, label, size), 0.20f * zoom);
        float dotW = UIGeometryBatch.squareWidth(buffer, 0.010f * zoom);
        float halfW = (textW + dotW) * 0.5f + 0.012f * zoom;
        float halfH = 0.016f * Math.max(0.75f, zoom);
        float gap = 0.008f * zoom;
        float sideX = -uy, sideY = ux; // unit perpendicular, same side as the offset
        float bcx = midX + sideX * (halfW + gap), bcy = midY + sideY * (halfH + gap);

        UIGeometryBatch.addRoundedCard(buffer, bcx - halfW, bcy - halfH, bcx + halfW, bcy + halfH, halfH, EditorTheme.SURFACE_RAISED, color, 0.95f);
        float dx0 = bcx - halfW + 0.008f * zoom;
        UIGeometryBatch.addPill(buffer, dx0, bcy - 0.005f * zoom, dx0 + dotW, bcy + 0.005f * zoom, color, 1.0f);
        float tx0 = dx0 + dotW + 0.006f * zoom;
        UIGeometryBatch.addTruncatedText(buffer, label, tx0, textTop(bcy, size), bcx + halfW - 0.004f * zoom, size, EditorTheme.TEXT, 1.0f);
    }

    /** Distance from a box center along unit direction (ux, uy) to the box edge. */
    private static float boxExit(float ux, float uy, float halfW, float halfH) {
        float tx = Math.abs(ux) > 0.0001f ? halfW / Math.abs(ux) : Float.MAX_VALUE;
        float ty = Math.abs(uy) > 0.0001f ? halfH / Math.abs(uy) : Float.MAX_VALUE;
        return Math.min(tx, ty);
    }

    private static void renderHintPill(UIGeometryBatch.Buffer buffer, String text) {
        float size = 0.7f;
        float w = UIGeometryBatch.textWidth(buffer, text, size) + 0.04f;
        float y0 = canvasY0 + 0.03f, y1 = y0 + 0.045f;
        float cx = (canvasX0 + canvasX1) * 0.5f;
        UIGeometryBatch.addRoundedCard(buffer, cx - w * 0.5f, y0, cx + w * 0.5f, y1, (y1 - y0) * 0.5f, EditorTheme.SURFACE_RAISED, EditorTheme.YELLOW, 0.95f);
        centeredTextIn(buffer, text, cx - w * 0.5f, cx + w * 0.5f, (y0 + y1) * 0.5f, size, EditorTheme.TEXT);
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, CharSequence text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = Math.max(x0 + 0.002f, (x0 + x1 - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, textTop(midY, size), x1, size, rgb, 1.0f);
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        centeredTextIn(buffer, text, canvasX0, canvasX1, midY, size, rgb);
    }

    // --- Input ---

    public static boolean handleCanvasClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        if (manager == null) return false;
        layout();
        AnimatorController controller = manager.getActiveAnimatorController();

        if (action == 1 && button == 1) { // Right press
            pendingTransitionSource = null;
            if (isInsideCanvas(ndcX, ndcY)) {
                openGraphContextMenu(ndcX, ndcY, controller, manager);
                return true;
            }
            return false;
        }

        if (action == 1 && button == 0 && pendingTransitionSource != null) { // Resolve transition link mode
            String source = pendingTransitionSource;
            pendingTransitionSource = null;
            AnimatorState destination = isInsideCanvas(ndcX, ndcY) ? findStateAt(controller, ndcX, ndcY) : null;
            if (destination != null) {
                createTransition(controller, source, destination.name, manager);
            } else {
                UISoundPlayer.play(UISound.CLOSE_MODAL);
            }
            return true;
        }

        if (action == 1 && button == 0) { // Press
            // 1. Header buttons
            if (DockPanel.ANIMATOR.rect.contains(ndcX, ndcY) && ndcY >= btnY0 && ndcY <= btnY1) {
                if (ndcX >= addX0 && ndcX <= addX1) {
                    promptAddState(controller);
                    return true;
                }
                if (ndcX >= resetX0 && ndcX <= resetX1) {
                    resetView();
                    UISoundPlayer.play(UISound.CLICK);
                    return true;
                }
            }

            // 2. Node cards, else pan the empty background
            if (isInsideCanvas(ndcX, ndcY)) {
                AnimatorState state = findStateAt(controller, ndcX, ndcY);
                if (state != null) {
                    manager.setSelectedStateName(state.name);
                    isDraggingNode = true;
                    draggedStateName = state.name;
                    dragStartNdcX = ndcX;
                    dragStartNdcY = ndcY;
                    nodeStartGraphX = state.graphX;
                    nodeStartGraphY = state.graphY;
                    UISoundPlayer.play(UISound.CLICK);
                    return true;
                }
                isPanning = true;
                dragStartNdcX = ndcX;
                dragStartNdcY = ndcY;
                panStartPanX = panX;
                panStartPanY = panY;
                return true;
            }
        } else if (action == 0 && button == 0) { // Release
            if (isDraggingNode || isPanning) {
                isDraggingNode = false;
                draggedStateName = null;
                isPanning = false;
                return true;
            }
        }
        return false;
    }

    public static boolean handleCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        layout();
        cursorNdcX = ndcX;
        cursorNdcY = ndcY;
        if (isPanning) {
            panX = panStartPanX + (ndcX - dragStartNdcX) / zoom;
            panY = panStartPanY + (ndcY - dragStartNdcY) / zoom;
            return true;
        }
        if (isDraggingNode && draggedStateName != null && manager != null) {
            AnimatorController controller = manager.getActiveAnimatorController();
            if (controller != null) {
                AnimatorState state = controller.getState(draggedStateName);
                if (state != null) {
                    state.graphX = nodeStartGraphX + (ndcX - dragStartNdcX) / zoom;
                    state.graphY = nodeStartGraphY + (ndcY - dragStartNdcY) / zoom;
                    return true;
                }
            }
        }
        return false;
    }

    /** Mouse-wheel zoom anchored on the cursor, so the graph point under it stays put. */
    public static boolean handleScroll(float ndcX, float ndcY, double yOffset) {
        layout();
        if (!isInsideCanvas(ndcX, ndcY)) return false;
        float gx = toGraphX(ndcX), gy = toGraphY(ndcY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (float) Math.pow(1.1, yOffset)));
        panX = (ndcX - viewCx) / zoom + GRAPH_CX - gx;
        panY = (ndcY - viewCy) / zoom + GRAPH_CY - gy;
        return true;
    }

    public static void resetView() {
        panX = 0.0f;
        panY = 0.0f;
        zoom = 1.0f;
    }

    private static boolean isInsideCanvas(float ndcX, float ndcY) {
        return DockPanel.ANIMATOR.rect.visible && ndcX >= canvasX0 && ndcX <= canvasX1 && ndcY >= canvasY0 && ndcY < headerY0;
    }

    /** Topmost (last-rendered) state node card under the cursor, or null. */
    private static AnimatorState findStateAt(AnimatorController controller, float ndcX, float ndcY) {
        if (controller == null) return null;
        float gx = toGraphX(ndcX), gy = toGraphY(ndcY);
        AnimatorState hit = null;
        for (AnimatorState state : controller.getStates().values()) {
            if (gx >= state.graphX && gx <= state.graphX + NODE_W && gy >= state.graphY - NODE_H && gy <= state.graphY) {
                hit = state;
            }
        }
        return hit;
    }

    /**
     * Builds the right-click menu for the graph canvas. Node cards get node actions; the empty
     * background gets creation actions. Item lambdas capture only state names (re-resolved on run)
     * and the workspace; OverlaySystem clears the item list on close, releasing them.
     */
    private static void openGraphContextMenu(float ndcX, float ndcY, AnimatorController controller, EditorWorkspace manager) {
        if (controller == null) return;
        List<MenuItem> items = new ArrayList<>();
        AnimatorState hitState = findStateAt(controller, ndcX, ndcY);

        if (hitState != null) {
            final String stateName = hitState.name;
            manager.setSelectedStateName(stateName);
            items.add(new MenuItem("Create Transition", () -> {
                if (controller.getState(stateName) != null) pendingTransitionSource = stateName;
            }));
            if (controller.getEntryState() != hitState) {
                items.add(new MenuItem("Set as Entry State", () -> setEntryState(controller, stateName)));
            }
            items.add(new MenuItem("Delete State", () -> deleteState(controller, stateName, manager)));
        } else {
            // Spawn with the card centered under the cursor
            final float spawnX = toGraphX(ndcX) - NODE_W * 0.5f;
            final float spawnY = toGraphY(ndcY) + NODE_H * 0.5f;
            items.add(new MenuItem("Create State", () -> createState(controller, spawnX, spawnY, false, manager)));
            items.add(new MenuItem("Create Blend Tree", () -> createState(controller, spawnX, spawnY, true, manager)));

            AnimatorState selected = (manager.getSelectedStateName() != null) ? controller.getState(manager.getSelectedStateName()) : null;
            if (selected != null && controller.getEntryState() != selected) {
                final String selectedName = selected.name;
                items.add(new MenuItem("Set '" + selectedName + "' as Entry State", () -> setEntryState(controller, selectedName)));
            }
            AnimatorComponent animator = manager.getSelectedAnimator();
            if (animator != null && animator.controller == controller) {
                if (animator.recordedPose != null && !animator.recordedPose.getTracks().isEmpty()) {
                    items.add(new MenuItem("Export Recorded Pose (.ranim)", manager::exportRecordedClip));
                }
                for (AssetMeta asset : manager.getAssetDirectory()) {
                    if (asset.type == AssetType.ANIMATION && !animator.clipAssetPaths.contains(asset.absolutePath)) {
                        items.add(new MenuItem("Add Clip '" + asset.name + "'", () -> manager.addClipAsset(asset)));
                    }
                }
            }
            items.add(new MenuItem("Reset View", AnimatorCanvasDelegate::resetView));
        }

        manager.getOverlayManager().openContextMenu(ndcX, ndcY, items);
    }

    private static void createState(AnimatorController controller, float graphX, float graphY, boolean blendTree, EditorWorkspace manager) {
        String name = controller.uniqueStateName(blendTree ? "Blend Tree" : "New State");
        AnimatorState state = new AnimatorState(name, null).setGraphPos(graphX, graphY);

        if (blendTree) {
            // 1D tree on a dedicated float parameter, seeded with the controller's existing clips
            // at thresholds 0, 1, 2, ... so it animates immediately (e.g. Idle -> Walk -> Run)
            BlendTree tree = new BlendTree(BlendTree.BlendType.SIMPLE_1D, "Blend");
            if (!controller.getParameters().containsKey(tree.parameterX)) {
                controller.setFloat(tree.parameterX, 0.0f);
            }
            float threshold = 0.0f;
            for (AnimatorState existing : controller.getStates().values()) {
                if (existing.clip != null) {
                    tree.addMotion(existing.clip, threshold);
                    threshold += 1.0f;
                }
            }
            state.blendTree = tree;
        }

        controller.addState(state);
        manager.setSelectedStateName(name);
        UISoundPlayer.play(UISound.SPAWN);
    }

    private static void setEntryState(AnimatorController controller, String stateName) {
        if (controller.setEntryState(stateName)) {
            UISoundPlayer.play(UISound.CLICK);
        }
    }

    private static void deleteState(AnimatorController controller, String stateName, EditorWorkspace manager) {
        if (!controller.removeState(stateName)) return;
        if (stateName.equals(draggedStateName)) {
            isDraggingNode = false;
            draggedStateName = null;
        }
        if (stateName.equals(pendingTransitionSource)) pendingTransitionSource = null;
        // Selection may reference the removed state or one of the transitions that targeted it
        manager.clearAnimatorSelection();
        UISoundPlayer.play(UISound.CLOSE_MODAL);
    }

    private static void createTransition(AnimatorController controller, String sourceName, String destinationName, EditorWorkspace manager) {
        AnimatorState source = (controller != null) ? controller.getState(sourceName) : null;
        if (source == null || sourceName.equals(destinationName)) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            return;
        }
        for (AnimatorTransition existing : source.transitions) {
            if (destinationName.equals(existing.destinationStateName)) {
                UISoundPlayer.play(UISound.CLOSE_MODAL);
                return;
            }
        }
        // Unconditioned transitions fire immediately, so default to exit-time until conditions are added
        source.addTransition(new AnimatorTransition(destinationName, 0.25f, true, 0.90f));
        manager.setSelectedStateName(sourceName);
        UISoundPlayer.play(UISound.SPAWN);
    }

    private static void promptAddState(AnimatorController controller) {
        if (controller == null) return;
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String name = NativeFileDialog.promptInput("Add State", "Enter new state name:", "NewState");
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            if (controller.getState(trimmed) == null) {
                // Centered in the current view
                AnimatorState state = new AnimatorState(trimmed, null)
                        .setGraphPos(toGraphX(viewCx) - NODE_W * 0.5f, toGraphY(viewCy) + NODE_H * 0.5f);
                controller.addState(state);
                UISoundPlayer.play(UISound.SPAWN);
            }
        }
    }
}
