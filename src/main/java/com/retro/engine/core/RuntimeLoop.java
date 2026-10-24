package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.world.SceneWorld.PlayState;

/**
 * Decoupled Standalone Game Runtime Execution Loop.
 * Enables standalone game builds and headless dedicated servers (--headless) to execute
 * pure 3D scene simulation without allocating or rendering EditorWorkspace UI overlays,
 * transform gizmos, or editor hotkeys.
 */
public class RuntimeLoop {

    private static final float MAX_FRAME_TIME = 0.1f; // 100 ms sanity cap
    private static final float TARGET_HEADLESS_TICK_TIME = 1.0f / 60.0f; // 60 Hz headless tick

    private boolean running = false;
    private boolean headless = false;

    public RuntimeLoop() {}

    public RuntimeLoop(boolean isHeadless) {
        this.headless = isHeadless;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        this.running = false;
    }

    /**
     * Executes the standalone runtime game loop using EngineContext subsystem handles.
     */
    public void run(EngineContext context, SceneWorld sceneWorld) {
        if (context == null || sceneWorld == null) {
            System.err.println("[RuntimeLoop] Error: Invalid EngineContext or SceneWorld provided.");
            return;
        }

        WindowHost window = context.getWindow();
        AssetStreamer streamingService = context.getStreamingService();
        SceneRenderPipeline renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();

        this.running = true;

        // Force SceneWorld into PlayState.SIMULATING for standalone runtime execution
        if (sceneWorld.getPlayState() != PlayState.SIMULATING) {
            sceneWorld.play();
        }

        long lastTime = System.nanoTime();

        System.out.println("[RuntimeLoop] Executing Standalone Game Loop (Headless Mode: " + headless + ")");

        while (running && (headless || (window != null && !window.shouldClose()))) {
            long currentTime = System.nanoTime();
            float rawDeltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;

            float deltaTime = Math.min(Math.max(rawDeltaTime, 0.0001f), MAX_FRAME_TIME);

            if (!headless) {
                // 1. Poll Native Window & Hardware Input Events
                if (window != null) {
                    window.pollEvents();
                }

                // 2. Continuous Scene World Simulation Tick
                sceneWorld.update(deltaTime);

                // 3. Submit Scene Graph Geometry to RHI Execution Queue (Zero Editor Overlay UI)
                if (renderSubmitter != null && rhi != null && streamingService != null) {
                    rhi.clearSceneQueue();

                    // Render scene geometry without EditorWorkspace UI batches or 3D grid
                    renderSubmitter.submitFrame(context.getEditorManager(), streamingService, rhi);

                    // Clear Editor UI Overlay & 3D Grid Buffers for Pure Standalone Game Render
                    rhi.updateGridMesh(null, 0);
                    rhi.updateUiMesh(null, 0);

                    rhi.beginFrame();
                    rhi.endFrame();
                    rhi.present();
                }
            } else {
                // Headless Dedicated Server Mode: Pure 60 Hz Simulation Tick without GPU Context
                sceneWorld.update(deltaTime);

                // Frame-pacing sleep for headless server thread
                long elapsedNanos = System.nanoTime() - currentTime;
                long targetNanos = (long) (TARGET_HEADLESS_TICK_TIME * 1_000_000_000.0f);
                if (elapsedNanos < targetNanos) {
                    try {
                        Thread.sleep((targetNanos - elapsedNanos) / 1_000_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        this.running = false;
        System.out.println("[RuntimeLoop] Standalone Game Loop Terminated.");
    }
}
