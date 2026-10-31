package com.retro.engine.scene.world;

import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.scene.serialization.EntityStateBackupManager;
import com.retro.engine.scene.world.SceneWorld.PlayState;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.ScriptEngine;

import java.util.List;

/**
 * High-level Simulation Lifecycle Manager & Coordinator for Retro Engine runtime playstate.
 * Delegates zero-allocation in-place entity state snapshots and scene cloning to EntityStateBackupManager,
 * and orchestrates physics world solver ticks, gameplay script frame updates, and 3D spatial audio listener ticks.
 */
public class SimulationSystem {

    private PlayState playState = PlayState.EDITING;
    private final PhysicsSystem physicsWorld = new PhysicsSystem();
    private final EntityStateBackupManager backupManager = new EntityStateBackupManager();

    public PlayState getPlayState() {
        return playState;
    }

    public PhysicsSystem getPhysicsWorld() {
        return physicsWorld;
    }

    public void play(List<Entity> activeEntities) {
        play(activeEntities, null);
    }

    public void play(List<Entity> activeEntities, SceneWorld sceneWorld) {
        if (playState == PlayState.EDITING) {
            if (activeEntities != null) {
                backupManager.captureSnapshot(activeEntities);
            }

            this.playState = PlayState.SIMULATING;

            // Initialize 3D Physics World
            physicsWorld.initWorld(activeEntities);

            ScriptEngine.initializeScripts(activeEntities, sceneWorld);
            System.out.println("[SimulationSystem] Simulation started; physics world & gameplay scripts initialized (Zero-Allocation Snapshot).");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
        }
    }

    public void pause() {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            Input.stopVibration();
            System.out.println("[SimulationSystem] Simulation paused.");
        }
    }

    public List<Entity> stop(List<Entity> activeEntities) {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            ScriptEngine.destroyScripts(activeEntities);
            physicsWorld.cleanup();
            Input.stopVibration();

            if (activeEntities != null) {
                backupManager.restoreSnapshot(activeEntities);
            }

            this.playState = PlayState.EDITING;
            System.out.println("[SimulationSystem] Simulation stopped; gameplay scripts destroyed, physics world cleaned up, and state restored in-place.");
            return null; // Signals to SceneWorld that state was restored in-place with zero handle invalidation!
        }
        return null;
    }

    public void update(float deltaTime, List<Entity> activeEntities) {
        update(deltaTime, activeEntities, null);
    }

    public void update(float deltaTime, List<Entity> activeEntities, SceneWorld sceneWorld) {
        if (playState == PlayState.SIMULATING && activeEntities != null) {
            // 1. Step 3D Physics Solver (RigidBodies, Character Controllers, Vehicles, Raycasts)
            physicsWorld.step(deltaTime, activeEntities);

            // 2. Execute gameplay script frame ticks
            ScriptEngine.updateScripts(activeEntities, deltaTime, sceneWorld);

            // 3. Evaluate 3D Spatial Audio Gain Attenuation
            updateSpatialAudio(activeEntities);
        }
    }

    private void updateSpatialAudio(List<Entity> activeEntities) {
        // Find active listener position (Primary Camera or default origin)
        float listenerX = 0.0f, listenerY = 0.0f, listenerZ = 0.0f;
        boolean listenerFound = false;

        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            Camera cam = entity.getComponent(Camera.class);
            if (cam != null && cam.isPrimary) {
                Transform tc = entity.getTransform();
                if (tc != null) {
                    listenerX = tc.posX;
                    listenerY = tc.posY;
                    listenerZ = tc.posZ;
                    listenerFound = true;
                    break;
                }
            }
        }

        if (!listenerFound) {
            for (int i = 0; i < activeEntities.size(); i++) {
                Entity entity = activeEntities.get(i);
                if (entity == null) continue;
                Camera cam = entity.getComponent(Camera.class);
                if (cam != null) {
                    Transform tc = entity.getTransform();
                    if (tc != null) {
                        listenerX = tc.posX;
                        listenerY = tc.posY;
                        listenerZ = tc.posZ;
                        break;
                    }
                }
            }
        }

        // Continuous 3D Spatial Audio Attenuation Tick
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            Transform tc = entity.getTransform();
            AudioSource audioSource = entity.getComponent(AudioSource.class);
            if (audioSource != null && tc != null) {
                audioSource.applySpatialGain(
                    listenerX, listenerY, listenerZ,
                    tc.posX, tc.posY, tc.posZ
                );
            }
        }
    }

    public List<Entity> cloneSceneEntities(List<Entity> source) {
        return backupManager.cloneSceneEntities(source);
    }
}
