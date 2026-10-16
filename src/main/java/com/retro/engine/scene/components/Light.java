package com.retro.engine.scene.components;

import com.retro.engine.scene.components.Component;

/**
 * Defines a directional or point light source in 3D world space.
 */
public class Light implements Component {
    public enum LightType { DIRECTIONAL, POINT, SPOT }

    public LightType type = LightType.DIRECTIONAL;
    public float intensity = 1.0f;
    public float r = 1.0f, g = 0.95f, b = 0.80f;

    public Light() {}

    public void cycleType() {
        type = switch (type) {
            case DIRECTIONAL -> LightType.POINT;
            case POINT -> LightType.SPOT;
            case SPOT -> LightType.DIRECTIONAL;
        };
    }

    @Override
    public String getComponentName() {
        return "Light Source";
    }
}
