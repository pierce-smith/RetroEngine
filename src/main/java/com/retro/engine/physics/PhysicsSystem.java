package com.retro.engine.physics;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.HierarchySystem;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.MeshCollider;
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
 * - NarrowphaseSolver: Exact contacts between oriented boxes, spheres and capsules
 * - ContactSolver: Sequential impulses with mass, friction and restitution
 * - CharacterControllerSolver: Vice City ground snapping, slopes & movement stepping
 * - VehicleSuspensionSolver: 4-wheel raycast suspension & vehicle dynamics
 * Hardened with physics lifecycle script updates and collision/trigger event dispatches.
 */
public class PhysicsSystem {

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f; // 60 Hz
    public static final float GRAVITY = -9.81f; // m/s^2
    // MAX_FRAME_DELTA / FIXED_TIMESTEP: physics keeps pace with script time down to 10 FPS, and a bad frame can't spiral
    public static final int MAX_PHYSICS_STEPS = 6;
    /** Overlap deeper than this counts as touching; anything shallower is float noise from a surface snap. */
    static final float TOUCH_TOLERANCE = 1e-4f;

    // Contacts start this far before shapes touch. The speed term must stay under BroadphaseSolver.FAT_AABB_MARGIN,
    // because a pair the broadphase doesn't report can't produce a contact
    private static final float CONTACT_MARGIN = 0.02f;
    private static final float MAX_SPECULATIVE_MARGIN = 0.18f;
    private static final float MAX_ANGULAR_SPEED = 60.0f; // rad/s, which keeps the quaternion step accurate
    static final float MAX_KINEMATIC_SPEED = 50.0f; // m/s, so a teleported platform doesn't launch what it touches
    private static final float REST_ANGULAR_SPEED_SQ = 1e-6f;
    private static final float DEFAULT_FRICTION = 0.5f; // Colliders with no RigidBody
    private static final float WARM_START_RANGE_SQ = 0.0225f;
    private static final float WARM_START_MIN_NORMAL_DOT = 0.9f;
    private static final float SWEEP_SKIN = 0.001f;

    public static class PhysicsBody {
        static final int SHAPE_BOX = 0, SHAPE_SPHERE = 1, SHAPE_CAPSULE = 2, SHAPE_MESH = 3;

        public Entity entity;
        public RigidBodyComponent rigidBody;
        public BoxCollider boxCollider;
        public SphereCollider sphereCollider;
        public CapsuleCollider capsuleCollider;
        public MeshCollider meshCollider;
        public CharacterControllerComponent characterController;
        public VehicleControllerComponent vehicleController;
        /** Set when a NavMeshAgent steers this body's Character Controller instead of player input. */
        public boolean navDriven;

        // World-space pose of the entity's pivot, in the Transform's Euler degrees
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx, sy, sz;

        public float vx, vy, vz;
        /** Angular velocity in radians per second, around world axes. */
        public float avx, avy, avz;

        public int treeNodeId = -1; // BVH Node Handle

        final PhysicsSystem owner;
        int id;

        // Collision shape: half extents along the local axes, plus the core segment of a sphere or capsule
        int shape = SHAPE_BOX;
        float hx, hy, hz;
        float radius, segmentHalf;
        CollisionMesh mesh;
        // The moving surface a character stands on, where it stood in that surface's frame, and that same spot in the world
        PhysicsBody ground;
        float anchorX, anchorY, anchorZ, anchorWorldX, anchorWorldY, anchorWorldZ, anchorYaw;
        float carriedX, carriedY, carriedZ;  // How far the ground has moved a character this frame
        float carryVx, carryVy, carryVz;     // The ground's speed under a character, measured over the last frame
        float airVx, airVz;                  // Speed kept from the ground while in the air
        float sweptX, sweptY, sweptZ;        // Where a moving body last checked for characters in its path
        boolean swept;
        int layerBit = 1;                 // This body's collision layer, as one set bit
        int collisionMask = -1;           // Layers it touches
        float ox, oy, oz;                 // Collider offset from the pivot, scaled, in the body's frame
        float cx, cy, cz;                 // World centre of the shape, which is also the centre of mass
        float minX, minY, minZ, maxX, maxY, maxZ;

        // Row-major world rotation, and the same rotation as a quaternion. Characters stay upright, so r stays identity
        final float[] r = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        final float[] q = {0, 0, 0, 1};
        private float cachedRx = Float.NaN, cachedRy, cachedRz; // The Euler angles r was built from
        private boolean eulerStale;       // The solver turned the body, so rx / ry / rz lag behind r

        float invMass;
        private float invIx, invIy, invIz;
        final float[] invInertia = new float[9]; // World space

        float svx, svy, svz;              // Velocity the solver works on. Only DYNAMIC bodies keep the result
        float pvx, pvy, pvz, pwx, pwy, pwz; // Split-impulse velocities: they remove overlap, then are thrown away
        float pushX, pushY, pushZ;        // How far contacts moved a character this step
        private float lastPx, lastPy, lastPz; // Where a kinematic body was last frame
        float kvx, kvy, kvz;

        public PhysicsBody(Entity entity) {
            this(entity, null);
        }

        PhysicsBody(Entity entity, PhysicsSystem owner) {
            this.entity = entity;
            this.owner = owner;
            bindComponents();
            syncFromTransform();
            lastPx = px; lastPy = py; lastPz = pz;
            refreshShape();
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
            this.meshCollider = entity.getComponent(MeshCollider.class);
            bindLayers();
        }

        private void bindLayers() {
            int layer = 0;
            String collidesWith = "";
            if (boxCollider != null) { layer = boxCollider.collisionLayer; collidesWith = boxCollider.collidesWith; }
            else if (sphereCollider != null) { layer = sphereCollider.collisionLayer; collidesWith = sphereCollider.collidesWith; }
            else if (capsuleCollider != null) { layer = capsuleCollider.collisionLayer; collidesWith = capsuleCollider.collidesWith; }
            else if (meshCollider != null) { layer = meshCollider.collisionLayer; collidesWith = meshCollider.collidesWith; }
            layerBit = 1 << Math.max(0, Math.min(31, layer));
            collisionMask = parseLayerMask(collidesWith);
        }

        /** True when physics moves this body, so its position here is newer than the Transform's. */
        public boolean isSimulated() {
            return (rigidBody != null && rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC)
                    || characterController != null || vehicleController != null;
        }

        /** True when contacts move this body. A Character Controller takes over from any RigidBody on the entity. */
        boolean isDynamic() {
            return mesh == null && characterController == null && rigidBody != null && rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        }

        /** True for a collider that never moves itself: no RigidBody or controller, or a STATIC body. */
        boolean isStatic() {
            if (mesh != null) return rigidBody == null || rigidBody.motionType != RigidBodyComponent.MotionType.KINEMATIC;
            if (characterController != null || vehicleController != null) return false;
            return rigidBody == null || rigidBody.motionType == RigidBodyComponent.MotionType.STATIC;
        }

        /** True when a character standing here moves with it. */
        boolean carriesRiders() {
            return !isStatic() && characterController == null;
        }

        /** True for something that moves by itself and can't be pushed, so it drives through characters. */
        boolean mowsCharacters() {
            return !isStatic() && !isDynamic() && characterController == null && !isTrigger();
        }

        /** True when any collider is a trigger: it reports overlaps but is not solid. */
        public boolean isTrigger() {
            return (boxCollider != null && boxCollider.isTrigger) || (sphereCollider != null && sphereCollider.isTrigger)
                    || (capsuleCollider != null && capsuleCollider.isTrigger);
        }

        public void syncFromTransform() {
            SceneWorld.WorldTransform wt = owner != null ? owner.parentedWorldTransform(entity) : null;
            if (wt != null) {
                this.px = wt.px(); this.py = wt.py(); this.pz = wt.pz();
                this.rx = wt.rx(); this.ry = wt.ry(); this.rz = wt.rz();
                this.sx = wt.sx(); this.sy = wt.sy(); this.sz = wt.sz();
                return;
            }
            Transform tc = entity.getTransform();
            if (tc != null) {
                this.px = tc.posX; this.py = tc.posY; this.pz = tc.posZ;
                this.rx = tc.rotX; this.ry = tc.rotY; this.rz = tc.rotZ;
                this.sx = tc.scaleX; this.sy = tc.scaleY; this.sz = tc.scaleZ;
            }
        }

        public void syncToTransform() {
            if (eulerStale) {
                float[] euler = owner.eulerScratch;
                PhysicsMath.matrixToEuler(r, euler);
                rx = euler[0]; ry = euler[1]; rz = euler[2];
                cachedRx = rx; cachedRy = ry; cachedRz = rz;
                eulerStale = false;
            }
            if (owner != null && owner.writeParentedPose(this)) return;
            Transform tc = entity.getTransform();
            if (tc != null) {
                tc.posX = this.px; tc.posY = this.py; tc.posZ = this.pz;
                tc.rotX = this.rx; tc.rotY = this.ry; tc.rotZ = this.rz;
                tc.scaleX = this.sx; tc.scaleY = this.sy; tc.scaleZ = this.sz;
            }
        }

        /** Rebuilds everything derived from the components and the pose: shape, mass, rotation and bounds. */
        void refreshShape() {
            float ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (mesh != null) {
                shape = SHAPE_MESH;
                setOffset(0.0f, 0.0f, 0.0f);
            } else if (boxCollider != null) {
                setBox(boxCollider.sizeX * ax * 0.5f, boxCollider.sizeY * ay * 0.5f, boxCollider.sizeZ * az * 0.5f);
                setOffset(boxCollider.offsetX, boxCollider.offsetY, boxCollider.offsetZ);
            } else if (sphereCollider != null) {
                shape = SHAPE_SPHERE;
                radius = sphereCollider.radius * Math.max(ax, Math.max(ay, az));
                segmentHalf = 0.0f;
                hx = radius; hy = radius; hz = radius;
                setOffset(sphereCollider.offsetX, sphereCollider.offsetY, sphereCollider.offsetZ);
            } else if (capsuleCollider != null) {
                shape = SHAPE_CAPSULE;
                radius = capsuleCollider.radius * Math.max(ax, az);
                hx = radius; hz = radius;
                hy = Math.max(capsuleCollider.height * ay * 0.5f, radius);
                segmentHalf = hy - radius;
                setOffset(capsuleCollider.offsetX, capsuleCollider.offsetY, capsuleCollider.offsetZ);
            } else {
                setBox(0.5f * ax, 0.5f * ay, 0.5f * az);
                setOffset(0.0f, 0.0f, 0.0f);
            }
            refreshMass();
            refreshRotation();
            refreshBounds();
        }

        private void setBox(float halfX, float halfY, float halfZ) {
            shape = SHAPE_BOX;
            hx = halfX; hy = halfY; hz = halfZ;
            radius = 0.0f;
            segmentHalf = 0.0f;
        }

        private void setOffset(float x, float y, float z) {
            ox = x * sx; oy = y * sy; oz = z * sz;
        }

        private void refreshMass() {
            if (!isDynamic()) {
                invMass = 0.0f;
                invIx = 0.0f; invIy = 0.0f; invIz = 0.0f;
                return;
            }
            float mass = Math.max(1e-4f, rigidBody.mass);
            invMass = 1.0f / mass;
            if (vehicleController != null) { // The suspension solver owns a vehicle's rotation
                invIx = 0.0f; invIy = 0.0f; invIz = 0.0f;
            } else if (shape == SHAPE_SPHERE) {
                invIx = invIy = invIz = 1.0f / (0.4f * mass * radius * radius);
            } else {
                // A capsule is close enough to the box around it
                invIx = 3.0f / (mass * (hy * hy + hz * hz));
                invIy = 3.0f / (mass * (hx * hx + hz * hz));
                invIz = 3.0f / (mass * (hx * hx + hy * hy));
            }
        }

        private void refreshRotation() {
            if (characterController != null || eulerStale) return;
            if (rx == cachedRx && ry == cachedRy && rz == cachedRz) return;
            PhysicsMath.eulerToMatrix(rx, ry, rz, r);
            PhysicsMath.matrixToQuat(r, q);
            cachedRx = rx; cachedRy = ry; cachedRz = rz;
        }

        /** Places the shape from the pivot, and recomputes its world bounds. */
        void refreshBounds() {
            cx = px + r[0] * ox + r[1] * oy + r[2] * oz;
            cy = py + r[3] * ox + r[4] * oy + r[5] * oz;
            cz = pz + r[6] * ox + r[7] * oy + r[8] * oz;
            float ex, ey, ez;
            if (shape == SHAPE_MESH) {
                refreshMeshBounds();
                return;
            }
            if (shape == SHAPE_BOX) {
                ex = Math.abs(r[0]) * hx + Math.abs(r[1]) * hy + Math.abs(r[2]) * hz;
                ey = Math.abs(r[3]) * hx + Math.abs(r[4]) * hy + Math.abs(r[5]) * hz;
                ez = Math.abs(r[6]) * hx + Math.abs(r[7]) * hy + Math.abs(r[8]) * hz;
            } else {
                ex = Math.abs(r[1]) * segmentHalf + radius;
                ey = Math.abs(r[4]) * segmentHalf + radius;
                ez = Math.abs(r[7]) * segmentHalf + radius;
            }
            minX = cx - ex; minY = cy - ey; minZ = cz - ez;
            maxX = cx + ex; maxY = cy + ey; maxZ = cz + ez;
        }

        private void refreshMeshBounds() {
            float lx = (mesh.minX + mesh.maxX) * 0.5f, ly = (mesh.minY + mesh.maxY) * 0.5f, lz = (mesh.minZ + mesh.maxZ) * 0.5f;
            float hxL = (mesh.maxX - mesh.minX) * 0.5f, hyL = (mesh.maxY - mesh.minY) * 0.5f, hzL = (mesh.maxZ - mesh.minZ) * 0.5f;
            float wx = cx + r[0] * lx + r[1] * ly + r[2] * lz;
            float wy = cy + r[3] * lx + r[4] * ly + r[5] * lz;
            float wz = cz + r[6] * lx + r[7] * ly + r[8] * lz;
            float ex = Math.abs(r[0]) * hxL + Math.abs(r[1]) * hyL + Math.abs(r[2]) * hzL;
            float ey = Math.abs(r[3]) * hxL + Math.abs(r[4]) * hyL + Math.abs(r[5]) * hzL;
            float ez = Math.abs(r[6]) * hxL + Math.abs(r[7]) * hyL + Math.abs(r[8]) * hzL;
            minX = wx - ex; minY = wy - ey; minZ = wz - ez;
            maxX = wx + ex; maxY = wy + ey; maxZ = wz + ez;
        }

        /** Rotates the local inverse inertia into world space: R * diag * R^T. */
        void refreshWorldInertia() {
            float[] m = r, w = invInertia;
            for (int i = 0; i < 3; i++) {
                float a = m[i * 3] * invIx, b = m[i * 3 + 1] * invIy, c = m[i * 3 + 2] * invIz;
                for (int j = 0; j < 3; j++) w[i * 3 + j] = a * m[j * 3] + b * m[j * 3 + 1] + c * m[j * 3 + 2];
            }
        }

        /** Radius of the largest sphere inside the shape, which is how far it can move without skipping past anything. */
        float innerRadius() {
            return shape == SHAPE_BOX ? Math.min(hx, Math.min(hy, hz)) : radius;
        }

        /** Works out how fast a kinematic body moved since last frame, so what it touches gets carried along. */
        void trackKinematicVelocity(float frameDelta) {
            if (frameDelta > 0.0f) {
                float inv = 1.0f / frameDelta;
                kvx = clampSpeed((px - lastPx) * inv);
                kvy = clampSpeed((py - lastPy) * inv);
                kvz = clampSpeed((pz - lastPz) * inv);
            }
            lastPx = px; lastPy = py; lastPz = pz;
        }

        private static float clampSpeed(float v) {
            return Math.max(-MAX_KINEMATIC_SPEED, Math.min(MAX_KINEMATIC_SPEED, v));
        }

        /** Moves the shape by this step's motion and turns it, keeping the pivot attached. */
        void advance(float mx, float my, float mz, float dt) {
            cx += mx; cy += my; cz += mz;
            if (invIx != 0.0f || invIy != 0.0f || invIz != 0.0f) {
                float speedSq = avx * avx + avy * avy + avz * avz;
                if (speedSq < REST_ANGULAR_SPEED_SQ) {
                    avx = 0.0f; avy = 0.0f; avz = 0.0f; // Stops rest noise creeping into the rotation
                } else if (speedSq > MAX_ANGULAR_SPEED * MAX_ANGULAR_SPEED) {
                    float scale = MAX_ANGULAR_SPEED / (float) Math.sqrt(speedSq);
                    avx *= scale; avy *= scale; avz *= scale;
                }
                float wx = avx + pwx, wy = avy + pwy, wz = avz + pwz;
                if (wx != 0.0f || wy != 0.0f || wz != 0.0f) {
                    PhysicsMath.integrateQuat(q, wx, wy, wz, dt);
                    PhysicsMath.quatToMatrix(q, r);
                    eulerStale = true;
                }
            }
            px = cx - (r[0] * ox + r[1] * oy + r[2] * oz);
            py = cy - (r[3] * ox + r[4] * oy + r[5] * oz);
            pz = cz - (r[6] * ox + r[7] * oy + r[8] * oz);
            refreshBounds();
        }
    }

    public static class BodyPair {
        public PhysicsBody bodyA;
        public PhysicsBody bodyB;
    }

    private final Map<String, PhysicsBody> bodyMap = new HashMap<>();
    private float lastFrameDelta;
    private final List<PhysicsBody> activeBodies = new ArrayList<>();

    // Domain Solvers
    private final BroadphaseSolver broadphaseSolver = new BroadphaseSolver();
    private final NarrowphaseSolver narrowphaseSolver = new NarrowphaseSolver();
    private final ContactSolver contactSolver = new ContactSolver();
    private final CharacterControllerSolver characterSolver = new CharacterControllerSolver();
    private final VehicleSuspensionSolver vehicleSolver = new VehicleSuspensionSolver();

    private final List<BodyPair> broadphasePairs = new ArrayList<>();
    private int activePairCount = 0;

    // This step's contacts and the last step's, swapped every step. Last step's impulses warm start this step's solve
    private ContactManifold[] manifolds = new ContactManifold[64];
    private ContactManifold[] previousManifolds = new ContactManifold[64];
    private int manifoldCount = 0;
    private int previousManifoldCount = 0;
    private final ContactCache contactCache = new ContactCache();
    private final ContactManifold triggerProbe = new ContactManifold();

    private final Map<String, CollisionMesh> collisionMeshes = new HashMap<>();
    private HierarchySystem hierarchy;
    private List<Entity> currentScene;
    private int nextBodyId = 1;
    final float[] eulerScratch = new float[3];

    // Contacts from this step and the previous one, compared to raise Enter / Stay / Exit (swapped every step)
    private ContactPairSet previousCollisionPairs = new ContactPairSet();
    private ContactPairSet currentCollisionPairs = new ContactPairSet();
    private ContactPairSet previousTriggerPairs = new ContactPairSet();
    private ContactPairSet currentTriggerPairs = new ContactPairSet();

    private float accumulator = 0.0f;
    private final RaycastHit sharedRaycastHit = new RaycastHit();
    private final RaycastHit sweepHit = new RaycastHit();

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
                entity.getComponent(MeshCollider.class) != null ||
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
        PhysicsBody body = newBody(entity);
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
        PhysicsBody body = newBody(entity);
        bodyMap.put(entity.id, body);
        insertBody(body);
    }

    private PhysicsBody newBody(Entity entity) {
        PhysicsBody body = new PhysicsBody(entity, this);
        body.id = nextBodyId++;
        if (body.meshCollider != null) {
            body.mesh = loadCollisionMesh(body);
            body.refreshShape();
        }
        return body;
    }

    /** Reads the triangles of a Mesh Collider, once per mesh and scale. A mesh that can't be read collides as a unit box. */
    private CollisionMesh loadCollisionMesh(PhysicsBody body) {
        String path = body.meshCollider.resolveMeshPath(body.entity);
        if (path == null) {
            System.err.println("[PhysicsSystem] Mesh Collider on '" + body.entity.name + "' has no mesh.");
            return null;
        }
        String key = path + '|' + body.sx + '|' + body.sy + '|' + body.sz;
        CollisionMesh mesh = collisionMeshes.get(key);
        if (mesh == null) {
            mesh = CollisionMesh.fromMesh(AssetStreamer.readMeshNow(path), body.sx, body.sy, body.sz);
            if (mesh == null) System.err.println("[PhysicsSystem] Mesh Collider on '" + body.entity.name + "' could not read " + path);
            else collisionMeshes.put(key, mesh);
        }
        return mesh;
    }

    /** Turns a list of layer numbers such as "0,3" into a mask. Empty means every layer. */
    static int parseLayerMask(String layers) {
        if (layers == null || layers.isBlank()) return -1;
        int mask = 0;
        for (String part : layers.split(",")) {
            try {
                int layer = Integer.parseInt(part.trim());
                if (layer >= 0 && layer < 32) mask |= 1 << layer;
            } catch (NumberFormatException e) {
                System.err.println("[PhysicsSystem] Ignoring collision layer '" + part.trim() + "': it isn't a number.");
            }
        }
        return mask;
    }

    private void insertBody(PhysicsBody body) {
        activeBodies.add(body);
        body.treeNodeId = broadphaseSolver.insertLeaf(body, body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ);
    }

    /** Brings a body's shape up to date after something moved it, including its place in the broadphase. */
    void refreshBody(PhysicsBody body) {
        body.refreshShape();
        updateBroadphase(body);
    }

    private void updateBroadphase(PhysicsBody body) {
        if (body.treeNodeId != -1) {
            broadphaseSolver.updateLeaf(body.treeNodeId, body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ);
        }
    }

    /** Lets parented bodies live in world space. Without it, every Transform is treated as a world transform. */
    public void setHierarchy(HierarchySystem hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** World transform of a parented entity, or null for a root entity, whose Transform already is its world pose. */
    private SceneWorld.WorldTransform parentedWorldTransform(Entity entity) {
        if (hierarchy == null || currentScene == null || entity.parentId == null || entity.parentId.isEmpty()) return null;
        return hierarchy.getComputedWorldTransform(entity, currentScene);
    }

    /** Writes a parented body's world pose back as a local Transform. Returns false for a root entity. */
    private boolean writeParentedPose(PhysicsBody body) {
        Entity entity = body.entity;
        if (hierarchy == null || currentScene == null || entity.parentId == null || entity.parentId.isEmpty()) return false;
        hierarchy.setWorldPose(entity, currentScene, body.px, body.py, body.pz, body.rx, body.ry, body.rz);
        return true;
    }

    private void flushPendingBodies() {
        for (int i = 0; i < pendingBodies.size(); i++) {
            PhysicsBody body = pendingBodies.get(i);
            // Its script may have moved it or added components since it spawned
            body.bindComponents();
            body.syncFromTransform();
            body.refreshShape();
            insertBody(body);
        }
        pendingBodies.clear();
    }

    public void initWorld(List<Entity> sceneEntities) {
        currentScene = sceneEntities;
        clearContacts();
        nextBodyId = 1;
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
        if (sceneEntities != null) currentScene = sceneEntities;
        if (!pendingBodies.isEmpty()) flushPendingBodies();

        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            body.syncFromTransform();
            if (body.rigidBody != null && body.rigidBody.motionType == RigidBodyComponent.MotionType.KINEMATIC) {
                body.trackKinematicVelocity(deltaTime);
            }
            refreshBody(body);
        }
        characterSolver.measureCarry(activeBodies, lastFrameDelta);
        lastFrameDelta = deltaTime;
        characterSolver.sweepMovers(activeBodies, deltaTime, this);
        characterSolver.carryRiders(activeBodies, this);

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

        // A rider moves with its ground every frame, including frames too short for a physics step
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            if (body.isSimulated() && (physicsRan || body.ground != null)) body.syncToTransform();
        }
    }

    /** Scripts run after physics, so a platform moved in onUpdate brings its riders along here, before the frame draws. */
    public void carryAfterScripts() {
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            if (body.ground == null || body.ground.treeNodeId == -1) continue;
            body.ground.syncFromTransform();
            refreshBody(body.ground);
            body.syncFromTransform();
        }
        characterSolver.carryRiders(activeBodies, this);
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            if (body.ground != null) body.syncToTransform();
        }
    }

    /** How far a character can travel from where it is before something solid stops it, ignoring {@code ignore}. */
    float clearDistance(PhysicsBody character, PhysicsBody ignore, float dx, float dy, float dz, float distance) {
        broadphaseSolver.cast(character.entity, ignore != null ? ignore.entity : null, character.cx, character.cy, character.cz,
                dx, dy, dz, distance, character.innerRadius(), true, character.collisionMask, sweepHit);
        return sweepHit.hit ? sweepHit.distance : distance;
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

        // 2. Apply gravity and drag. Positions move at step 8, once contacts have had their say
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            if (!body.isDynamic()) continue;
            RigidBodyComponent rb = body.rigidBody;
            if (rb.useGravity) body.vy += GRAVITY * dt;

            float dragFactor = Math.max(0.0f, 1.0f - rb.linearDrag * dt);
            body.vx *= dragFactor;
            body.vy *= dragFactor;
            body.vz *= dragFactor;

            float angDragFactor = Math.max(0.0f, 1.0f - rb.angularDrag * dt);
            body.avx *= angDragFactor;
            body.avy *= angDragFactor;
            body.avz *= angDragFactor;
        }

        // 3. Delegate to Character Controller Solver
        characterSolver.updateControllers(activeBodies, dt, this, sharedRaycastHit);

        // 4. Delegate to Vehicle Suspension Solver
        vehicleSolver.updateVehicles(activeBodies, dt, this, sharedRaycastHit);
        characterSolver.sweepMovers(activeBodies, dt, this);

        // 5. Scripts, characters and vehicles may all have moved bodies, so bring shapes and solver state up to date
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            prepareForSolve(body);
        }

        // 6. Find contacts: Broadphase Spatial Solver, then Narrowphase Collision Solver
        activePairCount = broadphaseSolver.generateOverlapPairs(broadphasePairs);
        collectContacts(dt);

        // 7. Solve them, then move every dynamic body
        contactSolver.solve(manifolds, manifoldCount, dt);
        integrateBodies(dt);
        characterSolver.carryRiders(activeBodies, this); // Vehicles and dynamic bodies have moved by now
        characterSolver.resolveContacts(manifolds, manifoldCount, this);
        characterSolver.anchorRiders(activeBodies);
        recordTouchingPairs();

        // 8. Dispatch Collision Lifecycle Events
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

        // 9. Dispatch Trigger Lifecycle Events
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

        // 10. Swap state sets for next step
        ContactPairSet tempColl = previousCollisionPairs;
        previousCollisionPairs = currentCollisionPairs;
        currentCollisionPairs = tempColl;

        ContactPairSet tempTrig = previousTriggerPairs;
        previousTriggerPairs = currentTriggerPairs;
        currentTriggerPairs = tempTrig;

        ContactManifold[] tempManifolds = previousManifolds;
        previousManifolds = manifolds;
        manifolds = tempManifolds;
        previousManifoldCount = manifoldCount;
        manifoldCount = 0;
        contactCache.rebuild(previousManifolds, previousManifoldCount);
    }

    private void prepareForSolve(PhysicsBody body) {
        if (!body.isStatic()) refreshBody(body);
        body.pushX = 0.0f; body.pushY = 0.0f; body.pushZ = 0.0f;
        if (body.isDynamic()) {
            body.refreshWorldInertia();
            body.svx = body.vx; body.svy = body.vy; body.svz = body.vz;
            body.pvx = 0.0f; body.pvy = 0.0f; body.pvz = 0.0f;
            body.pwx = 0.0f; body.pwy = 0.0f; body.pwz = 0.0f;
        } else if (body.characterController != null) {
            CharacterControllerComponent cc = body.characterController;
            body.svx = cc.velocityX + body.airVx; body.svy = cc.velocityY; body.svz = cc.velocityZ + body.airVz;
        } else if (body.vehicleController != null) {
            float yaw = (float) Math.toRadians(body.ry);
            float speed = body.vehicleController.currentSpeed;
            body.svx = (float) -Math.sin(yaw) * speed; body.svy = 0.0f; body.svz = (float) Math.cos(yaw) * speed;
        } else {
            body.svx = body.kvx; body.svy = body.kvy; body.svz = body.kvz;
        }
    }

    private static boolean boundsOverlap(PhysicsBody a, PhysicsBody b, float margin) {
        return a.minX - margin <= b.maxX && a.maxX + margin >= b.minX
                && a.minY - margin <= b.maxY && a.maxY + margin >= b.minY
                && a.minZ - margin <= b.maxZ && a.maxZ + margin >= b.minZ;
    }

    private static float speedOf(PhysicsBody b) {
        return (float) Math.sqrt(b.svx * b.svx + b.svy * b.svy + b.svz * b.svz);
    }

    private void collectContacts(float dt) {
        manifoldCount = 0;
        for (int i = 0; i < activePairCount; i++) {
            BodyPair pair = broadphasePairs.get(i);
            PhysicsBody a = pair.bodyA, b = pair.bodyB;
            if (a == null || b == null) continue;
            if ((a.collisionMask & b.layerBit) == 0 || (b.collisionMask & a.layerBit) == 0) continue;
            if (a.id > b.id) { PhysicsBody swap = a; a = b; b = swap; } // Same order every step, so contacts can be matched

            if (a.isTrigger() || b.isTrigger()) {
                if (boundsOverlap(a, b, 0.0f)) {
                    triggerProbe.a = a;
                    triggerProbe.b = b;
                    if (narrowphaseSolver.collide(triggerProbe, 0.0f)) recordTrigger(a, b);
                }
                continue;
            }
            if (a.isStatic() && b.isStatic()) continue;

            float margin = CONTACT_MARGIN + Math.min(MAX_SPECULATIVE_MARGIN, (speedOf(a) + speedOf(b)) * dt);
            if (!boundsOverlap(a, b, margin)) continue;

            ContactManifold m = nextManifold();
            m.a = a;
            m.b = b;
            if (!narrowphaseSolver.collide(m, margin)) continue;

            ContactManifold.Point deepest = m.deepest();
            // Mods (CollisionEvent) may change the restitution or cancel the contact; no-op without subscribers
            float restitution = ModLoader.onCollision(a.entity, b.entity, deepest.nx, deepest.ny, deepest.nz,
                    Math.max(0.0f, -deepest.separation), defaultRestitution(a, b));
            if (Float.isNaN(restitution)) continue;
            m.restitution = restitution;
            m.friction = (float) Math.sqrt(frictionOf(a) * frictionOf(b));
            m.key = ContactCache.key(a.id, b.id);
            restoreImpulses(m);
            manifoldCount++;
        }
    }

    private ContactManifold nextManifold() {
        if (manifoldCount == manifolds.length) {
            manifolds = java.util.Arrays.copyOf(manifolds, manifolds.length * 2);
            previousManifolds = java.util.Arrays.copyOf(previousManifolds, manifolds.length);
        }
        ContactManifold m = manifolds[manifoldCount];
        if (m == null) {
            m = new ContactManifold();
            manifolds[manifoldCount] = m;
        }
        return m;
    }

    private static float frictionOf(PhysicsBody body) {
        return body.rigidBody != null ? Math.max(0.0f, body.rigidBody.friction) : DEFAULT_FRICTION;
    }

    /** Restitution the contact uses unless a mod overrides it: the max of two dynamic bodies, else the dynamic one's. */
    private static float defaultRestitution(PhysicsBody a, PhysicsBody b) {
        boolean aDynamic = a.isDynamic(), bDynamic = b.isDynamic();
        if (aDynamic && bDynamic) return Math.max(a.rigidBody.restitution, b.rigidBody.restitution);
        if (aDynamic) return a.rigidBody.restitution;
        if (bDynamic) return b.rigidBody.restitution;
        return 0.0f;
    }

    /** Starts each new contact point from the impulse of the same point last step, which is what lets stacks settle. */
    private void restoreImpulses(ContactManifold m) {
        int previousIndex = contactCache.find(m.key);
        ContactManifold old = previousIndex >= 0 ? previousManifolds[previousIndex] : null;
        float[] r = m.a.r;
        for (int i = 0; i < m.count; i++) {
            ContactManifold.Point p = m.points[i];
            float dx = p.x - m.a.cx, dy = p.y - m.a.cy, dz = p.z - m.a.cz;
            p.localX = r[0] * dx + r[3] * dy + r[6] * dz;
            p.localY = r[1] * dx + r[4] * dy + r[7] * dz;
            p.localZ = r[2] * dx + r[5] * dy + r[8] * dz;
            p.normal.impulse = 0.0f;
            p.tangent1.impulse = 0.0f;
            p.tangent2.impulse = 0.0f;
            if (old == null) continue;

            ContactManifold.Point match = null;
            float best = WARM_START_RANGE_SQ;
            for (int j = 0; j < old.count; j++) {
                ContactManifold.Point o = old.points[j];
                float ex = o.localX - p.localX, ey = o.localY - p.localY, ez = o.localZ - p.localZ;
                float distSq = ex * ex + ey * ey + ez * ez;
                if (distSq < best && o.nx * p.nx + o.ny * p.ny + o.nz * p.nz > WARM_START_MIN_NORMAL_DOT) {
                    best = distSq;
                    match = o;
                }
            }
            if (match != null) {
                p.normal.impulse = match.normal.impulse;
                p.tangent1.impulse = match.tangent1.impulse;
                p.tangent2.impulse = match.tangent2.impulse;
            }
        }
    }

    private void integrateBodies(float dt) {
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsBody body = activeBodies.get(i);
            if (!body.isDynamic()) continue;
            body.vx = body.svx; body.vy = body.svy; body.vz = body.svz;

            float mx = body.vx * dt, my = body.vy * dt, mz = body.vz * dt;
            float distance = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            float inner = body.innerRadius();
            if (distance > inner * 0.5f) {
                // Fast enough to skip past something thin. Stop at the first surface; next step's contact deals with it
                float scale = sweepFraction(body, mx, my, mz, distance, inner);
                mx *= scale; my *= scale; mz *= scale;
            }
            body.advance(mx + body.pvx * dt, my + body.pvy * dt, mz + body.pvz * dt, dt);
            updateBroadphase(body);
        }
    }

    private float sweepFraction(PhysicsBody body, float mx, float my, float mz, float distance, float inner) {
        float inv = 1.0f / distance;
        broadphaseSolver.cast(body.entity, null, body.cx, body.cy, body.cz, mx * inv, my * inv, mz * inv, distance, inner, true, body.collisionMask, sweepHit);
        return sweepHit.hit ? Math.max(0.0f, sweepHit.distance - SWEEP_SKIN) * inv : 1.0f;
    }

    private void recordTouchingPairs() {
        for (int i = 0; i < manifoldCount; i++) {
            ContactManifold m = manifolds[i];
            if (m.isTouching()) recordCollision(m.a, m.b);
        }
    }

    private void clearContacts() {
        manifoldCount = 0;
        previousManifoldCount = 0;
        contactCache.clear();
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
        raycastExcludingInPlace(excludeEntity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, outHit, -1);
    }

    /** Raycast limited to the layers set in {@code layerMask}, one bit per layer. */
    public void raycastExcludingInPlace(Entity excludeEntity, float rayOriginX, float rayOriginY, float rayOriginZ,
                                        float rayDirX, float rayDirY, float rayDirZ, float maxDistance, RaycastHit outHit, int layerMask) {
        broadphaseSolver.raycastInPlace(excludeEntity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, outHit, layerMask);
    }

    public void cleanup() {
        clearContacts();
        collisionMeshes.clear();
        currentScene = null;
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
