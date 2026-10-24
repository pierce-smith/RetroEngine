package com.retro.engine.editor.ui.animator;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.animation.state.AnimatorTransition;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;

import java.util.Collection;

/**
 * SRP Delegate: Manages 2D Animator Graph Canvas rendering, grid layout,
 * node card positioning, state transitions, and node drag interactions.
 */
public class AnimatorCanvasDelegate {

    private static boolean isDraggingNode = false;
    private static String draggedStateName = null;
    private static float dragStartNdcX = 0.0f;
    private static float dragStartNdcY = 0.0f;
    private static float nodeStartGraphX = 0.0f;
    private static float nodeStartGraphY = 0.0f;

    public static void renderCanvas(UIGeometryBatch.Buffer buffer, AnimatorController controller, String selectedStateName) {
        float canvasX1 = -0.55f, canvasY1 = -0.50f, canvasX2 = 0.55f, canvasY2 = 0.86f;
        UIGeometryBatch.addQuad(buffer, canvasX1, canvasY1, canvasX2, canvasY2, 0.11f, 0.11f, 0.14f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, canvasX1, canvasY1, canvasX2, canvasY2, 0.003f, 0.22f, 0.25f, 0.32f, 1.0f);

        // Header Title
        UIGeometryBatch.addQuad(buffer, canvasX1, canvasY2 - 0.05f, canvasX2, canvasY2, 0.14f, 0.15f, 0.19f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, canvasX1, canvasY2 - 0.05f, canvasX2, canvasY2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);
        String ctrlName = (controller != null && controller.name != null) ? controller.name.toUpperCase() : "ANIMATOR CONTROLLER";
        UIGeometryBatch.addBoundedText(buffer, "ANIMATOR GRAPH [" + ctrlName + "]", canvasX1 + 0.02f, canvasY2 - 0.015f, canvasX2 - 0.20f, 1.1f, 0.3f, 0.8f, 1.0f, 1.0f);

        // [+ STATE] button in canvas header
        UIGeometryBatch.addQuad(buffer, canvasX2 - 0.18f, canvasY2 - 0.045f, canvasX2 - 0.01f, canvasY2 - 0.005f, 0.15f, 0.30f, 0.45f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, canvasX2 - 0.18f, canvasY2 - 0.045f, canvasX2 - 0.01f, canvasY2 - 0.005f, 0.001f, 0.0f, 0.7f, 1.0f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "+ STATE", canvasX2 - 0.17f, canvasY2 - 0.018f, canvasX2 - 0.015f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Grid pattern inside canvas
        renderCanvasGrid(buffer, canvasX1, canvasY1 + 0.02f, canvasX2, canvasY2 - 0.05f);

        // Special Entry & Any State Nodes
        AnimatorGraphPanel.NodePos entryPos = new AnimatorGraphPanel.NodePos(-0.48f, 0.65f, 0.16f, 0.08f);
        AnimatorGraphPanel.NodePos anyStatePos = new AnimatorGraphPanel.NodePos(-0.48f, 0.35f, 0.18f, 0.08f);
        renderSpecialNode(buffer, "ENTRY", entryPos, 0.15f, 0.65f, 0.25f, true);
        renderSpecialNode(buffer, "ANY STATE", anyStatePos, 0.65f, 0.25f, 0.25f, false);

        if (controller != null) {
            AnimatorState activeState = controller.getCurrentState();
            AnimatorState targetState = controller.getTargetState();
            String activeName = (activeState != null) ? activeState.name : "";
            String targetName = (targetState != null) ? targetState.name : "";

            Collection<AnimatorState> allStates = controller.getStates().values();

            // Dynamic Transition Arrows
            for (AnimatorState srcState : allStates) {
                float srcX = srcState.graphX + 0.22f;
                float srcY = srcState.graphY - 0.06f;

                for (AnimatorTransition transition : srcState.transitions) {
                    AnimatorState dstState = controller.getState(transition.destinationStateName);
                    if (dstState != null) {
                        float dstX = dstState.graphX;
                        float dstY = dstState.graphY - 0.06f;

                        StringBuilder condLabel = new StringBuilder();
                        for (int i = 0; i < transition.conditions.size(); i++) {
                            if (i > 0) condLabel.append(" && ");
                            condLabel.append(transition.conditions.get(i).getFormattedRule());
                        }
                        if (transition.conditions.isEmpty() && transition.hasExitTime) {
                            condLabel.append(String.format("Exit %.0f%%", transition.exitTime * 100.0f));
                        } else if (transition.conditions.isEmpty()) {
                            condLabel.append("DEFAULT");
                        }

                        boolean isBlending = controller.isCrossfading() && activeState == srcState && targetState == dstState;
                        renderTransitionLine(buffer, srcX, srcY, dstX, dstY, condLabel.toString(), 0.0f, 0.7f, 1.0f, isBlending);
                    }
                }
            }

            // Entry arrow to default/current state
            if (activeState != null) {
                renderTransitionLine(buffer, entryPos.x + entryPos.w, entryPos.y - entryPos.h * 0.5f, activeState.graphX, activeState.graphY - 0.06f, "ENTRY", 0.2f, 0.8f, 0.3f, false);
            }

            // Dynamic State Nodes
            for (AnimatorState state : allStates) {
                AnimatorGraphPanel.NodePos pos = new AnimatorGraphPanel.NodePos(state.graphX, state.graphY, 0.22f, 0.12f);
                boolean isActive = activeName.equalsIgnoreCase(state.name);
                boolean isTarget = targetName.equalsIgnoreCase(state.name);
                boolean isSelected = (selectedStateName != null && selectedStateName.equalsIgnoreCase(state.name));

                String clipName = (state.clip != null && state.clip.name != null) ? state.clip.name : "No Clip";
                renderStateNode(buffer, state.name, clipName, pos, isActive, isTarget, isSelected);
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "NO ANIMATOR CONTROLLER ASSIGNED", canvasX1 + 0.10f, 0.20f, canvasX2 - 0.10f, 1.2f, 0.6f, 0.6f, 0.6f, 1.0f);
        }
    }

    public static boolean handleCanvasClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        if (manager == null) return false;
        AnimatorController controller = manager.getActiveAnimatorController();

        if (action == 1 && button == 0) { // Press
            // 1. Check [+ STATE] button in Graph Canvas Header
            if (ndcX >= 0.37f && ndcX <= 0.54f && ndcY >= 0.81f && ndcY <= 0.86f) {
                promptAddState(controller);
                return true;
            }

            // 2. Check Central Canvas Node Card Clicks (-0.55f to 0.55f)
            if (ndcX >= -0.55f && ndcX <= 0.55f && ndcY >= -0.50f && ndcY <= 0.80f) {
                if (controller != null) {
                    for (AnimatorState state : controller.getStates().values()) {
                        float x1 = state.graphX;
                        float x2 = state.graphX + 0.22f;
                        float y1 = state.graphY - 0.12f;
                        float y2 = state.graphY;

                        if (ndcX >= x1 && ndcX <= x2 && ndcY >= y1 && ndcY <= y2) {
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
                    }
                }
            }
        } else if (action == 0 && button == 0) { // Release
            if (isDraggingNode) {
                isDraggingNode = false;
                draggedStateName = null;
                return true;
            }
        }
        return false;
    }

    public static boolean handleCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        if (isDraggingNode && draggedStateName != null && manager != null) {
            AnimatorController controller = manager.getActiveAnimatorController();
            if (controller != null) {
                AnimatorState state = controller.getState(draggedStateName);
                if (state != null) {
                    float deltaX = ndcX - dragStartNdcX;
                    float deltaY = ndcY - dragStartNdcY;

                    // Clamp node coordinates inside canvas bounds
                    state.graphX = Math.max(-0.53f, Math.min(0.31f, nodeStartGraphX + deltaX));
                    state.graphY = Math.max(-0.38f, Math.min(0.85f, nodeStartGraphY + deltaY));
                    return true;
                }
            }
        }
        return false;
    }

    private static void promptAddState(AnimatorController controller) {
        if (controller == null) return;
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String name = NativeFileDialog.promptInput("Add State", "Enter new state name:", "NewState");
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            if (controller.getState(trimmed) == null) {
                AnimatorState state = new AnimatorState(trimmed, null);
                state.graphX = 0.0f;
                state.graphY = 0.50f;
                controller.addState(state);
                UISoundPlayer.play(UISound.SPAWN);
            }
        }
    }

    private static void renderCanvasGrid(UIGeometryBatch.Buffer buffer, float minX, float minY, float maxX, float maxY) {
        float stepX = 0.10f;
        float stepY = 0.10f;

        for (float x = minX + stepX; x < maxX; x += stepX) {
            UIGeometryBatch.addLineSegment(buffer, x, minY, x, maxY, 0.001f, 0.16f, 0.17f, 0.22f, 0.6f);
        }
        for (float y = minY + stepY; y < maxY; y += stepY) {
            UIGeometryBatch.addLineSegment(buffer, minX, y, maxX, y, 0.001f, 0.16f, 0.17f, 0.22f, 0.6f);
        }
    }

    private static void renderSpecialNode(UIGeometryBatch.Buffer buffer, String title, AnimatorGraphPanel.NodePos pos, float r, float g, float b, boolean isEntry) {
        float x1 = pos.x, y1 = pos.y - pos.h, x2 = pos.x + pos.w, y2 = pos.y;
        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, r * 0.25f, g * 0.25f, b * 0.25f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, 0.003f, r, g, b, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, title, x1 + 0.015f, y2 - 0.025f, x2 - 0.01f, 1.1f, r, g, b, 1.0f);
    }

    private static void renderStateNode(UIGeometryBatch.Buffer buffer, String title, String clipName, AnimatorGraphPanel.NodePos pos, boolean isActive, boolean isTarget, boolean isSelected) {
        float x1 = pos.x, y1 = pos.y - pos.h, x2 = pos.x + pos.w, y2 = pos.y;

        float bgR = 0.15f, bgG = 0.18f, bgB = 0.24f;
        float borderR = 0.30f, borderG = 0.35f, borderB = 0.45f;

        if (isActive) {
            bgR = 0.12f; bgG = 0.32f; bgB = 0.50f;
            borderR = 0.0f; borderG = 0.70f; borderB = 1.0f;
        } else if (isTarget) {
            bgR = 0.35f; bgG = 0.25f; bgB = 0.10f;
            borderR = 1.0f; borderG = 0.60f; borderB = 0.0f;
        }

        if (isSelected) {
            borderR = 1.0f; borderG = 1.0f; borderB = 1.0f;
        }

        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, bgR, bgG, bgB, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, isSelected ? 0.004f : 0.002f, borderR, borderG, borderB, 1.0f);

        // Header bar
        UIGeometryBatch.addQuad(buffer, x1, y2 - 0.04f, x2, y2, borderR * 0.5f, borderG * 0.5f, borderB * 0.5f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, title.toUpperCase(), x1 + 0.015f, y2 - 0.012f, x2 - 0.01f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Clip name
        UIGeometryBatch.addBoundedText(buffer, clipName, x1 + 0.015f, y1 + 0.035f, x2 - 0.01f, 0.9f, 0.7f, 0.75f, 0.85f, 1.0f);

        // Status indicator
        if (isActive) {
            UIGeometryBatch.addQuad(buffer, x1 + 0.01f, y1 + 0.008f, x2 - 0.01f, y1 + 0.018f, 0.0f, 0.8f, 0.3f, 1.0f);
        }
    }

    private static void renderTransitionLine(UIGeometryBatch.Buffer buffer, float x1, float y1, float x2, float y2, String label, float r, float g, float b, boolean isBlending) {
        float thick = isBlending ? 0.005f : 0.002f;
        float lineR = isBlending ? 1.0f : r;
        float lineG = isBlending ? 0.5f : g;
        float lineB = isBlending ? 0.0f : b;

        UIGeometryBatch.addLineSegment(buffer, x1, y1, x2, y2, thick, lineR, lineG, lineB, 1.0f);

        // Arrowhead at midpoint
        float midX = (x1 + x2) * 0.5f;
        float midY = (y1 + y2) * 0.5f;
        float dirX = x2 - x1;
        float dirY = y2 - y1;
        UIGeometryBatch.addArrowhead(buffer, midX, midY, dirX, dirY, 0.025f, 0.015f, lineR, lineG, lineB, 1.0f);

        // Condition label badge
        if (label != null && !label.isEmpty()) {
            float badgeW = label.length() * 0.012f + 0.02f;
            UIGeometryBatch.addQuad(buffer, midX - badgeW * 0.5f, midY + 0.01f, midX + badgeW * 0.5f, midY + 0.05f, 0.08f, 0.09f, 0.12f, 0.9f);
            UIGeometryBatch.addFrameBorder(buffer, midX - badgeW * 0.5f, midY + 0.01f, midX + badgeW * 0.5f, midY + 0.05f, 0.001f, lineR, lineG, lineB, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, label, midX - badgeW * 0.5f + 0.008f, midY + 0.038f, midX + badgeW * 0.5f, 0.85f, 0.9f, 0.9f, 0.95f, 1.0f);
        }
    }
}
