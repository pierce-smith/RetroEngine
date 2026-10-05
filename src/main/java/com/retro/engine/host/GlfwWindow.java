package com.retro.engine.host;

import com.retro.engine.rhi.WindowInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class GlfwWindow {

    @FunctionalInterface
    public interface MouseButtonListener {
        void onMouseButton(int button, int action, double x, double y);
    }

    @FunctionalInterface
    public interface CursorPosListener {
        void onCursorPos(double x, double y, boolean isShiftDown, boolean isAltDown);
    }

    @FunctionalInterface
    public interface ScrollListener {
        void onScroll(double xoffset, double yoffset);
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

    private Consumer<List<String>> fileDropListener;
    private MouseButtonListener mouseButtonListener;
    private CursorPosListener cursorPosListener;
    private ScrollListener scrollListener;

    public GlfwWindow(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.windowedWidth = width;
        this.windowedHeight = height;
    }

    public void setOnFileDropListener(Consumer<List<String>> listener) {
        this.fileDropListener = listener;
    }

    public void setOnMouseButtonListener(MouseButtonListener listener) {
        this.mouseButtonListener = listener;
    }

    public void setOnCursorPosListener(CursorPosListener listener) {
        this.cursorPosListener = listener;
    }

    public void setOnScrollListener(ScrollListener listener) {
        this.scrollListener = listener;
    }

    public boolean isKeyDown(int keyCode) {
        if (windowHandle == NULL) return false;
        return glfwGetKey(windowHandle, keyCode) == GLFW_PRESS;
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

        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(window, true);
                } else if (key == GLFW_KEY_F11 || (key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0)) {
                    toggleFullscreen();
                }
            }
        });

        glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            if (mouseButtonListener != null) {
                double[] x = new double[1];
                double[] y = new double[1];
                glfwGetCursorPos(window, x, y);
                mouseButtonListener.onMouseButton(button, action, x[0], y[0]);
            }
        });

        glfwSetCursorPosCallback(windowHandle, (window, xpos, ypos) -> {
            if (cursorPosListener != null) {
                boolean isShift = (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS);
                boolean isAlt = (glfwGetKey(window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS) || (glfwGetKey(window, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS);
                cursorPosListener.onCursorPos(xpos, ypos, isShift, isAlt);
            }
        });

        glfwSetScrollCallback(windowHandle, (window, xoffset, yoffset) -> {
            if (scrollListener != null) {
                scrollListener.onScroll(xoffset, yoffset);
            }
        });

        glfwSetFramebufferSizeCallback(windowHandle, (window, w, h) -> {
            this.width = w;
            this.height = h;
        });

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

        System.out.println("[GLFW] Native window initialized successfully (" + width + "x" + height + ").");
        return true;
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(windowHandle);
    }

    public long getNativeHandle() {
        return windowHandle;
    }

    public long getWindowHandle() {
        return windowHandle;
    }

    public WindowInfo getWindowInfo() {
        return new WindowInfo(windowHandle, width, height, true);
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

    public void destroy() {
        if (windowHandle != NULL) {
            glfwDestroyWindow(windowHandle);
            windowHandle = NULL;
        }
        glfwTerminate();

        GLFWErrorCallback old = glfwSetErrorCallback(null);
        if (old != null) {
            old.free();
        }
        System.out.println("[GLFW] Window resources released.");
    }
}
