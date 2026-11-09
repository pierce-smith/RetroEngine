package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Health / stamina / loading bar: the base color is the track, the fill covers value / maxValue of the rect
 * (inset by PADDING) growing in FILL DIRECTION.
 */
@PublicApi
public class UIProgressBar extends UIElement {

    public enum FillDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT, BOTTOM_TO_TOP, TOP_TO_BOTTOM }

    @InspectorProperty(order = 12, label = "VALUE", min = 0.0f, max = 10000.0f, step = 1.0f)
    public float value = 100.0f;

    @InspectorProperty(order = 13, label = "MAX VALUE", min = 0.001f, max = 10000.0f, step = 1.0f)
    public float maxValue = 100.0f;

    @InspectorProperty(order = 14, label = "FILL DIRECTION", type = PropertyType.BUTTON_GROUP)
    public FillDirection fillDirection = FillDirection.LEFT_TO_RIGHT;

    @InspectorProperty(order = 15, label = "FILL RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float fillR = 0.85f;

    @InspectorProperty(order = 16, label = "FILL GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float fillG = 0.20f;

    @InspectorProperty(order = 17, label = "FILL BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float fillB = 0.20f;

    @InspectorProperty(order = 18, label = "PADDING", min = 0.0f, max = 50.0f, step = 1.0f)
    public float padding = 3.0f;

    public UIProgressBar() {
        width = 300.0f;
        height = 24.0f;
        colorR = 0.08f; colorG = 0.08f; colorB = 0.08f;
        alpha = 0.8f;
    }

    /** Filled fraction in [0, 1]. */
    public float fraction() {
        if (!(maxValue > 0.0f)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, value / maxValue));
    }

    @Override
    public String getComponentName() {
        return "UIProgressBar";
    }
}
