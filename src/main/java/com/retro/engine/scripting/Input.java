package com.retro.engine.scripting;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.core.WindowHost;

import java.util.Arrays;

/**
 * Static Input facade providing state queries for keyboard keys,
 * mouse buttons, and cursor coordinates during gameplay simulation, plus gamepad access and rumble.
 */
public class Input {

    private static WindowHost boundWindow = null;
    // Indexed by GLFW key / button code (GLFW_KEY_LAST = 348, GLFW_MOUSE_BUTTON_LAST = 7). Plain arrays rather than
    // sets of Integer, whose boxing allocated on every query for codes above 127 (Shift, arrows, F-keys).
    private static final int KEY_SLOTS = 512;
    private static final int BUTTON_SLOTS = 16;
    private static final boolean[] activeKeys = new boolean[KEY_SLOTS];
    private static final boolean[] pressedKeys = new boolean[KEY_SLOTS];
    private static final boolean[] activeMouseButtons = new boolean[BUTTON_SLOTS];
    private static final boolean[] pressedMouseButtons = new boolean[BUTTON_SLOTS];

    // Set while an editor text field has focus, so typing into the Inspector during Play does not move the player
    private static volatile boolean keyboardSuppressed = false;

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

    /** Editor hook: while true, keyboard queries report every key as up. */
    public static void setKeyboardSuppressed(boolean suppressed) {
        keyboardSuppressed = suppressed;
    }

    private static boolean inRange(int code, int slots) {
        return code >= 0 && code < slots;
    }

    public static boolean isKeyDown(int keyCode) {
        if (keyboardSuppressed) return false;
        if (boundWindow != null) {
            try {
                if (boundWindow.isKeyDown(keyCode)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return inRange(keyCode, KEY_SLOTS) && activeKeys[keyCode];
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
        return !keyboardSuppressed && inRange(keyCode, KEY_SLOTS) && pressedKeys[keyCode];
    }

    public static boolean isMouseButtonDown(int button) {
        return inRange(button, BUTTON_SLOTS) && activeMouseButtons[button];
    }

    public static boolean isMouseButtonPressed(int button) {
        return inRange(button, BUTTON_SLOTS) && pressedMouseButtons[button];
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
        if (!inRange(keyCode, KEY_SLOTS)) return; // GLFW_KEY_UNKNOWN (-1) and out-of-range codes
        if (down && !activeKeys[keyCode]) pressedKeys[keyCode] = true;
        activeKeys[keyCode] = down;
    }

    public static void setMouseButtonState(int button, boolean down) {
        if (!inRange(button, BUTTON_SLOTS)) return;
        if (down && !activeMouseButtons[button]) pressedMouseButtons[button] = true;
        activeMouseButtons[button] = down;
    }

    public static void setMousePosition(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    public static void setScroll(float yOffset) {
        scrollY = yOffset;
    }

    public static void endFrame() {
        Arrays.fill(pressedKeys, false);
        Arrays.fill(pressedMouseButtons, false);
        scrollY = 0.0f;
    }

    public static void clearState() {
        stopVibration();
        setCursorLocked(false);
        boundWindow = null;
        Arrays.fill(activeKeys, false);
        Arrays.fill(pressedKeys, false);
        Arrays.fill(activeMouseButtons, false);
        Arrays.fill(pressedMouseButtons, false);
        keyboardSuppressed = false;
        mouseX = 0.0f;
        mouseY = 0.0f;
        scrollY = 0.0f;
    }
}
