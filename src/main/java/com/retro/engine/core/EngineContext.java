package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.input.EditorInputRouter;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.editor.ui.panels.ViewportPanel;
import com.retro.engine.rhi.IRenderBackend;
import org.lwjgl.glfw.GLFW;

/**
 * EngineContext manages subsystem bootstrapping, window creation,
 * listener wiring, and RHI backend initialization.
 * Hardened with explicit listener unbinding and workspace disposal hooks.
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
    private boolean headlessMode = false;
    private boolean runtimeOnlyMode = false;

    public boolean initialize(int width, int height, String title) {
        return initialize(width, height, title, false, null);
    }

    public boolean initialize(int width, int height, String title, boolean isRuntimeOnly) {
        return initialize(width, height, title, isRuntimeOnly, null);
    }

    public boolean initializeHeadless() {
        return initializeHeadless(null);
    }

    public boolean initializeHeadless(String[] args) {
        this.headlessMode = true;
        this.runtimeOnlyMode = true;
        this.editorManager = new EditorWorkspace();
        this.cameraController = new ViewportCamera();
        this.hotkeyHandler = new HotkeyRouter();
        this.streamingService = new AssetStreamer();
        this.renderSubmitter = new SceneRenderPipeline();

        System.out.println("[EngineContext] Headless Dedicated Server Context Bootstrapped (No GLFW/GPU).");
        return true;
    }

    public boolean initialize(int width, int height, String title, boolean isRuntimeOnly, String[] cliArgs) {
        this.runtimeOnlyMode = isRuntimeOnly;
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
        if (!loader.initializeWithFallback(window.getWindowInfo(), cliArgs)) {
            System.err.println("[Fatal] Target backend failed to initialize with automatic fallback. Halting execution.");
            shutdown();
            return false;
        }

        rhi = loader.getBackend();

        // Listen for window resizes and update editorManager dimensions
        window.setOnWindowSizeListener((w, h) -> {
            if (editorManager != null) {
                editorManager.setWindowDimensions(w, h);
                System.out.println("[Engine] Window Resized -> " + w + "x" + h);
            }
        });

        window.setOnFileDropListener(filePaths -> {
            if (editorManager != null && editorManager.getAssetDatabase() != null) {
                editorManager.getAssetDatabase().onFilesDropped(filePaths);
                System.out.println("[Asset Directory] Ingested " + filePaths.size() + " asset(s).");
            }
        });

        if (!isRuntimeOnly) {
            window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
                if (selectionController == null || cameraController == null) return;
                boolean handled = selectionController.handleMouseClick(mouseX, mouseY, button, action);
                boolean inViewport = selectionController.isCursorInViewport(mouseX, mouseY);
                
                // Forward to camera controller if not handled by UI, OR if right-clicking inside the 3D viewport
                if (!handled || (button == 1 && inViewport)) {
                    cameraController.onMouseButton(button, action, mouseX, mouseY);
                }
            });

            window.setOnCursorPosListener((x, y) -> {
                if (selectionController == null || cameraController == null || window == null) return;
                boolean handledByGizmo = selectionController.onCursorPos(x, y);
                if (!handledByGizmo && selectionController.isCursorInViewport(x, y)) {
                    boolean isShiftDown = window.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
                    boolean isAltDown = window.isKeyDown(GLFW.GLFW_KEY_LEFT_ALT) || window.isKeyDown(GLFW.GLFW_KEY_RIGHT_ALT);
                    cameraController.onCursorPos(x, y, isShiftDown, isAltDown);
                }
            });

            window.setOnScrollListener((xoffset, yoffset) -> {
                if (selectionController != null) {
                    selectionController.handleScroll(yoffset);
                } else if (cameraController != null) {
                    cameraController.onScroll(yoffset);
                }
            });
        }

        streamingService.bindRenderBackend(rhi);

        if (rhi != null && rhi.getAPI() != null) {
            String apiName = rhi.getAPI().name().toUpperCase();
            ViewportPanel.setRhiCanvasTitle("3D VIEWPORT [" + apiName + " RHI CANVAS]");
        }

        System.out.println("[Engine] Running. Virtual Thread Asset Streaming Active (RuntimeOnly: " + isRuntimeOnly + ").");
        return true;
    }

    public boolean isHeadlessMode() { return headlessMode; }
    public boolean isRuntimeOnlyMode() { return runtimeOnlyMode; }

    public WindowHost getWindow() { return window; }
    public EditorWorkspace getEditorManager() { return editorManager; }
    public ViewportCamera getCameraController() { return cameraController; }
    public HotkeyRouter getHotkeyHandler() { return hotkeyHandler; }
    public AssetStreamer getStreamingService() { return streamingService; }
    public SceneRenderPipeline getRenderSubmitter() { return renderSubmitter; }
    public IRenderBackend getRhi() { return rhi; }

    public void unbindWindowListeners() {
        if (window != null) {
            window.unbindAllListeners();
        }
    }

    public void shutdown() {
        unbindWindowListeners();
        if (editorManager != null) {
            editorManager.dispose();
            editorManager = null;
        }
        if (streamingService != null) {
            streamingService.shutdown();
            streamingService = null;
        }
        if (rhi != null) {
            rhi.shutdown();
            rhi = null;
        }
        if (window != null) {
            window.destroy();
            window = null;
        }
        if (selectionController != null) {
            selectionController.dispose();
            selectionController = null;
        }
        cameraController = null;
        hotkeyHandler = null;
        renderSubmitter = null;
        System.out.println("[EngineContext] Shutdown complete and references unbound.");
    }
}
