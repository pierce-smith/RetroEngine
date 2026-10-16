package com.retro.engine.scene.components;

import com.retro.engine.scene.Entity;

import com.retro.engine.scene.components.Component;

/**
 * Encapsulates perspective 3D camera properties attached to a Entity.
 */
public class Camera implements Component {
    public float fov = 50.0f;
    public float nearPlane = 0.1f;
    public float farPlane = 1000.0f;
    public boolean isPrimary = true;

    public Camera() {}

    public Camera(float fov, float nearPlane, float farPlane) {
        this.fov = fov;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    @Override
    public String getComponentName() {
        return "Camera";
    }
}
