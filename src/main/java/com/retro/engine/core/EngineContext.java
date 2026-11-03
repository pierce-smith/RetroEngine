package com.retro.engine.core;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.input.EditorInputRouter;
import com.retro.engine.editor.input.HotkeyRouter;
import com.retro.engine.editor.input.InspectorInputDelegate;
import com.retro.engine.editor.overlays.BuildExporterOverlay;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.SceneRenderPipeline;
import com.retro.engine.graphics.ShaderSources;

import org.lwjgl.glfw.GLFW;

/**
 * EngineContext manages subsystem bootstrapping, input listener wiring, and RHI backend initialization.
 * Window lifecycle and display metrics are delegated to {@link DisplayService}.
 * Hardened with explicit listener unbinding and workspace disposal hooks.
 */
public class EngineContext {

    private DisplayService display;
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
    // Process-wide: set once a standalone runtime boots, so static asset resolvers can skip editor-only fallbacks
    private static volatile boolean runtimeProcess = false;

    public static boolean isRuntimeProcess() { return runtimeProcess; }

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
        runtimeProcess = true;
        this.editorManager = new EditorWorkspace();
        this.cameraController = new ViewportCamera();
        this.hotkeyHandler = new HotkeyRouter();
        this.streamingService = new AssetStreamer();
        this.renderSubmitter = new SceneRenderPipeline();

        System.out.println("[EngineContext] Headless Dedicated Server Context Bootstrapped (No GLFW/GPU).");
        return true;
    }

    /**
     * Editor only: when the engine's shader sources are on disk (running from a checkout), pipelines read them from there
     * and the asset watcher reports edits, which SceneRenderPipeline applies as a pipeline swap at the next frame boundary.
     */
    private void enableShaderHotReload() {
        java.io.File shaderRoot = ShaderSources.locateSourceRoot();
        if (shaderRoot == null) return;
        ShaderSources.setSourceRoot(shaderRoot);
        editorManager.getAssetDatabase().watchShaderSources(shaderRoot.getPath(), renderSubmitter::requestShaderReload);
        System.out.println("[EngineContext] Shader hot reload watching " + shaderRoot.getPath());
    }

    public boolean initialize(int width, int height, String title, boolean isRuntimeOnly, String[] cliArgs) {
        this.runtimeOnlyMode = isRuntimeOnly;
        if (isRuntimeOnly) runtimeProcess = true;
        display = new DisplayService();
        if (!display.create(width, height, title)) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            display = null;
            return false;
        }
        window = display.getWindow();

        com.retro.engine.scripting.Input.bindWindow(window);

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
        if (!loader.initializeWithFallback(display.getViewportSpec(), cliArgs)) {
            System.err.println("[Fatal] Target backend failed to initialize with automatic fallback. Halting execution.");
            shutdown();
            return false;
        }

        rhi = loader.getBackend();

        if (!isRuntimeOnly) enableShaderHotReload();

        // Listen for window resizes and update editorManager dimensions
        display.setOnWindowSizeListener((w, h) -> {
            if (editorManager != null) {
                editorManager.setWindowDimensions(w, h);
                System.out.println("[Engine] Window Resized -> " + w + "x" + h);
            }
        });

        display.setOnFramebufferSizeListener((w, h) -> {
            if (rhi != null) {
                rhi.onFramebufferResized(w, h);
            }
        });

        window.setOnFileDropListener(filePaths -> {
            if (editorManager != null && editorManager.hasActiveProject()) {
                editorManager.handleFileDrop(filePaths);
            }
        });

        if (isRuntimeOnly) {
            window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
                if (action == GLFW.GLFW_PRESS) relockCursorForPlay();
            });
        } else {
            window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
                if (selectionController == null || cameraController == null) return;
                boolean inViewport = selectionController.isCursorInViewport(mouseX, mouseY);
                // Clicking the game view during Play re-locks a mouse-look cursor that Escape freed; the click is consumed
                if (action == GLFW.GLFW_PRESS && inViewport && relockCursorForPlay()) return;
                boolean handled = selectionController.handleMouseClick(mouseX, mouseY, button, action);
                
                // Forward to camera controller if not handled by UI, OR if right-clicking inside the 3D viewport.
                // Releases always go through: a release that opens a context menu turns the region into
                // OVERLAY_MODAL, and dropping it would leave the camera stuck in RMB orbit/fly mode
                if (!handled || action == 0 || (button == 1 && inViewport)) {
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

            // Inline Inspector text fields own the keyboard while focused
            // The Build Exporter's Game Name field gets first claim on typing while it is focused
            window.setOnKeyListener((key, action, mods) -> {
                if (!BuildExporterOverlay.handleKey(key, action, editorManager)) InspectorInputDelegate.handleKey(key, action, editorManager);
            });
            window.setCharListener(cp -> {
                if (!BuildExporterOverlay.handleChar(cp, editorManager)) InspectorInputDelegate.handleChar(cp);
            });
            window.setTextInputCapture(() -> InspectorInputDelegate.isEditingText() || BuildExporterOverlay.isEditingText(editorManager));

            window.setOnScrollListener((xoffset, yoffset) -> {
                if (selectionController != null) {
                    selectionController.handleScroll(yoffset);
                } else if (cameraController != null) {
                    cameraController.onScroll(yoffset);
                }
            });
        }

        streamingService.bindRenderBackend(rhi);

        // Mod shader overrides rebuild pipelines through the same frame-boundary path as editor shader hot reload
        com.retro.engine.modding.ModShaders.bind(rhi != null ? rhi.getAPI() : null,
            files -> { if (renderSubmitter != null) renderSubmitter.requestShaderReload(files); });

        if (rhi != null && rhi.getAPI() != null) {
            String apiName = rhi.getAPI().name().toUpperCase();
            ViewportPanel.setRhiCanvasTitle("3D VIEWPORT [" + apiName + " RHI CANVAS]");
        }

        // Editor boots into the ProjectLauncherOverlay unless --project <dir> binds a project up front.
        // Runtime builds bind their exported Game_Data/ (or --project <dir>) and load its startup scene.
        // Mods: runtime builds load Game_Data/Mods/ unless --no-mods; the editor loads <project>/Mods/ only with --mods
        com.retro.engine.modding.ModLoader.setRuntimeModsEnabled(!hasCliFlag(cliArgs, "--no-mods"));
        com.retro.engine.modding.ModLoader.setEditorModsEnabled(hasCliFlag(cliArgs, "--mods"));

        if (isRuntimeOnly) {
            java.io.File gameData = resolveRuntimeGameData(cliArgs);
            if (gameData == null) {
                System.err.println("[EngineContext] No Game_Data/ or --project folder found; starting with an empty scene.");
            } else {
                try {
                    ProjectWorkspace.openRuntimeProject(editorManager, gameData);
                } catch (java.io.IOException e) {
                    System.err.println("[EngineContext] Failed to load game data: " + e.getMessage());
                }
            }
        } else {
            String cliProject = findCliValue(cliArgs, "--project");
            if (cliProject != null && !openProject(cliProject)) {
                System.err.println("[EngineContext] --project " + cliProject + " could not be opened; showing project launcher.");
            } else if (cliProject == null) {
                System.out.println("[EngineContext] No active project directory; showing project launcher.");
            }
        }

        System.out.println("[Engine] Running. Virtual Thread Asset Streaming Active (RuntimeOnly: " + isRuntimeOnly + ").");
        return true;
    }

    /** Opens an existing project directory (ProjectSettings.bin required) and binds the editor's AssetDatabase to it. */
    public boolean openProject(String projectPath) {
        if (editorManager == null || projectPath == null || projectPath.trim().isEmpty()) return false;
        java.io.File dir = new java.io.File(projectPath.trim()).getAbsoluteFile();
        if (!ProjectWorkspace.isProjectDirectory(dir)) {
            System.err.println("[EngineContext] Not a RetroEngine project (missing " + ProjectWorkspace.SETTINGS_FILE + "): " + dir);
            return false;
        }
        try {
            ProjectWorkspace.openProject(editorManager, dir);
            return true;
        } catch (java.io.IOException e) {
            System.err.println("[EngineContext] Failed to open project: " + e.getMessage());
            return false;
        }
    }

    private static java.io.File resolveRuntimeGameData(String[] cliArgs) {
        String[] candidates = {
            findCliValue(cliArgs, "--project"),
            System.getProperty(com.retro.engine.editor.StandaloneExporter.GAME_DATA_PROPERTY),
            new java.io.File(System.getProperty("user.dir"), com.retro.engine.editor.StandaloneExporter.GAME_DATA_DIR).getPath(),
            System.getProperty("user.dir")
        };
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            java.io.File dir = new java.io.File(c.trim()).getAbsoluteFile();
            if (ProjectWorkspace.isProjectDirectory(dir)) return dir;
        }
        return null;
    }

    private static boolean hasCliFlag(String[] args, String flag) {
        if (args == null) return false;
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    private static String findCliValue(String[] args, String flag) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if (flag.equalsIgnoreCase(args[i]) && i + 1 < args.length) return args[i + 1];
            if (args[i] != null && args[i].toLowerCase().startsWith(flag + "=")) return args[i].substring(flag.length() + 1);
        }
        return null;
    }

    /** Re-captures the cursor when gameplay wants mouse-look but Escape freed it; true if it did. */
    private boolean relockCursorForPlay() {
        if (window == null || window.isCursorCaptured() || !com.retro.engine.scripting.Input.isCursorLockWanted()) return false;
        if (editorManager == null || editorManager.getPlayState() != com.retro.engine.ecs.SceneWorld.PlayState.SIMULATING) return false;
        window.setCursorCaptured(true);
        return true;
    }

    public boolean isHeadlessMode() { return headlessMode; }
    public boolean isRuntimeOnlyMode() { return runtimeOnlyMode; }

    /** Null in headless mode (no GLFW). */
    public DisplayService getDisplay() { return display; }
    public WindowHost getWindow() { return window; }
    /** Null in headless mode (no GLFW). */
    public GamepadManager getGamepadManager() { return window != null ? window.getGamepadManager() : null; }
    public EditorWorkspace getEditorManager() { return editorManager; }
    public ViewportCamera getCameraController() { return cameraController; }
    public HotkeyRouter getHotkeyHandler() { return hotkeyHandler; }
    public AssetStreamer getStreamingService() { return streamingService; }
    public SceneRenderPipeline getRenderSubmitter() { return renderSubmitter; }
    public IRenderBackend getRhi() { return rhi; }

    public void unbindWindowListeners() {
        if (display != null) {
            display.unbindListeners();
        }
    }

    public void shutdown() {
        com.retro.engine.scripting.Input.unbindWindow();
        unbindWindowListeners();
        if (editorManager != null) {
            editorManager.dispose();
            editorManager = null;
        }
        com.retro.engine.audio.AudioEngine.shutdown();
        if (streamingService != null) {
            streamingService.shutdown();
            streamingService = null;
        }
        if (rhi != null) {
            rhi.shutdown();
            rhi = null;
        }
        if (display != null) {
            display.destroy();
            display = null;
        }
        window = null;
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
