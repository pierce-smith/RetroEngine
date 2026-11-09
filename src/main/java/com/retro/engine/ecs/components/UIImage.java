package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Tinted vector shape filling the element rect: solid or rounded rectangles, circles, rings, and crosshairs.
 * THICKNESS is the stroke width of rings and crosshair arms; GAP is the empty radius at the crosshair center.
 */
@PublicApi
public class UIImage extends UIElement {

    public enum Shape { RECT, ROUNDED_RECT, CIRCLE, RING, CROSSHAIR }

    @InspectorProperty(order = 12, label = "SHAPE", type = PropertyType.BUTTON_GROUP)
    public Shape shape = Shape.RECT;

    @InspectorProperty(order = 13, label = "THICKNESS", min = 0.5f, max = 100.0f, step = 0.5f)
    public float thickness = 3.0f;

    @InspectorProperty(order = 14, label = "GAP", min = 0.0f, max = 200.0f, step = 1.0f)
    public float gap = 6.0f;

    public UIImage() {
        width = 64.0f;
        height = 64.0f;
    }

    @Override
    public String getComponentName() {
        return "UIImage";
    }
}
