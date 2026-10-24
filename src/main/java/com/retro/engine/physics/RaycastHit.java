package com.retro.engine.physics;

import com.retro.engine.scene.entity.Entity;

/**
 * Encapsulates the results of a 3D raycast spatial query into the physics world.
 */
public class RaycastHit {
    public boolean hit = false;
    public Entity entity = null;
    public float distance = Float.MAX_VALUE;
    public float pointX = 0.0f;
    public float pointY = 0.0f;
    public float pointZ = 0.0f;
    public float normalX = 0.0f;
    public float normalY = 1.0f;
    public float normalZ = 0.0f;

    public RaycastHit() {}

    public RaycastHit(Entity entity, float distance, float px, float py, float pz, float nx, float ny, float nz) {
        this.hit = true;
        this.entity = entity;
        this.distance = distance;
        this.pointX = px;
        this.pointY = py;
        this.pointZ = pz;
        this.normalX = nx;
        this.normalY = ny;
        this.normalZ = nz;
    }
}
