package com.retro.engine.scripting.user;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Input layer for the Infinite Driver car on the built-in VehicleController: the VehicleSuspensionSolver moves the
 * car along its heading and turns it; this script only feeds it currentSpeed / currentSteering. There are no lanes
 * and no snapping: the steering input eases in, is converted to a target sideways speed, and the heading is steered
 * smoothly toward the angle that produces it (and back to straight when released). Road edges soften the steering.
 * The body rolls with the slide, the engine loop's pitch follows speed, and a chase camera trails with lag.
 * HighwayGameScript resets the car's forward travel each frame (floating origin), so it stays near Z = 0.
 *
 * HighwayGameScript drives it through setForwardSpeed / crash / isCrashed (reflection, scripts compile separately).
 */
public class CarDriverScript extends SimpleEntityScript {

    // Handling
    public float maxLateralSpeed = 10.0f;   // m/s sideways at full lock
    public float yawResponse = 7.0f;        // how quickly the heading chases its target (1/s)
    public float maxTurnRate = 140.0f;      // deg/s cap on heading change
    public float steerRiseRate = 4.5f;      // steering input ramp-in speed (per second)
    public float steerReturnRate = 6.0f;    // steering re-centre speed when released
    public float roadHalfWidth = 5.9f;      // drivable half width (edge lines)
    public float carHalfWidth = 0.9f;
    public float edgeSoftZone = 1.5f;       // steering toward an edge fades out over this distance

    // Body motion
    public float baseYaw = 180.0f;          // model faces +Z, the road runs toward -Z
    public float rollPerSpeed = 0.6f;       // degrees of body roll per m/s of lateral speed
    public float bodyBob = 0.025f;

    // Camera
    public String cameraName = "Main Camera";
    public float crashShake = 0.9f;         // camera shake intensity on impact (0 = off)
    public String tireScreechSound = "";    // optional, e.g. "Assets/Audio/tire_screech.ogg" (spatial, SFX bus)
    public float screechSteer = 0.85f;      // |steering| above this at speed triggers a screech
    public float screechCooldown = 0.6f;
    public float cameraHeight = 3.4f;
    public float cameraDistance = 8.0f;
    public float cameraPitch = 12.0f;
    public float cameraFollow = 0.75f;      // fraction of the car's X the camera tracks
    public float cameraLag = 5.0f;
    public float baseFov = 62.0f;
    public float speedFov = 16.0f;          // extra FOV at max speed

    // Audio
    public float idlePitch = 0.75f;
    public float topPitch = 1.6f;
    public float referenceTopSpeed = 55.0f;

    private static final int KEY_A = 65, KEY_D = 68, KEY_H = 72, KEY_LEFT = 263, KEY_RIGHT = 262;

    private float steer;          // smoothed -1..1
    private float lateralVel;     // m/s, measured
    private float yaw;            // mirrors the solver's heading between transform syncs
    private float trackX;
    private float lastX;
    private VehicleControllerComponent vehicle;
    private float forwardSpeed;   // set by the game manager
    private boolean crashed;
    private float spinVel;        // deg/s after a crash
    private float spinYaw;
    private float bobTime;
    private float camX;
    private float screechTimer;

    private Entity cameraEntity;
    private AudioSource engine, sfxStartUp, sfxHorn, sfxBoost, sfxCrash;

    @Override
    public void onStart() {
        vehicle = vehicleController(); // SimpleEntityScript cached accessors
        if (vehicle == null) logError("CarDriverScript needs a Vehicle Controller component on " + entity.name);
        else vehicle.maxSpeed = referenceTopSpeed;
        yaw = getRotationY();
        trackX = lastX = getPositionX();
        engine = audio();
        sfxStartUp = childAudio("SFX_StartUp");
        sfxHorn = childAudio("SFX_Horn");
        sfxBoost = childAudio("SFX_Boost");
        sfxCrash = childAudio("SFX_Crash");
        cameraEntity = findEntity(cameraName);
        camX = getPositionX() * cameraFollow;
        if (sfxStartUp != null) sfxStartUp.play();
        if (engine != null) engine.setPitch(idlePitch);
    }

    @Override
    public void onUpdate(float dt) {
        if (dt <= 0.0f) return;
        dt = Math.min(dt, 0.05f);
        float speedT = clamp01(forwardSpeed / referenceTopSpeed);

        if (!crashed) {
            // --- Analog-feeling steering from digital keys (or a real stick) ---
            float input = 0.0f;
            if (Input.isKeyDown(KEY_A) || Input.isKeyDown(KEY_LEFT)) input -= 1.0f;
            if (Input.isKeyDown(KEY_D) || Input.isKeyDown(KEY_RIGHT)) input += 1.0f;
            GamepadManager pad = Input.getGamepad();
            if (pad != null && pad.isConnected()) {
                float stick = pad.getAxis(GamepadManager.AXIS_LEFT_X);
                if (Math.abs(stick) > 0.15f) input = Math.max(-1.0f, Math.min(1.0f, input + stick));
            }
            float rate = (Math.abs(input) > Math.abs(steer) && input * steer >= 0.0f) ? steerRiseRate : steerReturnRate;
            steer = moveTowards(steer, input, rate * dt);

            if (Input.isKeyPressed(KEY_H) && sfxHorn != null) sfxHorn.play();
        } else {
            steer = moveTowards(steer, 0.0f, steerReturnRate * dt);
            spinYaw += spinVel * dt;
            spinVel = moveTowards(spinVel, 0.0f, 500.0f * dt);
        }

        // The solver owns X/Z/heading; measure the resulting slide for body roll and the camera
        float x = getPositionX();
        lateralVel += ((x - lastX) / dt - lateralVel) * Math.min(1.0f, dt * 12.0f);
        lastX = x;
        trackX = x;
        float limit = roadHalfWidth - carHalfWidth + 0.3f; // hard safety net only; steering keeps it inside
        if (Math.abs(x) > limit) setPosition(Math.signum(x) * limit, getPositionY(), getPositionZ());

        bobTime += dt * (6.0f + 10.0f * speedT);
        float y = crashed ? 0.0f : (float) Math.sin(bobTime) * bodyBob * (0.3f + speedT);
        Transform t = getTransform();
        t.posY = y;
        t.rotZ = lateralVel * rollPerSpeed;
        if (crashed) t.rotY = yaw + spinYaw;
        else yaw = t.rotY;

        // --- Tire screech: one-shot spatial clip on the SFX bus when cornering hard at speed (re-armed by a cooldown) ---
        screechTimer -= dt;
        if (!crashed && screechTimer <= 0.0f && Math.abs(steer) > screechSteer && speedT > 0.35f
                && tireScreechSound != null && tireScreechSound.length() > 0) {
            playSpatialSound(tireScreechSound, "SFX", 0.5f + 0.5f * speedT);
            screechTimer = screechCooldown;
        }

        // --- Engine note follows speed, with a little rise while steering hard ---
        if (engine != null) {
            float p = crashed ? idlePitch * 0.8f : idlePitch + (topPitch - idlePitch) * speedT + Math.abs(steer) * 0.05f;
            engine.setPitch(p);
        }
    }

    /** Runs before the VehicleSuspensionSolver each fixed step: feed it speed and the steering that turns the heading. */
    @Override
    public void onPhysicsUpdate(float fdt) {
        if (vehicle == null) return;
        float speed = crashed ? 0.0f : forwardSpeed;
        vehicle.currentSpeed = speed; // re-fed every step (the solver decays it)
        if (speed < 0.5f) { vehicle.currentSteering = 0.0f; return; }

        float speedT = clamp01(speed / referenceTopSpeed);
        float lat = steer * maxLateralSpeed * (0.75f + 0.35f * speedT);
        float limit = roadHalfWidth - carHalfWidth;
        if (lat > 0.0f) lat *= clamp01((limit - trackX) / edgeSoftZone);
        if (lat < 0.0f) lat *= clamp01((limit + trackX) / edgeSoftZone);
        if (trackX > limit) lat = -(trackX - limit) * 3.0f;
        if (trackX < -limit) lat = (-limit - trackX) * 3.0f;

        // Heading offset that yields the target sideways speed; the nose points along (-sin rotY, cos rotY), so
        // +X needs rotY above 180 (positive steering turns right)
        float offset = (float) Math.toDegrees(Math.asin(Math.max(-0.6f, Math.min(0.6f, lat / speed))));
        float turnRate = (baseYaw + offset - yaw) * yawResponse;
        turnRate = Math.max(-maxTurnRate, Math.min(maxTurnRate, turnRate));
        // Solver: rotY += steering * (speed / maxSpeed) * 30 * dt
        vehicle.currentSteering = turnRate / ((speed / vehicle.maxSpeed) * 30.0f);

        yaw += turnRate * fdt;
        trackX -= (float) Math.sin(Math.toRadians(yaw)) * speed * fdt;
    }

    @Override
    public void onLateUpdate(float dt) {
        if (cameraEntity == null) return;
        Transform cam = (Transform) cameraEntity.getComponent(Transform.class);
        if (cam == null) return;
        float speedT = clamp01(forwardSpeed / referenceTopSpeed);
        float k = 1.0f - (float) Math.exp(-cameraLag * Math.max(dt, 0.0f));
        camX += (getPositionX() * cameraFollow - camX) * k;
        cam.posX = camX;
        cam.posY = cameraHeight - speedT * 0.4f;
        cam.posZ = getPositionZ() + cameraDistance - speedT * 0.8f;
        cam.rotX = cameraPitch;
        cam.rotY = 0.0f;
        cam.rotZ = -lateralVel * 0.25f;  // slight lean with the slide
        Camera c = (Camera) cameraEntity.getComponent(Camera.class);
        if (c != null) c.fov = baseFov + speedFov * speedT;
    }

    // --- API used by HighwayGameScript ---

    public void setForwardSpeed(float speed) {
        if (sfxBoost != null && !crashed && speed > 1.0f && forwardSpeed <= 1.0f) sfxBoost.play();
        forwardSpeed = speed;
    }

    public float getLateralX() { return getPositionX(); }

    public boolean isCrashed() { return crashed; }

    public void crash() {
        if (crashed) return;
        crashed = true;
        if (vehicle != null) { vehicle.currentSpeed = 0.0f; vehicle.currentSteering = 0.0f; }
        spinVel = (lateralVel >= 0.0f ? 1.0f : -1.0f) * (360.0f + Math.abs(lateralVel) * 40.0f);
        if (sfxCrash != null) sfxCrash.play();
        if (sfxHorn != null) sfxHorn.play();
        if (engine != null) engine.setVolume(0.25f);
        vibrate(0.9f, 0.9f, 0.35f);
        shakeCamera(crashShake, 0.6f);
    }

    // --- Helpers ---

    private AudioSource childAudio(String name) {
        Entity e = findEntity(name);
        return e != null ? (AudioSource) e.getComponent(AudioSource.class) : null;
    }

    private static float moveTowards(float current, float target, float maxDelta) {
        if (Math.abs(target - current) <= maxDelta) return target;
        return current + Math.signum(target - current) * maxDelta;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
