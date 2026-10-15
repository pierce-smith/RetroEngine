package com.retro.engine;

import com.retro.engine.content.AssetStreamingService;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorSelectionController;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.editor.input.EditorHotkeyHandler;
import com.retro.engine.editor.render.SceneRenderSubmitter;
import com.retro.engine.host.GlfwWindow;
import com.retro.engine.host.HostBackendLoader;
import com.retro.engine.rhi.IRenderBackend;

/**
 * Main Engine Entry Point.
 * Clean, decoupled main loop delegating hotkeys, gizmo interactions, and panel routing to input sub-handlers.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Visual Editor IDE   ");
        System.out.println("  JDK 21+ Virtual Thread Asset Streaming   ");
        System.out.println("===========================================");

        GlfwWindow window = new GlfwWindow(1280, 720, "Retro Engine Editor & Runtime");
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            System.exit(1);
        }

        EditorPanelManager editorManager = new EditorPanelManager();
        editorManager.setWindowHandle(window.getWindowHandle());
        editorManager.setWindowDimensions(1280, 720);

        EditorSelectionController selectionController = new EditorSelectionController(editorManager);
        ViewportCameraController cameraController = new ViewportCameraController();
        selectionController.setCameraController(cameraController);

        // Single Source of Truth: Link CameraController to EditorPanelManager
        editorManager.setCameraController(cameraController);

        EditorHotkeyHandler hotkeyHandler = new EditorHotkeyHandler();
        AssetStreamingService streamingService = new AssetStreamingService();
        SceneRenderSubmitter renderSubmitter = new SceneRenderSubmitter();

        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        // Listen for window resizes and update editorManager dimensions
        window.setOnWindowSizeListener((w, h) -> {
            editorManager.setWindowDimensions(w, h);
            System.out.println("[Engine] Window Resized -> " + w + "x" + h);
        });

        window.setOnFileDropListener(filePaths -> {
            editorManager.onFilesDropped(filePaths);
            System.out.println("[Asset Directory] Ingested " + filePaths.size() + " asset(s).");
        });

        window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
            selectionController.handleMouseClick(mouseX, mouseY, button, action, editorManager);
            cameraController.onMouseButton(button, action, mouseX, mouseY);
        });

        window.setOnCursorPosListener((x, y) -> {
            boolean handledByGizmo = selectionController.onCursorPos(x, y, editorManager);
            if (!handledByGizmo) {
                boolean isShiftDown = window.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
                boolean isAltDown = window.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT) || window.isKeyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT);
                cameraController.onCursorPos(x, y, isShiftDown, isAltDown);
            }
        });

        window.setOnScrollListener((xoffset, yoffset) -> cameraController.onScroll(yoffset));

        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. Virtual Thread Asset Streaming & Hotkeys Active.");

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

            streamingService.shutdown();
            rhi.shutdown();
        }

        window.destroy();
        System.out.println("[Engine] Shutdown complete.");
    }
}