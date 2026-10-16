package com.retro.engine.host;

import com.retro.engine.content.AssetStreamingService;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorSelectionController;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.editor.input.EditorHotkeyHandler;
import com.retro.engine.editor.render.SceneRenderSubmitter;
import com.retro.engine.rhi.IRenderBackend;
import org.lwjgl.glfw.GLFW;

/**
 * EngineContext manages subsystem bootstrapping, window creation,
 * listener wiring, and RHI backend initialization.
 */
public class EngineContext {

    private GlfwWindow window;
    private EditorPanelManager editorManager;
    private EditorSelectionController selectionController;
    private ViewportCameraController cameraController;
    private EditorHotkeyHandler hotkeyHandler;
    private AssetStreamingService streamingService;
    private SceneRenderSubmitter renderSubmitter;
    private IRenderBackend rhi;

    public boolean initialize(int width, int height, String title) {
        window = new GlfwWindow(width, height, title);
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            return false;
        }

        editorManager = new EditorPanelManager();
        editorManager.setWindowHandle(window.getWindowHandle());
        editorManager.setWindowDimensions(width, height);

        selectionController = new EditorSelectionController(editorManager);
        cameraController = new ViewportCameraController();
        selectionController.setCameraController(cameraController);

        // Single Source of Truth: Link CameraController to EditorPanelManager
        editorManager.setCameraController(cameraController);

        hotkeyHandler = new EditorHotkeyHandler();
        streamingService = new AssetStreamingService();
        renderSubmitter = new SceneRenderSubmitter();

        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            return false;
        }

        rhi = loader.getBackend();

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
                boolean isShiftDown = window.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
                boolean isAltDown = window.isKeyDown(GLFW.GLFW_KEY_LEFT_ALT) || window.isKeyDown(GLFW.GLFW_KEY_RIGHT_ALT);
                cameraController.onCursorPos(x, y, isShiftDown, isAltDown);
            }
        });

        window.setOnScrollListener((xoffset, yoffset) -> cameraController.onScroll(yoffset));

        if (!rhi.initialize(window.getWindowInfo())) {
            System.err.println("[Fatal] Failed to initialize RHI backend.");
            window.destroy();
            return false;
        }

        System.out.println("[Engine] Running. Virtual Thread Asset Streaming & Hotkeys Active.");
        return true;
    }

    public GlfwWindow getWindow() { return window; }
    public EditorPanelManager getEditorManager() { return editorManager; }
    public ViewportCameraController getCameraController() { return cameraController; }
    public EditorHotkeyHandler getHotkeyHandler() { return hotkeyHandler; }
    public AssetStreamingService getStreamingService() { return streamingService; }
    public SceneRenderSubmitter getRenderSubmitter() { return renderSubmitter; }
    public IRenderBackend getRhi() { return rhi; }

    public void shutdown() {
        if (streamingService != null) {
            streamingService.shutdown();
        }
        if (rhi != null) {
            rhi.shutdown();
        }
        if (window != null) {
            window.destroy();
        }
        System.out.println("[Engine] Shutdown complete.");
    }
}
