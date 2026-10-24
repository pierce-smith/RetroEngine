package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.IRenderBackend;

/**
 * EngineLoop manages sub-millisecond frame pacing, GLFW event polling,
 * delta time smoothing via rolling history window, camera state synchronization,
 * double-buffered command flushing, and RHI rendering ticks at a rock-solid 60 FPS.
 */
public class EngineLoop {

    private static final long TARGET_FRAME_TIME_NS = 16_666_667L; // 60.00 FPS target (16.666 ms)
    private static final float MAX_FRAME_TIME = 0.1f;              // Cap (100ms) against OS focus/gesture spikes

    // 8-Frame Rolling Average Window for Frame Delta Time Smoothing
    private static final int SMOOTHING_SAMPLES = 8;
    private final float[] deltaHistory = new float[SMOOTHING_SAMPLES];
    private int deltaIndex = 0;
    private boolean historyFilled = false;

    public EngineLoop() {
        for (int i = 0; i < SMOOTHING_SAMPLES; i++) {
            deltaHistory[i] = 1.0f / 60.0f; // Pre-fill with 16.66ms
        }
    }

    public void run(EngineContext context) {
        WindowHost window = context.getWindow();
        EditorWorkspace editorManager = context.getEditorManager();
        ViewportCamera cameraController = context.getCameraController();
        HotkeyRouter hotkeyHandler = context.getHotkeyHandler();
        AssetStreamer streamingService = context.getStreamingService();
        SceneRenderPipeline renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();

        long targetNextFrameNs = System.nanoTime();
        long lastTimeNs = System.nanoTime();

        while (!window.shouldClose()) {
            window.pollEvents();

            long currentTimeNs = System.nanoTime();
            float rawDeltaTime = (currentTimeNs - lastTimeNs) / 1_000_000_000.0f;
            lastTimeNs = currentTimeNs;

            // Clamp raw delta time to guard against focus loss / window drag spikes
            float clampedDelta = Math.min(Math.max(rawDeltaTime, 0.0001f), MAX_FRAME_TIME);

            // Calculate smoothed delta time via 8-frame rolling window average
            deltaHistory[deltaIndex] = clampedDelta;
            deltaIndex = (deltaIndex + 1) % SMOOTHING_SAMPLES;
            if (deltaIndex == 0) historyFilled = true;

            float smoothedDelta = calculateSmoothedDelta();

            // 0. Double-Buffered Transactional Editor Command Flush at Frame Boundary
            if (editorManager != null && editorManager.getCommandHistory() != null) {
                editorManager.getCommandHistory().flushPendingCommands();
            }

            // Process Keyboard Hotkeys, Gizmo Snapping, and Flying Camera State
            if (hotkeyHandler != null && cameraController != null) {
                hotkeyHandler.processInput(window, editorManager, cameraController, smoothedDelta);
            }

            // Synchronize Camera MVP Parameters across RHI and EditorWorkspace
            if (cameraController != null && editorManager != null) {
                rhi.setCameraTransform(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );
                editorManager.setCameraState(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );
            }

            // Per-frame smooth delta update for simulation & viewport telemetry
            if (editorManager != null) {
                editorManager.update(smoothedDelta);
            }

            // 1-Line Scene Graph & Virtual Thread Streaming Render Pass
            if (renderSubmitter != null) {
                renderSubmitter.submitFrame(editorManager, streamingService, rhi);
            }

            if (editorManager != null) {
                rhi.updateGridMesh(editorManager.get3DGridVertices(), editorManager.get3DGridVertexCount());
                rhi.updateUiMesh(editorManager.getUiVertices(), editorManager.getUiVertexCount());
            }

            rhi.beginFrame();
            rhi.endFrame();
            rhi.present();

            // --- Sub-Millisecond Precision Frame Pacing Loop ---
            targetNextFrameNs += TARGET_FRAME_TIME_NS;
            long nowNs = System.nanoTime();

            // If behind schedule by more than 2 frames, resync anchor target to prevent catch-up spiral
            if (nowNs > targetNextFrameNs + (TARGET_FRAME_TIME_NS * 2)) {
                targetNextFrameNs = nowNs;
            }

            long sleepNs = targetNextFrameNs - nowNs;
            if (sleepNs > 0) {
                long sleepMs = sleepNs / 1_000_000L;
                if (sleepMs > 2) {
                    try {
                        Thread.sleep(sleepMs - 1);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                // High-precision spin-wait for remaining sub-millisecond duration
                while (System.nanoTime() < targetNextFrameNs) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    private float calculateSmoothedDelta() {
        int count = historyFilled ? SMOOTHING_SAMPLES : Math.max(1, deltaIndex);
        float sum = 0.0f;
        for (int i = 0; i < count; i++) {
            sum += deltaHistory[i];
        }
        return sum / count;
    }
}
