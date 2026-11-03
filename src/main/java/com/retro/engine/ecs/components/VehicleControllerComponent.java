package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;

/**
 * ECS component holding Vice City 4-wheel raycast suspension vehicle dynamics.
 */
public class VehicleControllerComponent extends Component {

    @InspectorProperty(order = 0, label = "ENGINE TORQUE", min = 10.0f, max = 2000.0f, step = 10.0f)
    public float engineTorque = 120.0f;

    @InspectorProperty(order = 1, label = "MAX SPEED", min = 5.0f, max = 200.0f, step = 5.0f)
    public float maxSpeed = 45.0f;

    @InspectorProperty(order = 2, label = "STEER ANGLE", min = 5.0f, max = 60.0f, step = 1.0f)
    public float steeringAngle = 32.0f;

    @InspectorProperty(order = 3, label = "BRAKE FORCE", min = 0.0f, max = 1000.0f, step = 10.0f)
    public float brakeForce = 80.0f;

    @InspectorProperty(order = 4, label = "SUSP STIFF", min = 5.0f, max = 500.0f, step = 5.0f)
    public float suspensionStiffness = 85.0f;

    @InspectorProperty(order = 5, label = "SUSP DAMP", min = 0.5f, max = 100.0f, step = 0.5f)
    public float suspensionDamping = 12.0f;

    @InspectorProperty(order = 6, label = "REST LENGTH", min = 0.1f, max = 2.0f, step = 0.05f)
    public float suspensionRestLength = 0.6f;

    @InspectorProperty(order = 7, label = "WHEEL RADIUS", min = 0.1f, max = 2.0f, step = 0.05f)
    public float wheelRadius = 0.35f;

    @InspectorProperty(order = 8, label = "TRACK WIDTH", min = 0.5f, max = 5.0f, step = 0.1f)
    public float wheelTrackWidth = 1.6f;

    @InspectorProperty(order = 9, label = "WHEEL BASE", min = 0.5f, max = 10.0f, step = 0.1f)
    public float wheelBase = 2.5f;

    // Runtime state
    public float currentSpeed = 0.0f;
    public float currentSteering = 0.0f;
    public boolean[] wheelGrounded = new boolean[4]; // FL, FR, RL, RR
    public float[] suspensionCompression = new float[4];

    public VehicleControllerComponent() {}

    @Override
    public String getComponentName() {
        return "Vehicle Controller";
    }
}
