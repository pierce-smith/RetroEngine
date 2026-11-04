package com.retro.engine.scripting.framework;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.ParticleSystem;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.physics.RaycastHit;
import com.retro.engine.scripting.EntityScript;

/**
 * High-level base class for gameplay scripts. Extends {@link EntityScript} with cached component accessors, spatial
 * and physics shortcuts, world queries, audio / camera / save-data helpers, plus a built-in timer queue
 * ({@link #tasks}) and tweener ({@link #tweens}) that tick automatically before every onUpdate (no super call needed).
 *
 * <p>Everything here that is meant for per-frame use is allocation-free. Methods that touch the disk or create
 * entities (spawnPrefab, setSaveData, saveGame) are flagged in their docs; call them on events, not every frame.</p>
 *
 * <p>Janino notes: the accessors return concrete types, so no casts are needed; schedule work with named or anonymous
 * {@link ScriptTask} classes created once (Janino has no lambdas).</p>
 */
public abstract class SimpleEntityScript extends EntityScript {

    /** Timers owned by this script; cancelled automatically when the script is destroyed. */
    protected final ScriptTaskQueue tasks = new ScriptTaskQueue(8);

    /** Tweens owned by this script; cancelled automatically when the script is destroyed. */
    protected final ScriptTweener tweens = new ScriptTweener(4);

    private final ComponentCache componentCache = new ComponentCache();
    private final float[] dirScratch = new float[3];
    private final float[] rightScratch = new float[3];
    private ScriptEventListener[] ownedListeners = new ScriptEventListener[2];
    private int ownedListenerCount = 0;

    @Override
    public void init(Entity entity, SceneWorld sceneWorld) {
        componentCache.bind(entity); // Before super.init, which calls onAwake
        super.init(entity, sceneWorld);
    }

    @Override
    public void onFrameworkUpdate(float deltaTime) {
        tasks.update(deltaTime);
        tweens.update(deltaTime);
    }

    @Override
    public void onFrameworkDestroy() {
        tasks.cancelAll();
        tweens.cancelAll();
        for (int i = 0; i < ownedListenerCount; i++) {
            ScriptEvents.unsubscribe(ownedListeners[i]);
            ownedListeners[i] = null;
        }
        ownedListenerCount = 0;
    }

    // =========================================================================
    // Cached component accessors
    // =========================================================================

    public Transform transform() { return componentCache.transform(); }
    public RigidBodyComponent rigidbody() { return componentCache.rigidbody(); }
    /** First collider (box, sphere, then capsule); use boxCollider() etc. for the concrete type. */
    public Component collider() { return componentCache.collider(); }
    public BoxCollider boxCollider() { return componentCache.boxCollider(); }
    public SphereCollider sphereCollider() { return componentCache.sphereCollider(); }
    public CapsuleCollider capsuleCollider() { return componentCache.capsuleCollider(); }
    public AnimatorComponent animator() { return componentCache.animator(); }
    public Light light() { return componentCache.light(); }
    public Camera camera() { return componentCache.camera(); }
    public AudioSource audio() { return componentCache.audio(); }
    public Material material() { return componentCache.material(); }
    public MeshFilter meshFilter() { return componentCache.meshFilter(); }
    public CharacterControllerComponent characterController() { return componentCache.characterController(); }
    public VehicleControllerComponent vehicleController() { return componentCache.vehicleController(); }
    public ParticleSystem particleSystem() { return componentCache.particleSystem(); }

    /** Forces the next accessor calls to look components up again. */
    public void invalidateComponentCache() { componentCache.invalidate(); }

    // =========================================================================
    // Particles (this entity's ParticleSystem; all no-ops without one, allocation-free)
    // =========================================================================

    /** Starts continuous emission on this entity's ParticleSystem. */
    public void play() {
        ParticleSystem ps = componentCache.particleSystem();
        if (ps != null) ps.play();
    }

    /** Stops continuous emission; live particles fade out over their remaining lifetime. */
    public void stop() {
        ParticleSystem ps = componentCache.particleSystem();
        if (ps != null) ps.stop();
    }

    /** Spawns a burst of {@code count} particles on the next simulation step (works while stopped). */
    public void emit(int count) {
        ParticleSystem ps = componentCache.particleSystem();
        if (ps != null) ps.emit(count);
    }

    // =========================================================================
    // World-space position
    // =========================================================================

    /** World position of this entity (parents included). */
    public Vector3 getWorldPosition(Vector3 out) {
        return getWorldPositionOf(entity, out);
    }

    /** World position of any entity (parents included); (0,0,0) for null. */
    public Vector3 getWorldPositionOf(Entity target, Vector3 out) {
        if (target == null) return out.zero();
        if (sceneWorld != null) {
            SceneWorld.WorldTransform wt = sceneWorld.getComputedWorldTransform(target);
            if (wt != null) return out.set(wt.px(), wt.py(), wt.pz());
        }
        Transform t = target.getTransform();
        return t != null ? out.set(t.posX, t.posY, t.posZ) : out.zero();
    }

    public float getWorldX() { return worldComponent(entity, 0); }
    public float getWorldY() { return worldComponent(entity, 1); }
    public float getWorldZ() { return worldComponent(entity, 2); }

    // =========================================================================
    // Spatial shortcuts
    // =========================================================================

    /**
     * Moves relative to this entity's facing: {@code dx} along its right vector, {@code dy} along world up and
     * {@code dz} along its forward vector (cameras: the view direction). For world-axis movement use
     * {@link #translate(float, float, float)}. This writes the transform directly; a dynamic rigidbody is better
     * moved with {@link #setVelocity} / {@link #applyImpulse}, a player with its CharacterController.
     */
    public void move(float dx, float dy, float dz) {
        Transform t = transform();
        if (t == null) return;
        getForward(dirScratch);
        getRight(rightScratch);
        t.setPosition(
                t.posX + rightScratch[0] * dx + dirScratch[0] * dz,
                t.posY + dy + dirScratch[1] * dz,
                t.posZ + rightScratch[2] * dx + dirScratch[2] * dz);
    }

    /** {@link #move(float, float, float)} taking a reusable vector (x = right, y = up, z = forward). */
    public void moveInPlace(Vector3 delta) {
        if (delta != null) move(delta.x, delta.y, delta.z);
    }

    /** Points this entity's forward axis at {@code target}'s world position (cameras aim their view direction). */
    public void lookAt(Entity target) {
        if (target == null || target == entity) return;
        SceneWorld.WorldTransform wt = (sceneWorld != null) ? sceneWorld.getComputedWorldTransform(target) : null;
        if (wt != null) {
            lookAt(wt.px(), wt.py(), wt.pz());
        } else if (target.getTransform() != null) {
            Transform tt = target.getTransform();
            lookAt(tt.posX, tt.posY, tt.posZ);
        }
    }

    /**
     * Points this entity's forward axis at a world position (pitch + yaw, roll kept). Exact for root entities and
     * entities whose parents only yaw/pitch.
     */
    public void lookAt(float x, float y, float z) {
        Transform t = transform();
        if (t == null) return;
        float dx = x - getWorldX(), dy = y - getWorldY(), dz = z - getWorldZ();
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        dx /= len; dy /= len; dz /= len;
        float pitch, yaw;
        // Matches EntityScript.getForward: models face +Z, cameras look down -Z
        if (camera() != null) {
            pitch = (float) Math.toDegrees(Math.asin(-dy));
            yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        } else {
            pitch = (float) Math.toDegrees(Math.asin(dy));
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        t.setRotation(pitch - parentWorldRotation(0), yaw - parentWorldRotation(1), t.rotZ);
    }

    /** Turns only around the Y axis to face {@code target} (characters that must stay upright). */
    public void lookAtFlat(Entity target) {
        if (target == null || target == entity) return;
        turnTowards(worldComponent(target, 0), worldComponent(target, 2), 360.0f);
    }

    /**
     * Yaws toward the world point (x, z) by at most {@code maxDegrees} (pass speed * deltaTime for a smooth turn).
     * Returns the remaining angle in degrees (0 = facing it).
     */
    public float turnTowards(float x, float z, float maxDegrees) {
        Transform t = transform();
        if (t == null) return 0.0f;
        float dx = x - getWorldX(), dz = z - getWorldZ();
        if (dx * dx + dz * dz < 1e-8f) return 0.0f;
        float targetYaw = camera() != null
                ? (float) Math.toDegrees(Math.atan2(dx, -dz))
                : (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetYaw -= parentWorldRotation(1);
        float delta = ScriptTweener.deltaAngle(t.rotY, targetYaw);
        float step = Math.max(-maxDegrees, Math.min(maxDegrees, delta));
        t.setRotation(t.rotX, t.rotY + step, t.rotZ);
        return Math.abs(delta - step);
    }

    /** World-space distance to another entity; {@code Float.MAX_VALUE} for null. */
    public float getDistanceTo(Entity target) {
        if (target == null) return Float.MAX_VALUE;
        float dx = worldComponent(target, 0) - getWorldX();
        float dy = worldComponent(target, 1) - getWorldY();
        float dz = worldComponent(target, 2) - getWorldZ();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float getDistanceTo(float x, float y, float z) {
        float dx = x - getWorldX(), dy = y - getWorldY(), dz = z - getWorldZ();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Distance on the ground plane (ignores height); {@code Float.MAX_VALUE} for null. */
    public float getFlatDistanceTo(Entity target) {
        if (target == null) return Float.MAX_VALUE;
        float dx = worldComponent(target, 0) - getWorldX();
        float dz = worldComponent(target, 2) - getWorldZ();
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    // =========================================================================
    // Physics shortcuts
    // =========================================================================

    /**
     * Instant change of momentum on this entity's DYNAMIC rigidbody: velocity += force / mass. Returns false when the
     * entity has no dynamic body in the running simulation.
     */
    public boolean applyImpulse(float fx, float fy, float fz) {
        PhysicsSystem.PhysicsBody body = dynamicBody();
        if (body == null) return false;
        float invMass = 1.0f / Math.max(1e-4f, body.rigidBody.mass);
        body.vx += fx * invMass;
        body.vy += fy * invMass;
        body.vz += fz * invMass;
        return true;
    }

    /** Overwrites the linear velocity of this entity's DYNAMIC rigidbody (m/s). Returns false without one. */
    public boolean setVelocity(float vx, float vy, float vz) {
        PhysicsSystem.PhysicsBody body = dynamicBody();
        if (body == null) return false;
        body.vx = vx;
        body.vy = vy;
        body.vz = vz;
        return true;
    }

    /** Linear velocity of this entity's physics body (zero without one). */
    public Vector3 getVelocity(Vector3 out) {
        PhysicsSystem physics = (sceneWorld != null) ? sceneWorld.getPhysicsWorld() : null;
        PhysicsSystem.PhysicsBody body = (physics != null) ? physics.getBody(entity) : null;
        return body != null ? out.set(body.vx, body.vy, body.vz) : out.zero();
    }

    /**
     * Allocation-free raycast from this entity's world position along its forward vector, ignoring itself. Fills
     * {@code outHit} (keep one RaycastHit field per script) and returns whether something was hit.
     */
    public boolean raycastInPlace(float maxDistance, RaycastHit outHit) {
        getForward(dirScratch);
        return raycast(getWorldX(), getWorldY(), getWorldZ(), dirScratch[0], dirScratch[1], dirScratch[2], maxDistance, outHit);
    }

    // =========================================================================
    // Entity lifecycle & world queries
    // =========================================================================

    /** Destroys this entity (and its children) at the end of the frame; this script gets onDestroy then. */
    public void destroySelf() {
        destroy(entity);
    }

    /** Destroys another entity (and its children) at the end of the frame. */
    public void destroy(Entity target) {
        if (sceneWorld != null && target != null) sceneWorld.requestEntityDestroy(target);
    }

    /** True once destroySelf / destroy was requested for this entity this frame. */
    public boolean isBeingDestroyed() {
        return sceneWorld != null && sceneWorld.isPendingDestroy(entity);
    }

    /**
     * Instantiates a prefab (e.g. "Prefabs/Coin.rprefab") and returns its root entity, or null when it could not be
     * loaded. Reads the prefab from disk and creates entities: call it on events, not every frame.
     */
    public Entity spawnPrefab(String assetPath) {
        if (sceneWorld == null) {
            logError("spawnPrefab('" + assetPath + "') failed: script is not bound to a SceneWorld.");
            return null;
        }
        return sceneWorld.instantiatePrefabRuntime(assetPath);
    }

    /** {@link #spawnPrefab(String)} and moves the root to (x, y, z). */
    public Entity spawnPrefab(String assetPath, float x, float y, float z) {
        Entity root = spawnPrefab(assetPath);
        if (root != null && root.getTransform() != null) root.getTransform().setPosition(x, y, z);
        return root;
    }

    /** First entity with exactly this name, or null. Scans the scene: cache the result in onStart. */
    public Entity findEntity(String name) {
        return sceneWorld != null ? sceneWorld.findEntityByName(name) : null;
    }

    /**
     * The player: the entity named {@code preferredName} when one exists, else the first entity with a
     * CharacterController, else null. Scans the scene: cache the result.
     */
    public Entity findPlayer(String preferredName) {
        if (sceneWorld == null) return null;
        if (preferredName != null && !preferredName.isEmpty()) {
            Entity named = sceneWorld.findEntityByName(preferredName);
            if (named != null) return named;
        }
        java.util.List<Entity> all = sceneWorld.getSceneEntities();
        for (int i = 0; i < all.size(); i++) {
            Entity e = all.get(i);
            if (e != null && e != entity && e.getComponent(CharacterControllerComponent.class) != null) return e;
        }
        return null;
    }

    /** True when {@code other} is named {@code preferredName} or has a CharacterController (i.e. looks like the player). */
    public boolean isPlayer(Entity other, String preferredName) {
        if (other == null) return false;
        if (preferredName != null && !preferredName.isEmpty() && preferredName.equals(other.name)) return true;
        return other.getComponent(CharacterControllerComponent.class) != null;
    }

    // =========================================================================
    // Audio & camera feedback
    // =========================================================================

    /** One-shot clip played from this entity's position (3D attenuated). */
    public boolean playSpatialSound(String clipPath, float volume) {
        return playSound(clipPath, volume, true);
    }

    /** One-shot non-spatial clip at full volume (UI, stingers). */
    public boolean playGlobalSound(String clipPath) {
        return playSound(clipPath, 1.0f, false);
    }

    public boolean playGlobalSound(String clipPath, float volume) {
        return playSound(clipPath, volume, false);
    }

    /** Shakes the Main Camera; intensity ~0..1, fading out over {@code duration} seconds. */
    public void shakeCamera(float intensity, float duration) {
        ScriptCameraShake.shake(intensity, duration);
    }

    // =========================================================================
    // Persistence (see ScriptSaveData; writes are not meant for every frame)
    // =========================================================================

    public void setSaveData(String key, Object val) { ScriptSaveData.set(key, val); }
    public boolean getSaveDataBool(String key, boolean defaultVal) { return ScriptSaveData.getBool(key, defaultVal); }
    public int getSaveDataInt(String key, int defaultVal) { return ScriptSaveData.getInt(key, defaultVal); }
    public float getSaveDataFloat(String key, float defaultVal) { return ScriptSaveData.getFloat(key, defaultVal); }
    public String getSaveDataString(String key, String defaultVal) { return ScriptSaveData.getString(key, defaultVal); }
    public boolean hasSaveData(String key) { return ScriptSaveData.has(key); }
    /** Writes save data to disk now (it is also written when Play stops and when the game exits). */
    public boolean saveGame() { return ScriptSaveData.save(); }

    // =========================================================================
    // Events
    // =========================================================================

    /** Publishes an event with this entity as the source. */
    public void publishEvent(String eventName, String text, float number) {
        ScriptEvents.publish(eventName, entity, text, number);
    }

    /** Subscribes a listener (often {@code this}) and unsubscribes it automatically when this script is destroyed. */
    public void subscribeEvents(ScriptEventListener listener) {
        if (listener == null) return;
        for (int i = 0; i < ownedListenerCount; i++) if (ownedListeners[i] == listener) return;
        if (ownedListenerCount == ownedListeners.length) {
            ScriptEventListener[] grown = new ScriptEventListener[ownedListeners.length * 2];
            System.arraycopy(ownedListeners, 0, grown, 0, ownedListenerCount);
            ownedListeners = grown;
        }
        ownedListeners[ownedListenerCount++] = listener;
        ScriptEvents.subscribe(listener);
    }

    // =========================================================================
    // Timers & tweens
    // =========================================================================

    public int runAfter(float delaySeconds, ScriptTask task) { return tasks.runAfter(delaySeconds, task); }
    public int runEvery(float intervalSeconds, ScriptTask task) { return tasks.runEvery(intervalSeconds, task); }
    public boolean cancelTask(int handle) { return tasks.cancel(handle); }

    public int tweenPositionTo(float x, float y, float z, float seconds, int easing) {
        return tweens.tweenPosition(transform(), x, y, z, seconds, easing);
    }

    public int tweenRotationTo(float x, float y, float z, float seconds, int easing) {
        return tweens.tweenRotation(transform(), x, y, z, seconds, easing);
    }

    public int tweenScaleTo(float x, float y, float z, float seconds, int easing) {
        return tweens.tweenScale(transform(), x, y, z, seconds, easing);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private PhysicsSystem.PhysicsBody dynamicBody() {
        PhysicsSystem physics = (sceneWorld != null) ? sceneWorld.getPhysicsWorld() : null;
        PhysicsSystem.PhysicsBody body = (physics != null) ? physics.getBody(entity) : null;
        if (body == null || body.rigidBody == null || body.rigidBody.motionType != RigidBodyComponent.MotionType.DYNAMIC) return null;
        return body;
    }

    /** One world-position component (0 = x, 1 = y, 2 = z) of {@code target}. */
    private float worldComponent(Entity target, int axis) {
        if (target == null) return 0.0f;
        if (sceneWorld != null) {
            SceneWorld.WorldTransform wt = sceneWorld.getComputedWorldTransform(target);
            if (wt != null) return axis == 0 ? wt.px() : (axis == 1 ? wt.py() : wt.pz());
        }
        Transform t = target.getTransform();
        if (t == null) return 0.0f;
        return axis == 0 ? t.posX : (axis == 1 ? t.posY : t.posZ);
    }

    /** World rotation (0 = pitch, 1 = yaw) of this entity's parent, 0 for root entities. */
    private float parentWorldRotation(int axis) {
        if (entity == null || entity.parentId == null || entity.parentId.isEmpty() || sceneWorld == null) return 0.0f;
        Entity parent = sceneWorld.findEntityById(entity.parentId);
        if (parent == null) return 0.0f;
        SceneWorld.WorldTransform wt = sceneWorld.getComputedWorldTransform(parent);
        if (wt == null) return 0.0f;
        return axis == 0 ? wt.rx() : wt.ry();
    }
}
