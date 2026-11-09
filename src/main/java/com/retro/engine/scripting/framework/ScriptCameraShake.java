package com.retro.engine.scripting.framework;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.Transform;

import java.util.List;

/**
 * Screen shake on the scene's Main Camera.
 *
 * <p>The offset is added to the camera's local transform after all scripts and physics ran for the frame and removed
 * again before the next frame starts, so scripts (mouse look, follow cameras) always read and write the clean pose
 * and the shake never accumulates or drifts.</p>
 *
 * <p>{@code intensity} is roughly 0..1: 1 moves the camera up to {@link #MAX_OFFSET} metres and rolls/pitches it up to
 * {@link #MAX_ANGLE} degrees. Strength fades out quadratically over the duration. A new shake replaces a weaker one
 * in progress; a weaker request during a strong shake is ignored.</p>
 */
@PublicApi
public final class ScriptCameraShake {

    public static final float MAX_OFFSET = 0.25f;
    public static final float MAX_ANGLE = 4.0f;
    private static final float FREQUENCY = 25.0f;

    private static float intensity = 0.0f;
    private static float duration = 0.0f;
    private static float remaining = 0.0f;
    private static float time = 0.0f;

    // The offset currently baked into a camera transform, removed in beginFrame
    private static Transform applied = null;
    private static float offX, offY, offZ, offPitch, offYaw, offRoll;

    private ScriptCameraShake() {}

    public static void shake(float shakeIntensity, float durationSeconds) {
        if (shakeIntensity <= 0.0f || durationSeconds <= 0.0f) return;
        if (shakeIntensity < currentStrength()) return;
        intensity = shakeIntensity;
        duration = durationSeconds;
        remaining = durationSeconds;
    }

    public static void stop() {
        remaining = 0.0f;
    }

    public static boolean isShaking() {
        return remaining > 0.0f;
    }

    /** Current faded strength (0 when idle). */
    public static float currentStrength() {
        if (remaining <= 0.0f || duration <= 0.0f) return 0.0f;
        float k = remaining / duration;
        return intensity * k * k;
    }

    /** Engine hook: strip last frame's offset and advance the timer, before physics and scripts run. */
    @Internal
    public static void beginFrame(float deltaTime) {
        removeOffset();
        if (remaining > 0.0f) {
            remaining -= deltaTime;
            time += deltaTime;
        }
    }

    /** Engine hook: apply this frame's offset to the Main Camera, after physics and scripts ran. */
    @Internal
    public static void endFrame(List<Entity> entities) {
        if (remaining <= 0.0f || entities == null) return;
        Entity cam = SceneWorld.findMainCamera(entities);
        Transform t = (cam != null) ? cam.getTransform() : null;
        if (t == null) return;

        float s = currentStrength();
        // Sums of incommensurate sines: smooth, non-repeating-looking noise without any random-number state
        float p = time * FREQUENCY;
        offX = s * MAX_OFFSET * noise(p, 0.0f);
        offY = s * MAX_OFFSET * noise(p, 1.7f);
        offZ = s * MAX_OFFSET * 0.5f * noise(p, 3.1f);
        offPitch = s * MAX_ANGLE * noise(p, 4.3f);
        offYaw = s * MAX_ANGLE * 0.5f * noise(p, 5.9f);
        offRoll = s * MAX_ANGLE * noise(p, 7.3f);

        t.posX += offX; t.posY += offY; t.posZ += offZ;
        t.rotX += offPitch; t.rotY += offYaw; t.rotZ += offRoll;
        applied = t;
    }

    /** Engine hook: remove any applied offset and cancel the shake (Play stopped / scene change). */
    @Internal
    public static void reset() {
        removeOffset();
        remaining = 0.0f;
        intensity = 0.0f;
        duration = 0.0f;
        time = 0.0f;
    }

    private static void removeOffset() {
        Transform t = applied;
        if (t == null) return;
        t.posX -= offX; t.posY -= offY; t.posZ -= offZ;
        t.rotX -= offPitch; t.rotY -= offYaw; t.rotZ -= offRoll;
        applied = null;
    }

    private static float noise(float p, float seed) {
        return (float) (Math.sin(p * 1.0 + seed) * 0.5 + Math.sin(p * 2.3 + seed * 1.9) * 0.3 + Math.sin(p * 4.1 + seed * 3.7) * 0.2);
    }
}
