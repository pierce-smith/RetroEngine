package com.retro.engine.editor.ui;

import com.retro.engine.animation.AnimatorCondition;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorTransition;

import java.util.Collection;
import java.util.List;

/**
 * AnimatorGraphPanel renders a TRULY DYNAMIC 2D Animator State Machine Node Workspace:
 * 1. Reads states, motion clips, and node positions directly from AnimatorController.getStates().
 * 2. Draws transition arrows and condition rules dynamically from AnimatorTransition.conditions.
 * 3. Renders parameter rows dynamically from AnimatorController.getParameters().
 * 4. Renders state details and outgoing transitions dynamically from selected AnimatorState.
 */
public class AnimatorGraphPanel {

    public static class NodePos {
        public float x;
        public float y;
        public float w;
        public float h;

        public NodePos(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static void render(UIGeometryBatch.Buffer buffer, AnimatorController controller, String selectedStateName, AnimatorTransition selectedTransition) {
        // 1. Central Canvas Framing & Grid Pattern (-0.55f to 0.55f, -0.50f to 0.86f)
        float canvasX1 = -0.55f, canvasY1 = -0.50f, canvasX2 = 0.55f, canvasY2 = 0.86f;
        UIGeometryBatch.addQuad(buffer, canvasX1, canvasY1, canvasX2, canvasY2, 0.11f, 0.11f, 0.14f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, canvasX1, canvasY1, canvasX2, canvasY2, 0.003f, 0.22f, 0.25f, 0.32f, 1.0f);

        // Header Title
        UIGeometryBatch.addQuad(buffer, canvasX1, canvasY2 - 0.05f, canvasX2, canvasY2, 0.14f, 0.15f, 0.19f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, canvasX1, canvasY2 - 0.05f, canvasX2, canvasY2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);
        String ctrlName = (controller != null && controller.name != null) ? controller.name.toUpperCase() : "ANIMATOR CONTROLLER";
        UIGeometryBatch.addBoundedText(buffer, "ANIMATOR GRAPH CANVAS [" + ctrlName + "]", canvasX1 + 0.02f, canvasY2 - 0.015f, canvasX2 - 0.02f, 1.1f, 0.3f, 0.8f, 1.0f, 1.0f);

        // Grid pattern inside canvas
        renderCanvasGrid(buffer, canvasX1, canvasY1 + 0.02f, canvasX2, canvasY2 - 0.05f);

        // Special Entry & Any State Nodes
        NodePos entryPos = new NodePos(-0.48f, 0.65f, 0.16f, 0.08f);
        NodePos anyStatePos = new NodePos(-0.48f, 0.35f, 0.18f, 0.08f);
        renderSpecialNode(buffer, "ENTRY", entryPos, 0.15f, 0.65f, 0.25f, true);
        renderSpecialNode(buffer, "ANY STATE", anyStatePos, 0.65f, 0.25f, 0.25f, false);

        if (controller != null) {
            AnimatorState activeState = controller.getCurrentState();
            AnimatorState targetState = controller.getTargetState();
            String activeName = (activeState != null) ? activeState.name : "";
            String targetName = (targetState != null) ? targetState.name : "";

            Collection<AnimatorState> allStates = controller.getStates().values();

            // 2. Dynamic Transition Arrows
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

            // 3. Dynamic State Nodes
            for (AnimatorState state : allStates) {
                NodePos pos = new NodePos(state.graphX, state.graphY, 0.22f, 0.12f);
                boolean isActive = activeName.equalsIgnoreCase(state.name);
                boolean isTarget = targetName.equalsIgnoreCase(state.name);
                boolean isSelected = (selectedStateName != null && selectedStateName.equalsIgnoreCase(state.name));

                String clipName = (state.clip != null && state.clip.name != null) ? state.clip.name : "No Clip";
                renderStateNode(buffer, state.name, clipName, pos, isActive, isTarget, isSelected);
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "NO ANIMATOR CONTROLLER ASSIGNED", canvasX1 + 0.10f, 0.20f, canvasX2 - 0.10f, 1.2f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // 4. Dynamic Parameters Sidebar (-1.00f to -0.55f)
        renderParametersSidebar(buffer, controller);

        // 5. Dynamic Inspector Sidebar (0.55f to 1.00f)
        renderInspectorSidebar(buffer, controller, selectedStateName, selectedTransition);
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

    private static void renderSpecialNode(UIGeometryBatch.Buffer buffer, String title, NodePos pos, float r, float g, float b, boolean isEntry) {
        float x1 = pos.x, y1 = pos.y - pos.h, x2 = pos.x + pos.w, y2 = pos.y;
        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, r * 0.25f, g * 0.25f, b * 0.25f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, 0.003f, r, g, b, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, title, x1 + 0.015f, y2 - 0.025f, x2 - 0.01f, 1.1f, r, g, b, 1.0f);
    }

    private static void renderStateNode(UIGeometryBatch.Buffer buffer, String title, String clipName, NodePos pos, boolean isActive, boolean isTarget, boolean isSelected) {
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

    private static void renderParametersSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller) {
        float x1 = -1.00f, y1 = -0.50f, x2 = -0.55f, y2 = 0.86f;

        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, 0.12f, 0.12f, 0.15f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);

        // Sidebar Header
        UIGeometryBatch.addQuad(buffer, x1, y2 - 0.05f, x2, y2, 0.16f, 0.17f, 0.22f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y2 - 0.05f, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "PARAMETERS & LAYERS", x1 + 0.02f, y2 - 0.018f, x2 - 0.02f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Dynamic Parameter List Items
        if (controller != null && !controller.getParameters().isEmpty()) {
            float itemY = y2 - 0.08f;
            for (AnimatorParameter param : controller.getParameters().values()) {
                String typeStr = param.type.name();
                String valStr = switch (param.type) {
                    case FLOAT -> String.format("%.2f", param.floatValue);
                    case INT -> String.valueOf(param.intValue);
                    case BOOL -> String.valueOf(param.boolValue);
                    case TRIGGER -> param.boolValue ? "SET" : "OFF";
                };
                renderParameterRow(buffer, x1 + 0.01f, itemY, x2 - 0.01f, typeStr, param.name, valStr);
                itemY -= 0.07f;
                if (itemY < y1 + 0.05f) break;
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "NO PARAMETERS DEFINED", x1 + 0.02f, y2 - 0.10f, x2 - 0.02f, 1.0f, 0.5f, 0.5f, 0.5f, 1.0f);
        }
    }

    private static void renderParameterRow(UIGeometryBatch.Buffer buffer, float x1, float topY, float x2, String type, String name, String val) {
        float bottomY = topY - 0.055f;
        UIGeometryBatch.addQuad(buffer, x1, bottomY, x2, topY, 0.16f, 0.18f, 0.22f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, bottomY, x2, topY, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);

        // Type badge
        UIGeometryBatch.addQuad(buffer, x1 + 0.005f, bottomY + 0.005f, x1 + 0.07f, topY - 0.005f, 0.22f, 0.28f, 0.38f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, type, x1 + 0.01f, topY - 0.015f, x1 + 0.068f, 0.85f, 0.0f, 0.7f, 1.0f, 1.0f);

        // Name
        UIGeometryBatch.addBoundedText(buffer, name, x1 + 0.08f, topY - 0.015f, x1 + 0.25f, 0.95f, 0.9f, 0.9f, 0.95f, 1.0f);

        // Value
        UIGeometryBatch.addBoundedText(buffer, val, x2 - 0.08f, topY - 0.015f, x2 - 0.005f, 0.95f, 1.0f, 0.8f, 0.2f, 1.0f);
    }

    private static void renderInspectorSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller, String selectedStateName, AnimatorTransition transition) {
        float x1 = 0.55f, y1 = -0.50f, x2 = 1.00f, y2 = 0.86f;

        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, 0.12f, 0.12f, 0.15f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);

        // Inspector Header
        UIGeometryBatch.addQuad(buffer, x1, y2 - 0.05f, x2, y2, 0.16f, 0.17f, 0.22f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y2 - 0.05f, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "ANIMATOR INSPECTOR", x1 + 0.02f, y2 - 0.018f, x2 - 0.02f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);

        AnimatorState selectedState = (controller != null && selectedStateName != null) ? controller.getState(selectedStateName) : null;
        if (selectedState == null && controller != null && controller.getCurrentState() != null) {
            selectedState = controller.getCurrentState();
        }

        if (selectedState != null) {
            float itemY = y2 - 0.09f;
            UIGeometryBatch.addBoundedText(buffer, "STATE: " + selectedState.name.toUpperCase(), x1 + 0.02f, itemY, x2 - 0.02f, 1.1f, 0.0f, 0.7f, 1.0f, 1.0f);

            itemY -= 0.06f;
            String clipStr = (selectedState.clip != null && selectedState.clip.name != null) ? selectedState.clip.name : "None";
            UIGeometryBatch.addBoundedText(buffer, "MOTION CLIP: " + clipStr, x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.8f, 0.8f, 0.85f, 1.0f);

            itemY -= 0.05f;
            UIGeometryBatch.addBoundedText(buffer, String.format("SPEED: %.2fx", selectedState.speed), x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.8f, 0.8f, 0.85f, 1.0f);

            itemY -= 0.05f;
            UIGeometryBatch.addBoundedText(buffer, "LOOP: " + (selectedState.isLooping ? "TRUE" : "FALSE"), x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.8f, 0.8f, 0.85f, 1.0f);

            // Dynamic Outgoing Transitions
            itemY -= 0.08f;
            UIGeometryBatch.addQuad(buffer, x1 + 0.01f, itemY - 0.03f, x2 - 0.01f, itemY + 0.02f, 0.18f, 0.20f, 0.26f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "OUTGOING TRANSITIONS", x1 + 0.02f, itemY, x2 - 0.02f, 1.0f, 1.0f, 0.8f, 0.2f, 1.0f);

            itemY -= 0.07f;
            if (!selectedState.transitions.isEmpty()) {
                for (AnimatorTransition trans : selectedState.transitions) {
                    StringBuilder rules = new StringBuilder();
                    for (int i = 0; i < trans.conditions.size(); i++) {
                        if (i > 0) rules.append(", ");
                        rules.append(trans.conditions.get(i).getFormattedRule());
                    }
                    if (trans.conditions.isEmpty() && trans.hasExitTime) {
                        rules.append(String.format("Exit %.0f%%", trans.exitTime * 100.0f));
                    } else if (trans.conditions.isEmpty()) {
                        rules.append("Immediate");
                    }

                    String transLine = "-> " + trans.destinationStateName + " (" + rules + ")";
                    UIGeometryBatch.addBoundedText(buffer, transLine, x1 + 0.02f, itemY, x2 - 0.02f, 0.85f, 0.9f, 0.9f, 0.9f, 1.0f);
                    itemY -= 0.05f;

                    UIGeometryBatch.addBoundedText(buffer, String.format("   BLEND: %.2fs | EXIT: %s", trans.duration, trans.hasExitTime ? "YES" : "NO"), x1 + 0.02f, itemY, x2 - 0.02f, 0.80f, 0.6f, 0.8f, 1.0f, 1.0f);
                    itemY -= 0.06f;
                    if (itemY < y1 + 0.05f) break;
                }
            } else {
                UIGeometryBatch.addBoundedText(buffer, "NO OUTGOING TRANSITIONS", x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.5f, 0.5f, 0.5f, 1.0f);
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "SELECT A STATE NODE TO INSPECT", x1 + 0.02f, y2 - 0.10f, x2 - 0.02f, 1.0f, 0.5f, 0.5f, 0.5f, 1.0f);
        }
    }
}
