package com.retro.engine.ecs.components;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Encapsulates perspective 3D camera properties attached to an Entity.
 */
@PublicApi
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

    // --- Post-processing (single fullscreen pass over the scene image while this is the Main Camera) ---
    @InspectorProperty(order = 5, label = "POST FX", type = PropertyType.TOGGLE, since = 7, prefabSince = 6)
    public boolean postProcessEnabled = false;

    /** Edge darkening strength, 0 = off. */
    @InspectorProperty(order = 6, label = "VIGNETTE", min = 0.0f, max = 1.0f, step = 0.05f, since = 7, prefabSince = 6)
    public float vignetteIntensity = 0.35f;

    /** CRT scanline darkening strength, 0 = off. */
    @InspectorProperty(order = 7, label = "SCANLINES", min = 0.0f, max = 1.0f, step = 0.05f, since = 7, prefabSince = 6)
    public float scanlineIntensity = 0.25f;

    /** Scanline scroll speed in lines per second (0 = static). */
    @InspectorProperty(order = 8, label = "SCANLINE SPEED", min = 0.0f, max = 60.0f, step = 1.0f, since = 7, prefabSince = 6)
    public float scanlineSpeed = 4.0f;

    /** Red/blue channel split at the screen edges, in pixels. */
    @InspectorProperty(order = 9, label = "CHROMATIC", min = 0.0f, max = 10.0f, step = 0.25f, since = 7, prefabSince = 6)
    public float chromaticOffset = 1.0f;

    @InspectorProperty(order = 10, label = "SATURATION", min = 0.0f, max = 2.0f, step = 0.05f, since = 7, prefabSince = 6)
    public float saturation = 1.0f;

    @InspectorProperty(order = 11, label = "CONTRAST", min = 0.0f, max = 2.0f, step = 0.05f, since = 7, prefabSince = 6)
    public float contrast = 1.0f;

    /** Runtime canvas set by scripts; takes precedence over {@link #uiCanvas} and is never persisted. */
    @Internal
    public transient UICanvasComponent activeCanvas;

    /**
     * Default screen-space canvas bound automatically when UI elements sit on or under this camera's entity with no
     * UICanvas above them, so HUD components render without extra setup. Created on first use; never persisted.
     */
    @Internal
    public transient UICanvasComponent autoCanvas;

    @Internal
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
