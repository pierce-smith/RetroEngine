package com.retro.engine.physics;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.Input;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Vice City Style Character Controller Simulation Solver.
 * Evaluates downward ground probe raycasting, step offset snapping,
 * gravity velocity integration, and horizontal friction damping.
 * <p>
 * Classic Xbox layout (keyboard equivalents in brackets):
 * <ul>
 *   <li>Left Stick [WASD]: move relative to the active camera's yaw (world axes when the scene has no camera)</li>
 *   <li>A [Space]: jump, with a light rumble on takeoff</li>
 *   <li>X [Left Shift]: sprint, scaling moveSpeed by {@link CharacterControllerComponent#sprintSpeedMultiplier}</li>
 *   <li>Right Stick [mouse-look]: orbit / pitch the camera on or under the character's transform tree</li>
 * </ul>
 * A character with such a camera locks the cursor for mouse-look when play starts (Escape frees it, a click re-locks).
 * Landing from a fall plays a heavy rumble scaled by the impact speed.
 */
public class CharacterControllerSolver {

    private static final float GROUND_SKIN = 0.05f;

    // Right Stick orbit rates at full deflection (degrees / second) and pitch range (positive looks down)
    private static final float ORBIT_YAW_SPEED = 180.0f;
    private static final float ORBIT_PITCH_SPEED = 120.0f;
    private static final float MIN_PITCH = -40.0f;
    private static final float MAX_PITCH = 75.0f;
    private static final float MOUSE_LOOK_DEGREES_PER_PIXEL = 0.15f;

    // Light (right motor) burst on jump takeoff
    private static final float JUMP_RUMBLE_STRENGTH = 0.4f;
    private static final float JUMP_RUMBLE_DURATION = 0.15f;
    // Heavy (left motor) burst on landing, ramping between these downward speeds (m/s); slower touchdowns stay silent
    private static final float LANDING_RUMBLE_MIN_SPEED = 3.0f;
    private static final float LANDING_RUMBLE_MAX_SPEED = 15.0f;

    /**
     * Camera that drives one character: {@code yawChain} holds the camera's transform and every ancestor's, whose rotY
     * sum is the camera's world yaw (the hierarchy composes Euler angles additively). {@code orbit} is set only when the
     * camera sits on the character's own transform tree, so the Right Stick may move it.
     */
    private record CameraRig(List<Transform> yawChain, OrbitState orbit) {}

    private static final CameraRig NO_CAMERA = new CameraRig(List.of(), null);

    /**
     * Orbit around a pivot in the camera's parent space. The pivot is captured from the authored pose (the point along
     * the camera's view ray closest to its parent's origin), so the first orbit step continues from that framing.
     */
    private static final class OrbitState {
        final Transform camera;
        final boolean onCharacter; // Camera on the character entity itself: the stick turns the whole body
        float yaw, pitch, distance, pivotX, pivotY, pivotZ;
        final float[] dir = new float[3]; // Scratch view direction, reused every orbit step

        OrbitState(Transform camera, boolean onCharacter) {
            this.camera = camera;
            this.onCharacter = onCharacter;
            this.yaw = camera.rotY;
            this.pitch = camera.rotX;
            float[] f = forward(yaw, pitch, dir);
            distance = Math.max(0.0f, -(camera.posX * f[0] + camera.posY * f[1] + camera.posZ * f[2]));
            pivotX = camera.posX + f[0] * distance;
            pivotY = camera.posY + f[1] * distance;
            pivotZ = camera.posZ + f[2] * distance;
        }
    }

    // Resolved once per character per play session; hierarchy edits during play are not picked up
    private final Map<CharacterControllerComponent, CameraRig> rigs = new IdentityHashMap<>();
    private List<Entity> sceneEntities = List.of();

    // Jump is an edge, sampled per frame and held until the next physics step so frames without a step do not drop it
    private boolean jumpRequested = false;
    private boolean prevJumpKeyDown = false;
    // Mouse motion arrives per frame, so it is banked in degrees and spent by the next physics step
    private float pendingLookYaw = 0.0f, pendingLookPitch = 0.0f;
    private boolean cursorLockRequested = false;

    /** Called once per frame before the fixed steps run. */
    public void beginFrame(List<Entity> sceneEntities) {
        this.sceneEntities = sceneEntities != null ? sceneEntities : List.of();
        boolean jumpKeyDown = Input.isKeyDown(GLFW_KEY_SPACE);
        GamepadManager pad = Input.getGamepad();
        if ((jumpKeyDown && !prevJumpKeyDown) || (pad != null && pad.isButtonJustPressed(GamepadManager.BUTTON_X))) {
            jumpRequested = true;
        }
        prevJumpKeyDown = jumpKeyDown;
        // Mouse right turns right, mouse up looks up (screen Y grows downward, positive pitch looks down)
        pendingLookYaw += Input.getMouseDeltaX() * MOUSE_LOOK_DEGREES_PER_PIXEL;
        pendingLookPitch += Input.getMouseDeltaY() * MOUSE_LOOK_DEGREES_PER_PIXEL;
    }

    /** Drops cached camera rigs and latched input; call when the physics world is rebuilt or torn down. */
    public void reset() {
        rigs.clear();
        jumpRequested = false;
        prevJumpKeyDown = false;
        pendingLookYaw = 0.0f;
        pendingLookPitch = 0.0f;
        cursorLockRequested = false;
    }

    public void updateControllers(List<PhysicsSystem.PhysicsBody> activeBodies, float dt, PhysicsSystem physicsSystem, RaycastHit sharedRaycastHit) {
        // Movement intent in camera space (x = right, y = forward), sampled once per step: WASD digital + Left Stick
        // analog (stick Y is +1 when pushed down), clamped to unit length
        float inputRight = 0.0f, inputForward = 0.0f;
        if (Input.isKeyDown(GLFW_KEY_D)) inputRight += 1.0f;
        if (Input.isKeyDown(GLFW_KEY_A)) inputRight -= 1.0f;
        if (Input.isKeyDown(GLFW_KEY_W)) inputForward += 1.0f;
        if (Input.isKeyDown(GLFW_KEY_S)) inputForward -= 1.0f;
        boolean sprint = Input.isKeyDown(GLFW_KEY_LEFT_SHIFT);
        float lookX = 0.0f, lookY = 0.0f;
        GamepadManager pad = Input.getGamepad();
        if (pad != null && pad.isConnected()) {
            inputRight += pad.getAxis(GamepadManager.AXIS_LEFT_X);
            inputForward -= pad.getAxis(GamepadManager.AXIS_LEFT_Y);
            sprint |= pad.isButtonPressed(GamepadManager.BUTTON_A);
            lookX = pad.getAxis(GamepadManager.AXIS_RIGHT_X);
            lookY = pad.getAxis(GamepadManager.AXIS_RIGHT_Y);
        } else {
            pad = null;
        }
        float inputLen = (float) Math.sqrt(inputRight * inputRight + inputForward * inputForward);
        if (inputLen > 1.0f) {
            inputRight /= inputLen;
            inputForward /= inputLen;
        }
        boolean hasMoveInput = inputLen > 0.0f;
        float lookYaw = lookX * ORBIT_YAW_SPEED * dt + pendingLookYaw;
        float lookPitch = lookY * ORBIT_PITCH_SPEED * dt + pendingLookPitch;
        pendingLookYaw = 0.0f;
        pendingLookPitch = 0.0f;
        boolean hasLookInput = lookYaw != 0.0f || lookPitch != 0.0f;
        boolean jump = jumpRequested;
        jumpRequested = false;

        PhysicsSystem.PhysicsBody inputOwner = findInputOwner(activeBodies);

        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsSystem.PhysicsBody body = activeBodies.get(i);
            CharacterControllerComponent cc = body.characterController;
            if (cc == null) continue;

            CameraRig rig = rigOf(body);
            boolean owned = body == inputOwner && cc.inputEnabled;
            if (rig.orbit() != null && !cursorLockRequested) {
                cursorLockRequested = true; // Once per session, so a script that frees the cursor keeps it free
                Input.setCursorLocked(true);
            }
            if (hasLookInput && owned && rig.orbit() != null) {
                applyOrbit(rig.orbit(), body, lookYaw, lookPitch);
            }

            // Only override while there is input, so script-set velocities and friction coasting still apply otherwise
            if (hasMoveInput && owned) {
                float yaw = (float) Math.toRadians(worldYaw(rig));
                float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
                // Camera right = (cos, 0, sin), forward = (sin, 0, -cos): yaw 0 looks down -Z
                float speed = cc.moveSpeed * (sprint ? cc.sprintSpeedMultiplier : 1.0f);
                cc.velocityX = (inputRight * cos + inputForward * sin) * speed;
                cc.velocityZ = (inputRight * sin - inputForward * cos) * speed;
            }

            if (jump && owned && cc.isGrounded) {
                cc.velocityY = cc.jumpForce;
                cc.isGrounded = false;
                if (pad != null) pad.setVibration(0.0f, JUMP_RUMBLE_STRENGTH, JUMP_RUMBLE_DURATION);
            }

            // Probe from the body centre: the ray must start outside the floor, and the collider's real half
            // height (box or capsule, scaled) is the distance from centre to feet
            float halfHeight = physicsSystem.getHalfWidthY(body);
            cc.velocityY += PhysicsSystem.GRAVITY * cc.gravityMultiplier * dt;
            float fallDistance = Math.max(0.0f, -cc.velocityY * dt);
            // Grounded bodies also look stepOffset below the feet so they stick to the ground walking down steps
            float reach = halfHeight + GROUND_SKIN + Math.max(fallDistance, cc.isGrounded ? cc.stepOffset : 0.0f);

            physicsSystem.raycastExcludingInPlace(body.entity, body.px, body.py, body.pz, 0.0f, -1.0f, 0.0f, reach, sharedRaycastHit);

            if (sharedRaycastHit.hit && cc.velocityY <= 0.0f) {
                if (!cc.isGrounded && pad != null) {
                    playLandingRumble(pad, -cc.velocityY);
                }
                cc.isGrounded = true;
                cc.velocityY = 0.0f;
                body.py = sharedRaycastHit.pointY + halfHeight;
            } else {
                cc.isGrounded = false;
                body.py += cc.velocityY * dt;
            }

            body.px += cc.velocityX * dt;
            body.pz += cc.velocityZ * dt;

            cc.velocityX *= Math.max(0.0f, 1.0f - 10.0f * dt);
            cc.velocityZ *= Math.max(0.0f, 1.0f - 10.0f * dt);

            if (body.treeNodeId != -1) {
                float minX = body.px - physicsSystem.getHalfWidthX(body);
                float maxX = body.px + physicsSystem.getHalfWidthX(body);
                float minY = body.py - physicsSystem.getHalfWidthY(body);
                float maxY = body.py + physicsSystem.getHalfWidthY(body);
                float minZ = body.pz - physicsSystem.getHalfWidthZ(body);
                float maxZ = body.pz + physicsSystem.getHalfWidthZ(body);
                physicsSystem.getBroadphaseSolver().updateLeaf(body.treeNodeId, minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
    }

    /**
     * The one character the keyboard and gamepad drive: the first with a camera on its own transform tree, else the
     * first in the scene. Nav-driven characters never qualify. Found every step, so a respawned player takes over.
     */
    private PhysicsSystem.PhysicsBody findInputOwner(List<PhysicsSystem.PhysicsBody> activeBodies) {
        PhysicsSystem.PhysicsBody first = null;
        for (int i = 0, n = activeBodies.size(); i < n; i++) {
            PhysicsSystem.PhysicsBody body = activeBodies.get(i);
            if (body.characterController == null || body.navDriven) continue;
            if (rigOf(body).orbit() != null) return body;
            if (first == null) first = body;
        }
        return first;
    }

    private CameraRig rigOf(PhysicsSystem.PhysicsBody body) {
        // get/put rather than computeIfAbsent: the capturing lambda would allocate every step
        CameraRig rig = rigs.get(body.characterController);
        if (rig == null) {
            rig = resolveCameraRig(body.entity);
            rigs.put(body.characterController, rig);
        }
        return rig;
    }

    private static void playLandingRumble(GamepadManager pad, float impactSpeed) {
        if (impactSpeed < LANDING_RUMBLE_MIN_SPEED) return;
        float t = Math.min(1.0f, (impactSpeed - LANDING_RUMBLE_MIN_SPEED) / (LANDING_RUMBLE_MAX_SPEED - LANDING_RUMBLE_MIN_SPEED));
        pad.setVibration(0.3f + 0.7f * t, 0.2f * t, 0.1f + 0.15f * t);
    }

    /**
     * Turns the view by the given degrees (positive yaw turns right, positive pitch looks down). A child camera swings
     * around its pivot and keeps facing it; a camera on the character itself turns the body (yaw only, so the collider
     * never tilts).
     */
    private static void applyOrbit(OrbitState orbit, PhysicsSystem.PhysicsBody body, float yawDelta, float pitchDelta) {
        orbit.yaw = wrapDegrees(orbit.yaw + yawDelta);
        if (orbit.onCharacter) {
            // The body's rotation is synced over the transform after the step, so turn the body
            body.ry = orbit.yaw;
            return;
        }
        // An authored pitch outside the range is kept rather than snapped; the stick only moves it back toward the range
        float lo = Math.min(MIN_PITCH, orbit.pitch), hi = Math.max(MAX_PITCH, orbit.pitch);
        orbit.pitch = Math.max(lo, Math.min(hi, orbit.pitch + pitchDelta));
        float[] f = forward(orbit.yaw, orbit.pitch, orbit.dir);
        Transform t = orbit.camera;
        t.setPosition(orbit.pivotX - f[0] * orbit.distance, orbit.pivotY - f[1] * orbit.distance, orbit.pivotZ - f[2] * orbit.distance);
        t.setRotation(orbit.pitch, orbit.yaw, t.rotZ);
    }

    /**
     * Camera for a character: the one on the character or its descendants (primary first), else the scene's main
     * camera (first primary, then any) for movement direction only, else none (world axes).
     */
    private CameraRig resolveCameraRig(Entity character) {
        Entity own = null, ownPrimary = null, scene = null, scenePrimary = null;
        for (int i = 0, n = sceneEntities.size(); i < n; i++) {
            Entity e = sceneEntities.get(i);
            if (e == null) continue;
            Camera cam = e.getComponent(Camera.class);
            if (cam == null || e.getTransform() == null) continue;
            if (e == character || e.isDescendantOf(character, sceneEntities)) {
                if (own == null) own = e;
                if (cam.isPrimary && ownPrimary == null) ownPrimary = e;
            }
            if (scene == null) scene = e;
            if (cam.isPrimary && scenePrimary == null) scenePrimary = e;
        }
        Entity camera = ownPrimary != null ? ownPrimary : own;
        if (camera != null) {
            return new CameraRig(yawChain(camera), new OrbitState(camera.getTransform(), camera == character));
        }
        camera = scenePrimary != null ? scenePrimary : scene;
        return camera != null ? new CameraRig(yawChain(camera), null) : NO_CAMERA;
    }

    /** Transforms from {@code entity} up to the root; bounded by the scene size so a corrupt parent loop terminates. */
    private List<Transform> yawChain(Entity entity) {
        List<Transform> chain = new ArrayList<>();
        for (Entity e = entity; e != null && chain.size() <= sceneEntities.size(); e = findEntity(e.parentId)) {
            if (e.getTransform() != null) chain.add(e.getTransform());
        }
        return chain;
    }

    private Entity findEntity(String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = 0, n = sceneEntities.size(); i < n; i++) {
            Entity e = sceneEntities.get(i);
            if (e != null && id.equals(e.id)) return e;
        }
        return null;
    }

    private static float worldYaw(CameraRig rig) {
        float yaw = 0.0f;
        List<Transform> chain = rig.yawChain();
        for (int i = 0, n = chain.size(); i < n; i++) yaw += chain.get(i).rotY;
        return yaw;
    }

    /** View direction for a camera rotation, matching SceneRenderPipeline.computeCameraView (R = Ry * Rx, looks down -Z). */
    private static float[] forward(float yawDeg, float pitchDeg, float[] out) {
        float yaw = (float) Math.toRadians(yawDeg), pitch = (float) Math.toRadians(pitchDeg);
        float cp = (float) Math.cos(pitch);
        out[0] = (float) Math.sin(yaw) * cp;
        out[1] = (float) -Math.sin(pitch);
        out[2] = (float) -Math.cos(yaw) * cp;
        return out;
    }

    private static float wrapDegrees(float deg) {
        deg %= 360.0f;
        if (deg >= 180.0f) deg -= 360.0f;
        else if (deg < -180.0f) deg += 360.0f;
        return deg;
    }
}
