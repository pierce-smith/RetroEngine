package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;

/**
 * Defines a directional or point light source in 3D world space.
 */
public class LightComponent implements Component {
    public enum LightType { DIRECTIONAL, POINT, SPOT }

    public LightType type = LightType.DIRECTIONAL;
    public float intensity = 1.0f;
    public float r = 1.0f, g = 0.95f, b = 0.80f;

    public LightComponent() {}

    @Override
    public String getComponentName() {
        return "Light Source";
    }
}
