package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.EditorInputRouter;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.SceneRenderPipeline;
import com.retro.engine.scripting.Input;

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

    /**
     * Hands scripts the cursor relative to the Scene tile, which shows the game during Play. The tile's rectangle is
     * last frame's layout, the same one the click being handled was aimed at.
     */
    private static void updateScriptPointer(WindowHost window, EditorWorkspace editorManager, EditorInputRouter router) {
        if (editorManager == null) return;
        DockSpace dock = editorManager.getDockSpace();
        int winW = editorManager.getWindowWidth(), winH = editorManager.getWindowHeight();
        if (dock == null || !dock.isSceneViewportVisible() || winW <= 0 || winH <= 0) {
            Input.updatePointer(0.0, 0.0, 0.0f, 0.0f, 0.0f, 0.0f, false);
            return;
        }
        float x0 = dock.getContentX0(DockPanel.SCENE), x1 = dock.getContentX1(DockPanel.SCENE);
        float y0 = dock.getContentY0(DockPanel.SCENE), y1 = dock.getContentY1(DockPanel.SCENE);
        double cx = window.getCursorX(), cy = window.getCursorY();
        // NDC y points up, window y down: the tile's top edge is y1
        Input.updatePointer(cx, cy, (x0 + 1.0f) * 0.5f * winW, (1.0f - y1) * 0.5f * winH,
                (x1 - x0) * 0.5f * winW, (y1 - y0) * 0.5f * winH,
                router != null && router.isCursorInViewport(cx, cy));
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
            float frameDelta = Math.min((frameStartNs - lastFrameStartNs) / 1_000_000_000.0f, MAX_FRAME_DELTA);
            lastFrameStartNs = frameStartNs;
            FrameTimings.MAIN.begin();

            window.pollEvents();
            updateScriptPointer(window, editorManager, context.getInputRouter());

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

            FrameTimings.MAIN.mark(FrameTimings.POLL);

            // Scene simulation. A crash here stops Play instead of closing the editor and losing unsaved work.
            if (editorManager != null) {
                try {
                    editorManager.update(frameDelta);
                } catch (RuntimeException | StackOverflowError e) {
                    Log.error("EngineLoop", "Scene update failed; stopping Play mode.");
                    e.printStackTrace();
                    try {
                        editorManager.stop();
                    } catch (RuntimeException stopFailure) {
                        stopFailure.printStackTrace();
                    }
                }
            }

            FrameTimings.MAIN.mark(FrameTimings.UPDATE);

            // Single Scene Graph & Virtual Thread Streaming Render Pass
            if (renderSubmitter != null) {
                renderSubmitter.submitFrame(editorManager, streamingService, rhi);
            }
            FrameTimings.MAIN.mark(FrameTimings.SUBMIT);

            if (rhi != null) {
                rhi.beginFrame();
                rhi.endFrame();
                FrameTimings.MAIN.mark(FrameTimings.RENDER);
                rhi.present();
                FrameTimings.MAIN.mark(FrameTimings.PRESENT);
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
            FrameTimings.MAIN.mark(FrameTimings.PACE);
        }
    }
}
