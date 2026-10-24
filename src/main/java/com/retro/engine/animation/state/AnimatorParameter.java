package com.retro.engine.animation.state;

/**
 * Parameter stored within an AnimatorController used to drive state machine transitions.
 * Supports FLOAT, INT, BOOL, and TRIGGER types.
 */
public class AnimatorParameter {

    public enum ParameterType {
        FLOAT, INT, BOOL, TRIGGER
    }

    public String name;
    public ParameterType type;

    public float floatValue = 0.0f;
    public int intValue = 0;
    public boolean boolValue = false;

    public AnimatorParameter(String name, ParameterType type) {
        this.name = name;
        this.type = type;
    }

    public AnimatorParameter(String name, float value) {
        this.name = name;
        this.type = ParameterType.FLOAT;
        this.floatValue = value;
    }

    public AnimatorParameter(String name, int value) {
        this.name = name;
        this.type = ParameterType.INT;
        this.intValue = value;
    }

    public AnimatorParameter(String name, boolean value) {
        this.name = name;
        this.type = ParameterType.BOOL;
        this.boolValue = value;
    }

    public void setTrigger() {
        if (this.type == ParameterType.TRIGGER) {
            this.boolValue = true;
        }
    }

    public void resetTrigger() {
        if (this.type == ParameterType.TRIGGER) {
            this.boolValue = false;
        }
    }
}
