package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorInputRouter;
import com.retro.engine.editor.gizmos.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.IRenderBackend;
import org.lwjgl.glfw.GLFW;

/**
 * EngineContext manages subsystem bootstrapping, window creation,
 * listener wiring, and RHI backend initialization.
 */
public class EngineContext {

    private WindowHost window;
    private EditorWorkspace editorManager;
    private EditorInputRouter selectionController;
    private ViewportCamera cameraController;
    private HotkeyRouter hotkeyHandler;
    private AssetStreamer streamingService;
    private SceneRenderPipeline renderSubmitter;
    private IRenderBackend rhi;

    public boolean initialize(int width, int height, String title) {
        window = new WindowHost(width, height, title);
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            return false;
        }

        editorManager = new EditorWorkspace();
        editorManager.setWindowHandle(window.getWindowHandle());
        editorManager.setWindowDimensions(width, height);

        selectionController = new EditorInputRouter(editorManager);
        cameraController = new ViewportCamera();
        selectionController.setCameraController(cameraController);

        // Single Source of Truth: Link CameraController to EditorWorkspace
        editorManager.setCameraController(cameraController);

        hotkeyHandler = new HotkeyRouter();
        streamingService = new AssetStreamer();
        renderSubmitter = new SceneRenderPipeline();

        BackendLoader loader = new BackendLoader();
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
            boolean handled = selectionController.handleMouseClick(mouseX, mouseY, button, action, editorManager);
            boolean inViewport = selectionController.isCursorInViewport(mouseX, mouseY, editorManager);
            
            // Forward to camera controller if not handled by UI, OR if right-clicking inside the 3D viewport
            if (!handled || (button == 1 && inViewport)) {
                cameraController.onMouseButton(button, action, mouseX, mouseY);
            }
        });

        window.setOnCursorPosListener((x, y) -> {
            boolean handledByGizmo = selectionController.onCursorPos(x, y, editorManager);
            if (!handledByGizmo && selectionController.isCursorInViewport(x, y, editorManager)) {
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

    public WindowHost getWindow() { return window; }
    public EditorWorkspace getEditorManager() { return editorManager; }
    public ViewportCamera getCameraController() { return cameraController; }
    public HotkeyRouter getHotkeyHandler() { return hotkeyHandler; }
    public AssetStreamer getStreamingService() { return streamingService; }
    public SceneRenderPipeline getRenderSubmitter() { return renderSubmitter; }
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
