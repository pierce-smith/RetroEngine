package com.retro.engine.scene.components.physics;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

/**
 * ECS Box Collider shape definition.
 */
public class BoxCollider extends Component {

    @InspectorProperty(order = 0, label = "SIZE X", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeX = 1.0f;

    @InspectorProperty(order = 1, label = "SIZE Y", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeY = 1.0f;

    @InspectorProperty(order = 2, label = "SIZE Z", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeZ = 1.0f;

    @InspectorProperty(order = 3, label = "OFFSET X", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetX = 0.0f;

    @InspectorProperty(order = 4, label = "OFFSET Y", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetY = 0.0f;

    @InspectorProperty(order = 5, label = "OFFSET Z", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetZ = 0.0f;

    @InspectorProperty(order = 6, label = "TRIGGER", type = PropertyType.TOGGLE)
    public boolean isTrigger = false;

    public BoxCollider() {}

    @Override
    public String getComponentName() {
        return "Box Collider";
    }
}
