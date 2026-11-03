package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.SceneRenderPipeline;

/**
 * The editor's frame loop: polls window events, flushes queued editor commands, runs hotkeys and the camera,
 * updates the scene, submits the frame, then paces to 60 FPS (sleep for most of the budget, spin the remainder).
 *
 * <p>Frame deltas above {@link #MAX_FRAME_DELTA} (window drags, breakpoints, a slow asset import) are clamped, so a
 * stall cannot fly the camera across the scene or feed physics a huge step. PhysicsSystem additionally caps
 * catch-up at {@code MAX_PHYSICS_STEPS} fixed steps per frame.
 */
public class EngineLoop {

    private static final long TARGET_FRAME_TIME_NS = 16_666_667L; // 60 FPS
    private static final long SLEEP_THRESHOLD_NS = 2_000_000L;
    public static final float MAX_FRAME_DELTA = 0.1f;

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
            float frameDelta = Math.min((frameStartNs - lastFrameStartNs) / 1_000_000_000.0f, MAX_FRAME_DELTA);
            lastFrameStartNs = frameStartNs;

            window.pollEvents();

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

            // Scene simulation. A crash here stops Play instead of closing the editor and losing unsaved work.
            if (editorManager != null) {
                try {
                    editorManager.update(frameDelta);
                } catch (RuntimeException | StackOverflowError e) {
                    System.err.println("[EngineLoop] Scene update failed; stopping Play mode.");
                    e.printStackTrace();
                    try {
                        editorManager.stop();
                    } catch (RuntimeException stopFailure) {
                        stopFailure.printStackTrace();
                    }
                }
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
}
