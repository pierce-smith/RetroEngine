package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;

/**
 * ECS component holding 3D rigid body dynamic parameters.
 */
public class RigidBodyComponent implements Component {

    public enum MotionType { STATIC, KINEMATIC, DYNAMIC }

    @InspectorProperty(label = "MOTION", type = PropertyType.BUTTON_GROUP)
    public MotionType motionType = MotionType.DYNAMIC;

    @InspectorProperty(label = "MASS", min = 0.1f, max = 1000.0f, step = 0.5f)
    public float mass = 1.0f;

    @InspectorProperty(label = "GRAVITY", type = PropertyType.TOGGLE)
    public boolean useGravity = true;

    @InspectorProperty(label = "DRAG", min = 0.0f, max = 10.0f, step = 0.05f)
    public float linearDrag = 0.05f;

    @InspectorProperty(label = "ANG DRAG", min = 0.0f, max = 10.0f, step = 0.05f)
    public float angularDrag = 0.05f;

    @InspectorProperty(label = "FRICTION", min = 0.0f, max = 1.0f, step = 0.05f)
    public float friction = 0.5f;

    @InspectorProperty(label = "BOUNCE", min = 0.0f, max = 1.0f, step = 0.05f)
    public float restitution = 0.1f;

    public RigidBodyComponent() {}

    @Override
    public String getComponentName() {
        return "Rigid Body";
    }
}
