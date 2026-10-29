package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.editor.ui.panels.ViewportPanel;
import com.retro.engine.rhi.IRenderBackend;

/**
 * EngineLoop manages sub-millisecond frame pacing, GLFW event polling,
 * delta time smoothing via rolling history window, camera state synchronization,
 * double-buffered command flushing, and RHI rendering ticks at a rock-solid 60 FPS.
 * Each frame sleeps for the bulk of its remaining budget, then spin-waits the final
 * sub-millisecond remainder; subsystems receive the true, un-clamped frame delta.
 */
public class EngineLoop {

    private static final long TARGET_FRAME_TIME_NS = 16_666_667L; // 60 FPS
    private static final long SLEEP_THRESHOLD_NS = 2_000_000L;

    private static final int SMOOTHING_SAMPLES = 8;
    private final float[] deltaHistory = new float[SMOOTHING_SAMPLES];
    private int deltaIndex = 0;
    private boolean historyFilled = false;

    public EngineLoop() {
        for (int i = 0; i < SMOOTHING_SAMPLES; i++) {
            deltaHistory[i] = 1.0f / 60.0f;
        }
    }

    public void run(EngineContext context) {
        if (context == null) return;
        WindowHost window = context.getWindow();
        EditorWorkspace editorManager = context.getEditorManager();
        ViewportCamera cameraController = context.getCameraController();
        HotkeyRouter hotkeyHandler = context.getHotkeyHandler();
        AssetStreamer streamingService = context.getStreamingService();
        SceneRenderPipeline renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();

        long lastFrameStartNs = System.nanoTime();

        while (window != null && !window.shouldClose()) {
            long frameStartNs = System.nanoTime();
            float frameDelta = (frameStartNs - lastFrameStartNs) / 1_000_000_000.0f;
            lastFrameStartNs = frameStartNs;

            window.pollEvents();

            // Rolling 8-frame history of the true, un-clamped frame delta
            deltaHistory[deltaIndex] = frameDelta;
            deltaIndex = (deltaIndex + 1) % SMOOTHING_SAMPLES;
            if (deltaIndex == 0) historyFilled = true;

            float smoothedDelta = calculateSmoothedDelta();

            // 0. Double-Buffered Transactional Editor Command Flush at Frame Boundary
            if (editorManager != null && editorManager.getCommandHistory() != null) {
                editorManager.getCommandHistory().flushPendingCommands();
            }

            // Process Keyboard Hotkeys, Gizmo Snapping, and Flying Camera State
            if (hotkeyHandler != null && cameraController != null) {
                hotkeyHandler.processInput(window, editorManager, cameraController, frameDelta);
            }

            // Play mode locks the viewport to the scene's Main Camera; Stop restores the editor camera
            ViewportPanel.syncPlayCamera(editorManager, cameraController);

            // Synchronize Camera MVP Parameters across RHI and EditorWorkspace
            if (cameraController != null && editorManager != null && rhi != null) {
                rhi.setCameraProjection(cameraController.getFov(), cameraController.getNear(), cameraController.getFar());
                rhi.setCameraTransform(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );
                editorManager.getGizmoStateController().setCameraState(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );
            }

            // Per-frame delta update for simulation & viewport telemetry using actual frame delta
            if (editorManager != null) {
                editorManager.update(frameDelta);
            }

            // Single Scene Graph & Virtual Thread Streaming Render Pass
            if (renderSubmitter != null) {
                renderSubmitter.submitFrame(editorManager, streamingService, rhi);
            }

            if (rhi != null) {
                rhi.beginFrame();
                rhi.endFrame();
                rhi.present();
            }

            // --- Sub-Millisecond Precision Frame Pacing Loop ---
            long remainingNs = TARGET_FRAME_TIME_NS - (System.nanoTime() - frameStartNs);
            if (remainingNs > 0) {
                // Sleep through the bulk of the wait, leaving at least 1ms for the spin to absorb OS sleep overshoot
                if (remainingNs > SLEEP_THRESHOLD_NS) {
                    try {
                        Thread.sleep(remainingNs / 1_000_000L - 1);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                // High-precision spin-wait for the remaining sub-millisecond duration
                long frameDeadlineNs = frameStartNs + TARGET_FRAME_TIME_NS;
                while (System.nanoTime() < frameDeadlineNs) {
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
