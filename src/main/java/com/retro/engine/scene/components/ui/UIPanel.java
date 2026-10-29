package com.retro.engine.scene.components.ui;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;

/** Filled (optionally rounded and bordered) rectangle: HUD backplates, frames, and solid blocks. */
public class UIPanel extends UIElement {

    @InspectorProperty(order = 12, label = "CORNER RADIUS", min = 0.0f, max = 500.0f, step = 1.0f)
    public float cornerRadius = 0.0f;

    @InspectorProperty(order = 13, label = "BORDER", min = 0.0f, max = 50.0f, step = 1.0f)
    public float borderThickness = 0.0f;

    @InspectorProperty(order = 14, label = "BORDER RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float borderR = 1.0f;

    @InspectorProperty(order = 15, label = "BORDER GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float borderG = 1.0f;

    @InspectorProperty(order = 16, label = "BORDER BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float borderB = 1.0f;

    public UIPanel() {
        colorR = 0.08f; colorG = 0.08f; colorB = 0.10f;
        alpha = 0.75f;
    }

    @Override
    public String getComponentName() {
        return "UIPanel";
    }
}
