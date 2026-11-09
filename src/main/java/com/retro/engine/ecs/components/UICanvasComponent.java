package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Root of a game UI: every {@link UIElement} on this entity or its descendants (down to the next nested canvas) is
 * laid out in this canvas's reference-pixel space. Screen-space overlays render through the primary Camera (attached
 * to the camera's entity or named by its UI CANVAS field); world-space billboards render wherever they are visible.
 */
@PublicApi
public class UICanvasComponent extends Component {

    public enum RenderMode { SCREEN_SPACE_OVERLAY, WORLD_SPACE_BILLBOARD }

    @InspectorProperty(order = 0, label = "ENABLED", type = PropertyType.TOGGLE)
    public boolean enabled = true;

    @InspectorProperty(order = 1, label = "RENDER MODE", type = PropertyType.BUTTON_GROUP)
    public RenderMode renderMode = RenderMode.SCREEN_SPACE_OVERLAY;

    /** Layout resolution; overlays scale uniformly so this rect fits the viewport. */
    @InspectorProperty(order = 2, label = "REF WIDTH", min = 160.0f, max = 3840.0f, step = 10.0f)
    public float referenceWidth = 1280.0f;

    @InspectorProperty(order = 3, label = "REF HEIGHT", min = 120.0f, max = 2160.0f, step = 10.0f)
    public float referenceHeight = 720.0f;

    /** Higher canvases draw on top of lower ones within the same render mode. */
    @InspectorProperty(order = 4, label = "SORT ORDER", min = -100.0f, max = 100.0f, step = 1.0f)
    public float sortOrder = 0.0f;

    /** World-space size of one reference pixel for billboards (0.01 = a 1280px canvas spans 12.8 units). */
    @InspectorProperty(order = 5, label = "WORLD SCALE", min = 0.0005f, max = 0.1f, step = 0.0005f)
    public float worldUnitsPerPixel = 0.01f;

    @Override
    public String getComponentName() {
        return "UICanvas";
    }
}
