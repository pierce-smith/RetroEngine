package com.retro.engine.scripting;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.core.WindowHost;
import java.util.HashSet;
import java.util.Set;

/**
 * Static Input facade providing state queries for keyboard keys,
 * mouse buttons, and cursor coordinates during gameplay simulation, plus gamepad access and rumble.
 */
public class Input {

    private static WindowHost boundWindow = null;
    private static final Set<Integer> activeKeys = new HashSet<>();
    private static final Set<Integer> pressedKeys = new HashSet<>();
    private static final Set<Integer> activeMouseButtons = new HashSet<>();
    private static final Set<Integer> pressedMouseButtons = new HashSet<>();

    // Whether gameplay wants the cursor locked; Escape may free it meanwhile, and a click in the game view re-locks it
    private static boolean cursorLockWanted = false;

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

    /** Active gamepad of the bound window, or null when headless / unbound. */
    public static GamepadManager getGamepad() {
        return boundWindow != null ? boundWindow.getGamepadManager() : null;
    }

    /**
     * Rumbles the active gamepad: {@code lowFreq} drives the heavy left motor, {@code highFreq} the light right motor
     * (both 0..1), for {@code duration} seconds. Replaces any rumble in progress; no-op without a controller.
     */
    public static void vibrate(float lowFreq, float highFreq, float duration) {
        GamepadManager pad = getGamepad();
        if (pad != null) pad.setVibration(lowFreq, highFreq, duration);
    }

    public static void stopVibration() {
        GamepadManager pad = getGamepad();
        if (pad != null) pad.stopVibration();
    }

    /** Locks (hides, captures) the cursor for mouse-look, or frees it, e.g. while a menu is open. */
    public static void setCursorLocked(boolean locked) {
        cursorLockWanted = locked;
        setCursorCaptured(locked);
    }

    /** True while the cursor is actually captured (false after Escape freed it, even if a lock is still wanted). */
    public static boolean isCursorLocked() {
        return boundWindow != null && boundWindow.isCursorCaptured();
    }

    public static boolean isCursorLockWanted() {
        return cursorLockWanted;
    }

    /** Captures or frees the cursor without changing whether gameplay wants it locked. */
    public static void setCursorCaptured(boolean captured) {
        if (boundWindow != null) boundWindow.setCursorCaptured(captured);
    }

    /** Mouse motion this frame in pixels while the cursor is locked (+X right, +Y down); 0 while it is free. */
    public static float getMouseDeltaX() {
        return boundWindow != null ? boundWindow.getMouseDeltaX() : 0.0f;
    }

    public static float getMouseDeltaY() {
        return boundWindow != null ? boundWindow.getMouseDeltaY() : 0.0f;
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
        stopVibration();
        setCursorLocked(false);
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
