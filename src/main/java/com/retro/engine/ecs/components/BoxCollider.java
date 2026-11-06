package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

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

    @InspectorProperty(order = 7, label = "LAYER", min = 0, max = 31, step = 1, since = 10, prefabSince = 9)
    public int collisionLayer = 0;

    /** Layer numbers this collider touches, such as "0,3". Empty means every layer. */
    @InspectorProperty(order = 8, label = "COLLIDES WITH", since = 10, prefabSince = 9)
    public String collidesWith = "";

    public BoxCollider() {}

    @Override
    public String getComponentName() {
        return "Box Collider";
    }
}
