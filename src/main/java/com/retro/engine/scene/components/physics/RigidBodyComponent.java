package com.retro.engine.scene.components.physics;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

/**
 * ECS component holding 3D rigid body dynamic parameters.
 */
public class RigidBodyComponent implements Component {

    public enum MotionType { STATIC, KINEMATIC, DYNAMIC }

    @InspectorProperty(order = 0, label = "MOTION", type = PropertyType.BUTTON_GROUP)
    public MotionType motionType = MotionType.DYNAMIC;

    @InspectorProperty(order = 1, label = "MASS", min = 0.1f, max = 1000.0f, step = 0.5f)
    public float mass = 1.0f;

    @InspectorProperty(order = 2, label = "GRAVITY", type = PropertyType.TOGGLE)
    public boolean useGravity = true;

    @InspectorProperty(order = 3, label = "DRAG", min = 0.0f, max = 10.0f, step = 0.05f)
    public float linearDrag = 0.05f;

    @InspectorProperty(order = 4, label = "ANG DRAG", min = 0.0f, max = 10.0f, step = 0.05f)
    public float angularDrag = 0.05f;

    @InspectorProperty(order = 5, label = "FRICTION", min = 0.0f, max = 1.0f, step = 0.05f)
    public float friction = 0.5f;

    @InspectorProperty(order = 6, label = "BOUNCE", min = 0.0f, max = 1.0f, step = 0.05f)
    public float restitution = 0.1f;

    public RigidBodyComponent() {}

    @Override
    public String getComponentName() {
        return "Rigid Body";
    }
}
