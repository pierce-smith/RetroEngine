package com.retro.engine.scripting;

import com.retro.engine.physics.RaycastHit;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;

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

    public float[] getForward() {
        Transform t = getTransform();
        if (t == null) return new float[]{0.0f, 0.0f, 1.0f};
        float radY = (float) Math.toRadians(t.rotY);
        float radX = (float) Math.toRadians(t.rotX);
        float x = (float) (Math.sin(radY) * Math.cos(radX));
        float y = (float) (-Math.sin(radX));
        float z = (float) (Math.cos(radY) * Math.cos(radX));
        return new float[]{x, y, z};
    }

    public float[] getRight() {
        float[] f = getForward();
        return new float[]{f[2], 0.0f, -f[0]};
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
