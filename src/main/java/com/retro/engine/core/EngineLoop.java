package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.IRenderBackend;

/**
 * EngineLoop manages frame delta timing with sub-millisecond precision, event polling,
 * input processing, camera state synchronization, double-buffered command flushing, and RHI rendering ticks.
 */
public class EngineLoop {

    private static final long TARGET_FRAME_TIME_NS = 16_666_667L; // 60 FPS (16.666 ms)
    private static final float MAX_FRAME_TIME = 0.1f; // Sanity cap (100ms) to prevent giant time spikes

    public void run(EngineContext context) {
        WindowHost window = context.getWindow();
        EditorWorkspace editorManager = context.getEditorManager();
        ViewportCamera cameraController = context.getCameraController();
        HotkeyRouter hotkeyHandler = context.getHotkeyHandler();
        AssetStreamer streamingService = context.getStreamingService();
        SceneRenderPipeline renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();

        long lastTime = System.nanoTime();

        while (!window.shouldClose()) {
            window.pollEvents();

            long currentTime = System.nanoTime();
            float rawDeltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;

            // Clamp delta time to protect against window gestures or focus spikes
            float deltaTime = Math.min(Math.max(rawDeltaTime, 0.0001f), MAX_FRAME_TIME);

            // 0. Double-Buffered Transactional Editor Command Flush at Frame Boundary
            if (editorManager != null && editorManager.getCommandHistory() != null) {
                editorManager.getCommandHistory().flushPendingCommands();
            }

            // Process Keyboard Hotkeys, Gizmo Snapping, and Flying Camera State
            hotkeyHandler.processInput(window, editorManager, cameraController, deltaTime);

            // Synchronize Camera MVP Parameters across RHI and EditorWorkspace
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

            // Per-frame smooth delta update for simulation & viewport telemetry
            editorManager.update(deltaTime);

            // 1-Line Scene Graph & Virtual Thread Streaming Render Pass
            renderSubmitter.submitFrame(editorManager, streamingService, rhi);

            rhi.updateGridMesh(editorManager.get3DGridVertices(), editorManager.get3DGridVertexCount());
            rhi.updateUiMesh(editorManager.getUiVertices(), editorManager.getUiVertexCount());

            rhi.beginFrame();
            rhi.endFrame();
            rhi.present();

            // Sub-millisecond Frame Pacing (Pacing loop to achieve stable 60 FPS)
            long frameEndTime = System.nanoTime();
            long elapsedNs = frameEndTime - currentTime;
            long sleepNs = TARGET_FRAME_TIME_NS - elapsedNs;

            if (sleepNs > 0) {
                long sleepMs = sleepNs / 1_000_000L;
                if (sleepMs > 2) {
                    try {
                        Thread.sleep(sleepMs - 1);
                    } catch (InterruptedException ignored) {}
                }
                while (System.nanoTime() - currentTime < TARGET_FRAME_TIME_NS) {
                    Thread.onSpinWait();
                }
            }
        }
    }
}
