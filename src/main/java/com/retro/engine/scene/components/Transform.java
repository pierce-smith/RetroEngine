package com.retro.engine.scene.components;

import com.retro.engine.scene.components.Component;

/**
 * Encapsulates 3D position, rotation (degrees), and scale.
 */
public class Transform implements Component {
    public float posX = 0.0f, posY = 0.0f, posZ = 0.0f;
    public float rotX = 0.0f, rotY = 0.0f, rotZ = 0.0f;
    public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;

    public Transform() {}

    public Transform(float px, float py, float pz) {
        this.posX = px;
        this.posY = py;
        this.posZ = pz;
    }

    @Override
    public String getComponentName() {
        return "Transform";
    }
}
