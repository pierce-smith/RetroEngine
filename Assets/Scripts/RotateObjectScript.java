package com.retro.engine.scripting.user;

import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scene.components.Transform;

/**
 * Example gameplay script that rotates an entity continuously on its Y axis.
 */
public class RotateObjectScript extends EntityScript {

    private float rotationSpeed = 60.0f; // degrees per second

    @Override
    public void onStart() {
        System.out.println("[RotateObjectScript] Script initialized on Entity '" + (entity != null ? entity.name : "null") + "'");
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (entity != null) {
            Transform tc = entity.getTransform();
            if (tc != null) {
                tc.rotY = (tc.rotY + rotationSpeed * deltaTime) % 360.0f;
            }
        }
    }

    @Override
    public void onDestroy() {
        System.out.println("[RotateObjectScript] Script destroyed on Entity '" + (entity != null ? entity.name : "null") + "'");
    }
}
