package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;

/**
 * Encapsulates perspective 3D camera properties attached to a SceneEntity.
 */
public class CameraComponent implements Component {
    public float fov = 50.0f;
    public float nearPlane = 0.1f;
    public float farPlane = 1000.0f;
    public boolean isPrimary = true;

    public CameraComponent() {}

    public CameraComponent(float fov, float nearPlane, float farPlane) {
        this.fov = fov;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    @Override
    public String getComponentName() {
        return "Camera";
    }
}
