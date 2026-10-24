package com.retro.engine.scripting.user;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scripting.EntityScript;

/**
 * Example gameplay script that rotates an entity continuously on its Y axis.
 * Hardened with post-migration sub-package imports for Entity and Transform.
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
                tc.setRotation(tc.rotX, (tc.rotY + rotationSpeed * deltaTime) % 360.0f, tc.rotZ);
            }
        }
    }

    @Override
    public void onDestroy() {
        System.out.println("[RotateObjectScript] Script destroyed on Entity '" + (entity != null ? entity.name : "null") + "'");
    }
}
