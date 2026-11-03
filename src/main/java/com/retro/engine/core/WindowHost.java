package com.retro.engine.core;

import com.retro.engine.graphics.ViewportSpec;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * WindowHost encapsulates GLFW window lifecycle, screen-to-backing projections,
 * and decoupling of platform native callbacks via functional interfaces.
 * Hardened with explicit unbind hooks to eliminate GC retention leaks.
 */
public class WindowHost {

    @FunctionalInterface
    public interface WindowSizeListener {
        void onResize(int width, int height);
    }

    @FunctionalInterface
    public interface FramebufferSizeListener {
        void onFramebufferResize(int pixelWidth, int pixelHeight);
    }

    @FunctionalInterface
    public interface MouseClickListener {
        void onClick(double mouseX, double mouseY, int button, int action);
    }

    @FunctionalInterface
    public interface MouseClick3ArgListener {
        void onClick(double mouseX, double mouseY, int button);
    }

    @FunctionalInterface
    public interface CursorPosListener {
        void onCursorPos(double x, double y, boolean isShiftDown, boolean isAltDown);
    }

    @FunctionalInterface
    public interface CursorPos2ArgListener {
        void onCursorPos(double x, double y);
    }

    @FunctionalInterface
    public interface ScrollListener {
        void onScroll(double xoffset, double yoffset);
    }

    @FunctionalInterface
    public interface SingleScrollListener {
        void onScroll(double yoffset);
    }

    @FunctionalInterface
    public interface KeyListener {
        void onKey(int key, int action, int mods);
    }

    @FunctionalInterface
    public interface KeyPressListener {
        void onKeyPress(int key, int action);
    }

    @FunctionalInterface
    public interface CharListener {
        void onChar(int codepoint);
    }

    private long windowHandle = NULL;
    private int width;
    private int height;
    private final String title;
    private boolean isFullscreen = false;

    private int windowedX = 100;
    private int windowedY = 100;
    private int windowedWidth;
    private int windowedHeight;

    private WindowSizeListener windowSizeListener;
    private FramebufferSizeListener framebufferSizeListener;
    private Consumer<List<String>> fileDropListener;
    private MouseClickListener mouseClickListener;
    private MouseClick3ArgListener mouseClick3ArgListener;
    private BiConsumer<Double, Double> mouseClickBiConsumer;

    private CursorPosListener cursorPosListener;
    private CursorPos2ArgListener cursorPos2ArgListener;

    private ScrollListener scrollListener;
    private SingleScrollListener singleScrollListener;

    private KeyListener keyListener;
    // OS-reported modifier flags on each key's most recent press; polled modifier state can latch
    private final int[] keyPressMods = new int[GLFW_KEY_LAST + 1];
    private KeyPressListener keyPressListener;
    private CharListener charListener;
    // True while an editor text field owns the keyboard, so Escape cancels the edit instead of closing the window
    private BooleanSupplier textInputCapture;

    private GamepadManager gamepadManager;

    // Mouse-look: while captured the cursor is hidden and unbounded, and motion accumulates per frame
    private boolean cursorCaptured = false;
    // Set by an Escape press that neither released the cursor nor went to a text field; see consumeEscapePress()
    private boolean escapePressed = false;
    private boolean cursorCaptureFresh = false; // Next position sample only re-bases, so capturing never jumps the view
    private double lastCursorX, lastCursorY;
    private double mouseDeltaX, mouseDeltaY;

    public WindowHost(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.windowedWidth = width;
        this.windowedHeight = height;
    }

    public void setOnWindowSizeListener(WindowSizeListener listener) {
        this.windowSizeListener = listener;
    }

    public void setOnFramebufferSizeListener(FramebufferSizeListener listener) {
        this.framebufferSizeListener = listener;
    }

    public void setOnFileDropListener(Consumer<List<String>> listener) {
        this.fileDropListener = listener;
    }

    public void setOnMouseClickListener(MouseClickListener listener) {
        this.mouseClickListener = listener;
    }
    public void setMouseButtonListener(MouseClickListener listener) {
        this.mouseClickListener = listener;
    }
    public void setOnMouseClickListener(MouseClick3ArgListener listener) {
        this.mouseClick3ArgListener = listener;
    }
    public void setMouseButtonListener(MouseClick3ArgListener listener) {
        this.mouseClick3ArgListener = listener;
    }
    public void setOnMouseClickListener(BiConsumer<Double, Double> listener) {
        this.mouseClickBiConsumer = listener;
    }

    public void setOnCursorPosListener(CursorPosListener listener) {
        this.cursorPosListener = listener;
    }
    public void setCursorPosListener(CursorPosListener listener) {
        this.cursorPosListener = listener;
    }
    public void setOnCursorPosListener(CursorPos2ArgListener listener) {
        this.cursorPos2ArgListener = listener;
    }
    public void setCursorPosListener(CursorPos2ArgListener listener) {
        this.cursorPos2ArgListener = listener;
    }

    public void setOnScrollListener(ScrollListener listener) {
        this.scrollListener = listener;
    }
    public void setScrollListener(ScrollListener listener) {
        this.scrollListener = listener;
    }
    public void setOnScrollListener(SingleScrollListener listener) {
        this.singleScrollListener = listener;
    }
    public void setScrollListener(SingleScrollListener listener) {
        this.singleScrollListener = listener;
    }

    public void setOnKeyListener(KeyListener listener) {
        this.keyListener = listener;
    }
    public void setKeyListener(KeyListener listener) {
        this.keyListener = listener;
    }
    public void setOnKeyListener(KeyPressListener listener) {
        this.keyPressListener = listener;
    }
    public void setKeyPressListener(KeyPressListener listener) {
        this.keyPressListener = listener;
    }

    public void setCharListener(CharListener listener) {
        this.charListener = listener;
    }

    public void setTextInputCapture(BooleanSupplier capture) {
        this.textInputCapture = capture;
    }

    private static final String ICON_FILE = "Assets/Editor/icon.png";
    private static final String ICON_RESOURCE = "/icons/icon.png";

    /**
     * Sets the window/taskbar icon from {@value #ICON_FILE} (next to the working directory), falling back to the
     * bundled {@value #ICON_RESOURCE} resource. macOS has no per-window icon (the bundle icon is used), so it is skipped.
     */
    private void applyWindowIcon() {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            java.nio.ByteBuffer pixels = null;
            java.io.File file = new java.io.File(ICON_FILE);
            if (file.isFile()) {
                pixels = org.lwjgl.stb.STBImage.stbi_load(file.getAbsolutePath(), w, h, channels, 4);
            }
            if (pixels == null) {
                try (java.io.InputStream in = WindowHost.class.getResourceAsStream(ICON_RESOURCE)) {
                    if (in != null) {
                        byte[] bytes = in.readAllBytes();
                        java.nio.ByteBuffer encoded = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
                        try {
                            encoded.put(bytes).flip();
                            pixels = org.lwjgl.stb.STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
                        } finally {
                            org.lwjgl.system.MemoryUtil.memFree(encoded);
                        }
                    }
                }
            }
            if (pixels == null) {
                System.err.println("[GLFW] Window icon not loaded (" + ICON_FILE + " / " + ICON_RESOURCE + "): "
                    + org.lwjgl.stb.STBImage.stbi_failure_reason());
                return;
            }
            try {
                org.lwjgl.glfw.GLFWImage.Buffer images = org.lwjgl.glfw.GLFWImage.malloc(1, stack);
                images.get(0).set(w.get(0), h.get(0), pixels);
                glfwSetWindowIcon(windowHandle, images);
            } finally {
                org.lwjgl.stb.STBImage.stbi_image_free(pixels);
            }
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("[GLFW] Window icon setup failed: " + e);
        }
    }

    public boolean create() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            System.err.println("[GLFW] Failed to initialize GLFW!");
            return false;
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        windowHandle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (windowHandle == NULL) {
            System.err.println("[GLFW] Failed to create GLFW window!");
            return false;
        }

        applyWindowIcon();

        gamepadManager = new GamepadManager();

        // Key Callback
        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS && key >= 0 && key <= GLFW_KEY_LAST) {
                keyPressMods[key] = mods;
            }
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_ESCAPE) {
                    if (cursorCaptured) {
                        setCursorCaptured(false); // Escape frees the mouse first; it never closes the window
                    } else if (textInputCapture == null || !textInputCapture.getAsBoolean()) {
                        escapePressed = true; // Consumed by the editor (HotkeyRouter) to clear the selection
                    }
                } else if (key == GLFW_KEY_F11 || (key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0)) {
                    toggleFullscreen();
                }
            }
            if (keyListener != null) {
                keyListener.onKey(key, action, mods);
            }
            if (keyPressListener != null) {
                keyPressListener.onKeyPress(key, action);
            }
        });

        // Character Callback (Unicode text input, after keyboard layout and modifiers are applied)
        glfwSetCharCallback(windowHandle, (window, codepoint) -> {
            if (charListener != null) {
                charListener.onChar(codepoint);
            }
        });

        // Window Size Callback
        glfwSetWindowSizeCallback(windowHandle, (window, w, h) -> {
            this.width = w;
            this.height = h;
            if (windowSizeListener != null) {
                windowSizeListener.onResize(w, h);
            }
        });

        // Framebuffer Size Callback (backing pixels; differs from the window size on Retina/HiDPI displays)
        glfwSetFramebufferSizeCallback(windowHandle, (window, w, h) -> {
            if (framebufferSizeListener != null) {
                framebufferSizeListener.onFramebufferResize(w, h);
            }
        });

        // Drag-and-Drop Callback
        glfwSetDropCallback(windowHandle, (window, count, names) -> {
            PointerBuffer nameBuffer = PointerBuffer.create(names, count);
            List<String> droppedFiles = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                droppedFiles.add(nameBuffer.getStringUTF8(i));
            }
            System.out.println("[GLFW] Files dropped into window: " + droppedFiles);
            if (fileDropListener != null) {
                fileDropListener.accept(droppedFiles);
            }
        });

        // Mouse Button Callback
        glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(windowHandle, x, y);

            if (mouseClickListener != null) {
                mouseClickListener.onClick(x[0], y[0], button, action);
            }
            if (mouseClick3ArgListener != null) {
                mouseClick3ArgListener.onClick(x[0], y[0], button);
            }
            if (mouseClickBiConsumer != null) {
                mouseClickBiConsumer.accept(x[0], y[0]);
            }
        });

        // Cursor Position Callback
        glfwSetCursorPosCallback(windowHandle, (window, xpos, ypos) -> {
            if (cursorCaptured) {
                if (!cursorCaptureFresh) {
                    mouseDeltaX += xpos - lastCursorX;
                    mouseDeltaY += ypos - lastCursorY;
                }
                cursorCaptureFresh = false;
                lastCursorX = xpos;
                lastCursorY = ypos;
            }
            boolean isShiftDown = isKeyDown(GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW_KEY_RIGHT_SHIFT);
            boolean isAltDown = isKeyDown(GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW_KEY_RIGHT_ALT);

            if (cursorPosListener != null) {
                cursorPosListener.onCursorPos(xpos, ypos, isShiftDown, isAltDown);
            }
            if (cursorPos2ArgListener != null) {
                cursorPos2ArgListener.onCursorPos(xpos, ypos);
            }
        });

        // Scroll Callback
        glfwSetScrollCallback(windowHandle, (window, xoffset, yoffset) -> {
            if (scrollListener != null) {
                scrollListener.onScroll(xoffset, yoffset);
            }
            if (singleScrollListener != null) {
                singleScrollListener.onScroll(yoffset);
            }
        });

        System.out.println("[GLFW] Native window initialized successfully (" + width + "x" + height + ").");
        return true;
    }

    public void pollEvents() {
        mouseDeltaX = 0.0;
        mouseDeltaY = 0.0;
        glfwPollEvents();
        if (gamepadManager != null) {
            gamepadManager.update();
        }
    }

    /** Hides and locks the cursor for mouse-look (raw, unaccelerated motion where the platform supports it). */
    public void setCursorCaptured(boolean captured) {
        if (windowHandle == NULL || captured == cursorCaptured) return;
        cursorCaptured = captured;
        cursorCaptureFresh = true;
        mouseDeltaX = 0.0;
        mouseDeltaY = 0.0;
        glfwSetInputMode(windowHandle, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(windowHandle, GLFW_RAW_MOUSE_MOTION, captured ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    public boolean isCursorCaptured() { return cursorCaptured; }

    /** True once per Escape press that wasn't used to release the cursor or cancel text input. */
    public boolean consumeEscapePress() {
        boolean pressed = escapePressed;
        escapePressed = false;
        return pressed;
    }

    /** Cursor motion in pixels since the last {@link #pollEvents()} while captured (+X right, +Y down); 0 otherwise. */
    public float getMouseDeltaX() { return (float) mouseDeltaX; }
    public float getMouseDeltaY() { return (float) mouseDeltaY; }

    public boolean shouldClose() {
        return glfwWindowShouldClose(windowHandle);
    }

    public boolean isKeyDown(int key) {
        if (windowHandle == NULL) return false;
        return glfwGetKey(windowHandle, key) == GLFW_PRESS;
    }

    /** Modifier bits (GLFW_MOD_*) the OS reported when {@code key} was last pressed. */
    public int getKeyPressMods(int key) {
        if (key < 0 || key > GLFW_KEY_LAST) return 0;
        return keyPressMods[key];
    }

    /** False while another application has keyboard focus; joysticks keep reporting input regardless. */
    public boolean isFocused() {
        if (windowHandle == NULL) return false;
        return glfwGetWindowAttrib(windowHandle, GLFW_FOCUSED) == GLFW_TRUE;
    }

    public GamepadManager getGamepadManager() { return gamepadManager; }

    public long getNativeHandle() { return windowHandle; }
    public long getWindowHandle() { return windowHandle; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public ViewportSpec getWindowInfo() {
        return new ViewportSpec(windowHandle, width, height, true);
    }

    public void toggleFullscreen() {
        long primaryMonitor = glfwGetPrimaryMonitor();
        if (primaryMonitor == NULL) return;

        GLFWVidMode vidMode = glfwGetVideoMode(primaryMonitor);
        if (vidMode == null) return;

        if (!isFullscreen) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pX = stack.mallocInt(1);
                IntBuffer pY = stack.mallocInt(1);
                IntBuffer pW = stack.mallocInt(1);
                IntBuffer pH = stack.mallocInt(1);

                glfwGetWindowPos(windowHandle, pX, pY);
                glfwGetWindowSize(windowHandle, pW, pH);

                windowedX = pX.get(0);
                windowedY = pY.get(0);
                windowedWidth = pW.get(0);
                windowedHeight = pH.get(0);
            }

            glfwSetWindowMonitor(windowHandle, primaryMonitor, 0, 0, vidMode.width(), vidMode.height(), vidMode.refreshRate());
            isFullscreen = true;
            System.out.println("[GLFW] Mode: Fullscreen (" + vidMode.width() + "x" + vidMode.height() + ")");
        } else {
            glfwSetWindowMonitor(windowHandle, NULL, windowedX, windowedY, windowedWidth, windowedHeight, 0);
            isFullscreen = false;
            System.out.println("[GLFW] Mode: Windowed (" + windowedWidth + "x" + windowedHeight + ")");
        }
    }

    public void unbindAllListeners() {
        this.windowSizeListener = null;
        this.framebufferSizeListener = null;
        this.fileDropListener = null;
        this.mouseClickListener = null;
        this.mouseClick3ArgListener = null;
        this.mouseClickBiConsumer = null;
        this.cursorPosListener = null;
        this.cursorPos2ArgListener = null;
        this.scrollListener = null;
        this.singleScrollListener = null;
        this.keyListener = null;
        this.keyPressListener = null;
        this.charListener = null;
        this.textInputCapture = null;
    }

    public void destroy() {
        unbindAllListeners();
        if (gamepadManager != null) {
            gamepadManager.destroy();
            gamepadManager = null;
        }
        if (windowHandle != NULL) {
            glfwSetKeyCallback(windowHandle, null);
            glfwSetCharCallback(windowHandle, null);
            glfwSetWindowSizeCallback(windowHandle, null);
            glfwSetFramebufferSizeCallback(windowHandle, null);
            glfwSetDropCallback(windowHandle, null);
            glfwSetMouseButtonCallback(windowHandle, null);
            glfwSetCursorPosCallback(windowHandle, null);
            glfwSetScrollCallback(windowHandle, null);
            glfwDestroyWindow(windowHandle);
            windowHandle = NULL;
        }
        glfwTerminate();

        GLFWErrorCallback old = glfwSetErrorCallback(null);
        if (old != null) {
            old.free();
        }
        System.out.println("[GLFW] Window resources released and callbacks unbound.");
    }
}
