package com.retro.engine.physics;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.physics.CapsuleCollider;

import com.retro.engine.scripting.Input;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Vice City Style Character Controller Simulation Solver.
 * Evaluates downward ground probe raycasting, step offset snapping,
 * gravity velocity integration, and horizontal friction damping.
 * Horizontal movement is driven by keyboard WASD and the active gamepad's Left Stick (world X/Z, W / stick up = -Z).
 */
public class CharacterControllerSolver {

    private static final float GROUND_SKIN = 0.05f;

    public void updateControllers(List<PhysicsSystem.PhysicsBody> activeBodies, float dt, PhysicsSystem physicsSystem, RaycastHit sharedRaycastHit) {
        // Movement intent, sampled once per step: WASD digital + Left Stick analog, clamped to unit length
        float moveX = 0.0f, moveZ = 0.0f;
        if (Input.isKeyDown(GLFW_KEY_D)) moveX += 1.0f;
        if (Input.isKeyDown(GLFW_KEY_A)) moveX -= 1.0f;
        if (Input.isKeyDown(GLFW_KEY_S)) moveZ += 1.0f;
        if (Input.isKeyDown(GLFW_KEY_W)) moveZ -= 1.0f;
        GamepadManager pad = Input.getGamepad();
        if (pad != null && pad.isConnected()) {
            moveX += pad.getAxis(GamepadManager.AXIS_LEFT_X);
            moveZ += pad.getAxis(GamepadManager.AXIS_LEFT_Y);
        }
        float moveLen = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (moveLen > 1.0f) {
            moveX /= moveLen;
            moveZ /= moveLen;
        }
        boolean hasMoveInput = moveLen > 0.0f;

        for (PhysicsSystem.PhysicsBody body : activeBodies) {
            CharacterControllerComponent cc = body.characterController;
            if (cc == null) continue;

            // Only override while there is input, so script-set velocities and friction coasting still apply otherwise
            if (hasMoveInput) {
                cc.velocityX = moveX * cc.moveSpeed;
                cc.velocityZ = moveZ * cc.moveSpeed;
            }

            // Probe from the body centre: the ray must start outside the floor, and the collider's real half
            // height (box or capsule, scaled) is the distance from centre to feet
            float halfHeight = physicsSystem.getHalfWidthY(body);
            cc.velocityY += PhysicsSystem.GRAVITY * cc.gravityMultiplier * dt;
            float fallDistance = Math.max(0.0f, -cc.velocityY * dt);
            // Grounded bodies also look stepOffset below the feet so they stick to the ground walking down steps
            float reach = halfHeight + GROUND_SKIN + Math.max(fallDistance, cc.isGrounded ? cc.stepOffset : 0.0f);

            physicsSystem.raycastExcludingInPlace(body.entity, body.px, body.py, body.pz, 0.0f, -1.0f, 0.0f, reach, sharedRaycastHit);

            if (sharedRaycastHit.hit && cc.velocityY <= 0.0f) {
                cc.isGrounded = true;
                cc.velocityY = 0.0f;
                body.py = sharedRaycastHit.pointY + halfHeight;
            } else {
                cc.isGrounded = false;
                body.py += cc.velocityY * dt;
            }

            body.px += cc.velocityX * dt;
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
