package com.retro.engine.scene.components.ui;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

/**
 * Common rect, tint and ordering for game UI elements. Positions are in the owning canvas's reference pixels with
 * y up: the element's center sits at (anchor * canvas size + offset), so anchor (0.5, 0.5) centers it and (0, 1) pins
 * it to the top-left corner. Subclass properties continue the {@link InspectorProperty#order()} sequence at 12.
 */
public abstract class UIElement extends Component {

    @InspectorProperty(order = 0, label = "VISIBLE", type = PropertyType.TOGGLE)
    public boolean visible = true;

    @InspectorProperty(order = 1, label = "ANCHOR X", min = 0.0f, max = 1.0f, step = 0.05f)
    public float anchorX = 0.5f;

    @InspectorProperty(order = 2, label = "ANCHOR Y", min = 0.0f, max = 1.0f, step = 0.05f)
    public float anchorY = 0.5f;

    @InspectorProperty(order = 3, label = "OFFSET X", min = -4000.0f, max = 4000.0f, step = 5.0f)
    public float offsetX = 0.0f;

    @InspectorProperty(order = 4, label = "OFFSET Y", min = -4000.0f, max = 4000.0f, step = 5.0f)
    public float offsetY = 0.0f;

    @InspectorProperty(order = 5, label = "WIDTH", min = 0.0f, max = 4000.0f, step = 5.0f)
    public float width = 200.0f;

    @InspectorProperty(order = 6, label = "HEIGHT", min = 0.0f, max = 4000.0f, step = 5.0f)
    public float height = 50.0f;

    @InspectorProperty(order = 7, label = "RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorR = 1.0f;

    @InspectorProperty(order = 8, label = "GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorG = 1.0f;

    @InspectorProperty(order = 9, label = "BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorB = 1.0f;

    @InspectorProperty(order = 10, label = "ALPHA", min = 0.0f, max = 1.0f, step = 0.05f)
    public float alpha = 1.0f;

    /** Higher elements draw on top of lower ones within a canvas. */
    @InspectorProperty(order = 11, label = "SORT ORDER", min = -100.0f, max = 100.0f, step = 1.0f)
    public float sortOrder = 0.0f;
}
