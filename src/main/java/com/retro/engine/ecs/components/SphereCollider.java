package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * ECS Sphere Collider shape definition.
 */
@PublicApi
public class SphereCollider extends Component {

    @InspectorProperty(order = 0, label = "RADIUS", min = 0.01f, max = 100.0f, step = 0.1f)
    public float radius = 0.5f;

    @InspectorProperty(order = 1, label = "OFFSET X", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetX = 0.0f;

    @InspectorProperty(order = 2, label = "OFFSET Y", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetY = 0.0f;

    @InspectorProperty(order = 3, label = "OFFSET Z", min = -50.0f, max = 50.0f, step = 0.1f)
    public float offsetZ = 0.0f;

    @InspectorProperty(order = 4, label = "TRIGGER", type = PropertyType.TOGGLE)
    public boolean isTrigger = false;

    @InspectorProperty(order = 5, label = "LAYER", min = 0, max = 31, step = 1, since = 10, prefabSince = 9)
    public int collisionLayer = 0;

    /** Layer numbers this collider touches, such as "0,3". Empty means every layer. */
    @InspectorProperty(order = 6, label = "COLLIDES WITH", since = 10, prefabSince = 9)
    public String collidesWith = "";

    public SphereCollider() {}

    @Override
    public String getComponentName() {
        return "Sphere Collider";
    }
}
