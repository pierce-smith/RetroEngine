package com.retro.engine.core;

import com.retro.engine.rhi.ViewportSpec;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * DisplayService owns the GLFW window lifecycle and display metrics: it creates the {@link WindowHost},
 * routes window/framebuffer resize events to registered listeners, and answers monitor scale and size queries.
 * Input listeners stay on the WindowHost; this service only concerns itself with the display surface.
 */
public class DisplayService {

    /** Snapshot of the window and the monitor it sits on. Scale is backing pixels per screen coordinate. */
    public record DisplayMetrics(int windowWidth, int windowHeight,
                                 int framebufferWidth, int framebufferHeight,
                                 float contentScaleX, float contentScaleY,
                                 int monitorWidth, int monitorHeight, int refreshRate) {}

    private WindowHost window;
    private WindowHost.WindowSizeListener windowSizeListener;
    private WindowHost.FramebufferSizeListener framebufferSizeListener;

    /** Initializes GLFW and opens the window. Returns false (and leaves no window) if either step fails. */
    public boolean create(int width, int height, String title) {
        WindowHost host = new WindowHost(width, height, title);
        if (!host.create()) {
            host.destroy();
            return false;
        }
        window = host;
        window.setOnWindowSizeListener((w, h) -> {
            if (windowSizeListener != null) windowSizeListener.onResize(w, h);
        });
        // Swapchain-backed RHIs rebuild their presentation images at the new pixel size before the next frame
        window.setOnFramebufferSizeListener((w, h) -> {
            if (framebufferSizeListener != null) framebufferSizeListener.onFramebufferResize(w, h);
        });
        return true;
    }

    public void setOnWindowSizeListener(WindowHost.WindowSizeListener listener) {
        this.windowSizeListener = listener;
    }

    public void setOnFramebufferSizeListener(WindowHost.FramebufferSizeListener listener) {
        this.framebufferSizeListener = listener;
    }

    public WindowHost getWindow() { return window; }
    public boolean isOpen() { return window != null && window.getWindowHandle() != NULL; }

    public long getWindowHandle() { return window != null ? window.getWindowHandle() : NULL; }
    public int getWindowWidth() { return window != null ? window.getWidth() : 0; }
    public int getWindowHeight() { return window != null ? window.getHeight() : 0; }

    public ViewportSpec getViewportSpec() {
        return window != null ? window.getWindowInfo() : null;
    }

    /** Backing pixel size; differs from the window size on Retina/HiDPI displays. */
    public int[] getFramebufferSize() {
        if (!isOpen()) return new int[] {0, 0};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(window.getWindowHandle(), w, h);
            return new int[] {w.get(0), h.get(0)};
        }
    }

    /** Content scale of the window (follows it across monitors); {1, 1} without a window. */
    public float[] getContentScale() {
        if (!isOpen()) return new float[] {1f, 1f};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer x = stack.mallocFloat(1);
            FloatBuffer y = stack.mallocFloat(1);
            glfwGetWindowContentScale(window.getWindowHandle(), x, y);
            return new float[] {x.get(0), y.get(0)};
        }
    }

    /** Content scale of the primary monitor; {1, 1} if GLFW reports no monitor. */
    public float[] getMonitorScale() {
        long monitor = glfwGetPrimaryMonitor();
        if (monitor == NULL) return new float[] {1f, 1f};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer x = stack.mallocFloat(1);
            FloatBuffer y = stack.mallocFloat(1);
            glfwGetMonitorContentScale(monitor, x, y);
            return new float[] {x.get(0), y.get(0)};
        }
    }

    public DisplayMetrics getMetrics() {
        int[] fb = getFramebufferSize();
        float[] scale = getContentScale();
        long monitor = isOpen() ? glfwGetPrimaryMonitor() : NULL;
        GLFWVidMode mode = monitor != NULL ? glfwGetVideoMode(monitor) : null;
        return new DisplayMetrics(getWindowWidth(), getWindowHeight(), fb[0], fb[1], scale[0], scale[1],
                mode != null ? mode.width() : 0, mode != null ? mode.height() : 0, mode != null ? mode.refreshRate() : 0);
    }

    public void unbindListeners() {
        windowSizeListener = null;
        framebufferSizeListener = null;
        if (window != null) window.unbindAllListeners();
    }

    /** Releases the window and terminates GLFW. Safe to call more than once. */
    public void destroy() {
        windowSizeListener = null;
        framebufferSizeListener = null;
        if (window != null) {
            window.destroy();
            window = null;
        }
    }
}
