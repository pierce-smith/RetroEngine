package com.retro.engine.scene.components.rendering;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

/**
 * Defines a directional or point light source in 3D world space.
 */
public class Light implements Component {
    public enum LightType { DIRECTIONAL, POINT, SPOT }

    @InspectorProperty(label = "TYPE", type = PropertyType.BUTTON_GROUP)
    public LightType type = LightType.DIRECTIONAL;

    @InspectorProperty(label = "INTENSITY", min = 0.0f, max = 100.0f, step = 0.5f)
    public float intensity = 1.0f;

    @InspectorProperty(label = "RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float r = 1.0f;

    @InspectorProperty(label = "GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float g = 0.95f;

    @InspectorProperty(label = "BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float b = 0.80f;

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
