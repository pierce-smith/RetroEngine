package com.retro.engine.spline;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.SplineComponent;
import com.retro.engine.ecs.components.SplineFollower;
import com.retro.engine.ecs.components.Transform;

import java.util.List;

/**
 * Ticks every {@link SplineFollower}: resolves its target spline once (by entity name, else the parent's spline),
 * advances the arc-length distance at constant world speed with the follower's wrap mode, and writes position (and
 * optionally tangent-aligned rotation) into the Transform. Runs each simulation frame before physics. Allocation-free.
 */
public final class SplineSystem {

    private final float[] pos = new float[3];
    private final float[] tan = new float[3];

    /** Resets every follower's runtime state (play start / stop). */
    public void reset(List<Entity> entities) {
        if (entities == null) return;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            SplineFollower f = e.getComponent(SplineFollower.class);
            if (f != null) f.resetRuntime();
        }
    }

    public void update(float dt, List<Entity> entities) {
        if (entities == null) return;
        // Bind spline owners so evaluations include their world offset
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            SplineComponent s = (e != null) ? e.getComponent(SplineComponent.class) : null;
            if (s != null) s.owner = e;
        }
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            SplineFollower f = e.getComponent(SplineFollower.class);
            if (f == null) continue;
            Transform t = e.getTransform();
            if (t == null) continue;
            if (!f.resolved) {
                f.resolved = true;
                if (f.spline == null) f.spline = resolve(f, e, entities);
            }
            SplineComponent s = f.spline;
            if (s == null) continue;
            step(f, s, t, dt);
        }
    }

    private void step(SplineFollower f, SplineComponent s, Transform t, float dt) {
        float len = s.getLength();
        if (f.pendingProgress == f.pendingProgress) { // not NaN
            f.distance = clamp01(f.pendingProgress) * len;
            f.pendingProgress = Float.NaN;
        }
        if (f.playing && dt > 0.0f && len > 0.0f) {
            f.distance += f.speed * f.direction * dt;
            switch (f.wrapMode) {
                case CLAMP -> {
                    if (f.distance >= len) { f.distance = len; f.playing = false; }
                    else if (f.distance <= 0.0f) { f.distance = 0.0f; f.playing = false; }
                }
                case LOOP -> {
                    f.distance %= len;
                    if (f.distance < 0.0f) f.distance += len;
                }
                case PING_PONG -> {
                    // Reflect off either end (handles steps longer than the spline)
                    for (int guard = 0; guard < 8 && (f.distance > len || f.distance < 0.0f); guard++) {
                        if (f.distance > len) f.distance = 2.0f * len - f.distance;
                        else f.distance = -f.distance;
                        f.direction = -f.direction;
                    }
                    f.distance = Math.max(0.0f, Math.min(len, f.distance));
                }
            }
        }
        float u = SplineEvaluator.getPointAtDistance(s, f.distance, pos);
        t.setPosition(pos[0], pos[1], pos[2]);
        if (f.alignToTangent) {
            SplineEvaluator.getTangentAt(s, u, tan);
            float sign = (f.speed * f.direction < 0.0f) ? -1.0f : 1.0f;
            float dx = tan[0] * sign, dy = tan[1] * sign, dz = tan[2] * sign;
            // Models face +Z (matches SimpleEntityScript.lookAt for non-camera entities)
            float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, dy))));
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            t.setRotation(pitch, yaw, t.rotZ);
        }
    }

    private static SplineComponent resolve(SplineFollower f, Entity self, List<Entity> entities) {
        String name = f.splineEntity;
        if (name != null && !name.isBlank()) {
            for (int i = 0; i < entities.size(); i++) {
                Entity e = entities.get(i);
                if (e != null && name.equals(e.name)) {
                    SplineComponent s = e.getComponent(SplineComponent.class);
                    if (s != null) return s;
                }
            }
            return null;
        }
        String parentId = self.parentId;
        if (parentId == null) return null;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e != null && parentId.equals(e.id)) return e.getComponent(SplineComponent.class);
        }
        return null;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
