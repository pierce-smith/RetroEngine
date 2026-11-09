package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/** Single-line SDF text (score counters, timers, labels), vertically centered in its rect. */
@PublicApi
public class UIText extends UIElement {

    public enum Alignment { LEFT, CENTER, RIGHT }

    @InspectorProperty(order = 12, label = "TEXT", type = PropertyType.TEXT)
    public String text = "SCORE: 0";

    /** Em height in reference pixels. */
    @InspectorProperty(order = 13, label = "FONT SIZE", min = 4.0f, max = 400.0f, step = 1.0f)
    public float fontSize = 32.0f;

    @InspectorProperty(order = 14, label = "ALIGN", type = PropertyType.BUTTON_GROUP)
    public Alignment alignment = Alignment.CENTER;

    public UIText() {
        width = 320.0f;
        height = 48.0f;
    }

    @Override
    public String getComponentName() {
        return "UIText";
    }
}
