package com.retro.engine.scripting;

import com.retro.engine.audio.AudioBus;
import com.retro.engine.audio.AudioEngine;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.graphics.DecalSpec;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.physics.RaycastHit;

/**
 * Base class for all user gameplay scripts in RetroEngine.
 * Provides virtual lifecycle hooks, spatial transform accessors,
 * component queries, physics raycasting, and logging utilities.
 */
public abstract class EntityScript {

    protected Entity entity;
    protected SceneWorld sceneWorld;

    public void init(Entity entity, SceneWorld sceneWorld) {
        this.entity = entity;
        this.sceneWorld = sceneWorld;
        SceneManager.bind(sceneWorld);
        onAwake();
    }

    // =========================================================================
    // Virtual Lifecycle & Physics Hooks
    // =========================================================================

    public void onAwake() {}
    public void onEnable() {}
    public void onDisable() {}
    public void onStart() {}
    public void onUpdate(float deltaTime) {}
    public void onLateUpdate(float deltaTime) {}
    public void onPhysicsUpdate(float fixedDeltaTime) {}

    public void onCollisionEnter(Entity other) {}
    public void onCollisionStay(Entity other) {}
    public void onCollisionExit(Entity other) {}

    public void onTriggerEnter(Entity other) {}
    public void onTriggerStay(Entity other) {}
    public void onTriggerExit(Entity other) {}

    public void onMouseEnter() {}
    public void onMouseExit() {}
    public void onMouseDown() {}
    public void onMouseUp() {}

    public void onDrawGizmos() {}
    public void onDestroy() {}

    // =========================================================================
    // Framework Hooks (engine-internal)
    // =========================================================================

    /**
     * Called by the ScriptEngine every frame just before {@link #onUpdate(float)}. Framework base classes (e.g.
     * {@code SimpleEntityScript}) tick their timers and tweens here so subclasses need not call super.onUpdate.
     * Gameplay scripts should override onUpdate instead.
     */
    public void onFrameworkUpdate(float deltaTime) {}

    /** Called by the ScriptEngine right after {@link #onDestroy()} so framework base classes can release resources. */
    public void onFrameworkDestroy() {}

    // =========================================================================
    // Transform & Spatial Accessors
    // =========================================================================

    public float getPositionX() {
        Transform t = getTransform();
        return t != null ? t.posX : 0.0f;
    }

    public float getPositionY() {
        Transform t = getTransform();
        return t != null ? t.posY : 0.0f;
    }

    public float getPositionZ() {
        Transform t = getTransform();
        return t != null ? t.posZ : 0.0f;
    }

    public void setPosition(float x, float y, float z) {
        Transform t = getTransform();
        if (t != null) {
            t.posX = x;
            t.posY = y;
            t.posZ = z;
        }
    }

    public void translate(float dx, float dy, float dz) {
        Transform t = getTransform();
        if (t != null) {
            t.posX += dx;
            t.posY += dy;
            t.posZ += dz;
        }
    }

    public float getRotationX() {
        Transform t = getTransform();
        return t != null ? t.rotX : 0.0f;
    }

    public float getRotationY() {
        Transform t = getTransform();
        return t != null ? t.rotY : 0.0f;
    }

    public float getRotationZ() {
        Transform t = getTransform();
        return t != null ? t.rotZ : 0.0f;
    }

    public void setRotation(float rx, float ry, float rz) {
        Transform t = getTransform();
        if (t != null) {
            t.setRotation(rx, ry, rz);
        }
    }

    public void rotate(float drx, float dry, float drz) {
        Transform t = getTransform();
        if (t != null) {
            t.setRotation(
                (t.rotX + drx) % 360.0f,
                (t.rotY + dry) % 360.0f,
                (t.rotZ + drz) % 360.0f
            );
        }
    }

    public float getScaleX() {
        Transform t = getTransform();
        return t != null ? t.scaleX : 1.0f;
    }

    public float getScaleY() {
        Transform t = getTransform();
        return t != null ? t.scaleY : 1.0f;
    }

    public float getScaleZ() {
        Transform t = getTransform();
        return t != null ? t.scaleZ : 1.0f;
    }

    public void setScale(float sx, float sy, float sz) {
        Transform t = getTransform();
        if (t != null) {
            t.scaleX = sx;
            t.scaleY = sy;
            t.scaleZ = sz;
        }
    }

    /** Unit forward vector as a new array. In onUpdate prefer {@link #getForward(float[])}, which does not allocate. */
    public float[] getForward() {
        return getForward(new float[3]);
    }

    /** Writes the unit forward vector into {@code out[0..2]} and returns {@code out}. Allocation-free. */
    public float[] getForward(float[] out) {
        Transform t = getTransform();
        if (t == null) {
            out[0] = 0.0f; out[1] = 0.0f; out[2] = 1.0f;
            return out;
        }
        // Matches the renderers' rotation R = Rz(-z) * Ry(-y) * Rx(-x): models face local +Z, cameras look down local -Z
        float radY = (float) Math.toRadians(t.rotY);
        float radX = (float) Math.toRadians(t.rotX);
        float sign = (entity != null && entity.getComponent(com.retro.engine.ecs.components.Camera.class) != null) ? -1.0f : 1.0f;
        out[0] = sign * (float) (-Math.sin(radY) * Math.cos(radX));
        out[1] = sign * (float) Math.sin(radX);
        out[2] = sign * (float) (Math.cos(radY) * Math.cos(radX));
        return out;
    }

    /** Right vector as a new array. In onUpdate prefer {@link #getRight(float[])}, which does not allocate. */
    public float[] getRight() {
        return getRight(new float[3]);
    }

    /** Writes the right vector (forward turned 90 degrees about Y) into {@code out[0..2]} and returns {@code out}. */
    public float[] getRight(float[] out) {
        getForward(out);
        float fx = out[0], fz = out[2];
        float len = (float) Math.sqrt(fx * fx + fz * fz);
        if (len < 1e-6f) len = 1.0f;
        out[0] = -fz / len;   // forward x up, flattened: the viewer's right
        out[1] = 0.0f;
        out[2] = fx / len;
        return out;
    }

    public float[] getUp() {
        return new float[]{0.0f, 1.0f, 0.0f};
    }

    // =========================================================================
    // Component Queries & Shorthands
    // =========================================================================

    public <T extends Component> T getComponent(Class<T> clazz) {
        return entity != null ? entity.getComponent(clazz) : null;
    }

    public <T extends Component> boolean hasComponent(Class<T> clazz) {
        return entity != null && entity.getComponent(clazz) != null;
    }

    public <T extends Component> T getOrAddComponent(Class<T> clazz) {
        if (entity == null) return null;
        T comp = entity.getComponent(clazz);
        if (comp == null) {
            try {
                comp = clazz.getDeclaredConstructor().newInstance();
                entity.addComponent(comp);
            } catch (Exception e) {
                System.err.println("[EntityScript] Could not instantiate component: " + clazz.getName());
            }
        }
        return comp;
    }

    public Transform getTransform() {
        return entity != null ? entity.getTransform() : null;
    }

    public RigidBodyComponent getRigidBody() {
        return getComponent(RigidBodyComponent.class);
    }

    public MeshFilter getMeshFilter() {
        return getComponent(MeshFilter.class);
    }

    public Material getMaterial() {
        return getComponent(Material.class);
    }

    public Light getLight() {
        return getComponent(Light.class);
    }

    public Camera getCamera() {
        return getComponent(Camera.class);
    }

    public AudioSource getAudioSource() {
        return getComponent(AudioSource.class);
    }

    // =========================================================================
    // Audio
    // =========================================================================

    /** Plays a clip once at full volume from this entity's position. */
    public boolean playSound(String path) {
        return playSound(path, 1.0f, true);
    }

    /**
     * Plays a clip once (fire-and-forget; .ogg/.wav). Spatial clips play from this entity's current world position,
     * using its AudioSource's MIN/MAX DIST when it has one (else 1 / 25); non-spatial clips play flat (music, UI).
     * Returns false when the clip could not be loaded or audio is unavailable.
     */
    public boolean playSound(String path, float volume, boolean spatial) {
        return playSound(path, volume, spatial, AudioBus.SFX, false);
    }

    /**
     * Like {@link #playSound(String, float, boolean)} but routed through a mixer bus. {@code ducksBgm} marks a
     * critical clip that lowers the BGM bus while it plays (VOICE clips always do).
     */
    public boolean playSound(String path, float volume, boolean spatial, AudioBus bus, boolean ducksBgm) {
        float x = 0.0f, y = 0.0f, z = 0.0f;
        if (spatial && entity != null) {
            SceneWorld.WorldTransform wt = (sceneWorld != null) ? sceneWorld.getComputedWorldTransform(entity) : null;
            if (wt != null) {
                x = wt.px(); y = wt.py(); z = wt.pz();
            } else if (entity.getTransform() != null) {
                Transform t = entity.getTransform();
                x = t.posX; y = t.posY; z = t.posZ;
            }
        }
        return playSoundAt(path, volume, x, y, z, spatial, bus, ducksBgm);
    }

    /** Plays a spatial clip once at an arbitrary world position. */
    public boolean playSoundAt(String path, float volume, float x, float y, float z) {
        return playSoundAt(path, volume, x, y, z, true, AudioBus.SFX, false);
    }

    /** Plays a spatial clip once at an arbitrary world position through a mixer bus. */
    public boolean playSoundAt(String path, float volume, float x, float y, float z, AudioBus bus) {
        return playSoundAt(path, volume, x, y, z, true, bus, false);
    }

    private boolean playSoundAt(String path, float volume, float x, float y, float z, boolean spatial,
                                AudioBus bus, boolean ducksBgm) {
        AudioSource src = (entity != null) ? entity.getComponent(AudioSource.class) : null;
        float min = (src != null) ? src.minDistance : 1.0f;
        float max = (src != null) ? src.maxDistance : 25.0f;
        return AudioEngine.playOneShot(path, volume, spatial, x, y, z, min, max, bus, ducksBgm);
    }

    /** Stops every clip started with playSound / playSoundAt (component AudioSources are unaffected). */
    public void stopAllSounds() {
        AudioEngine.stopAllOneShots();
    }

    public CharacterControllerComponent getCharacterController() {
        return getComponent(CharacterControllerComponent.class);
    }

    public VehicleControllerComponent getVehicleController() {
        return getComponent(VehicleControllerComponent.class);
    }

    public AnimatorComponent getAnimator() {
        return getComponent(AnimatorComponent.class);
    }

    // =========================================================================
    // Physics & Raycasting Utilities
    // =========================================================================

    public RaycastHit raycast(float rayOriginX, float rayOriginY, float rayOriginZ,
                              float rayDirX, float rayDirY, float rayDirZ, float maxDistance) {
        if (sceneWorld != null && sceneWorld.getPhysicsWorld() != null) {
            return sceneWorld.getPhysicsWorld().raycastExcluding(entity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance);
        }
        return new RaycastHit();
    }

    /**
     * Allocation-free raycast for per-frame use: fills {@code out} (reuse one RaycastHit field per script) and returns
     * whether something was hit. Ignores this script's own entity.
     */
    public boolean raycast(float rayOriginX, float rayOriginY, float rayOriginZ,
                           float rayDirX, float rayDirY, float rayDirZ, float maxDistance, RaycastHit out) {
        if (out == null) return false;
        if (sceneWorld == null || sceneWorld.getPhysicsWorld() == null) {
            out.hit = false;
            out.entity = null;
            out.distance = Float.MAX_VALUE;
            return false;
        }
        sceneWorld.getPhysicsWorld().raycastExcludingInPlace(entity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, out);
        return out.hit;
    }

    /**
     * The same raycast limited to some collision layers. {@code layerMask} has one bit per layer, so
     * {@code (1 << 0) | (1 << 3)} sees layers 0 and 3 only.
     */
    public boolean raycast(float rayOriginX, float rayOriginY, float rayOriginZ,
                           float rayDirX, float rayDirY, float rayDirZ, float maxDistance, RaycastHit out, int layerMask) {
        if (out == null) return false;
        if (sceneWorld == null || sceneWorld.getPhysicsWorld() == null) {
            out.hit = false;
            out.entity = null;
            out.distance = Float.MAX_VALUE;
            return false;
        }
        sceneWorld.getPhysicsWorld().raycastExcludingInPlace(entity, rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ, maxDistance, out, layerMask);
        return out.hit;
    }

    // =========================================================================
    // Decals
    // =========================================================================

    /**
     * Stamps a decal where a raycast landed, at a random rotation so repeats don't look identical. {@code texture} is a
     * texture path or a built-in such as {@code DecalTextures.BLOOD}; {@code size} is its width in world units. Returns
     * false when the raycast missed.
     */
    public boolean spawnDecal(String texture, RaycastHit hit, float size) {
        if (hit == null || !hit.hit) return false;
        DecalSpec spec = new DecalSpec(texture, size).setHit(hit);
        spec.rotation = (float) (Math.random() * 360.0);
        spawnDecal(spec);
        return true;
    }

    /** Stamps a decal with every option set by hand. The spec is copied, so keep one per weapon and reuse it. */
    public void spawnDecal(DecalSpec spec) {
        if (sceneWorld != null) sceneWorld.getDecals().spawn(spec);
    }

    // =========================================================================
    // Controller Haptics
    // =========================================================================

    /** Rumbles the active gamepad (heavy left motor, light right motor, both 0..1) for {@code duration} seconds. */
    public void vibrate(float lowFreq, float highFreq, float duration) {
        Input.vibrate(lowFreq, highFreq, duration);
    }

    public void stopVibration() {
        Input.stopVibration();
    }

    // =========================================================================
    // Scene Management
    // =========================================================================

    /** Requests a level transition; the scene swaps at the end of the current frame, so it is safe from any callback. */
    public void loadScene(String scenePath) {
        if (sceneWorld == null) {
            logError("loadScene('" + scenePath + "') failed: script is not bound to a SceneWorld.");
            return;
        }
        sceneWorld.requestSceneLoad(scenePath);
    }

    // =========================================================================
    // Logging Utilities
    // =========================================================================

    public void logInfo(String message) {
        System.out.println("[" + (entity != null ? entity.name : "Script") + "] " + message);
    }

    public void logWarning(String message) {
        System.out.println("[WARNING][" + (entity != null ? entity.name : "Script") + "] " + message);
    }

    public void logError(String message) {
        System.err.println("[ERROR][" + (entity != null ? entity.name : "Script") + "] " + message);
    }
}
