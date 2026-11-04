package com.retro.engine.physics;

import com.retro.engine.ecs.components.VehicleControllerComponent;

import java.util.List;

/**
 * 4-Wheel Raycast Suspension Vehicle Dynamics Solver.
 * Simulates FL, FR, RL, RR wheel suspension rays, Hooke's Law spring forces,
 * steering angles, acceleration, and speed decay.
 */
public class VehicleSuspensionSolver {

    private final float[][] wheelScratch = new float[4][3];

    /**
     * Suspension mount points (FL, FR, RL, RR) in world space; the editor's debug view draws from the same geometry.
     * The vehicle's pivot is its ground contact (the bottom of the wheels, as most car models are authored), so each
     * mount sits wheelRadius + suspensionRestLength above the pivot: at rest a wheel's center is wheelRadius above the
     * pivot, i.e. at center = mountY - suspensionRestLength + compression.
     * Axes follow the renderers' model matrix R = Rz(-z) * Ry(-y) * Rx(-x): the nose (local +Z) points along
     * (-sin y, cos y) and local +X, the driver's left, along (cos y, sin y).
     */
    public static float[][] computeWheelMounts(float px, float py, float pz, float rotYDeg, VehicleControllerComponent vc, float[][] out) {
        float radY = (float) Math.toRadians(rotYDeg);
        float fx = (float) -Math.sin(radY), fz = (float) Math.cos(radY);
        float lx = (float) Math.cos(radY), lz = (float) Math.sin(radY);
        float halfTrack = vc.wheelTrackWidth * 0.5f, halfBase = vc.wheelBase * 0.5f;
        float mountY = py + vc.wheelRadius + vc.suspensionRestLength;
        for (int i = 0; i < 4; i++) {
            float side = (i % 2 == 0) ? halfTrack : -halfTrack;   // FL, RL on the left
            float along = (i < 2) ? halfBase : -halfBase;          // front axle first
            out[i][0] = px + lx * side + fx * along;
            out[i][1] = mountY;
            out[i][2] = pz + lz * side + fz * along;
        }
        return out;
    }

    public void updateVehicles(List<PhysicsSystem.PhysicsBody> activeBodies, float dt, PhysicsSystem physicsSystem, RaycastHit sharedRaycastHit) {
        for (int b = 0, n = activeBodies.size(); b < n; b++) {
            PhysicsSystem.PhysicsBody body = activeBodies.get(b);
            VehicleControllerComponent vc = body.vehicleController;
            if (vc == null) continue;

            float radY = (float) Math.toRadians(body.ry);
            float forwardX = (float) -Math.sin(radY);
            float forwardZ = (float) Math.cos(radY);
            float[][] wheelMounts = computeWheelMounts(body.px, body.py, body.pz, body.ry, vc, wheelScratch);

            float maxRayDist = vc.suspensionRestLength + vc.wheelRadius;
            int groundedCount = 0;

            for (int i = 0; i < 4; i++) {
                float wx = wheelMounts[i][0];
                float wy = wheelMounts[i][1];
                float wz = wheelMounts[i][2];

                physicsSystem.raycastExcludingInPlace(body.entity, wx, wy, wz, 0.0f, -1.0f, 0.0f, maxRayDist, sharedRaycastHit);

                if (sharedRaycastHit.hit) {
                    vc.wheelGrounded[i] = true;
                    groundedCount++;
                    float compression = maxRayDist - sharedRaycastHit.distance;
                    vc.suspensionCompression[i] = compression;
                    float springForce = compression * vc.suspensionStiffness - body.vy * vc.suspensionDamping;
                    if (springForce > 0.0f) {
                        body.vy += (springForce / 100.0f) * dt;
                    }
                } else {
                    vc.wheelGrounded[i] = false;
                    vc.suspensionCompression[i] = 0.0f;
                }
            }

            if (groundedCount < 2) {
                body.vy += PhysicsSystem.GRAVITY * dt;
            }

            if (vc.currentSpeed != 0.0f) {
                body.px += forwardX * vc.currentSpeed * dt;
                body.pz += forwardZ * vc.currentSpeed * dt;
            }

            if (vc.currentSteering != 0.0f && Math.abs(vc.currentSpeed) > 0.1f) {
                body.ry += vc.currentSteering * (vc.currentSpeed / vc.maxSpeed) * 30.0f * dt;
            }

            vc.currentSpeed *= Math.max(0.0f, 1.0f - 1.5f * dt);

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
}
