package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;

/**
 * Encapsulates perspective 3D camera properties attached to an Entity.
 */
public class Camera implements Component {

    @InspectorProperty(label = "FOV", min = 10.0f, max = 170.0f, step = 5.0f)
    public float fov = 50.0f;

    @InspectorProperty(label = "NEAR", min = 0.01f, max = 10.0f, step = 0.1f)
    public float nearPlane = 0.1f;

    @InspectorProperty(label = "FAR", min = 10.0f, max = 10000.0f, step = 50.0f)
    public float farPlane = 1000.0f;

    @InspectorProperty(label = "PRIMARY", type = PropertyType.TOGGLE)
    public boolean isPrimary = true;

    public Camera() {}

    public Camera(float fov, float nearPlane, float farPlane) {
        this.fov = fov;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    @Override
    public String getComponentName() {
        return "Camera";
    }
}
