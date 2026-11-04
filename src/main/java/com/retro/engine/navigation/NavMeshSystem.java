package com.retro.engine.navigation;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.ecs.components.Transform;

import java.io.File;
import java.util.List;

/**
 * Owns the active scene's {@link NavMesh} and ticks every {@link NavMeshAgent}: pending path requests are resolved
 * with {@link NavMeshQuery}, then agents steer along their corners (acceleration, auto-braking, turning). Runs each
 * simulation frame before physics, so Character Controller velocities it sets are integrated in the same frame.
 * The per-frame update is allocation-free.
 */
public final class NavMeshSystem {

    private static final float CORNER_REACHED = 0.15f;
    private static final float ARRIVE_EPSILON = 0.01f;

    private NavMesh mesh;
    private NavMeshQuery query;
    private final float[] snap = new float[3];

    public NavMesh getMesh() { return mesh; }

    /** Installs a mesh (null clears it) and sizes the pathfinder's scratch storage for it. */
    public void setMesh(NavMesh mesh) {
        this.mesh = mesh;
        this.query = (mesh != null && mesh.polyCount > 0) ? new NavMeshQuery(mesh) : null;
    }

    /** Loads the {@code .navmesh} that sits next to the given scene file (clears the mesh when there is none). */
    public void loadForScene(String sceneDiskPath) {
        String path = NavMesh.pathForScene(sceneDiskPath);
        NavMesh loaded = (path != null) ? NavMesh.load(new File(path)) : null;
        setMesh(loaded);
        if (loaded != null) System.out.println("[NavMesh] Loaded " + loaded.polyCount + " polygons from " + path);
    }

    /** Resets every agent's runtime state (play start / stop). */
    public void resetAgents(List<Entity> entities) {
        if (entities == null) return;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            NavMeshAgent agent = (e != null) ? e.getComponent(NavMeshAgent.class) : null;
            if (agent != null) agent.resetRuntime();
        }
    }

    public void update(float dt, List<Entity> entities) {
        if (entities == null || dt <= 0.0f) return;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            NavMeshAgent agent = e.getComponent(NavMeshAgent.class);
            if (agent == null) continue;
            Transform t = e.getTransform();
            if (t == null) continue;
            updateAgent(agent, t, e.getComponent(CharacterControllerComponent.class), dt);
        }
    }

    private void updateAgent(NavMeshAgent a, Transform t, CharacterControllerComponent cc, float dt) {
        float px = t.posX, pz = t.posZ;
        float footY = t.posY - a.baseOffset;

        if (a.pathPending) {
            a.pathPending = false;
            plan(a, px, footY, pz);
        }

        float desiredX = 0.0f, desiredZ = 0.0f;
        if (a.hasPath) {
            // Advance past reached intermediate corners
            while (a.cornerIndex < a.cornerCount - 1) {
                int c = a.cornerIndex * 3;
                if (distXZ(px, pz, a.corners[c], a.corners[c + 2]) > CORNER_REACHED) break;
                a.cornerIndex++;
            }
            int c = a.cornerIndex * 3;
            float toX = a.corners[c] - px, toZ = a.corners[c + 2] - pz;
            float toLen = (float) Math.sqrt(toX * toX + toZ * toZ);
            float remaining = toLen;
            for (int k = a.cornerIndex; k < a.cornerCount - 1; k++) {
                int o = k * 3;
                remaining += distXZ(a.corners[o], a.corners[o + 2], a.corners[o + 3], a.corners[o + 5]);
            }
            a.remainingDistance = remaining;

            boolean last = a.cornerIndex >= a.cornerCount - 1;
            if (last && a.pathTruncated && toLen <= CORNER_REACHED) {
                plan(a, px, footY, pz); // Corner list ran out before the destination: continue from here
            } else if (last && !a.pathTruncated && remaining <= Math.max(a.stoppingDistance, ARRIVE_EPSILON)) {
                a.clearPath(); // Arrived
            } else if (toLen > 1e-5f) {
                float target = a.speed;
                if (a.autoBraking && last && !a.pathTruncated && a.acceleration > 0.0f) {
                    float brake = Math.max(0.0f, remaining - a.stoppingDistance);
                    target = Math.min(target, (float) Math.sqrt(2.0f * a.acceleration * brake));
                    // Never overshoot the goal within this frame
                    target = Math.min(target, brake / dt);
                }
                desiredX = toX / toLen * target;
                desiredZ = toZ / toLen * target;
            }
        }

        // Accelerate toward the desired velocity
        float dvx = desiredX - a.velocityX, dvz = desiredZ - a.velocityZ;
        float dvLen = (float) Math.sqrt(dvx * dvx + dvz * dvz);
        float maxDv = (a.acceleration > 0.0f) ? a.acceleration * dt : Float.MAX_VALUE;
        if (dvLen > maxDv) { dvx *= maxDv / dvLen; dvz *= maxDv / dvLen; }
        a.velocityX += dvx;
        a.velocityZ += dvz;
        if (!a.hasPath && !a.autoBraking) { a.velocityX = 0.0f; a.velocityZ = 0.0f; }

        float vLen2 = a.velocityX * a.velocityX + a.velocityZ * a.velocityZ;
        if (vLen2 > 1e-4f && a.angularSpeed > 0.0f) {
            // Yaw 0 faces -Z (CharacterControllerSolver convention)
            float wantYaw = (float) Math.toDegrees(Math.atan2(a.velocityX, -a.velocityZ));
            float delta = wrap(wantYaw - t.rotY);
            float maxTurn = a.angularSpeed * dt;
            if (delta > maxTurn) delta = maxTurn; else if (delta < -maxTurn) delta = -maxTurn;
            t.setRotation(t.rotX, wrap(t.rotY + delta), t.rotZ);
        }

        if (cc != null) {
            // Physics integrates and grounds the controller this frame
            cc.velocityX = a.velocityX;
            cc.velocityZ = a.velocityZ;
            return;
        }
        if (vLen2 <= 1e-8f) return;
        float nx = px + a.velocityX * dt, nz = pz + a.velocityZ * dt, ny = footY;
        if (mesh != null) {
            int poly = mesh.findPoly(nx, footY, nz, mesh.stepHeight + 0.5f);
            if (poly >= 0) {
                ny = mesh.polyY[poly];
            } else if (mesh.findNearestPoly(nx, footY, nz, 1.0f, mesh.stepHeight + 0.5f, snap) >= 0) {
                nx = snap[0]; ny = snap[1]; nz = snap[2]; // Keep the agent on the mesh
            }
        }
        t.setPosition(nx, ny + a.baseOffset, nz);
    }

    private void plan(NavMeshAgent a, float px, float py, float pz) {
        a.clearPath();
        if (query == null) return;
        int n = query.findPath(px, py, pz, a.destX, a.destY, a.destZ, a.corners, NavMeshAgent.MAX_CORNERS);
        if (n <= 0) return;
        a.cornerCount = n;
        a.cornerIndex = 0;
        a.hasPath = true;
        a.pathPartial = query.lastPathPartial;
        a.pathTruncated = query.lastPathTruncated;
        float rem = distXZ(px, pz, a.corners[0], a.corners[2]);
        for (int k = 0; k < n - 1; k++) {
            int o = k * 3;
            rem += distXZ(a.corners[o], a.corners[o + 2], a.corners[o + 3], a.corners[o + 5]);
        }
        a.remainingDistance = rem;
    }

    private static float distXZ(float ax, float az, float bx, float bz) {
        float dx = bx - ax, dz = bz - az;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static float wrap(float deg) {
        deg %= 360.0f;
        if (deg > 180.0f) deg -= 360.0f;
        else if (deg < -180.0f) deg += 360.0f;
        return deg;
    }
}
