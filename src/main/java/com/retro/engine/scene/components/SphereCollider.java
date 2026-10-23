package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;
import com.retro.engine.editor.ui.InspectorProperty.PropertyType;

/**
 * ECS Sphere Collider shape definition.
 */
public class SphereCollider implements Component {

    @InspectorProperty(label = "RADIUS", min = 0.01f, max = 100.0f, step = 0.1f)
    public float radius = 0.5f;

    @InspectorProperty(label = "OFFSET X", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetX = 0.0f;

    @InspectorProperty(label = "OFFSET Y", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetY = 0.0f;

    @InspectorProperty(label = "OFFSET Z", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetZ = 0.0f;

    @InspectorProperty(label = "TRIGGER", type = PropertyType.TOGGLE)
    public boolean isTrigger = false;

    public SphereCollider() {}

    @Override
    public String getComponentName() {
        return "Sphere Collider";
    }
}
