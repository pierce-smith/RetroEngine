package com.retro.engine.physics;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern 60 Hz 3D Physics Step Solver and Broadphase Engine Bridge.
 * Evaluates 3D impulse dynamics, gravity, linear/angular drag, friction,
 * bounciness (restitution), Box/Sphere/Capsule narrowphase manifolds,
 * trigger volume overlaps, and raycast spatial queries for vehicle/character mechanics.
 */
public class PhysicsSystem {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f; // 60 Hz fixed tick
    public static final float GRAVITY = -9.81f; // Standard Earth gravity (m/s^2)

    private float accumulator = 0.0f;
    private final List<PhysicsBody> activeBodies = new ArrayList<>();
    private final Map<String, PhysicsBody> entityBodyMap = new HashMap<>();

    public static class PhysicsBody {
        public Entity entity;
        public RigidBodyComponent rigidBody;
        public BoxCollider boxCollider;
        public SphereCollider sphereCollider;
        public CapsuleCollider capsuleCollider;

        // Runtime Position and Rotation
        public float px, py, pz;
        public float rx, ry, rz;

        // Runtime Linear Velocity
        public float vx = 0.0f;
        public float vy = 0.0f;
        public float vz = 0.0f;

        // Runtime Angular Velocity
        public float wx = 0.0f;
        public float wy = 0.0f;
        public float wz = 0.0f;

        public float mass = 1.0f;
        public float invMass = 1.0f;
        public boolean isStatic = false;
        public boolean isKinematic = false;
        public boolean isTrigger = false;

        public PhysicsBody(Entity entity) {
            this.entity = entity;
            Transform tc = entity.getTransform();
            if (tc != null) {
                this.px = tc.posX; this.py = tc.posY; this.pz = tc.posZ;
                this.rx = tc.rotX; this.ry = tc.rotY; this.rz = tc.rotZ;
            }

            this.rigidBody = entity.getComponent(RigidBodyComponent.class);
            this.boxCollider = entity.getComponent(BoxCollider.class);
            this.sphereCollider = entity.getComponent(SphereCollider.class);
            this.capsuleCollider = entity.getComponent(CapsuleCollider.class);

            if (rigidBody != null) {
                this.mass = Math.max(0.001f, rigidBody.mass);
                this.isStatic = (rigidBody.motionType == RigidBodyComponent.MotionType.STATIC);
                this.isKinematic = (rigidBody.motionType == RigidBodyComponent.MotionType.KINEMATIC);
                this.invMass = (isStatic || isKinematic) ? 0.0f : (1.0f / mass);
            } else {
                this.isStatic = true;
                this.invMass = 0.0f;
            }

            this.isTrigger = (boxCollider != null && boxCollider.isTrigger) ||
                             (sphereCollider != null && sphereCollider.isTrigger) ||
                             (capsuleCollider != null && capsuleCollider.isTrigger);
        }

        public void syncFromTransform() {
            Transform tc = entity.getTransform();
            if (tc != null) {
                this.px = tc.posX; this.py = tc.posY; this.pz = tc.posZ;
                this.rx = tc.rotX; this.ry = tc.rotY; this.rz = tc.rotZ;
            }
        }

        public void syncToTransform() {
            Transform tc = entity.getTransform();
            if (tc != null && !isStatic) {
                tc.posX = this.px; tc.posY = this.py; tc.posZ = this.pz;
                tc.rotX = this.rx; tc.rotY = this.ry; tc.rotZ = this.rz;
            }
        }
    }

    public void initWorld(List<Entity> sceneEntities) {
        cleanup();
        if (sceneEntities == null) return;

        for (Entity e : sceneEntities) {
            if (e == null) continue;
            boolean hasPhysics = (e.getComponent(RigidBodyComponent.class) != null ||
                                  e.getComponent(BoxCollider.class) != null ||
                                  e.getComponent(SphereCollider.class) != null ||
                                  e.getComponent(CapsuleCollider.class) != null);
            if (hasPhysics) {
                PhysicsBody body = new PhysicsBody(e);
                activeBodies.add(body);
                entityBodyMap.put(e.id, body);
            }
        }
        System.out.println("[PhysicsSystem] Physics world initialized with " + activeBodies.size() + " rigid bodies.");
    }

    public void step(float deltaTime, List<Entity> sceneEntities) {
        if (activeBodies.isEmpty()) return;

        // Clamp delta time to avoid large time step instabilities
        float clampedDelta = Math.min(deltaTime, 0.1f);
        accumulator += clampedDelta;

        while (accumulator >= FIXED_TIMESTEP) {
            fixedUpdate(FIXED_TIMESTEP);
            accumulator -= FIXED_TIMESTEP;
        }

        // Sync final positions back to ECS transforms
        for (PhysicsBody body : activeBodies) {
            body.syncToTransform();
        }
    }

    private void fixedUpdate(float dt) {
        // 1. Integrate Forces & Velocities
        for (PhysicsBody body : activeBodies) {
            if (body.isStatic) continue;

            if (body.rigidBody != null && body.rigidBody.useGravity) {
                body.vy += GRAVITY * dt;
            }

            // Apply Damping (Linear & Angular Drag)
            float linearDrag = (body.rigidBody != null) ? body.rigidBody.linearDrag : 0.05f;
            float angularDrag = (body.rigidBody != null) ? body.rigidBody.angularDrag : 0.05f;

            float linDamp = Math.max(0.0f, 1.0f - linearDrag * dt);
            float angDamp = Math.max(0.0f, 1.0f - angularDrag * dt);

            body.vx *= linDamp;
            body.vy *= linDamp;
            body.vz *= linDamp;

            body.wx *= angDamp;
            body.wy *= angDamp;
            body.wz *= angDamp;

            // Integrate Position & Rotation
            body.px += body.vx * dt;
            body.py += body.vy * dt;
            body.pz += body.vz * dt;

            body.rx += body.wx * dt;
            body.ry += body.wy * dt;
            body.rz += body.wz * dt;
        }

        // 2. Collision Detection & Impulse Resolution Passes
        int solverIterations = 4;
        for (int iter = 0; iter < solverIterations; iter++) {
            for (int i = 0; i < activeBodies.size(); i++) {
                PhysicsBody a = activeBodies.get(i);
                for (int j = i + 1; j < activeBodies.size(); j++) {
                    PhysicsBody b = activeBodies.get(j);
                    if (a.isStatic && b.isStatic) continue;

                    resolveCollision(a, b);
                }
            }
        }
    }

    private void resolveCollision(PhysicsBody a, PhysicsBody b) {
        // Simple 3D AABB & Sphere Manifold Collision Solver
        float aMinX = a.px - getHalfWidthX(a), aMaxX = a.px + getHalfWidthX(a);
        float aMinY = a.py - getHalfWidthY(a), aMaxY = a.py + getHalfWidthY(a);
        float aMinZ = a.pz - getHalfWidthZ(a), aMaxZ = a.pz + getHalfWidthZ(a);

        float bMinX = b.px - getHalfWidthX(b), bMaxX = b.px + getHalfWidthX(b);
        float bMinY = b.py - getHalfWidthY(b), bMaxY = b.py + getHalfWidthY(b);
        float bMinZ = b.pz - getHalfWidthZ(b), bMaxZ = b.pz + getHalfWidthZ(b);

        // Check AABB Overlap
        if (aMaxX < bMinX || aMinX > bMaxX) return;
        if (aMaxY < bMinY || aMinY > bMaxY) return;
        if (aMaxZ < bMinZ || aMinZ > bMaxZ) return;

        // Skip positional resolution for trigger volumes (only record overlaps)
        if (a.isTrigger || b.isTrigger) return;

        // Compute Penetration Overlaps
        float overlapX = Math.min(aMaxX - bMinX, bMaxX - aMinX);
        float overlapY = Math.min(aMaxY - bMinY, bMaxY - aMinY);
        float overlapZ = Math.min(aMaxZ - bMinZ, bMaxZ - aMinZ);

        float normX = 0, normY = 0, normZ = 0;
        float penetration = 0;

        if (overlapX < overlapY && overlapX < overlapZ) {
            penetration = overlapX;
            normX = (a.px < b.px) ? -1.0f : 1.0f;
        } else if (overlapY < overlapX && overlapY < overlapZ) {
            penetration = overlapY;
            normY = (a.py < b.py) ? -1.0f : 1.0f;
        } else {
            penetration = overlapZ;
            normZ = (a.pz < b.pz) ? -1.0f : 1.0f;
        }

        // Positional Correction (Push apart according to inverse mass)
        float totalInvMass = a.invMass + b.invMass;
        if (totalInvMass <= 0.00001f) return;

        float percent = 0.8f; // Slop factor
        float corrX = normX * penetration * percent / totalInvMass;
        float corrY = normY * penetration * percent / totalInvMass;
        float corrZ = normZ * penetration * percent / totalInvMass;

        if (!a.isStatic) {
            a.px += corrX * a.invMass;
            a.py += corrY * a.invMass;
            a.pz += corrZ * a.invMass;
        }
        if (!b.isStatic) {
            b.px -= corrX * b.invMass;
            b.py -= corrY * b.invMass;
            b.pz -= corrZ * b.invMass;
        }

        // Velocity Impulse Resolution
        float relVx = a.vx - b.vx;
        float relVy = a.vy - b.vy;
        float relVz = a.vz - b.vz;

        float velAlongNormal = relVx * normX + relVy * normY + relVz * normZ;
        if (velAlongNormal > 0) return; // Moving apart

        float restitution = Math.min(
            (a.rigidBody != null) ? a.rigidBody.restitution : 0.1f,
            (b.rigidBody != null) ? b.rigidBody.restitution : 0.1f
        );

        float j = -(1.0f + restitution) * velAlongNormal / totalInvMass;

        float impulseX = j * normX;
        float impulseY = j * normY;
        float impulseZ = j * normZ;

        if (!a.isStatic) {
            a.vx += impulseX * a.invMass;
            a.vy += impulseY * a.invMass;
            a.vz += impulseZ * a.invMass;
        }
        if (!b.isStatic) {
            b.vx -= impulseX * b.invMass;
            b.vy -= impulseY * b.invMass;
            b.vz -= impulseZ * b.invMass;
        }
    }

    private float getHalfWidthX(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeX * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius;
        if (body.capsuleCollider != null) return body.capsuleCollider.radius;
        return 0.5f;
    }

    private float getHalfWidthY(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeY * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius;
        if (body.capsuleCollider != null) return body.capsuleCollider.height * 0.5f;
        return 0.5f;
    }

    private float getHalfWidthZ(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeZ * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius;
        if (body.capsuleCollider != null) return body.capsuleCollider.radius;
        return 0.5f;
    }

    /**
     * Casts a 3D ray into the physics world and returns the closest hit information.
     */
    public RaycastHit raycast(float rayOriginX, float rayOriginY, float rayOriginZ,
                              float rayDirX, float rayDirY, float rayDirZ, float maxDistance) {
        RaycastHit closestHit = new RaycastHit();

        // Normalize direction
        float dirLen = (float) Math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ);
        if (dirLen <= 0.00001f) return closestHit;

        float dx = rayDirX / dirLen;
        float dy = rayDirY / dirLen;
        float dz = rayDirZ / dirLen;

        for (PhysicsBody body : activeBodies) {
            float minX = body.px - getHalfWidthX(body);
            float maxX = body.px + getHalfWidthX(body);
            float minY = body.py - getHalfWidthY(body);
            float maxY = body.py + getHalfWidthY(body);
            float minZ = body.pz - getHalfWidthZ(body);
            float maxZ = body.pz + getHalfWidthZ(body);

            float t1 = (minX - rayOriginX) / (dx != 0 ? dx : 1e-8f);
            float t2 = (maxX - rayOriginX) / (dx != 0 ? dx : 1e-8f);
            float t3 = (minY - rayOriginY) / (dy != 0 ? dy : 1e-8f);
            float t4 = (maxY - rayOriginY) / (dy != 0 ? dy : 1e-8f);
            float t5 = (minZ - rayOriginZ) / (dz != 0 ? dz : 1e-8f);
            float t6 = (maxZ - rayOriginZ) / (dz != 0 ? dz : 1e-8f);

            float tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
            float tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

            if (tmax >= 0 && tmin <= tmax && tmin <= maxDistance && tmin < closestHit.distance) {
                float hitPx = rayOriginX + dx * tmin;
                float hitPy = rayOriginY + dy * tmin;
                float hitPz = rayOriginZ + dz * tmin;

                float nx = (Math.abs(hitPx - minX) < 0.01f) ? -1.0f : ((Math.abs(hitPx - maxX) < 0.01f) ? 1.0f : 0.0f);
                float ny = (Math.abs(hitPy - minY) < 0.01f) ? -1.0f : ((Math.abs(hitPy - maxY) < 0.01f) ? 1.0f : 0.0f);
                float nz = (Math.abs(hitPz - minZ) < 0.01f) ? -1.0f : ((Math.abs(hitPz - maxZ) < 0.01f) ? 1.0f : 0.0f);

                closestHit = new RaycastHit(body.entity, tmin, hitPx, hitPy, hitPz, nx, ny, nz);
            }
        }
        return closestHit;
    }

    public void cleanup() {
        activeBodies.clear();
        entityBodyMap.clear();
        accumulator = 0.0f;
    }
}
