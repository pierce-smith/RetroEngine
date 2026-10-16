package com.retro.engine.host;

import com.retro.engine.content.AssetStreamingService;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.editor.input.EditorHotkeyHandler;
import com.retro.engine.editor.render.SceneRenderSubmitter;
import com.retro.engine.rhi.IRenderBackend;

/**
 * EngineLoop manages frame delta timing, event polling,
 * input processing, camera state synchronization, and RHI rendering ticks.
 */
public class EngineLoop {

    public void run(EngineContext context) {
        GlfwWindow window = context.getWindow();
        EditorPanelManager editorManager = context.getEditorManager();
        ViewportCameraController cameraController = context.getCameraController();
        EditorHotkeyHandler hotkeyHandler = context.getHotkeyHandler();
        AssetStreamingService streamingService = context.getStreamingService();
        SceneRenderSubmitter renderSubmitter = context.getRenderSubmitter();
        IRenderBackend rhi = context.getRhi();

        long lastTime = System.nanoTime();

        while (!window.shouldClose()) {
            window.pollEvents();

            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
            lastTime = currentTime;

            // Process Keyboard Hotkeys, Gizmo Snapping, and Flying Camera State
            hotkeyHandler.processInput(window, editorManager, cameraController, deltaTime);

            // Synchronize Camera MVP Parameters across RHI and EditorPanelManager
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
