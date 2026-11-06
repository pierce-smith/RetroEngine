package com.retro.engine.physics;

import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.modding.ModLoader;

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
            if (a.isTrigger() || b.isTrigger()) {
                physicsSystem.recordTrigger(a, b);
                return;
            }

            if (isStatic(a) && isStatic(b)) return;

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
            if (sepY == 0.0f && (tryStepUp(a, b, halfAy, halfBy) || tryStepUp(b, a, halfBy, halfAy))) return;
            separateBodies(a, b, sepX * penetration, sepY * penetration, sepZ * penetration, restitution);
        }
    }

    /** A collider with no RigidBody or controller is scenery, the same as a STATIC body. */
    private static boolean isStatic(PhysicsSystem.PhysicsBody body) {
        if (body.characterController != null || body.vehicleController != null) return false;
        return body.rigidBody == null || body.rigidBody.motionType == RigidBodyComponent.MotionType.STATIC;
    }

    // Float slack so a rise of exactly stepOffset (e.g. 0.3 m steps with a 0.3 m offset) still counts
    private static final float STEP_TOLERANCE = 0.02f;

    /**
     * Side contact between a grounded character and a static / kinematic obstacle whose top is no more than the
     * controller's stepOffset above the character's feet: lift the character onto it instead of pushing it back.
     * The controller's ground probe then snaps it to the new surface on the next step. Returns true when it stepped.
     */
    private static boolean tryStepUp(PhysicsSystem.PhysicsBody character, PhysicsSystem.PhysicsBody obstacle,
                                     float characterHalfY, float obstacleHalfY) {
        CharacterControllerComponent cc = character.characterController;
        if (cc == null || !cc.isGrounded || cc.velocityY > 0.0f) return false;
        if (obstacle.characterController != null) return false;
        if (obstacle.rigidBody != null && obstacle.rigidBody.motionType == RigidBodyComponent.MotionType.DYNAMIC) return false;
        float rise = (obstacle.py + obstacleHalfY) - (character.py - characterHalfY);
        if (rise <= 0.0f || rise > cc.stepOffset + STEP_TOLERANCE) return false;
        character.py += rise;
        return true;
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
        } else if (a.characterController != null && b.characterController == null) {
            // Character controllers move by velocity without sweeping, so walls (static / kinematic bodies) must push
            // them back out here; standing on surfaces is handled by the controller's own ground raycast
            pushCharacterOut(a, -sepX, -sepY, -sepZ);
        } else if (b.characterController != null && a.characterController == null) {
            pushCharacterOut(b, sepX, sepY, sepZ);
        } else if (a.characterController != null) {
            pushCharacterOut(a, -sepX * 0.5f, -sepY * 0.5f, -sepZ * 0.5f);
            pushCharacterOut(b, sepX * 0.5f, sepY * 0.5f, sepZ * 0.5f);
        }
    }

    /** Moves a character body by (mx, my, mz) and cancels its velocity into the surface it was pushed out of. */
    private static void pushCharacterOut(PhysicsSystem.PhysicsBody body, float mx, float my, float mz) {
        CharacterControllerComponent cc = body.characterController;
        body.px += mx; body.py += my; body.pz += mz;
        if (mx * cc.velocityX < 0.0f) cc.velocityX = 0.0f;
        if (my * cc.velocityY < 0.0f) cc.velocityY = 0.0f; // head hit a ceiling
        if (mz * cc.velocityZ < 0.0f) cc.velocityZ = 0.0f;
    }
}
