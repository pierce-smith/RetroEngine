package com.retro.engine.physics;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.modding.ModLoader;
import com.retro.engine.modding.events.TriggerEvent;
import com.retro.engine.scripting.ScriptEngine;

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
 * Hardened with physics lifecycle script updates and collision/trigger event dispatches.
 */
public class PhysicsSystem {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f; // 60 Hz
    public static final float GRAVITY = -9.81f; // m/s^2
    // MAX_FRAME_DELTA / FIXED_TIMESTEP: physics keeps pace with script time down to 10 FPS, and a bad frame can't spiral
    public static final int MAX_PHYSICS_STEPS = 6;

    public static class PhysicsBody {
        public Entity entity;
        public RigidBodyComponent rigidBody;
        public BoxCollider boxCollider;
        public SphereCollider sphereCollider;
        public CapsuleCollider capsuleCollider;
        public CharacterControllerComponent characterController;
        public VehicleControllerComponent vehicleController;
        /** Set when a NavMeshAgent steers this body's Character Controller instead of player input. */
        public boolean navDriven;

        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;

        public float vx, vy, vz;
        public float avx, avy, avz;

        public int treeNodeId = -1; // BVH Node Handle

        public PhysicsBody(Entity entity) {
            this.entity = entity;
            bindComponents();
            syncFromTransform();
        }

        /** Reads the entity's physics components. */
        void bindComponents() {
            this.rigidBody = entity.getComponent(RigidBodyComponent.class);
            this.boxCollider = entity.getComponent(BoxCollider.class);
            this.sphereCollider = entity.getComponent(SphereCollider.class);
            this.capsuleCollider = entity.getComponent(CapsuleCollider.class);
            this.characterController = entity.getComponent(CharacterControllerComponent.class);
            this.vehicleController = entity.getComponent(VehicleControllerComponent.class);
            this.navDriven = entity.getComponent(com.retro.engine.ecs.components.NavMeshAgent.class) != null;
        }

        /** True when physics moves this body, so its position here is newer than the Transform's. */
        public boolean isSimulated() {
            return (rigidBody != null && rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC)
                    || characterController != null || vehicleController != null;
        }

        /** True when any collider is a trigger: it reports overlaps but is not solid. */
        public boolean isTrigger() {
            return (boxCollider != null && boxCollider.isTrigger) || (sphereCollider != null && sphereCollider.isTrigger)
                    || (capsuleCollider != null && capsuleCollider.isTrigger);
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

    // Contacts from this step and the previous one, compared to raise Enter / Stay / Exit (swapped every step)
    private ContactPairSet previousCollisionPairs = new ContactPairSet();
    private ContactPairSet currentCollisionPairs = new ContactPairSet();
    private ContactPairSet previousTriggerPairs = new ContactPairSet();
    private ContactPairSet currentTriggerPairs = new ContactPairSet();

    private float accumulator = 0.0f;
    private final RaycastHit sharedRaycastHit = new RaycastHit();

    // Bodies of entities spawned mid-play. Scripts can reach them at once through getBody; they join the simulation at
    // the start of the next step so no solver loop sees them mid-iteration
    private final List<PhysicsBody> pendingBodies = new ArrayList<>();

    public BroadphaseSolver getBroadphaseSolver() {
        return broadphaseSolver;
    }

    /** Live physics body of {@code entity}, or null when it has none (no collider / rigidbody, or not playing). */
    public PhysicsBody getBody(Entity entity) {
        return (entity != null && entity.id != null) ? bodyMap.get(entity.id) : null;
    }

    /** True when the entity has a component the physics world simulates or collides against. */
    public static boolean isPhysicsEntity(Entity entity) {
        return entity != null && (entity.getComponent(RigidBodyComponent.class) != null ||
                entity.getComponent(BoxCollider.class) != null ||
                entity.getComponent(SphereCollider.class) != null ||
                entity.getComponent(CapsuleCollider.class) != null ||
                entity.getComponent(CharacterControllerComponent.class) != null ||
                entity.getComponent(VehicleControllerComponent.class) != null);
    }

    /**
     * Adds an entity spawned during play (e.g. an instantiated prefab) to the simulation. The body exists at once, so
     * a script can set its velocity in onStart, and starts simulating on the next step. Entities without physics
     * components are ignored.
     */
    public void registerRuntimeEntity(Entity entity) {
        if (!isPhysicsEntity(entity) || entity.id == null || bodyMap.containsKey(entity.id)) return;
        PhysicsBody body = new PhysicsBody(entity);
        bodyMap.put(entity.id, body);
        pendingBodies.add(body);
    }

    /** Removes an entity's body (e.g. one destroyed during play) from the bodies list and the broadphase tree. */
    public void removeRuntimeEntity(Entity entity) {
        if (entity == null || entity.id == null) return;
        PhysicsBody body = bodyMap.remove(entity.id);
        if (body == null) return;
        pendingBodies.remove(body);
        activeBodies.remove(body);
        if (body.treeNodeId != -1) {
            broadphaseSolver.removeLeaf(body.treeNodeId);
            body.treeNodeId = -1;
        }
    }

    private void addBody(Entity entity) {
        PhysicsBody body = new PhysicsBody(entity);
        bodyMap.put(entity.id, body);
        insertBody(body);
    }

    private void insertBody(PhysicsBody body) {
        activeBodies.add(body);

        float minX = body.px - getHalfWidthX(body);
        float maxX = body.px + getHalfWidthX(body);
        float minY = body.py - getHalfWidthY(body);
        float maxY = body.py + getHalfWidthY(body);
        float minZ = body.pz - getHalfWidthZ(body);
        float maxZ = body.pz + getHalfWidthZ(body);
        body.treeNodeId = broadphaseSolver.insertLeaf(body, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void flushPendingBodies() {
        for (int i = 0; i < pendingBodies.size(); i++) {
            PhysicsBody body = pendingBodies.get(i);
            // Its script may have moved it or added components since it spawned
            body.bindComponents();
            body.syncFromTransform();
            insertBody(body);
        }
        pendingBodies.clear();
    }

    public void initWorld(List<Entity> sceneEntities) {
        bodyMap.clear();
        pendingBodies.clear();
        activeBodies.clear();
        broadphaseSolver.clear();
        broadphasePairs.clear();
        previousCollisionPairs.clear();
        currentCollisionPairs.clear();
        previousTriggerPairs.clear();
        currentTriggerPairs.clear();
        activePairCount = 0;
        accumulator = 0.0f;
        characterSolver.reset();

        if (sceneEntities == null) return;

        for (Entity entity : sceneEntities) {
            if (isPhysicsEntity(entity)) addBody(entity);
        }
    }

    public void step(float deltaTime, List<Entity> sceneEntities) {
        if (activeBodies.isEmpty() && pendingBodies.isEmpty() && sceneEntities != null) {
            initWorld(sceneEntities);
        }
        if (!pendingBodies.isEmpty()) flushPendingBodies();

        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
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

        characterSolver.beginFrame(sceneEntities);
        accumulator += Math.min(deltaTime, 0.1f);

        int steps = 0;
        boolean physicsRan = false;
        while (accumulator >= FIXED_TIMESTEP && steps < MAX_PHYSICS_STEPS) {
            fixedUpdate(FIXED_TIMESTEP);
            accumulator -= FIXED_TIMESTEP;
            steps++;
            physicsRan = true;
        }

        if (steps >= MAX_PHYSICS_STEPS && accumulator >= FIXED_TIMESTEP) {
            accumulator = 0.0f;
        }

        if (physicsRan) {
            for (int i = 0, n = activeBodies.size(); i < n; i++) {
                PhysicsBody body = activeBodies.get(i);
                if (body.isSimulated()) body.syncToTransform();
            }
        }
    }

    public void recordCollision(PhysicsBody a, PhysicsBody b) {
        if (a != null && b != null && a.entity != null && b.entity != null) {
            currentCollisionPairs.add(a.entity, b.entity);
        }
    }

    public void recordTrigger(PhysicsBody a, PhysicsBody b) {
        if (a != null && b != null && a.entity != null && b.entity != null) {
            currentTriggerPairs.add(a.entity, b.entity);
        }
    }

    private void fixedUpdate(float dt) {
        currentCollisionPairs.clear();
        currentTriggerPairs.clear();

        // 1. Dispatch 60 Hz onPhysicsUpdate(dt) to all active EntityScripts
        ScriptEngine.physicsUpdateScripts(activeBodies, dt);

        // 2. Step RigidBodies Motion Integration
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
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

        // 3. Delegate to Character Controller Solver
        characterSolver.updateControllers(activeBodies, dt, this, sharedRaycastHit);

        // 4. Delegate to Vehicle Suspension Solver
        vehicleSolver.updateVehicles(activeBodies, dt, this, sharedRaycastHit);

        // 5. Delegate to Broadphase Spatial Solver
        activePairCount = broadphaseSolver.generateOverlapPairs(broadphasePairs);

        // 6. Delegate to Narrowphase Collision Solver
        for (int i = 0; i < activePairCount; i++) {
            BodyPair pair = broadphasePairs.get(i);
            if (pair.bodyA != null && pair.bodyB != null) {
                narrowphaseSolver.resolveCollision(pair.bodyA, pair.bodyB, this);
            }
        }

        // 7. Dispatch Collision Lifecycle Events
        for (int i = 0; i < currentCollisionPairs.size(); i++) {
            Entity a = currentCollisionPairs.first(i), b = currentCollisionPairs.second(i);
            if (previousCollisionPairs.contains(a, b)) {
                ScriptEngine.dispatchCollisionStay(a, b);
                ScriptEngine.dispatchCollisionStay(b, a);
            } else {
                ScriptEngine.dispatchCollisionEnter(a, b);
                ScriptEngine.dispatchCollisionEnter(b, a);
            }
        }
        for (int i = 0; i < previousCollisionPairs.size(); i++) {
            Entity a = previousCollisionPairs.first(i), b = previousCollisionPairs.second(i);
            if (!currentCollisionPairs.contains(a, b)) {
                ScriptEngine.dispatchCollisionExit(a, b);
                ScriptEngine.dispatchCollisionExit(b, a);
            }
        }

        // 8. Dispatch Trigger Lifecycle Events
        for (int i = 0; i < currentTriggerPairs.size(); i++) {
            Entity a = currentTriggerPairs.first(i), b = currentTriggerPairs.second(i);
            if (previousTriggerPairs.contains(a, b)) {
                ScriptEngine.dispatchTriggerStay(a, b);
                ScriptEngine.dispatchTriggerStay(b, a);
                ModLoader.onTrigger(TriggerEvent.Phase.STAY, a, b);
            } else {
                ScriptEngine.dispatchTriggerEnter(a, b);
                ScriptEngine.dispatchTriggerEnter(b, a);
                ModLoader.onTrigger(TriggerEvent.Phase.ENTER, a, b);
            }
        }
        for (int i = 0; i < previousTriggerPairs.size(); i++) {
            Entity a = previousTriggerPairs.first(i), b = previousTriggerPairs.second(i);
            if (!currentTriggerPairs.contains(a, b)) {
                ScriptEngine.dispatchTriggerExit(a, b);
                ScriptEngine.dispatchTriggerExit(b, a);
                ModLoader.onTrigger(TriggerEvent.Phase.EXIT, a, b);
            }
        }

        // 9. Swap state sets for next step
        ContactPairSet tempColl = previousCollisionPairs;
        previousCollisionPairs = currentCollisionPairs;
        currentCollisionPairs = tempColl;

        ContactPairSet tempTrig = previousTriggerPairs;
        previousTriggerPairs = currentTriggerPairs;
        currentTriggerPairs = tempTrig;
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
        pendingBodies.clear();
        bodyMap.clear();
        activeBodies.clear();
        broadphaseSolver.clear();
        broadphasePairs.clear();
        previousCollisionPairs.clear();
        currentCollisionPairs.clear();
        previousTriggerPairs.clear();
        currentTriggerPairs.clear();
        activePairCount = 0;
        characterSolver.reset();
    }
}
