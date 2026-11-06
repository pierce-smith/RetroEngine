package com.retro.engine.scripting.user;

import com.retro.engine.animation.AnimatorState;
import com.retro.engine.core.GamepadManager;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.physics.RaycastHit;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Third-person layer over the built-in CharacterController, which keeps handling movement, jump and the camera orbit.
 * Put it on the ThirdPersonController prefab root: Player (controller) -> Main Camera, Hero (rigged mesh + Animator).
 * The root must not rotate, because the orbit and move direction are measured from it; only the Hero child turns.
 *
 * <p>WASD moves relative to the camera, Left Shift sprints, Left Ctrl or C (gamepad B) sneaks and wins over sprint.
 *
 * <p>Animation: idle, walk, sneak and sprint crossfade by ground speed. The gait clips all start on the same foot, so a
 * fade between them keeps the stride phase and the feet never swap, and playback follows ground speed so they don't
 * slide. Stopping during a sneak holds the crouch with both feet down instead of standing up.
 */
public class ThirdPersonScript extends SimpleEntityScript {

    public String idleClip = "anim_idle";
    public String walkClip = "anim_walk";
    public String sneakClip = "anim_sneak";
    public String sprintClip = "anim_sprint";

    public float walkSpeed = 1.4f;
    public float sneakSpeed = 0.6f;
    public float sprintSpeed = 4.5f;

    // Ground speed each clip was authored for (printed by tools/blender/generate_hero_character.py)
    public float walkClipSpeed = 1.02f;
    public float sneakClipSpeed = 0.39f;
    public float sprintClipSpeed = 4.06f;

    public float idleFade = 0.25f;     // seconds to blend in or out of idle
    public float gaitFade = 0.2f;      // seconds to blend walk <-> sprint
    public float sneakFade = 0.3f;     // seconds to blend in or out of sneak
    public float turnSpeed = 720.0f;   // degrees per second the Hero turns toward its direction of travel
    public float cameraMargin = 0.2f;  // gap kept between the camera and whatever it would clip into

    private static final int KEY_C = 67, KEY_LEFT_CONTROL = 341;
    private static final float MOVE_START = 0.2f, MOVE_STOP = 0.08f; // m/s; the gap stops flicker near zero
    private static final float MIN_PLAYBACK = 0.5f, MAX_PLAYBACK = 1.75f;
    private static final float MIN_CAMERA_DISTANCE = 0.3f;
    // Idle has the right foot forward, which is this far into a stride, so the first step leaves with the left foot
    private static final float STEP_OFF_PHASE = 0.55f;
    // Sneak phases with both feet planted, where a stopped crouch may hold
    private static final float PLANTED_A = 0.06f, PLANTED_B = 0.56f, PLANTED_SPAN = 0.1f;
    private static final float SNEAK_SETTLE_PLAYBACK = 0.6f;

    private CharacterControllerComponent controller;
    private AnimatorComponent animator;
    private Transform hero, camera;

    private String state;
    private boolean moving;
    private float lastX, lastZ, velocityX, velocityZ, groundSpeed;
    private float pivotX, pivotY, pivotZ, cameraDistance;
    private final float[] viewDir = new float[3];
    private final RaycastHit cameraHit = new RaycastHit();

    @Override
    public void onStart() {
        controller = characterController();
        Entity heroEntity = findDescendantAnimator();
        Entity cameraEntity = findChildCamera();
        if (controller == null || heroEntity == null) {
            logWarning("ThirdPersonScript needs a CharacterController on '" + entity.name + "' and a rigged child.");
            return;
        }
        animator = (AnimatorComponent) heroEntity.getComponent(AnimatorComponent.class);
        hero = heroEntity.getTransform();
        if (animator.controller == null || !hasClips()) {
            logWarning("Animator on '" + heroEntity.name + "' is missing a locomotion clip; it won't animate.");
            animator = null;
        } else {
            state = idleClip;
            animator.playAnimation(idleClip);
        }
        lastX = getWorldX();
        lastZ = getWorldZ();
        if (cameraEntity != null) {
            camera = cameraEntity.getTransform();
            captureOrbit();
        }
    }

    @Override
    public void onUpdate(float dt) {
        if (controller == null) return;
        updateVelocity(dt);
        boolean sneaking = isSneakHeld();
        controller.moveSpeed = sneaking ? sneakSpeed : walkSpeed;
        controller.sprintSpeedMultiplier = sneaking ? 1.0f : sprintSpeed / walkSpeed;
        updateFacing(dt);
        if (animator != null) updateAnimation(sneaking);
        if (camera != null) updateCamera();
    }

    private boolean isSneakHeld() {
        if (Input.isKeyDown(KEY_LEFT_CONTROL) || Input.isKeyDown(KEY_C)) return true;
        GamepadManager pad = Input.getGamepad();
        return pad != null && pad.isConnected() && pad.isButtonPressed(GamepadManager.BUTTON_B);
    }

    /** Measured from real motion, so walls and script-driven moves show up. Smoothed: physics steps don't land every frame. */
    private void updateVelocity(float dt) {
        if (dt <= 0.0f) return;
        float x = getWorldX(), z = getWorldZ();
        float blend = Math.min(1.0f, dt * 12.0f);
        velocityX += ((x - lastX) / dt - velocityX) * blend;
        velocityZ += ((z - lastZ) / dt - velocityZ) * blend;
        lastX = x;
        lastZ = z;
        groundSpeed = (float) Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        moving = groundSpeed > (moving ? MOVE_STOP : MOVE_START);
    }

    /** The mesh's nose is local +Z, which rotY points at (-sin, cos). */
    private void updateFacing(float dt) {
        if (groundSpeed < MOVE_START) return;
        float wanted = (float) Math.toDegrees(Math.atan2(-velocityX, velocityZ));
        float delta = wrapDegrees(wanted - hero.rotY);
        float step = turnSpeed * dt;
        float turned = hero.rotY + Math.max(-step, Math.min(step, delta));
        hero.setRotation(hero.rotX, wrapDegrees(turned), hero.rotZ);
    }

    private void updateAnimation(boolean sneaking) {
        String wanted = chooseState(sneaking);
        if (!wanted.equals(state)) changeState(wanted);
        // Lock the fade's stride phases together, since clips of different lengths drift apart otherwise
        if (animator.transitioning && isGait(animator.currentStateId) && isGait(animator.targetStateId)) {
            animator.targetTime = phaseOf(animator.currentStateId, animator.currentTime) * duration(animator.targetStateId);
        }
        animator.playbackSpeed = playbackFor(state);
    }

    private String chooseState(boolean sneaking) {
        if (!controller.isGrounded) return state; // no jump clip: the current one carries through the air
        if (sneaking) return sneakClip;
        if (!moving) return idleClip;
        return groundSpeed > (walkSpeed + sprintSpeed) * 0.5f ? sprintClip : walkClip;
    }

    private void changeState(String next) {
        boolean fromIdle = state.equals(idleClip);
        float fade = gaitFade;
        if (fromIdle || next.equals(idleClip)) fade = idleFade;
        if (state.equals(sneakClip) || next.equals(sneakClip)) fade = sneakFade;
        state = next;
        animator.crossFade(next, fade);
        if (animator.transitioning && fromIdle && !next.equals(sneakClip)) {
            animator.targetTime = STEP_OFF_PHASE * duration(next);
        }
    }

    private float playbackFor(String current) {
        if (current.equals(idleClip)) return 1.0f;
        if (current.equals(sneakClip) && !moving) {
            String shown = animator.transitioning ? animator.targetStateId : animator.currentStateId;
            float time = animator.transitioning ? animator.targetTime : animator.currentTime;
            return isPlanted(phaseOf(shown, time)) ? 0.0f : SNEAK_SETTLE_PLAYBACK;
        }
        float authored = current.equals(sneakClip) ? sneakClipSpeed : current.equals(sprintClip) ? sprintClipSpeed : walkClipSpeed;
        return Math.max(MIN_PLAYBACK, Math.min(MAX_PLAYBACK, groundSpeed / authored));
    }

    private static boolean isPlanted(float phase) {
        return (phase >= PLANTED_A && phase < PLANTED_A + PLANTED_SPAN) || (phase >= PLANTED_B && phase < PLANTED_B + PLANTED_SPAN);
    }

    private boolean isGait(String name) {
        return walkClip.equals(name) || sprintClip.equals(name) || sneakClip.equals(name);
    }

    private float duration(String name) {
        AnimatorState s = animator.controller.getState(name);
        return s != null && s.clip != null ? Math.max(0.001f, s.clip.durationSeconds) : 1.0f;
    }

    private float phaseOf(String name, float time) {
        float phase = time / duration(name);
        return phase - (float) Math.floor(phase);
    }

    private boolean hasClips() {
        return animator.controller.getState(idleClip) != null && animator.controller.getState(walkClip) != null
                && animator.controller.getState(sneakClip) != null && animator.controller.getState(sprintClip) != null;
    }

    /** The same pivot and distance the controller derives from the camera's authored pose. */
    private void captureOrbit() {
        forward(camera.rotY, camera.rotX, viewDir);
        cameraDistance = Math.max(0.0f, -(camera.posX * viewDir[0] + camera.posY * viewDir[1] + camera.posZ * viewDir[2]));
        pivotX = camera.posX + viewDir[0] * cameraDistance;
        pivotY = camera.posY + viewDir[1] * cameraDistance;
        pivotZ = camera.posZ + viewDir[2] * cameraDistance;
    }

    /** Rebuilds the camera from its orbit angles each frame, pulled in along the view ray when something is in the way. */
    private void updateCamera() {
        forward(camera.rotY, camera.rotX, viewDir);
        float reach = cameraDistance + cameraMargin;
        float distance = cameraDistance;
        if (raycast(getWorldX() + pivotX, getWorldY() + pivotY, getWorldZ() + pivotZ,
                -viewDir[0], -viewDir[1], -viewDir[2], reach, cameraHit)) {
            distance = Math.max(MIN_CAMERA_DISTANCE, Math.min(cameraDistance, cameraHit.distance - cameraMargin));
        }
        camera.setPosition(pivotX - viewDir[0] * distance, pivotY - viewDir[1] * distance, pivotZ - viewDir[2] * distance);
    }

    /** View direction for a camera rotation: yaw 0 looks down -Z and positive pitch looks down. */
    private static void forward(float yawDegrees, float pitchDegrees, float[] out) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        out[0] = (float) (Math.sin(yaw) * cosPitch);
        out[1] = (float) -Math.sin(pitch);
        out[2] = (float) (-Math.cos(yaw) * cosPitch);
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        else if (d < -180.0f) d += 360.0f;
        return d;
    }

    private Entity findDescendantAnimator() {
        if (sceneWorld == null) return null;
        java.util.List all = sceneWorld.getSceneEntities();
        java.util.ArrayList ids = new java.util.ArrayList();
        ids.add(entity.id);
        for (int k = 0; k < ids.size(); k++) {
            String parent = (String) ids.get(k);
            for (int i = 0; i < all.size(); i++) {
                Entity e = (Entity) all.get(i);
                if (!parent.equals(e.parentId)) continue;
                if (e.getComponent(AnimatorComponent.class) != null) return e;
                ids.add(e.id);
            }
        }
        return null;
    }

    private Entity findChildCamera() {
        if (sceneWorld == null) return null;
        java.util.List all = sceneWorld.getSceneEntities();
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            if (entity.id.equals(e.parentId) && e.getComponent(Camera.class) != null) return e;
        }
        return null;
    }
}
