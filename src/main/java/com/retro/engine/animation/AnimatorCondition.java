package com.retro.engine.animation;

import com.retro.engine.api.PublicApi;

/**
 * Condition evaluated against an AnimatorParameter to trigger a state machine transition.
 */
@PublicApi
public class AnimatorCondition {

    public enum Comparison {
        GREATER, GREATER_EQUAL, LESS, LESS_EQUAL, EQUALS, NOT_EQUALS, TRUE, FALSE
    }

    public String parameterName;
    public Comparison mode;
    public float threshold;

    public AnimatorCondition(String parameterName, Comparison mode, float threshold) {
        this.parameterName = parameterName;
        this.mode = mode;
        this.threshold = threshold;
    }

    public AnimatorCondition(String parameterName, Comparison mode) {
        this(parameterName, mode, 0.0f);
    }

    public String getFormattedRule() {
        if (parameterName == null) return "";
        if (mode == null) return parameterName;
        switch (mode) {
            case GREATER -> { return parameterName + " > " + formatVal(threshold); }
            case GREATER_EQUAL -> { return parameterName + " >= " + formatVal(threshold); }
            case LESS -> { return parameterName + " < " + formatVal(threshold); }
            case LESS_EQUAL -> { return parameterName + " <= " + formatVal(threshold); }
            case EQUALS -> { return parameterName + " == " + formatVal(threshold); }
            case NOT_EQUALS -> { return parameterName + " != " + formatVal(threshold); }
            case TRUE -> { return parameterName + " == true"; }
            case FALSE -> { return parameterName + " == false"; }
            default -> { return parameterName; }
        }
    }

    private String formatVal(float val) {
        if (val == (int) val) {
            return String.valueOf((int) val);
        }
        return String.format("%.2f", val);
    }

    /**
     * Evaluates if the condition is satisfied against a parameter.
     */
    public boolean evaluate(AnimatorParameter param) {
        if (param == null) return false;

        switch (param.type) {
            case FLOAT -> {
                if (mode == Comparison.GREATER) return param.floatValue > threshold;
                if (mode == Comparison.GREATER_EQUAL) return param.floatValue >= threshold;
                if (mode == Comparison.LESS) return param.floatValue < threshold;
                if (mode == Comparison.LESS_EQUAL) return param.floatValue <= threshold;
                if (mode == Comparison.EQUALS) return Math.abs(param.floatValue - threshold) < 0.0001f;
                if (mode == Comparison.NOT_EQUALS) return Math.abs(param.floatValue - threshold) >= 0.0001f;
            }
            case INT -> {
                int intThresh = Math.round(threshold);
                if (mode == Comparison.GREATER) return param.intValue > intThresh;
                if (mode == Comparison.GREATER_EQUAL) return param.intValue >= intThresh;
                if (mode == Comparison.LESS) return param.intValue < intThresh;
                if (mode == Comparison.LESS_EQUAL) return param.intValue <= intThresh;
                if (mode == Comparison.EQUALS) return param.intValue == intThresh;
                if (mode == Comparison.NOT_EQUALS) return param.intValue != intThresh;
            }
            case BOOL, TRIGGER -> {
                if (mode == Comparison.TRUE) return param.boolValue;
                if (mode == Comparison.FALSE) return !param.boolValue;
                if (mode == Comparison.EQUALS) return param.boolValue == (threshold > 0.5f);
                if (mode == Comparison.NOT_EQUALS) return param.boolValue != (threshold > 0.5f);
            }
        }
        return false;
    }
}
