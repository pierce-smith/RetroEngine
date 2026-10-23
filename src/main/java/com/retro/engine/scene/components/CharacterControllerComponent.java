package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;
import com.retro.engine.editor.ui.InspectorProperty.PropertyType;

/**
 * ECS component holding Vice City style 3D character controller movement & physics parameters.
 */
public class CharacterControllerComponent implements Component {

    @InspectorProperty(label = "MOVE SPEED", min = 0.1f, max = 50.0f, step = 0.5f)
    public float moveSpeed = 5.0f;

    @InspectorProperty(label = "JUMP FORCE", min = 0.0f, max = 30.0f, step = 0.5f)
    public float jumpForce = 6.5f;

    @InspectorProperty(label = "STEP OFFSET", min = 0.0f, max = 2.0f, step = 0.05f)
    public float stepOffset = 0.3f;

    @InspectorProperty(label = "SLOPE LIMIT", min = 0.0f, max = 89.0f, step = 1.0f)
    public float slopeLimit = 45.0f;

    @InspectorProperty(label = "GRAVITY MULT", min = 0.0f, max = 5.0f, step = 0.1f)
    public float gravityMultiplier = 1.0f;

    @InspectorProperty(label = "GROUNDED", type = PropertyType.TOGGLE)
    public boolean isGrounded = false;

    // Runtime state
    public float velocityX = 0.0f;
    public float velocityY = 0.0f;
    public float velocityZ = 0.0f;

    public CharacterControllerComponent() {}

    @Override
    public String getComponentName() {
        return "Character Controller";
    }
}
