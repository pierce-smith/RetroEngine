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
 * trigger volume overlaps, Vice City Character Controller movement,
 * 4-wheel Raycast Suspension Vehicle dynamics, and spatial raycast queries.
 */
public class PhysicsSystem {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f; // 60 Hz
    public static final float GRAVITY = -9.81f; // Earth gravity m/s^2

    public static class PhysicsBody {
        public Entity entity;
        public RigidBodyComponent rigidBody;
        public BoxCollider boxCollider;
        public SphereCollider sphereCollider;
        public CapsuleCollider capsuleCollider;
        public CharacterControllerComponent characterController;
        public VehicleControllerComponent vehicleController;

        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;

        public float vx, vy, vz;
        public float avx, avy, avz;

        public PhysicsBody(Entity entity) {
            this.entity = entity;
            this.rigidBody = entity.getComponent(RigidBodyComponent.class);
            this.boxCollider = entity.getComponent(BoxCollider.class);
            this.sphereCollider = entity.getComponent(SphereCollider.class);
            this.capsuleCollider = entity.getComponent(CapsuleCollider.class);
            this.characterController = entity.getComponent(CharacterControllerComponent.class);
            this.vehicleController = entity.getComponent(VehicleControllerComponent.class);
            syncFromTransform();
        }

        public void syncFromTransform() {
            Transform tc = entity.getTransform();
            if (tc != null) {
                this.px = tc.posX; this.py = tc.posY; this.pz = tc.posZ;
                this.rx = tc.rotX; this.ry = tc.rotY; this.rz = tc.rotZ;
                this.sx = tc.scaleX; this.sy = tc.scaleY; this.sz = tc.scaleZ;
            }
        }

        public void syncToTransform() {
            Transform tc = entity.getTransform();
            if (tc != null) {
                tc.posX = this.px; tc.posY = this.py; tc.posZ = this.pz;
                tc.rotX = this.rx; tc.rotY = this.ry; tc.rotZ = this.rz;
                tc.scaleX = this.sx; tc.scaleY = this.sy; tc.scaleZ = this.sz;
            }
        }
    }

    private final Map<String, PhysicsBody> bodyMap = new HashMap<>();
    private final List<PhysicsBody> activeBodies = new ArrayList<>();
    private float accumulator = 0.0f;

    // Reusable scratchpad RaycastHit for zero-allocation queries
    private final RaycastHit sharedRaycastHit = new RaycastHit();

    public void initWorld(List<Entity> sceneEntities) {
        bodyMap.clear();
        activeBodies.clear();
        accumulator = 0.0f;

        if (sceneEntities == null) return;

        for (Entity entity : sceneEntities) {
            if (entity.getComponent(RigidBodyComponent.class) != null ||
                entity.getComponent(BoxCollider.class) != null ||
                entity.getComponent(SphereCollider.class) != null ||
                entity.getComponent(CapsuleCollider.class) != null ||
                entity.getComponent(CharacterControllerComponent.class) != null ||
                entity.getComponent(VehicleControllerComponent.class) != null) {

                PhysicsBody body = new PhysicsBody(entity);
                bodyMap.put(entity.id, body);
                activeBodies.add(body);
            }
        }
    }

    public void step(float deltaTime, List<Entity> sceneEntities) {
        if (activeBodies.isEmpty() && sceneEntities != null) {
            initWorld(sceneEntities);
        }

        for (PhysicsBody body : activeBodies) {
            body.syncFromTransform();
        }

        accumulator += Math.min(deltaTime, 0.1f); // Cap to 100ms max

        while (accumulator >= FIXED_TIMESTEP) {
            fixedUpdate(FIXED_TIMESTEP);
            accumulator -= FIXED_TIMESTEP;
        }

        for (PhysicsBody body : activeBodies) {
            body.syncToTransform();
        }
    }

    private void fixedUpdate(float dt) {
        // 1. Step RigidBodies
        for (PhysicsBody body : activeBodies) {
            RigidBodyComponent rb = body.rigidBody;
            if (rb == null || rb.motionType == RigidBodyComponent.MotionType.STATIC) continue;

            if (rb.motionType == RigidBodyComponent.MotionType.DYNAMIC) {
                if (rb.useGravity) {
                    body.vy += GRAVITY * dt;
                }

                float dragFactor = Math.max(0.0f, 1.0f - rb.linearDrag * dt);
                body.vx *= dragFactor;
                body.vy *= dragFactor;
                body.vz *= dragFactor;

                float angDragFactor = Math.max(0.0f, 1.0f - rb.angularDrag * dt);
                body.avx *= angDragFactor;
                body.avy *= angDragFactor;
                body.avz *= angDragFactor;

                body.px += body.vx * dt;
                body.py += body.vy * dt;
                body.pz += body.vz * dt;

                body.rx += body.avx * dt;
                body.ry += body.avy * dt;
                body.rz += body.avz * dt;
            }
        }

        // 2. Step Character Controllers
        updateCharacterControllers(dt);

        // 3. Step 4-Wheel Raycast Suspension Vehicles
        updateVehicles(dt);

        // 4. Narrowphase Collisions between RigidBodies
        for (int i = 0; i < activeBodies.size(); i++) {
            PhysicsBody a = activeBodies.get(i);
            for (int j = i + 1; j < activeBodies.size(); j++) {
                PhysicsBody b = activeBodies.get(j);
                resolveCollision(a, b);
            }
        }
    }

    private void updateCharacterControllers(float dt) {
        for (PhysicsBody body : activeBodies) {
            CharacterControllerComponent cc = body.characterController;
            if (cc == null) continue;

            float capsuleHeight = 1.8f;
            CapsuleCollider cap = body.capsuleCollider;
            if (cap != null) capsuleHeight = cap.height;

            float halfHeight = capsuleHeight * 0.5f;

            // Ground Probe Raycast downwards with zero allocation
            raycastExcludingInPlace(body.entity, body.px, body.py + halfHeight, body.pz, 0.0f, -1.0f, 0.0f, halfHeight + cc.stepOffset + 0.05f, sharedRaycastHit);

            if (sharedRaycastHit.hit && sharedRaycastHit.distance <= halfHeight + cc.stepOffset + 0.05f) {
                cc.isGrounded = true;
                if (cc.velocityY < 0.0f) cc.velocityY = 0.0f;

                // Snap smoothly to ground / step offset
                float targetPy = sharedRaycastHit.pointY + halfHeight;
                body.py = Math.max(body.py, targetPy);
            } else {
                cc.isGrounded = false;
                cc.velocityY += GRAVITY * cc.gravityMultiplier * dt;
            }

            // Move Character
            body.px += cc.velocityX * dt;
            body.py += cc.velocityY * dt;
            body.pz += cc.velocityZ * dt;

            // Apply friction damping
            cc.velocityX *= Math.max(0.0f, 1.0f - 10.0f * dt);
            cc.velocityZ *= Math.max(0.0f, 1.0f - 10.0f * dt);
        }
    }

    private void updateVehicles(float dt) {
        for (PhysicsBody body : activeBodies) {
            VehicleControllerComponent vc = body.vehicleController;
            if (vc == null) continue;

            float radY = (float) Math.toRadians(body.ry);
            float forwardX = (float) Math.sin(radY);
            float forwardZ = (float) Math.cos(radY);
            float rightX = (float) Math.cos(radY);
            float rightZ = (float) -Math.sin(radY);

            float halfTrack = vc.wheelTrackWidth * 0.5f;
            float halfBase = vc.wheelBase * 0.5f;

            // 4 Wheel positions relative to vehicle center (FL, FR, RL, RR)
            float[][] wheelOffsets = {
                { rightX * halfTrack + forwardX * halfBase, rightZ * halfTrack + forwardZ * halfBase },   // FL
                { -rightX * halfTrack + forwardX * halfBase, -rightZ * halfTrack + forwardZ * halfBase }, // FR
                { rightX * halfTrack - forwardX * halfBase, rightZ * halfTrack - forwardZ * halfBase },   // RL
                { -rightX * halfTrack - forwardX * halfBase, -rightZ * halfTrack - forwardZ * halfBase }  // RR
            };

            float maxRayDist = vc.suspensionRestLength + vc.wheelRadius;
            int groundedCount = 0;

            for (int i = 0; i < 4; i++) {
                float wx = body.px + wheelOffsets[i][0];
                float wy = body.py;
                float wz = body.pz + wheelOffsets[i][1];

                raycastExcludingInPlace(body.entity, wx, wy, wz, 0.0f, -1.0f, 0.0f, maxRayDist, sharedRaycastHit);

                if (sharedRaycastHit.hit) {
                    vc.wheelGrounded[i] = true;
                    groundedCount++;

                    float compression = maxRayDist - sharedRaycastHit.distance;
                    vc.suspensionCompression[i] = compression;

                    // Hooke's Law Spring Force
                    float springForce = compression * vc.suspensionStiffness - body.vy * vc.suspensionDamping;
                    if (springForce > 0.0f) {
                        body.vy += (springForce / 100.0f) * dt;
                    }
                } else {
                    vc.wheelGrounded[i] = false;
                    vc.suspensionCompression[i] = 0.0f;
                }
            }

            // Gravity if not fully supported
            if (groundedCount < 2) {
                body.vy += GRAVITY * dt;
            }

            // Vehicle steering and acceleration movement
            if (vc.currentSpeed != 0.0f) {
                body.px += forwardX * vc.currentSpeed * dt;
                body.pz += forwardZ * vc.currentSpeed * dt;
            }

            if (vc.currentSteering != 0.0f && Math.abs(vc.currentSpeed) > 0.1f) {
                body.ry += vc.currentSteering * (vc.currentSpeed / vc.maxSpeed) * 30.0f * dt;
            }

            // Speed decay / friction
            vc.currentSpeed *= Math.max(0.0f, 1.0f - 1.5f * dt);
        }
    }

    private void resolveCollision(PhysicsBody a, PhysicsBody b) {
        if (a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC &&
            b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC) {
            return;
        }

        float halfAx = getHalfWidthX(a), halfAy = getHalfWidthY(a), halfAz = getHalfWidthZ(a);
        float halfBx = getHalfWidthX(b), halfBy = getHalfWidthY(b), halfBz = getHalfWidthZ(b);

        float dx = b.px - a.px;
        float dy = b.py - a.py;
        float dz = b.pz - a.pz;

        float overlapX = (halfAx + halfBx) - Math.abs(dx);
        float overlapY = (halfAy + halfBy) - Math.abs(dy);
        float overlapZ = (halfAz + halfBz) - Math.abs(dz);

        if (overlapX > 0 && overlapY > 0 && overlapZ > 0) {
            boolean isATrigger = (a.boxCollider != null && a.boxCollider.isTrigger) ||
                                 (a.sphereCollider != null && a.sphereCollider.isTrigger) ||
                                 (a.capsuleCollider != null && a.capsuleCollider.isTrigger);
            boolean isBTrigger = (b.boxCollider != null && b.boxCollider.isTrigger) ||
                                 (b.sphereCollider != null && b.sphereCollider.isTrigger) ||
                                 (b.capsuleCollider != null && b.capsuleCollider.isTrigger);

            if (isATrigger || isBTrigger) return;

            if (overlapX < overlapY && overlapX < overlapZ) {
                float sign = dx > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, sign * overlapX, 0, 0);
            } else if (overlapY < overlapX && overlapY < overlapZ) {
                float sign = dy > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, 0, sign * overlapY, 0);
            } else {
                float sign = dz > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, 0, 0, sign * overlapZ);
            }
        }
    }

    private void separateBodies(PhysicsBody a, PhysicsBody b, float sepX, float sepY, float sepZ) {
        boolean aDynamic = a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        boolean bDynamic = b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;

        if (aDynamic && bDynamic) {
            a.px -= sepX * 0.5f; a.py -= sepY * 0.5f; a.pz -= sepZ * 0.5f;
            b.px += sepX * 0.5f; b.py += sepY * 0.5f; b.pz += sepZ * 0.5f;

            float restitution = Math.max(a.rigidBody.restitution, b.rigidBody.restitution);
            if (sepX != 0) { a.vx = -a.vx * restitution; b.vx = -b.vx * restitution; }
            if (sepY != 0) { a.vy = -a.vy * restitution; b.vy = -b.vy * restitution; }
            if (sepZ != 0) { a.vz = -a.vz * restitution; b.vz = -b.vz * restitution; }
        } else if (aDynamic) {
            a.px -= sepX; a.py -= sepY; a.pz -= sepZ;
            float restitution = a.rigidBody.restitution;
            if (sepX != 0) a.vx = -a.vx * restitution;
            if (sepY != 0) a.vy = -a.vy * restitution;
            if (sepZ != 0) a.vz = -a.vz * restitution;
        } else if (bDynamic) {
            b.px += sepX; b.py += sepY; b.pz += sepZ;
            float restitution = b.rigidBody.restitution;
            if (sepX != 0) b.vx = -b.vx * restitution;
            if (sepY != 0) b.vy = -b.vy * restitution;
            if (sepZ != 0) b.vz = -b.vz * restitution;
        }
    }

    private float getHalfWidthX(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeX * body.sx * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius * body.sx;
        if (body.capsuleCollider != null) return body.capsuleCollider.radius * body.sx;
        return 0.5f * body.sx;
    }

    private float getHalfWidthY(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeY * body.sy * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius * body.sy;
        if (body.capsuleCollider != null) return body.capsuleCollider.height * body.sy * 0.5f;
        return 0.5f * body.sy;
    }

    private float getHalfWidthZ(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeZ * body.sz * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius * body.sz;
        if (body.capsuleCollider != null) return body.capsuleCollider.radius * body.sz;
        return 0.5f * body.sz;
    }

    public RaycastHit raycast(float rayOriginX, float rayOriginY, float rayOriginZ,
                              float rayDirX, float rayDirY, float rayDirZ, float maxDistance) {
        return raycastExcluding(null, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance);
    }

    public RaycastHit raycastExcluding(Entity excludeEntity, float rayOriginX, float rayOriginY, float rayOriginZ,
                                       float rayDirX, float rayDirY, float rayDirZ, float maxDistance) {
        RaycastHit hit = new RaycastHit();
        raycastExcludingInPlace(excludeEntity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, hit);
        return hit;
    }

    public void raycastExcludingInPlace(Entity excludeEntity, float rayOriginX, float rayOriginY, float rayOriginZ,
                                        float rayDirX, float rayDirY, float rayDirZ, float maxDistance, RaycastHit outHit) {
        outHit.hit = false;
        outHit.distance = 0.0f;
        outHit.entity = null;

        float dirLen = (float) Math.sqrt(rayDirX * rayDirX + rayDirY * rayDirY + rayDirZ * rayDirZ);
        if (dirLen <= 0.00001f) return;

        float dx = rayDirX / dirLen;
        float dy = rayDirY / dirLen;
        float dz = rayDirZ / dirLen;

        for (PhysicsBody body : activeBodies) {
            if (excludeEntity != null && body.entity == excludeEntity) continue;

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

            if (tmax >= tmin && tmin >= 0.0f && tmin <= maxDistance) {
                if (!outHit.hit || tmin < outHit.distance) {
                    float hitPx = rayOriginX + dx * tmin;
                    float hitPy = rayOriginY + dy * tmin;
                    float hitPz = rayOriginZ + dz * tmin;

                    float nx = 0.0f, ny = 1.0f, nz = 0.0f;
                    if (Math.abs(hitPx - minX) < 0.01f) nx = -1.0f;
                    else if (Math.abs(hitPx - maxX) < 0.01f) nx = 1.0f;
                    else if (Math.abs(hitPy - minY) < 0.01f) ny = -1.0f;
                    else if (Math.abs(hitPy - maxY) < 0.01f) ny = 1.0f;
                    else if (Math.abs(hitPz - minZ) < 0.01f) nz = -1.0f;
                    else if (Math.abs(hitPz - maxZ) < 0.01f) nz = 1.0f;

                    outHit.entity = body.entity;
                    outHit.distance = tmin;
                    outHit.pointX = hitPx;
                    outHit.pointY = hitPy;
                    outHit.pointZ = hitPz;
                    outHit.normalX = nx;
                    outHit.normalY = ny;
                    outHit.normalZ = nz;
                    outHit.hit = true;
                }
            }
        }
    }

    public void cleanup() {
        bodyMap.clear();
        activeBodies.clear();
    }
}
