package com.retro.engine.core;

import com.retro.engine.scene.components.Camera;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.gizmos.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.IRenderBackend;

/**
 * EngineLoop manages frame delta timing, event polling,
 * input processing, camera state synchronization, and RHI rendering ticks.
 */
public class EngineLoop {

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
            float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;

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

            editorManager.update(deltaTime);

            // 1-Line Scene Graph & Virtual Thread Streaming Render Pass
            renderSubmitter.submitFrame(editorManager, streamingService, rhi);

            rhi.updateGridMesh(editorManager.get3DGridVertices(), editorManager.get3DGridVertexCount());
            rhi.updateUiMesh(editorManager.getUiVertices(), editorManager.getUiVertexCount());

            rhi.beginFrame();
            rhi.endFrame();
            rhi.present();
        }
    }
}
