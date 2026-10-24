package com.retro.engine.editor.ui.animator;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorParameter;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;

/**
 * SRP Delegate: Manages Animator Parameters Sidebar rendering, parameter row layout,
 * interactive value editing (toggles, numeric prompts), and [+ PARAM] creation logic.
 */
public class AnimatorParametersDelegate {

    public static void renderParametersSidebar(UIGeometryBatch.Buffer buffer, AnimatorController controller) {
        float x1 = -1.00f, y1 = -0.50f, x2 = -0.55f, y2 = 0.86f;

        UIGeometryBatch.addQuad(buffer, x1, y1, x2, y2, 0.12f, 0.12f, 0.15f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y1, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);

        // Sidebar Header
        UIGeometryBatch.addQuad(buffer, x1, y2 - 0.05f, x2, y2, 0.16f, 0.17f, 0.22f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x1, y2 - 0.05f, x2, y2, 0.002f, 0.22f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "PARAMETERS", x1 + 0.02f, y2 - 0.018f, x1 + 0.26f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);

        // [+ PARAM] button
        UIGeometryBatch.addQuad(buffer, x2 - 0.16f, y2 - 0.045f, x2 - 0.01f, y2 - 0.005f, 0.20f, 0.35f, 0.25f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, x2 - 0.16f, y2 - 0.045f, x2 - 0.01f, y2 - 0.005f, 0.001f, 0.3f, 0.8f, 0.4f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "+ PARAM", x2 - 0.155f, y2 - 0.018f, x2 - 0.015f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (controller != null && !controller.getParameters().isEmpty()) {
            float itemY = y2 - 0.08f;
            for (AnimatorParameter param : controller.getParameters().values()) {
                String typeStr = param.type.name();
                String valStr = switch (param.type) {
                    case FLOAT -> String.format("%.2f", param.floatValue);
                    case INT -> String.valueOf(param.intValue);
                    case BOOL, TRIGGER -> String.valueOf(param.boolValue);
                };
                renderParameterRow(buffer, x1 + 0.01f, itemY, x2 - 0.01f, typeStr, param.name, valStr);
                itemY -= 0.07f;
                if (itemY < y1 + 0.05f) break;
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "NO PARAMETERS DEFINED", x1 + 0.02f, y2 - 0.10f, x2 - 0.02f, 0.9f, 0.5f, 0.5f, 0.5f, 1.0f);
        }
    }

    public static boolean handleParametersClick(float ndcX, float ndcY, AnimatorController controller) {
        // 1. Check [+ PARAM] button
        if (ndcX >= -0.72f && ndcX <= -0.56f && ndcY >= 0.81f && ndcY <= 0.86f) {
            promptAddParameter(controller);
            return true;
        }

        // 2. Check Parameter Rows
        if (ndcX >= -1.00f && ndcX <= -0.55f && ndcY >= -0.50f && ndcY <= 0.80f) {
            if (controller != null && processParameterClick(ndcX, ndcY, controller)) {
                UISoundPlayer.play(UISound.CLICK);
                return true;
            }
        }
        return false;
    }

    private static boolean processParameterClick(float ndcX, float ndcY, AnimatorController controller) {
        if (controller == null) return false;
        float topY = 0.86f - 0.08f;

        for (AnimatorParameter param : controller.getParameters().values()) {
            float bottomY = topY - 0.055f;
            if (ndcY >= bottomY && ndcY <= topY) {
                switch (param.type) {
                    case BOOL -> param.boolValue = !param.boolValue;
                    case TRIGGER -> param.setTrigger();
                    case FLOAT -> {
                        String input = NativeFileDialog.promptInput("Edit Parameter", "Enter float value for '" + param.name + "':", String.format("%.2f", param.floatValue));
                        if (input != null && !input.trim().isEmpty()) {
                            try {
                                param.floatValue = Float.parseFloat(input.trim());
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    case INT -> {
                        String input = NativeFileDialog.promptInput("Edit Parameter", "Enter int value for '" + param.name + "':", String.valueOf(param.intValue));
                        if (input != null && !input.trim().isEmpty()) {
                            try {
                                param.intValue = Integer.parseInt(input.trim());
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                return true;
            }
            topY -= 0.07f;
        }
        return false;
    }

    private static void promptAddParameter(AnimatorController controller) {
        if (controller == null) return;
        UISoundPlayer.play(UISound.OPEN_MODAL);
        String name = NativeFileDialog.promptInput("Add Parameter", "Enter parameter name:", "NewParam");
        if (name != null && !name.trim().isEmpty()) {
            String typeStr = NativeFileDialog.promptInput("Parameter Type", "Type (FLOAT, INT, BOOL, TRIGGER):", "FLOAT");
            String trimmedName = name.trim();
            if (typeStr != null && !typeStr.trim().isEmpty()) {
                String typeUpper = typeStr.trim().toUpperCase();
                switch (typeUpper) {
                    case "INT" -> controller.setInt(trimmedName, 0);
                    case "BOOL" -> controller.setBool(trimmedName, false);
                    case "TRIGGER" -> controller.setTrigger(trimmedName);
                    default -> controller.setFloat(trimmedName, 0.0f);
                }
                UISoundPlayer.play(UISound.SPAWN);
            }
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
}
