package com.retro.engine.animation;

import com.retro.engine.api.PublicApi;

/**
 * Parameter used to drive state machine transitions. The AnimatorController holds the definition and default value;
 * each AnimatorComponent holds its own copy with the live value.
 * Supports FLOAT, INT, BOOL, and TRIGGER types.
 */
@PublicApi
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

    /** Independent copy (an animator's own value seeded from a controller default, or a duplicated animator's value). */
    public AnimatorParameter copy() {
        AnimatorParameter c = new AnimatorParameter(name, type);
        c.floatValue = floatValue;
        c.intValue = intValue;
        c.boolValue = boolValue;
        return c;
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
