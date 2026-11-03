package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Encapsulates perspective 3D camera properties attached to an Entity.
 */
public class Camera extends Component {

    @InspectorProperty(order = 0, label = "FOV", min = 10.0f, max = 170.0f, step = 5.0f)
    public float fov = 50.0f;

    @InspectorProperty(order = 1, label = "NEAR", min = 0.01f, max = 10.0f, step = 0.1f)
    public float nearPlane = 0.1f;

    @InspectorProperty(order = 2, label = "FAR", min = 10.0f, max = 10000.0f, step = 50.0f)
    public float farPlane = 1000.0f;

    @InspectorProperty(order = 3, label = "PRIMARY", type = PropertyType.TOGGLE)
    public boolean isPrimary = true;

    /** Entity id or name of the screen-space UICanvas shown through this camera when it is primary ("" = none). */
    @InspectorProperty(order = 4, label = "UI CANVAS", type = PropertyType.TEXT, since = 3, prefabSince = 3)
    public String uiCanvas = "";

    /** Runtime canvas set by scripts; takes precedence over {@link #uiCanvas} and is never persisted. */
    public transient UICanvasComponent activeCanvas;

    /**
     * Default screen-space canvas bound automatically when UI elements sit on or under this camera's entity with no
     * UICanvas above them, so HUD components render without extra setup. Created on first use; never persisted.
     */
    public transient UICanvasComponent autoCanvas;

    public UICanvasComponent getOrCreateAutoCanvas() {
        if (autoCanvas == null) autoCanvas = new UICanvasComponent();
        return autoCanvas;
    }

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
