package com.retro.engine.scripting;

import com.retro.engine.core.WindowHost;
import java.util.HashSet;
import java.util.Set;

/**
 * Static Input facade providing state queries for keyboard keys,
 * mouse buttons, and cursor coordinates during gameplay simulation.
 */
public class Input {

    private static WindowHost boundWindow = null;
    private static final Set<Integer> activeKeys = new HashSet<>();
    private static final Set<Integer> pressedKeys = new HashSet<>();
    private static final Set<Integer> activeMouseButtons = new HashSet<>();
    private static final Set<Integer> pressedMouseButtons = new HashSet<>();

    private static float mouseX = 0.0f;
    private static float mouseY = 0.0f;
    private static float scrollY = 0.0f;

    public static void bindWindow(WindowHost window) {
        boundWindow = window;
    }

    public static void unbindWindow() {
        boundWindow = null;
    }

    public static boolean isKeyDown(int keyCode) {
        if (boundWindow != null) {
            try {
                if (boundWindow.isKeyDown(keyCode)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return activeKeys.contains(keyCode);
    }

    public static boolean isKeyPressed(int keyCode) {
        return pressedKeys.contains(keyCode);
    }

    public static boolean isMouseButtonDown(int button) {
        return activeMouseButtons.contains(button);
    }

    public static boolean isMouseButtonPressed(int button) {
        return pressedMouseButtons.contains(button);
    }

    public static float getMouseX() {
        return mouseX;
    }

    public static float getMouseY() {
        return mouseY;
    }

    public static float getScrollY() {
        return scrollY;
    }

    public static void setKeyState(int keyCode, boolean down) {
        if (down) {
            if (!activeKeys.contains(keyCode)) {
                pressedKeys.add(keyCode);
            }
            activeKeys.add(keyCode);
        } else {
            activeKeys.remove(keyCode);
        }
    }

    public static void setMouseButtonState(int button, boolean down) {
        if (down) {
            if (!activeMouseButtons.contains(button)) {
                pressedMouseButtons.add(button);
            }
            activeMouseButtons.add(button);
        } else {
            activeMouseButtons.remove(button);
        }
    }

    public static void setMousePosition(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    public static void setScroll(float yOffset) {
        scrollY = yOffset;
    }

    public static void endFrame() {
        pressedKeys.clear();
        pressedMouseButtons.clear();
        scrollY = 0.0f;
    }

    public static void clearState() {
        boundWindow = null;
        activeKeys.clear();
        pressedKeys.clear();
        activeMouseButtons.clear();
        pressedMouseButtons.clear();
        mouseX = 0.0f;
        mouseY = 0.0f;
        scrollY = 0.0f;
    }
}
