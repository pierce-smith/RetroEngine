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
    // Current pacing, so scripts can change one half (Game.setVsync / setFrameRateCap) and keep the other
    private IRenderBackend pacingRhi;
    private boolean vsync = true;
    private int frameRateCap = 0;
    private int refreshHz = 0;

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

    /**
     * Windowed only: asks the backend for vsync on or off and paces frames to {@code frameRateCap} (0 = none; with vsync
     * off and no cap, a backstop just above {@code refreshHz}). Called at startup and again whenever a script changes either.
     */
    public void setFramePacing(IRenderBackend rhi, boolean vsync, int frameRateCap, int refreshHz) {
        this.pacingRhi = rhi;
        this.vsync = vsync;
        this.frameRateCap = frameRateCap;
        this.refreshHz = refreshHz;
        if (rhi != null) rhi.setVsync(vsync);
        setFrameLimiter(FrameLimiter.create(vsync, frameRateCap, refreshHz));
    }

    public boolean isVsync() { return vsync; }
    public int getFrameRateCap() { return frameRateCap; }

    /** {@link com.retro.engine.scripting.Game}'s host in exported games and headless runs. */
    private final class GameHost implements com.retro.engine.scripting.Game.Host {
        private final WindowHost window;
        private String headlessTitle = "";

        GameHost(WindowHost window) { this.window = window; }

        @Override public boolean isEditor() { return false; }
        @Override public String getTitle() { return window != null ? window.getTitle() : headlessTitle; }
        @Override public void setTitle(String title) {
            if (window != null) window.setTitle(title);
            else headlessTitle = title;
        }
        @Override public void quit() {
            Log.info("RuntimeLoop", "Game.quit() called; closing.");
            quitRequested = true;
            stop();
        }
        @Override public boolean isFullscreen() { return window != null && window.isFullscreen(); }
        @Override public void setFullscreen(boolean fullscreen) { if (window != null) window.setFullscreen(fullscreen); }
        @Override public int getWindowWidth() { return window != null ? window.getWidth() : 0; }
        @Override public int getWindowHeight() { return window != null ? window.getHeight() : 0; }
        @Override public void setResolution(int width, int height) { if (window != null) window.setResolution(width, height); }
        @Override public java.util.List<com.retro.engine.scripting.Game.DisplayMode> getDisplayModes() {
            if (window == null) return java.util.List.of();
            java.util.List<com.retro.engine.scripting.Game.DisplayMode> modes = new java.util.ArrayList<>();
            for (int[] m : window.getDisplayModes()) modes.add(new com.retro.engine.scripting.Game.DisplayMode(m[0], m[1], m[2]));
            return java.util.List.copyOf(modes);
        }
        @Override public boolean isVsync() { return vsync; }
        @Override public void setVsync(boolean on) {
            if (headless) { vsync = on; return; }
            setFramePacing(pacingRhi, on, frameRateCap, refreshHz);
        }
        @Override public int getFrameRateCap() { return frameRateCap; }
        @Override public void setFrameRateCap(int fps) {
            if (headless) { frameRateCap = fps; return; }
            setFramePacing(pacingRhi, vsync, fps, refreshHz);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        this.running = false;
    }

    // Set when a script called Game.quit(), so the scene is stopped (onDestroy runs) before the process exits
    private boolean quitRequested = false;

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
        com.retro.engine.scripting.Game.bind(new GameHost(headless ? null : window));

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
                    // The game fills the window, so window points are game-view pixels
                    com.retro.engine.scripting.Input.updatePointer(window.getCursorX(), window.getCursorY(),
                            0.0f, 0.0f, window.getWidth(), window.getHeight(), true);
                }
                timings.mark(FrameTimings.POLL);

                // 2. Continuous Scene World Simulation Tick, then the window/display/quit requests scripts made in it
                sceneWorld.update(deltaTime);
                com.retro.engine.scripting.Game.applyPending();
                if (!running) break;
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
                com.retro.engine.scripting.Game.applyPending();
                if (!running) break;
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

        // A finished --frames run, or Game.quit(), stops the scene so every script's onDestroy runs
        if (limited || quitRequested) sceneWorld.stop();
        com.retro.engine.scripting.Game.bind(null);
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
