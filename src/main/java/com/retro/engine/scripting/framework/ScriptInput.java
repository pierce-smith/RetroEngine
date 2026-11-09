package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;
import com.retro.engine.core.GamepadManager;
import com.retro.engine.scripting.Input;

import java.util.HashMap;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Action-mapped input: gameplay code asks for named actions ("Jump", "Interact") and axes ("Horizontal") instead of
 * raw key codes, and each name may be bound to several keys, mouse buttons and gamepad buttons / sticks at once.
 *
 * <p>Default bindings (override or extend them with the bind methods, e.g. from a game manager's onAwake):</p>
 * <ul>
 *   <li>Actions: Jump (Space, pad A), Interact (E, pad X), Fire (left mouse, pad right trigger),
 *       AltFire (right mouse, pad left trigger), Sprint (Left Shift, pad left stick click), Crouch (Left Ctrl, pad B),
 *       Reload (R, pad Y), Pause (Escape, pad Start)</li>
 *   <li>Axes: Horizontal (A/D, Left/Right, pad left stick X), Vertical (S/W, Down/Up, pad left stick Y, up = +1),
 *       LookX / LookY (pad right stick)</li>
 * </ul>
 *
 * <p>Queries are allocation-free (String keys hash once and cache their hash). Unknown names return false / 0 and log a
 * warning once per name. Pressed = went down this frame; Down = held.</p>
 */
@PublicApi
public final class ScriptInput {

    private static final int MAX_BINDINGS = 6;

    private static final class Action {
        final int[] keys = new int[MAX_BINDINGS];
        final int[] mouseButtons = new int[MAX_BINDINGS];
        final int[] padButtons = new int[MAX_BINDINGS];
        int keyCount, mouseCount, padCount;
    }

    private static final class Axis {
        final int[] positiveKeys = new int[MAX_BINDINGS];
        final int[] negativeKeys = new int[MAX_BINDINGS];
        int positiveCount, negativeCount;
        int padAxis = -1;
        boolean invertPad = false;
    }

    private static final HashMap<String, Action> actions = new HashMap<String, Action>();
    private static final HashMap<String, Axis> axes = new HashMap<String, Axis>();
    private static final HashMap<String, Boolean> warnedNames = new HashMap<String, Boolean>();

    private static float mouseSensitivity = 1.0f;

    static {
        resetToDefaults();
    }

    private ScriptInput() {}

    // =========================================================================
    // Queries
    // =========================================================================

    /** True on the frame any binding of {@code action} went down. */
    public static boolean isActionPressed(String action) {
        Action a = action(action);
        if (a == null) return false;
        for (int i = 0; i < a.keyCount; i++) if (Input.isKeyPressed(a.keys[i])) return true;
        for (int i = 0; i < a.mouseCount; i++) if (Input.isMouseButtonPressed(a.mouseButtons[i])) return true;
        GamepadManager pad = connectedPad();
        if (pad != null) {
            for (int i = 0; i < a.padCount; i++) if (pad.isButtonJustPressed(a.padButtons[i])) return true;
        }
        return false;
    }

    /** True while any binding of {@code action} is held. */
    public static boolean isActionDown(String action) {
        Action a = action(action);
        if (a == null) return false;
        for (int i = 0; i < a.keyCount; i++) if (Input.isKeyDown(a.keys[i])) return true;
        for (int i = 0; i < a.mouseCount; i++) if (Input.isMouseButtonDown(a.mouseButtons[i])) return true;
        GamepadManager pad = connectedPad();
        if (pad != null) {
            for (int i = 0; i < a.padCount; i++) if (pad.isButtonPressed(a.padButtons[i])) return true;
        }
        return false;
    }

    /**
     * Axis value in -1..1: keys contribute -1 / 0 / +1, the gamepad stick its dead-zoned value; the input with the
     * larger magnitude wins, so keyboard and pad never add up past full speed.
     */
    public static float getAxis(String axis) {
        Axis a = axis(axis);
        if (a == null) return 0.0f;
        float keys = 0.0f;
        for (int i = 0; i < a.positiveCount; i++) if (Input.isKeyDown(a.positiveKeys[i])) { keys += 1.0f; break; }
        for (int i = 0; i < a.negativeCount; i++) if (Input.isKeyDown(a.negativeKeys[i])) { keys -= 1.0f; break; }
        float stick = 0.0f;
        if (a.padAxis >= 0) {
            GamepadManager pad = connectedPad();
            if (pad != null) {
                stick = pad.getAxis(a.padAxis);
                if (a.invertPad) stick = -stick;
            }
        }
        float v = Math.abs(stick) > Math.abs(keys) ? stick : keys;
        return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
    }

    /** -1, 0 or +1: {@link #getAxis(String)} snapped, for menus and grid movement. */
    public static int getAxisRaw(String axis) {
        float v = getAxis(axis);
        return v > 0.5f ? 1 : (v < -0.5f ? -1 : 0);
    }

    /** Writes this frame's mouse motion (pixels x sensitivity, +X right, +Y down) into {@code out[0..1]}; 0 while unlocked. */
    public static float[] getMouseDelta(float[] out) {
        out[0] = getMouseDeltaX();
        out[1] = getMouseDeltaY();
        return out;
    }

    /** Writes this frame's mouse motion into {@code out.x / out.y} (z = 0). */
    public static Vector3 getMouseDelta(Vector3 out) {
        return out.set(getMouseDeltaX(), getMouseDeltaY(), 0.0f);
    }

    public static float getMouseDeltaX() {
        return Input.getMouseDeltaX() * mouseSensitivity;
    }

    public static float getMouseDeltaY() {
        return Input.getMouseDeltaY() * mouseSensitivity;
    }

    public static float getScroll() {
        return Input.getScrollY();
    }

    public static void setMouseSensitivity(float sensitivity) {
        mouseSensitivity = sensitivity;
    }

    public static float getMouseSensitivity() {
        return mouseSensitivity;
    }

    public static boolean hasAction(String action) {
        return action != null && actions.containsKey(action);
    }

    public static boolean hasAxis(String axis) {
        return axis != null && axes.containsKey(axis);
    }

    // =========================================================================
    // Binding (setup time; these may allocate)
    // =========================================================================

    /** Adds a keyboard key (GLFW_KEY_*) to {@code action}, creating the action if needed. */
    public static void bindKey(String action, int glfwKey) {
        Action a = getOrCreateAction(action);
        if (a != null && a.keyCount < MAX_BINDINGS && !contains(a.keys, a.keyCount, glfwKey)) a.keys[a.keyCount++] = glfwKey;
    }

    /** Adds a mouse button (GLFW_MOUSE_BUTTON_*) to {@code action}. */
    public static void bindMouseButton(String action, int glfwMouseButton) {
        Action a = getOrCreateAction(action);
        if (a != null && a.mouseCount < MAX_BINDINGS && !contains(a.mouseButtons, a.mouseCount, glfwMouseButton)) {
            a.mouseButtons[a.mouseCount++] = glfwMouseButton;
        }
    }

    /** Adds a gamepad button (GamepadManager.BUTTON_*, including the trigger pseudo-buttons) to {@code action}. */
    public static void bindGamepadButton(String action, int padButton) {
        Action a = getOrCreateAction(action);
        if (a != null && a.padCount < MAX_BINDINGS && !contains(a.padButtons, a.padCount, padButton)) {
            a.padButtons[a.padCount++] = padButton;
        }
    }

    /** Adds a positive and a negative key to {@code axis}, creating the axis if needed. */
    public static void bindAxisKeys(String axis, int positiveKey, int negativeKey) {
        Axis a = getOrCreateAxis(axis);
        if (a == null) return;
        if (a.positiveCount < MAX_BINDINGS && !contains(a.positiveKeys, a.positiveCount, positiveKey)) a.positiveKeys[a.positiveCount++] = positiveKey;
        if (a.negativeCount < MAX_BINDINGS && !contains(a.negativeKeys, a.negativeCount, negativeKey)) a.negativeKeys[a.negativeCount++] = negativeKey;
    }

    /** Sets the gamepad stick axis (GamepadManager.AXIS_*) of {@code axis}; {@code invert} flips its sign. */
    public static void bindAxisGamepad(String axis, int padAxis, boolean invert) {
        Axis a = getOrCreateAxis(axis);
        if (a == null) return;
        a.padAxis = padAxis;
        a.invertPad = invert;
    }

    /** Removes every binding of an action or axis with this name. */
    public static void unbind(String name) {
        if (name == null) return;
        actions.remove(name);
        axes.remove(name);
    }

    /** Restores the default action / axis map listed in the class comment. */
    public static void resetToDefaults() {
        actions.clear();
        axes.clear();
        warnedNames.clear();
        mouseSensitivity = 1.0f;

        bindKey("Jump", GLFW_KEY_SPACE);
        bindGamepadButton("Jump", GamepadManager.BUTTON_A);
        bindKey("Interact", GLFW_KEY_E);
        bindGamepadButton("Interact", GamepadManager.BUTTON_X);
        bindMouseButton("Fire", GLFW_MOUSE_BUTTON_LEFT);
        bindGamepadButton("Fire", GamepadManager.BUTTON_RIGHT_TRIGGER);
        bindMouseButton("AltFire", GLFW_MOUSE_BUTTON_RIGHT);
        bindGamepadButton("AltFire", GamepadManager.BUTTON_LEFT_TRIGGER);
        bindKey("Sprint", GLFW_KEY_LEFT_SHIFT);
        bindGamepadButton("Sprint", GamepadManager.BUTTON_LEFT_STICK);
        bindKey("Crouch", GLFW_KEY_LEFT_CONTROL);
        bindGamepadButton("Crouch", GamepadManager.BUTTON_B);
        bindKey("Reload", GLFW_KEY_R);
        bindGamepadButton("Reload", GamepadManager.BUTTON_Y);
        bindKey("Pause", GLFW_KEY_ESCAPE);
        bindGamepadButton("Pause", GamepadManager.BUTTON_START);

        bindAxisKeys("Horizontal", GLFW_KEY_D, GLFW_KEY_A);
        bindAxisKeys("Horizontal", GLFW_KEY_RIGHT, GLFW_KEY_LEFT);
        bindAxisGamepad("Horizontal", GamepadManager.AXIS_LEFT_X, false);
        bindAxisKeys("Vertical", GLFW_KEY_W, GLFW_KEY_S);
        bindAxisKeys("Vertical", GLFW_KEY_UP, GLFW_KEY_DOWN);
        bindAxisGamepad("Vertical", GamepadManager.AXIS_LEFT_Y, true); // GLFW sticks report up as -1
        bindAxisGamepad("LookX", GamepadManager.AXIS_RIGHT_X, false);
        bindAxisGamepad("LookY", GamepadManager.AXIS_RIGHT_Y, false);
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private static GamepadManager connectedPad() {
        GamepadManager pad = Input.getGamepad();
        return (pad != null && pad.isConnected()) ? pad : null;
    }

    private static Action action(String name) {
        if (name == null) return null;
        Action a = actions.get(name);
        if (a == null) warnUnknown(name, "action");
        return a;
    }

    private static Axis axis(String name) {
        if (name == null) return null;
        Axis a = axes.get(name);
        if (a == null) warnUnknown(name, "axis");
        return a;
    }

    private static void warnUnknown(String name, String what) {
        if (warnedNames.containsKey(name)) return;
        warnedNames.put(name, Boolean.TRUE);
        System.err.println("[ScriptInput] Unknown input " + what + " '" + name + "'; bind it first (e.g. ScriptInput.bindKey).");
    }

    private static Action getOrCreateAction(String name) {
        if (name == null || name.isEmpty()) return null;
        Action a = actions.get(name);
        if (a == null) {
            a = new Action();
            actions.put(name, a);
            warnedNames.remove(name);
        }
        return a;
    }

    private static Axis getOrCreateAxis(String name) {
        if (name == null || name.isEmpty()) return null;
        Axis a = axes.get(name);
        if (a == null) {
            a = new Axis();
            axes.put(name, a);
            warnedNames.remove(name);
        }
        return a;
    }

    private static boolean contains(int[] values, int count, int v) {
        for (int i = 0; i < count; i++) if (values[i] == v) return true;
        return false;
    }
}
