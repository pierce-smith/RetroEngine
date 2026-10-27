package com.retro.engine.scripting.user;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.Input;

/**
 * Interactive test script for RetroEngine verifying:
 * 1. Janino dynamic in-memory compilation (Phase 2)
 * 2. WASD Movement & Spacebar Jump (Phase 1 Input & Transform APIs)
 * 3. 60 Hz Physics updates (Phase 3 onPhysicsUpdate)
 * 4. Collision and Trigger callbacks (Phase 3 onCollisionEnter & onTriggerEnter)
 */
public class PhysicsTestCubeScript extends EntityScript {

    public float moveSpeed = 8.0f;
    public float rotationSpeed = 45.0f; // degrees per second
    public float jumpForce = 4.0f;

    private boolean spaceWasDown = false;
    private boolean resetWasDown = false;

    @Override
    public void onStart() {
        logInfo("PhysicsTestCubeScript initialized on Entity '" + (entity != null ? entity.name : "null") + "'");
        logInfo("Controls: WASD to Move, SPACE to Jump/Boost, C to Reset Position.");
    }

    @Override
    public void onUpdate(float deltaTime) {
        // 1. Continuous Y-axis rotation (Phase 1 Transform API)
        rotate(0.0f, rotationSpeed * deltaTime, 0.0f);

        // 2. WASD Movement (Phase 1 Input API)
        // GLFW Key Codes: W=87, S=83, A=65, D=68, SPACE=32, C=67
        float dx = 0.0f;
        float dz = 0.0f;

        if (Input.isKeyDown(87)) dz -= 1.0f; // W (Forward)
        if (Input.isKeyDown(83)) dz += 1.0f; // S (Backward)
        if (Input.isKeyDown(65)) dx -= 1.0f; // A (Left)
        if (Input.isKeyDown(68)) dx += 1.0f; // D (Right)

        if (dx != 0.0f || dz != 0.0f) {
            translate(dx * moveSpeed * deltaTime, 0.0f, dz * moveSpeed * deltaTime);
        }

        // 3. Spacebar Jump / Impulse (Rising-edge Input trigger)
        boolean spaceDown = Input.isKeyDown(32);
        if (spaceDown && !spaceWasDown) {
            logInfo("Spacebar pressed! Applying upward boost.");
            translate(0.0f, jumpForce, 0.0f);
        }
        spaceWasDown = spaceDown;

        // 4. Reset position on 'C' key (67) - avoided 'R' which triggers Editor Scale Gizmo
        boolean resetDown = Input.isKeyDown(67);
        if (resetDown && !resetWasDown) {
            logInfo("Resetting position to origin.");
            setPosition(0.0f, 2.0f, 0.0f);
        }
        resetWasDown = resetDown;
    }

    @Override
    public void onPhysicsUpdate(float fixedDeltaTime) {
        // 60 Hz Fixed Timestep logic (Phase 3 Verification)
        // Gentle downward drift when airborne
        if (getPositionY() > 0.5f) {
            translate(0.0f, -9.81f * fixedDeltaTime * 0.2f, 0.0f);
        }
    }

    @Override
    public void onCollisionEnter(Entity other) {
        logInfo("COLLISION ENTER -> Hit solid object: '" + (other != null ? other.name : "null") + "'");
    }

    @Override
    public void onCollisionExit(Entity other) {
        logInfo("COLLISION EXIT -> Separated from object: '" + (other != null ? other.name : "null") + "'");
    }

    @Override
    public void onTriggerEnter(Entity other) {
        logInfo("TRIGGER ENTER -> Touched trigger volume of: '" + (other != null ? other.name : "null") + "'");
    }

    @Override
    public void onTriggerExit(Entity other) {
        logInfo("TRIGGER EXIT -> Exited trigger volume of: '" + (other != null ? other.name : "null") + "'");
    }

    @Override
    public void onDestroy() {
        logInfo("PhysicsTestCubeScript destroyed.");
    }
}
