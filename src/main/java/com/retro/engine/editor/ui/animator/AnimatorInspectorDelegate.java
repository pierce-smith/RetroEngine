package com.retro.engine.editor.ui.animator;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.animation.state.AnimatorTransition;
import com.retro.engine.animation.state.BlendTree;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;

/**
 * SRP Delegate: Manages Animator Inspector Sidebar rendering, state details,
 * motion clip specs, speed/loop toggles, and outgoing transition condition lists.
 */
public class AnimatorInspectorDelegate {

    public static void renderInspectorSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller, String selectedStateName, AnimatorTransition transition) {
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
            if (selectedState.blendTree != null) {
                String treeStr = String.format("BLEND TREE: %s ON %s (%d MOTIONS)",
                        selectedState.blendTree.type == BlendTree.BlendType.SIMPLE_1D ? "1D" : "2D",
                        selectedState.blendTree.parameterX, selectedState.blendTree.getMotions().size());
                UIGeometryBatch.addBoundedText(buffer, treeStr, x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.8f, 0.8f, 0.85f, 1.0f);
            } else {
                String clipStr = (selectedState.clip != null && selectedState.clip.name != null) ? selectedState.clip.name : "None";
                UIGeometryBatch.addBoundedText(buffer, "MOTION CLIP: " + clipStr, x1 + 0.02f, itemY, x2 - 0.02f, 0.9f, 0.8f, 0.8f, 0.85f, 1.0f);
            }

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
