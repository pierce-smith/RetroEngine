package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * ECS Capsule Collider shape definition.
 */
public class CapsuleCollider extends Component {

    @InspectorProperty(order = 0, label = "RADIUS", min = 0.01f, max = 50.0f, step = 0.1f)
    public float radius = 0.5f;

    @InspectorProperty(order = 1, label = "HEIGHT", min = 0.01f, max = 50.0f, step = 0.1f)
    public float height = 1.8f;

    @InspectorProperty(order = 2, label = "OFFSET X", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetX = 0.0f;

    @InspectorProperty(order = 3, label = "OFFSET Y", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetY = 0.0f;

    @InspectorProperty(order = 4, label = "OFFSET Z", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetZ = 0.0f;

    @InspectorProperty(order = 5, label = "TRIGGER", type = PropertyType.TOGGLE)
    public boolean isTrigger = false;

    @InspectorProperty(order = 6, label = "LAYER", min = 0, max = 31, step = 1, since = 10, prefabSince = 9)
    public int collisionLayer = 0;

    /** Layer numbers this collider touches, such as "0,3". Empty means every layer. */
    @InspectorProperty(order = 7, label = "COLLIDES WITH", since = 10, prefabSince = 9)
    public String collidesWith = "";

    public CapsuleCollider() {}

    @Override
    public String getComponentName() {
        return "Capsule Collider";
    }
}
