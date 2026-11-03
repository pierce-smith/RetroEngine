package com.retro.engine.physics;

import com.retro.engine.modding.ModLoader;
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

            if (isATrigger || isBTrigger) {
                physicsSystem.recordTrigger(a, b);
                return;
            }

            if (a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC &&
                b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC) {
                return;
            }

            // Contact normal (A -> B) along the axis of least penetration
            float sepX = 0, sepY = 0, sepZ = 0, penetration;
            if (overlapX < overlapY && overlapX < overlapZ) {
                sepX = dx > 0 ? 1.0f : -1.0f;
                penetration = overlapX;
            } else if (overlapY < overlapX && overlapY < overlapZ) {
                sepY = dy > 0 ? 1.0f : -1.0f;
                penetration = overlapY;
            } else {
                sepZ = dz > 0 ? 1.0f : -1.0f;
                penetration = overlapZ;
            }

            // Mods (CollisionEvent) may change the restitution or cancel the contact; no-op without subscribers
            float restitution = ModLoader.onCollision(a.entity, b.entity, sepX, sepY, sepZ, penetration, defaultRestitution(a, b));
            if (Float.isNaN(restitution)) return;

            physicsSystem.recordCollision(a, b);
            separateBodies(a, b, sepX * penetration, sepY * penetration, sepZ * penetration, restitution);
        }
    }

    /** Restitution the contact uses unless a mod overrides it: the max of two dynamic bodies, else the dynamic one's. */
    private static float defaultRestitution(PhysicsSystem.PhysicsBody a, PhysicsSystem.PhysicsBody b) {
        boolean aDynamic = a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        boolean bDynamic = b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        if (aDynamic && bDynamic) return Math.max(a.rigidBody.restitution, b.rigidBody.restitution);
        if (aDynamic) return a.rigidBody.restitution;
        if (bDynamic) return b.rigidBody.restitution;
        return 0.0f;
    }

    private void separateBodies(PhysicsSystem.PhysicsBody a, PhysicsSystem.PhysicsBody b, float sepX, float sepY, float sepZ, float restitution) {
        boolean aDynamic = a.rigidBody != null && a.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;
        boolean bDynamic = b.rigidBody != null && b.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC;

        if (aDynamic && bDynamic) {
            a.px -= sepX * 0.5f; a.py -= sepY * 0.5f; a.pz -= sepZ * 0.5f;
            b.px += sepX * 0.5f; b.py += sepY * 0.5f; b.pz += sepZ * 0.5f;
            if (sepX != 0) { a.vx = -a.vx * restitution; b.vx = -b.vx * restitution; }
            if (sepY != 0) { a.vy = -a.vy * restitution; b.vy = -b.vy * restitution; }
            if (sepZ != 0) { a.vz = -a.vz * restitution; b.vz = -b.vz * restitution; }
        } else if (aDynamic) {
            a.px -= sepX; a.py -= sepY; a.pz -= sepZ;
            if (sepX != 0) a.vx = -a.vx * restitution;
            if (sepY != 0) a.vy = -a.vy * restitution;
            if (sepZ != 0) a.vz = -a.vz * restitution;
        } else if (bDynamic) {
            b.px += sepX; b.py += sepY; b.pz += sepZ;
            if (sepX != 0) b.vx = -b.vx * restitution;
            if (sepY != 0) b.vy = -b.vy * restitution;
            if (sepZ != 0) b.vz = -b.vz * restitution;
        }
    }
}
