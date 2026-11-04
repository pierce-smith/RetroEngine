package com.retro.engine.ecs;

import com.retro.engine.audio.AudioEngine;
import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.ParticleSystem;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.serialization.EntityStateBackupManager;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.ScriptEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Play / Pause / Stop for the scene. Play snapshots the scene (EntityStateBackupManager) and starts physics and
 * scripts; each update steps physics, runs script ticks and updates spatial audio; Stop tears both down and restores
 * the snapshot. Returns from {@link #stop} tell SceneWorld whether it has to rebuild entity handles.
 */
public class SimulationSystem {

    private PlayState playState = PlayState.EDITING;
    private final PhysicsSystem physicsWorld = new PhysicsSystem();
    private final EntityStateBackupManager backupManager = new EntityStateBackupManager();
    // Sources the pause button paused; resume restarts only these, not ones a script paused itself
    private final List<AudioSource> pausedAudioSources = new ArrayList<>();

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

            // Start PLAY ON START audio before scripts so their onAwake/onStart can stop or retune it
            if (activeEntities != null) startAudioOnPlay(activeEntities, sceneWorld);

            ScriptEngine.initializeScripts(activeEntities, sceneWorld);
            System.out.println("[SimulationSystem] Simulation started; scene snapshot taken, physics and scripts started.");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
            AudioEngine.setOneShotsPaused(false);
            resumeAudioSources();
            if (Input.isCursorLockWanted()) Input.setCursorCaptured(true);
        }
    }

    public void pause() {
        pause(null);
    }

    public void pause(List<Entity> activeEntities) {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            AudioEngine.setOneShotsPaused(true);
            pauseAudioSources(activeEntities);
            Input.stopVibration();
            Input.setCursorCaptured(false); // Keeps the lock wanted, so resuming re-locks
            System.out.println("[SimulationSystem] Simulation paused.");
        }
    }

    public List<Entity> stop(List<Entity> activeEntities) {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            ScriptEngine.destroyScripts(activeEntities);
            physicsWorld.cleanup();
            AudioEngine.stopAllOneShots();
            pausedAudioSources.clear();
            Input.stopVibration();
            Input.setCursorLocked(false);

            boolean structureChanged = activeEntities != null && backupManager.restoreSnapshot(activeEntities);

            this.playState = PlayState.EDITING;
            System.out.println("[SimulationSystem] Simulation stopped; scene restored to its pre-play state.");
            // A copy, because SceneWorld clears its own list before re-adding the returned entities
            return structureChanged ? new ArrayList<>(activeEntities) : null;
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

            // 3. Sync 3D audio source positions to OpenAL
            updateSpatialAudio(activeEntities, sceneWorld);

            // 4. Advance particle systems after scripts, so this frame's play()/stop()/emit() calls take effect now
            updateParticles(deltaTime, activeEntities, sceneWorld);
        }
        // Clear this frame's pressed edges (isKeyPressed / isMouseButtonPressed) after scripts have seen them, and
        // every frame outside Play too, so editor clicks don't arrive as a burst of presses on the first Play frame
        com.retro.engine.scripting.Input.endFrame();
    }

    /** Simulates every ParticleSystem at its entity's world pose (indexed loop, no allocation). */
    private void updateParticles(float deltaTime, List<Entity> activeEntities, SceneWorld sceneWorld) {
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            ParticleSystem ps = entity.getComponent(ParticleSystem.class);
            if (ps == null) continue;
            SceneWorld.WorldTransform wt = (sceneWorld != null) ? sceneWorld.getComputedWorldTransform(entity) : null;
            if (wt != null) {
                ps.simulate(deltaTime, wt.px(), wt.py(), wt.pz(), wt.rx(), wt.ry(), wt.rz());
            } else {
                Transform tc = entity.getTransform();
                if (tc != null) ps.simulate(deltaTime, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ);
            }
        }
    }

    private void pauseAudioSources(List<Entity> activeEntities) {
        pausedAudioSources.clear();
        if (activeEntities == null) return;
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            AudioSource audioSource = entity.getComponent(AudioSource.class);
            if (audioSource != null && audioSource.state == AudioSource.PlaybackState.PLAYING) {
                audioSource.pause();
                pausedAudioSources.add(audioSource);
            }
        }
    }

    private void resumeAudioSources() {
        for (AudioSource audioSource : pausedAudioSources) {
            if (audioSource.state == AudioSource.PlaybackState.PAUSED) audioSource.play(); // Continues from the pause point
        }
        pausedAudioSources.clear();
    }

    private void startAudioOnPlay(List<Entity> activeEntities, SceneWorld sceneWorld) {
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            AudioSource audioSource = entity.getComponent(AudioSource.class);
            if (audioSource != null && audioSource.playOnStart) audioSource.play();
        }
        updateSpatialAudio(activeEntities, sceneWorld); // Place sources before the first mixed frame
    }

    /**
     * Pushes each AudioSource's world position (and live inspector/script edits) to OpenAL. The listener itself is
     * synced to the active viewport camera by SceneRenderPipeline every frame.
     */
    private void updateSpatialAudio(List<Entity> activeEntities, SceneWorld sceneWorld) {
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            if (entity == null) continue;
            AudioSource audioSource = entity.getComponent(AudioSource.class);
            if (audioSource == null || audioSource.getSourceHandle() == 0) continue;
            SceneWorld.WorldTransform wt = (sceneWorld != null) ? sceneWorld.getComputedWorldTransform(entity) : null;
            if (wt != null) {
                audioSource.updateSpatial(wt.px(), wt.py(), wt.pz());
            } else {
                Transform tc = entity.getTransform();
                if (tc != null) audioSource.updateSpatial(tc.posX, tc.posY, tc.posZ);
            }
        }
    }
}
