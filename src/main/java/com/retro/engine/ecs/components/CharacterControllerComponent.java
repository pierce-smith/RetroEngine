package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * ECS component holding Vice City style 3D character controller movement & physics parameters.
 */
public class CharacterControllerComponent extends Component {

    @InspectorProperty(order = 0, label = "MOVE SPEED", min = 0.1f, max = 50.0f, step = 0.5f)
    public float moveSpeed = 5.0f;

    @InspectorProperty(order = 1, label = "JUMP FORCE", min = 0.0f, max = 30.0f, step = 0.5f)
    public float jumpForce = 6.5f;

    @InspectorProperty(order = 2, label = "STEP OFFSET", min = 0.0f, max = 2.0f, step = 0.05f)
    public float stepOffset = 0.3f;

    @InspectorProperty(order = 3, label = "SLOPE LIMIT", min = 0.0f, max = 89.0f, step = 1.0f)
    public float slopeLimit = 45.0f;

    @InspectorProperty(order = 4, label = "GRAVITY MULT", min = 0.0f, max = 5.0f, step = 0.1f)
    public float gravityMultiplier = 1.0f;

    @InspectorProperty(order = 5, label = "GROUNDED", type = PropertyType.TOGGLE)
    public boolean isGrounded = false;

    /** Applied to moveSpeed while sprint (gamepad X / Left Shift) is held. */
    @InspectorProperty(order = 6, label = "SPRINT MULT", min = 1.0f, max = 4.0f, step = 0.05f, since = 5, prefabSince = 4)
    public float sprintSpeedMultiplier = 1.75f;

    // Runtime state
    /** False while scripts suspend player control (cutscenes): WASD/stick, look and jump are ignored. Not persisted. */
    public boolean inputEnabled = true;
    public float velocityX = 0.0f;
    public float velocityY = 0.0f;
    public float velocityZ = 0.0f;

    public CharacterControllerComponent() {}

    @Override
    public String getComponentName() {
        return "Character Controller";
    }
}
