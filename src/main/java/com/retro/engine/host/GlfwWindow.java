package com.retro.engine.host;

import com.retro.engine.rhi.WindowInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class GlfwWindow {

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

    private GLFWKeyCallback keyCallback;
    private GLFWFramebufferSizeCallback framebufferSizeCallback;
    private GLFWDropCallback dropCallback;

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

        // Key Callback: Option+Enter / F11 for Fullscreen, ESC to close
        keyCallback = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(win, true);
                } else if (key == GLFW_KEY_F11 || (key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0)) {
                    toggleFullscreen();
                }
            }
        });
        glfwSetKeyCallback(windowHandle, keyCallback);

        // Framebuffer size callback
        framebufferSizeCallback = GLFWFramebufferSizeCallback.create((win, w, h) -> {
            this.width = w;
            this.height = h;
        });
        glfwSetFramebufferSizeCallback(windowHandle, framebufferSizeCallback);

        // Native Mouse Drag-and-Drop Callback
        dropCallback = GLFWDropCallback.create((win, count, names) -> {
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
        glfwSetDropCallback(windowHandle, dropCallback);

        System.out.println("[GLFW] Native window initialized successfully (" + width + "x" + height + ").");
        return true;
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(windowHandle);
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

    public WindowInfo getWindowInfo() {
        return new WindowInfo(windowHandle, width, height, true);
    }

    public void destroy() {
        if (keyCallback != null) {
            keyCallback.free();
            keyCallback = null;
        }
        if (framebufferSizeCallback != null) {
            framebufferSizeCallback.free();
            framebufferSizeCallback = null;
        }
        if (dropCallback != null) {
            dropCallback.free();
            dropCallback = null;
        }

        if (windowHandle != NULL) {
            glfwDestroyWindow(windowHandle);
            windowHandle = NULL;
        }
        glfwTerminate();

        GLFWErrorCallback errCb = glfwSetErrorCallback(null);
        if (errCb != null) {
            errCb.free();
        }
        System.out.println("[GLFW] Window resources released.");
    }
}