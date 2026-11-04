package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.SplineFollower;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

/**
 * Opening flyover on the CutsceneCamera entity (Camera + SplineFollower riding the CutsceneTrack spline).
 *
 * onStart: suspends player control (setPlayerInputEnabled(false)) and makes this camera the primary one.
 * Fly: the SplineFollower carries the camera along the track while it keeps looking at focusX/Y/Z.
 * Handoff: when the track completes (getSplineProgress() >= 1) or maxDuration elapses, the camera eases into the
 * player's camera pose over blendTime seconds, then the player camera becomes primary again, input is restored and
 * this entity is destroyed. Press Space / Enter to skip straight to the handoff.
 *
 * Zero-allocation per frame: entities, cameras and the Vector3 scratch are resolved / allocated once in onStart.
 */
public class OpeningCutsceneScript extends SimpleEntityScript {

    public String playerName = "Player";
    public float focusX = 0.0f, focusY = 1.0f, focusZ = -4.0f; // point the flyover keeps in frame
    public float maxDuration = 15.0f;                          // safety timer if the track never completes
    public float blendTime = 1.2f;                             // seconds to ease into the player camera

    private static final int KEY_SPACE = 32;
    private static final int KEY_ENTER = 257;
    private static final int FLY = 0, BLEND = 1, DONE = 2;

    private int phase;
    private float timer;
    private Entity playerCamEntity;
    private Camera playerCam;
    private Transform playerRoot;   // parent carrying the player's yaw (null if the camera is a root)
    private final Vector3 targetPos = new Vector3();
    private float startX, startY, startZ, startPitch, startYaw;

    @Override
    public void onStart() {
        phase = FLY;
        timer = 0.0f;
        resolvePlayerCamera();

        Camera mine = camera();
        if (mine != null) mine.isPrimary = true;
        if (playerCam != null) playerCam.isPrimary = false;
        setPlayerInputEnabled(false);

        SplineFollower follower = (SplineFollower) entity.getComponent(SplineFollower.class);
        if (follower == null || follower.spline == null && findEntity(follower.splineEntity) == null) {
            logWarning("OpeningCutsceneScript: no SplineFollower / track found; handing control to the player.");
            beginBlend();
        }
        aim();
    }

    @Override
    public void onUpdate(float dt) {
        if (phase == DONE) return;
        timer += dt;

        if (phase == FLY) {
            aim();
            boolean skip = Input.isKeyPressed(KEY_SPACE) || Input.isKeyPressed(KEY_ENTER);
            if (getSplineProgress() >= 1.0f || timer >= maxDuration || skip) beginBlend();
            return;
        }

        // BLEND: smoothstep from the last flyover pose into the player's camera pose
        float t = blendTime > 0.0f ? Math.min(1.0f, timer / blendTime) : 1.0f;
        float s = t * t * (3.0f - 2.0f * t);
        getWorldPositionOf(playerCamEntity, targetPos);
        float pitch = playerPitch(), yaw = playerYaw();
        setPosition(lerp(startX, targetPos.x, s), lerp(startY, targetPos.y, s), lerp(startZ, targetPos.z, s));
        setRotation(lerp(startPitch, pitch, s), startYaw + wrap(yaw - startYaw) * s, 0.0f);
        if (t >= 1.0f) finish();
    }

    private void beginBlend() {
        pauseSpline();
        phase = BLEND;
        timer = 0.0f;
        startX = getPositionX(); startY = getPositionY(); startZ = getPositionZ();
        startPitch = getRotationX(); startYaw = getRotationY();
        if (playerCamEntity == null) finish();
    }

    private void finish() {
        phase = DONE;
        Camera mine = camera();
        if (mine != null) mine.isPrimary = false;
        if (playerCam != null) playerCam.isPrimary = true;
        setPlayerInputEnabled(true);
        destroySelf();
    }

    private void aim() {
        lookAt(focusX, focusY, focusZ);
    }

    /** The player's camera: a Camera on the player entity or one of its direct children. */
    private void resolvePlayerCamera() {
        Entity player = findPlayer(playerName);
        if (player == null) return;
        java.util.List all = sceneWorld.getSceneEntities();
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            if (e == null || e == entity) continue;
            boolean onPlayer = e == player || (player.id != null && player.id.equals(e.parentId));
            Camera c = (Camera) e.getComponent(Camera.class);
            if (onPlayer && c != null) {
                playerCamEntity = e;
                playerCam = c;
                playerRoot = (e != player) ? player.getTransform() : null;
                return;
            }
        }
    }

    private float playerPitch() {
        Transform t = playerCamEntity.getTransform();
        return (t != null ? t.rotX : 0.0f) + (playerRoot != null ? playerRoot.rotX : 0.0f);
    }

    private float playerYaw() {
        Transform t = playerCamEntity.getTransform();
        return (t != null ? t.rotY : 0.0f) + (playerRoot != null ? playerRoot.rotY : 0.0f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float wrap(float deg) {
        deg %= 360.0f;
        if (deg > 180.0f) deg -= 360.0f;
        else if (deg < -180.0f) deg += 360.0f;
        return deg;
    }
}
