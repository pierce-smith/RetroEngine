package com.retro.engine.physics;

import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.physics.CapsuleCollider;

import java.util.List;

/**
 * Vice City Style Character Controller Simulation Solver.
 * Evaluates downward ground probe raycasting, step offset snapping,
 * gravity velocity integration, and horizontal friction damping.
 */
public class CharacterControllerSolver {

    public void updateControllers(List<PhysicsSystem.PhysicsBody> activeBodies, float dt, PhysicsSystem physicsSystem, RaycastHit sharedRaycastHit) {
        for (PhysicsSystem.PhysicsBody body : activeBodies) {
            CharacterControllerComponent cc = body.characterController;
            if (cc == null) continue;

            float capsuleHeight = 1.8f;
            CapsuleCollider cap = body.capsuleCollider;
            if (cap != null) capsuleHeight = cap.height;

            float halfHeight = capsuleHeight * 0.5f;

            physicsSystem.raycastExcludingInPlace(body.entity, body.px, body.py + halfHeight, body.pz, 0.0f, -1.0f, 0.0f, halfHeight + cc.stepOffset + 0.05f, sharedRaycastHit);

            if (sharedRaycastHit.hit && sharedRaycastHit.distance <= halfHeight + cc.stepOffset + 0.05f) {
                cc.isGrounded = true;
                if (cc.velocityY < 0.0f) cc.velocityY = 0.0f;
                float targetPy = sharedRaycastHit.pointY + halfHeight;
                body.py = Math.max(body.py, targetPy);
            } else {
                cc.isGrounded = false;
                cc.velocityY += PhysicsSystem.GRAVITY * cc.gravityMultiplier * dt;
            }

            body.px += cc.velocityX * dt;
            body.py += cc.velocityY * dt;
            body.pz += cc.velocityZ * dt;

            cc.velocityX *= Math.max(0.0f, 1.0f - 10.0f * dt);
            cc.velocityZ *= Math.max(0.0f, 1.0f - 10.0f * dt);

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
