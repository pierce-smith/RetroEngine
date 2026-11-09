package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Defines a directional or point light source in 3D world space.
 */
@PublicApi
public class Light extends Component {
    public enum LightType { DIRECTIONAL, POINT, SPOT }

    @InspectorProperty(order = 0, label = "TYPE", type = PropertyType.BUTTON_GROUP)
    public LightType type = LightType.DIRECTIONAL;

    @InspectorProperty(order = 1, label = "INTENSITY", min = 0.0f, max = 100.0f, step = 0.5f)
    public float intensity = 1.0f;

    @InspectorProperty(order = 2, label = "RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float r = 1.0f;

    @InspectorProperty(order = 3, label = "GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float g = 0.95f;

    @InspectorProperty(order = 4, label = "BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
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
