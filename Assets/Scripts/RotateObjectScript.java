package com.retro.engine.scripting.user;

import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.Input;

/**
 * Example gameplay script that rotates an entity continuously on its Y axis.
 * Pressing Spacebar toggles the direction of rotation.
 */
public class RotateObjectScript extends EntityScript {

    private float rotationSpeed = 60.0f; // degrees per second
    private boolean spaceWasDown = false;

    @Override
    public void onStart() {
        logInfo("RotateObjectScript initialized on " + (entity != null ? entity.name : "null"));
    }

    @Override
    public void onUpdate(float deltaTime) {
        // GLFW_KEY_SPACE = 32
        boolean spaceIsDown = Input.isKeyDown(32);

        // Single-frame edge detection: reverse direction on initial press
        if (spaceIsDown && !spaceWasDown) {
            rotationSpeed = -rotationSpeed;
            logInfo("Spacebar pressed! Reversed rotation direction. Current speed: " + rotationSpeed);
        }
        spaceWasDown = spaceIsDown;

        // Rotate around Y axis
        rotate(0.0f, rotationSpeed * deltaTime, 0.0f);
    }

    @Override
    public void onDestroy() {
        logInfo("RotateObjectScript destroyed on " + (entity != null ? entity.name : "null"));
    }
}
