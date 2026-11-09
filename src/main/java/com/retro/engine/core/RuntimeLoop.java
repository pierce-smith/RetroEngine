package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.GameView;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.SceneRenderPipeline;

/**
 * Decoupled Standalone Game Runtime Execution Loop.
 * Enables standalone game builds and headless runs (--headless) to execute
 * pure 3D scene simulation without allocating or rendering EditorWorkspace UI overlays,
 * transform gizmos, or editor hotkeys.
 */
public class RuntimeLoop {

    private static final float TARGET_HEADLESS_TICK_TIME = 1.0f / 60.0f; // 60 Hz headless tick
    private static final long MAX_BENCH_FPS = 2000L; // Sizes the capture buffer; faster frames past the end go uncounted

    private boolean running = false;
    private boolean headless = false;
    private long maxFrames = 0L;
    private long benchWarmupNs, benchCaptureNs;
    private FrameLimiter frameLimiter;

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

    /**
     * Headless only: run exactly this many frames, then stop the scene so onDestroy runs too. Frames use the fixed tick
     * and don't sleep, so a run is repeatable and finishes as fast as the machine allows. 0 runs until stopped.
     */
    public void setMaxFrames(long maxFrames) {
        this.maxFrames = maxFrames;
    }

    /**
     * Windowed only: runs for {@code warmupSeconds}, measures every frame for {@code captureSeconds}, logs one
     * "[Bench]" result line, then stops. This is the capture step of the Volume 5 benchmark protocol.
     */
    public void setBenchmark(float warmupSeconds, float captureSeconds) {
        benchWarmupNs = (long) (warmupSeconds * 1_000_000_000.0);
        benchCaptureNs = (long) (captureSeconds * 1_000_000_000.0);
    }

    /** Windowed only: paces frames on the CPU (frame cap, and the backstop for drivers that force vsync off). */
    public void setFrameLimiter(FrameLimiter frameLimiter) {
        this.frameLimiter = frameLimiter;
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
            Log.error("RuntimeLoop", "Error: Invalid EngineContext or SceneWorld provided.");
            return;
        }

        WindowHost window = context.getWindow();
        AssetStreamer streamingService = context.getStreamingService();
        SceneRenderPipeline renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();
        ViewportCamera camera = context.getCameraController();

        this.running = true;

        // Force SceneWorld into PlayState.SIMULATING for standalone runtime execution
        if (sceneWorld.getPlayState() != PlayState.SIMULATING) {
            sceneWorld.play();
        }

        long lastTime = System.nanoTime();

        Log.info("RuntimeLoop", "Executing Standalone Game Loop (Headless Mode: " + headless + ")");

        long frame = 0L;
        boolean limited = headless && maxFrames > 0L;
        FrameTimings timings = FrameTimings.MAIN;
        long benchStartNs = System.nanoTime();
        boolean benchCapturing = false;

        while (running && (headless || (window != null && !window.shouldClose())) && !(limited && frame >= maxFrames)) {
            frame++;
            long currentTime = System.nanoTime();
            timings.begin();

            if (benchCaptureNs > 0L) {
                long benchElapsedNs = currentTime - benchStartNs;
                if (!benchCapturing && benchElapsedNs >= benchWarmupNs) {
                    benchCapturing = true;
                    timings.startCapture((int) Math.min(Integer.MAX_VALUE - 8L, benchCaptureNs / 1_000_000_000L * MAX_BENCH_FPS));
                } else if (benchCapturing && benchElapsedNs >= benchWarmupNs + benchCaptureNs) {
                    reportBenchmark(timings.finishCapture(), sceneWorld);
                    break;
                }
            }
            float rawDeltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;

            float deltaTime = Math.min(Math.max(rawDeltaTime, 0.0001f), EngineLoop.MAX_FRAME_DELTA);

            if (!headless) {
                // 1. Poll Native Window & Hardware Input Events
                if (window != null) {
                    window.pollEvents();
                }
                timings.mark(FrameTimings.POLL);

                // 2. Continuous Scene World Simulation Tick
                sceneWorld.update(deltaTime);
                timings.mark(FrameTimings.UPDATE);

                // 3. Fullscreen viewport at the WindowHost size, viewed through the scene's Main Camera
                if (window != null) GameView.setFullscreen(window.getWidth(), window.getHeight());
                if (camera != null && rhi != null) {
                    ViewportPanel.syncPlayCamera(context.getEditorManager(), camera);
                    rhi.setCameraTransform(camera.getPanX(), camera.getPanY(), camera.getZoom(), camera.getPitch(), camera.getYaw());
                    rhi.setCameraProjection(camera.getFov(), camera.getNear(), camera.getFar());
                }

                // 4. Submit Scene Graph Geometry to RHI Execution Queue (Zero Editor Overlay UI)
                if (renderSubmitter != null && rhi != null && streamingService != null) {
                    rhi.clearSceneQueue();

                    // Render scene geometry without EditorWorkspace UI batches or 3D grid
                    renderSubmitter.submitFrame(context.getEditorManager(), streamingService, rhi);

                    // No editor grid in standalone; the UI stream already carries only the game HUD
                    rhi.updateGridMesh(null, 0);
                    timings.mark(FrameTimings.SUBMIT);

                    rhi.beginFrame();
                    rhi.endFrame();
                    timings.mark(FrameTimings.RENDER);
                    rhi.present();
                    timings.mark(FrameTimings.PRESENT);
                }
                if (frameLimiter != null) {
                    frameLimiter.waitForNextFrame();
                    timings.mark(FrameTimings.PACE);
                }
            } else {
                // Headless: pure simulation tick with no GPU context
                sceneWorld.update(limited ? TARGET_HEADLESS_TICK_TIME : deltaTime);
                if (limited) continue;

                // Frame-pacing sleep for the headless thread
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

        if (limited) sceneWorld.stop();
        this.running = false;
        Log.info("RuntimeLoop", "Standalone Game Loop Terminated after " + frame + " frames.");
    }

    private static void reportBenchmark(FrameTimings.Capture c, SceneWorld world) {
        if (c == null) {
            Log.error("Bench", "No frames were captured.");
            return;
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT,
                "entities=%d frames=%d median=%.2fms p99=%.2fms max=%.2fms",
                world.getSceneEntities().size(), c.frames(), c.medianMs(), c.p99Ms(), c.maxMs()));
        for (int i = 0; i < FrameTimings.PHASE_COUNT; i++) {
            sb.append(String.format(java.util.Locale.ROOT, " %s=%.2f", FrameTimings.phaseName(i).toLowerCase(java.util.Locale.ROOT), c.phaseAverageMs()[i]));
        }
        Log.info("Bench", sb.toString());
    }
}
