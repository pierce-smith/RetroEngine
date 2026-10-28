package com.retro.engine.scripting.user;

import com.retro.engine.scripting.EntityScript;

/**
 * Starter test script: spins its entity around the Y axis.
 */
public class StarterRotateScript extends EntityScript {

    private float rotationSpeed = 45.0f; // degrees per second

    @Override
    public void onStart() {
        logInfo("StarterRotateScript attached to " + (entity != null ? entity.name : "null"));
    }

    @Override
    public void onUpdate(float deltaTime) {
        rotate(0.0f, rotationSpeed * deltaTime, 0.0f);
    }
}
