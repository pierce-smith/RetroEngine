package com.retro.engine.scripting;

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
        float radY = (float) Math.toRadians(t.rotY);
        float radX = (float) Math.toRadians(t.rotX);
        out[0] = (float) (Math.sin(radY) * Math.cos(radX));
        out[1] = (float) (-Math.sin(radX));
        out[2] = (float) (Math.cos(radY) * Math.cos(radX));
        return out;
    }

    /** Right vector as a new array. In onUpdate prefer {@link #getRight(float[])}, which does not allocate. */
    public float[] getRight() {
        return getRight(new float[3]);
    }

    /** Writes the right vector (forward turned 90 degrees about Y) into {@code out[0..2]} and returns {@code out}. */
    public float[] getRight(float[] out) {
        getForward(out);
        float fx = out[0];
        out[0] = out[2];
        out[1] = 0.0f;
        out[2] = -fx;
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
