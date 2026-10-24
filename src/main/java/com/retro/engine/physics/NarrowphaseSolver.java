package com.retro.engine.physics;

import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;

/**
 * Dedicated Narrowphase Collision Solver.
 * Evaluates AABB / Sphere / Capsule shape overlap manifolds, trigger volumes,
 * and positional separation impulse adjustments.
 */
public class NarrowphaseSolver {

    public void resolveCollision(PhysicsSystem.PhysicsBody a, PhysicsSystem.PhysicsBody b, PhysicsSystem physicsSystem) {
        if (a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC &&
            b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC) {
            return;
        }

        float halfAx = physicsSystem.getHalfWidthX(a), halfAy = physicsSystem.getHalfWidthY(a), halfAz = physicsSystem.getHalfWidthZ(a);
        float halfBx = physicsSystem.getHalfWidthX(b), halfBy = physicsSystem.getHalfWidthY(b), halfBz = physicsSystem.getHalfWidthZ(b);

        float dx = b.px - a.px;
        float dy = b.py - a.py;
        float dz = b.pz - a.pz;

        float overlapX = (halfAx + halfBx) - Math.abs(dx);
        float overlapY = (halfAy + halfBy) - Math.abs(dy);
        float overlapZ = (halfAz + halfBz) - Math.abs(dz);

        if (overlapX > 0 && overlapY > 0 && overlapZ > 0) {
            boolean isATrigger = (a.boxCollider != null && a.boxCollider.isTrigger) ||
                                 (a.sphereCollider != null && a.sphereCollider.isTrigger) ||
                                 (a.capsuleCollider != null && a.capsuleCollider.isTrigger);
            boolean isBTrigger = (b.boxCollider != null && b.boxCollider.isTrigger) ||
                                 (b.sphereCollider != null && b.sphereCollider.isTrigger) ||
                                 (b.capsuleCollider != null && b.capsuleCollider.isTrigger);

            if (isATrigger || isBTrigger) return;

            if (overlapX < overlapY && overlapX < overlapZ) {
                float sign = dx > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, sign * overlapX, 0, 0);
            } else if (overlapY < overlapX && overlapY < overlapZ) {
                float sign = dy > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, 0, sign * overlapY, 0);
            } else {
                float sign = dz > 0 ? 1.0f : -1.0f;
                separateBodies(a, b, 0, 0, sign * overlapZ);
            }
        }
    }

    private void separateBodies(PhysicsSystem.PhysicsBody a, PhysicsSystem.PhysicsBody b, float sepX, float sepY, float sepZ) {
        boolean aDynamic = a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        boolean bDynamic = b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;

        if (aDynamic && bDynamic) {
            a.px -= sepX * 0.5f; a.py -= sepY * 0.5f; a.pz -= sepZ * 0.5f;
            b.px += sepX * 0.5f; b.py += sepY * 0.5f; b.pz += sepZ * 0.5f;
            float restitution = Math.max(a.rigidBody.restitution, b.rigidBody.restitution);
            if (sepX != 0) { a.vx = -a.vx * restitution; b.vx = -b.vx * restitution; }
            if (sepY != 0) { a.vy = -a.vy * restitution; b.vy = -b.vy * restitution; }
            if (sepZ != 0) { a.vz = -a.vz * restitution; b.vz = -b.vz * restitution; }
        } else if (aDynamic) {
            a.px -= sepX; a.py -= sepY; a.pz -= sepZ;
            float restitution = a.rigidBody.restitution;
            if (sepX != 0) a.vx = -a.vx * restitution;
            if (sepY != 0) a.vy = -a.vy * restitution;
            if (sepZ != 0) a.vz = -a.vz * restitution;
        } else if (bDynamic) {
            b.px += sepX; b.py += sepY; b.pz += sepZ;
            float restitution = b.rigidBody.restitution;
            if (sepX != 0) b.vx = -b.vx * restitution;
            if (sepY != 0) b.vy = -b.vy * restitution;
            if (sepZ != 0) b.vz = -b.vz * restitution;
        }
    }
}
