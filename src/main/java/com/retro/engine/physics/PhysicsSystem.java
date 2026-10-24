package com.retro.engine.physics;

import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;
import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern 60 Hz 3D Physics Step Solver Orchestrator.
 * Fully SRP-aligned facade delegating to specialized domain solvers:
 * - BroadphaseSolver: Zero-allocation Dynamic AABB Tree (BVH) spatial index
 * - NarrowphaseSolver: Collision shape overlap manifolds & positional separation
 * - CharacterControllerSolver: Vice City ground snapping & movement stepping
 * - VehicleSuspensionSolver: 4-wheel raycast suspension & vehicle dynamics
 * Hardened with selective kinematic/scripted transform synchronization guards.
 */
public class PhysicsSystem {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f; // 60 Hz
    public static final float GRAVITY = -9.81f; // m/s^2
    public static final int MAX_PHYSICS_STEPS = 2; // Guard against accumulator catch-up spirals

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

        public int treeNodeId = -1; // BVH Node Handle

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

    public static class BodyPair {
        public PhysicsBody bodyA;
        public PhysicsBody bodyB;
    }

    private final Map<String, PhysicsBody> bodyMap = new HashMap<>();
    private final List<PhysicsBody> activeBodies = new ArrayList<>();

    // Domain Solvers
    private final BroadphaseSolver broadphaseSolver = new BroadphaseSolver();
    private final NarrowphaseSolver narrowphaseSolver = new NarrowphaseSolver();
    private final CharacterControllerSolver characterSolver = new CharacterControllerSolver();
    private final VehicleSuspensionSolver vehicleSolver = new VehicleSuspensionSolver();

    private final List<BodyPair> broadphasePairs = new ArrayList<>();
    private int activePairCount = 0;

    private float accumulator = 0.0f;
    private final RaycastHit sharedRaycastHit = new RaycastHit();

    public BroadphaseSolver getBroadphaseSolver() {
        return broadphaseSolver;
    }

    public void initWorld(List<Entity> sceneEntities) {
        bodyMap.clear();
        activeBodies.clear();
        broadphaseSolver.clear();
        broadphasePairs.clear();
        activePairCount = 0;
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

                float minX = body.px - getHalfWidthX(body);
                float maxX = body.px + getHalfWidthX(body);
                float minY = body.py - getHalfWidthY(body);
                float maxY = body.py + getHalfWidthY(body);
                float minZ = body.pz - getHalfWidthZ(body);
                float maxZ = body.pz + getHalfWidthZ(body);
                body.treeNodeId = broadphaseSolver.insertLeaf(body, minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
    }

    public void step(float deltaTime, List<Entity> sceneEntities) {
        if (activeBodies.isEmpty() && sceneEntities != null) {
            initWorld(sceneEntities);
        }

        for (PhysicsBody body : activeBodies) {
            body.syncFromTransform();
            if (body.treeNodeId != -1) {
                float minX = body.px - getHalfWidthX(body);
                float maxX = body.px + getHalfWidthX(body);
                float minY = body.py - getHalfWidthY(body);
                float maxY = body.py + getHalfWidthY(body);
                float minZ = body.pz - getHalfWidthZ(body);
                float maxZ = body.pz + getHalfWidthZ(body);
                broadphaseSolver.updateLeaf(body.treeNodeId, minX, minY, minZ, maxX, maxY, maxZ);
            }
        }

        accumulator += Math.min(deltaTime, 0.1f);

        int steps = 0;
        boolean physicsRan = false;
        while (accumulator >= FIXED_TIMESTEP && steps < MAX_PHYSICS_STEPS) {
            fixedUpdate(FIXED_TIMESTEP);
            accumulator -= FIXED_TIMESTEP;
            steps++;
            physicsRan = true;
        }

        if (steps >= MAX_PHYSICS_STEPS) {
            accumulator = 0.0f; // Flush residual accumulator to prevent catch-up spirals
        }

        // Only write back to transform if physics solver actually stepped,
        // and only for DYNAMIC bodies or active character/vehicle controllers
        if (physicsRan) {
            for (PhysicsBody body : activeBodies) {
                if (body.rigidBody != null && body.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC) {
                    body.syncToTransform();
                } else if (body.characterController != null || body.vehicleController != null) {
                    body.syncToTransform();
                }
            }
        }
    }

    private void fixedUpdate(float dt) {
        // 1. Step RigidBodies Motion Integration
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

                if (body.treeNodeId != -1) {
                    float minX = body.px - getHalfWidthX(body);
                    float maxX = body.px + getHalfWidthX(body);
                    float minY = body.py - getHalfWidthY(body);
                    float maxY = body.py + getHalfWidthY(body);
                    float minZ = body.pz - getHalfWidthZ(body);
                    float maxZ = body.pz + getHalfWidthZ(body);
                    broadphaseSolver.updateLeaf(body.treeNodeId, minX, minY, minZ, maxX, maxY, maxZ);
                }
            }
        }

        // 2. Delegate to Character Controller Solver
        characterSolver.updateControllers(activeBodies, dt, this, sharedRaycastHit);

        // 3. Delegate to Vehicle Suspension Solver
        vehicleSolver.updateVehicles(activeBodies, dt, this, sharedRaycastHit);

        // 4. Delegate to Broadphase Spatial Solver
        activePairCount = broadphaseSolver.generateOverlapPairs(broadphasePairs);

        // 5. Delegate to Narrowphase Collision Solver
        for (int i = 0; i < activePairCount; i++) {
            BodyPair pair = broadphasePairs.get(i);
            if (pair.bodyA != null && pair.bodyB != null) {
                narrowphaseSolver.resolveCollision(pair.bodyA, pair.bodyB, this);
            }
        }
    }

    public float getHalfWidthX(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeX * body.sx * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius * body.sx;
        if (body.capsuleCollider != null) return body.capsuleCollider.radius * body.sx;
        return 0.5f * body.sx;
    }

    public float getHalfWidthY(PhysicsBody body) {
        if (body.boxCollider != null) return body.boxCollider.sizeY * body.sy * 0.5f;
        if (body.sphereCollider != null) return body.sphereCollider.radius * body.sy;
        if (body.capsuleCollider != null) return body.capsuleCollider.height * body.sy * 0.5f;
        return 0.5f * body.sy;
    }

    public float getHalfWidthZ(PhysicsBody body) {
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
        broadphaseSolver.raycastInPlace(excludeEntity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, outHit, this);
    }

    public void cleanup() {
        bodyMap.clear();
        activeBodies.clear();
        broadphaseSolver.clear();
        broadphasePairs.clear();
        activePairCount = 0;
    }
}
