package com.retro.engine.core;

import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GamepadManager polls GLFW_JOYSTICK_1..GLFW_JOYSTICK_16 through the SDL-style gamepad mapping database
 * (glfwGetGamepadState), tracks the first connected gamepad as the active controller, and exposes
 * held / just-pressed buttons and deadzoned axes. Hot-plugging is handled implicitly by per-frame polling.
 * Triggers are additionally exposed as virtual buttons ({@link #BUTTON_LEFT_TRIGGER}, {@link #BUTTON_RIGHT_TRIGGER}).
 */
public class GamepadManager {

    // Face buttons (Xbox layout; PlayStation equivalents Cross / Circle / Square / Triangle)
    public static final int BUTTON_A = GLFW_GAMEPAD_BUTTON_A;
    public static final int BUTTON_B = GLFW_GAMEPAD_BUTTON_B;
    public static final int BUTTON_X = GLFW_GAMEPAD_BUTTON_X;
    public static final int BUTTON_Y = GLFW_GAMEPAD_BUTTON_Y;
    // Bumpers (L1 / R1)
    public static final int BUTTON_LEFT_BUMPER = GLFW_GAMEPAD_BUTTON_LEFT_BUMPER;
    public static final int BUTTON_RIGHT_BUMPER = GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER;
    public static final int BUTTON_BACK = GLFW_GAMEPAD_BUTTON_BACK;
    public static final int BUTTON_START = GLFW_GAMEPAD_BUTTON_START;
    public static final int BUTTON_GUIDE = GLFW_GAMEPAD_BUTTON_GUIDE;
    // Stick clicks (L3 / R3)
    public static final int BUTTON_LEFT_STICK = GLFW_GAMEPAD_BUTTON_LEFT_THUMB;
    public static final int BUTTON_RIGHT_STICK = GLFW_GAMEPAD_BUTTON_RIGHT_THUMB;
    // D-Pad
    public static final int BUTTON_DPAD_UP = GLFW_GAMEPAD_BUTTON_DPAD_UP;
    public static final int BUTTON_DPAD_RIGHT = GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
    public static final int BUTTON_DPAD_DOWN = GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
    public static final int BUTTON_DPAD_LEFT = GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
    // Virtual trigger buttons (L2 / R2): pressed once the analog trigger passes TRIGGER_PRESS_THRESHOLD
    public static final int BUTTON_LEFT_TRIGGER = GLFW_GAMEPAD_BUTTON_LAST + 1;
    public static final int BUTTON_RIGHT_TRIGGER = GLFW_GAMEPAD_BUTTON_LAST + 2;
    private static final int BUTTON_COUNT = GLFW_GAMEPAD_BUTTON_LAST + 3;

    // Sticks report -1..1 (Y is +1 when pushed DOWN); triggers report -1 (released) .. 1 (fully pressed)
    public static final int AXIS_LEFT_X = GLFW_GAMEPAD_AXIS_LEFT_X;
    public static final int AXIS_LEFT_Y = GLFW_GAMEPAD_AXIS_LEFT_Y;
    public static final int AXIS_RIGHT_X = GLFW_GAMEPAD_AXIS_RIGHT_X;
    public static final int AXIS_RIGHT_Y = GLFW_GAMEPAD_AXIS_RIGHT_Y;
    public static final int AXIS_LEFT_TRIGGER = GLFW_GAMEPAD_AXIS_LEFT_TRIGGER;
    public static final int AXIS_RIGHT_TRIGGER = GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER;
    private static final int AXIS_COUNT = GLFW_GAMEPAD_AXIS_LAST + 1;

    public static final float STICK_DEADZONE = 0.15f;
    public static final float TRIGGER_DEADZONE = 0.05f;
    private static final float TRIGGER_PRESS_THRESHOLD = 0.5f;

    private GLFWGamepadState state = GLFWGamepadState.calloc();
    private final boolean[] buttons = new boolean[BUTTON_COUNT];
    private final boolean[] prevButtons = new boolean[BUTTON_COUNT];
    private final float[] axes = new float[AXIS_COUNT];
    private int activeJoystick = -1;
    private String activeName = null;
    // GUIDs of joysticks already resolved or reported as unmapped, so each device is handled once
    private final java.util.Set<String> checkedUnmapped = new java.util.HashSet<>();
    // This platform's lines from the bundled + user databases, searched for firmware-variant fallbacks
    private final java.util.List<String> platformMappings = new java.util.ArrayList<>();

    private static final String BUNDLED_DB = "/input/gamecontrollerdb.txt";
    private static final String USER_DB = "gamecontrollerdb.txt";

    public GamepadManager() {
        clearInputs();
        loadBundledMappings();
        loadUserMappings();
    }

    /** Bundled SDL_GameControllerDB (zlib license), newer than the copy compiled into GLFW. */
    private void loadBundledMappings() {
        try (java.io.InputStream in = GamepadManager.class.getResourceAsStream(BUNDLED_DB)) {
            if (in == null) return;
            applyMappings(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "bundled " + BUNDLED_DB);
        } catch (java.io.IOException e) {
            System.err.println("[Gamepad] Could not read bundled controller mappings: " + e.getMessage());
        }
    }

    /** An SDL-format gamecontrollerdb.txt in the working directory adds or overrides mappings. */
    private void loadUserMappings() {
        java.io.File db = new java.io.File(System.getProperty("user.dir"), USER_DB);
        if (!db.isFile()) return;
        try {
            applyMappings(java.nio.file.Files.readString(db.toPath()), db.toString());
        } catch (java.io.IOException e) {
            System.err.println("[Gamepad] Could not read " + db + ": " + e.getMessage());
        }
    }

    private void applyMappings(String database, String source) {
        // GLFW skips other platforms' lines anyway; filtering first keeps the fallback search list small
        String platformTag = "platform:" + glfwPlatformName() + ",";
        StringBuilder filtered = new StringBuilder();
        for (String line : database.split("\\R")) {
            if (line.startsWith("#") || !line.contains(platformTag)) continue;
            platformMappings.add(line);
            filtered.append(line).append('\n');
        }
        if (filtered.isEmpty()) return;
        if (updateMappings(filtered.toString())) {
            System.out.println("[Gamepad] Loaded controller mappings from " + source);
        } else {
            System.err.println("[Gamepad] Failed to parse controller mappings in " + source);
        }
    }

    private static boolean updateMappings(String mappings) {
        // Off-heap: the database is far larger than the MemoryStack
        java.nio.ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memUTF8(mappings);
        try {
            return glfwUpdateGamepadMappings(buffer);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }
    }

    private static String glfwPlatformName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "Mac OS X";
        if (os.contains("win")) return "Windows";
        return "Linux";
    }

    /** Polls all joystick slots once; call once per frame after glfwPollEvents. */
    public void update() {
        System.arraycopy(buttons, 0, prevButtons, 0, BUTTON_COUNT);
        if (state == null) return;

        // Prefer the controller already in use so a second pad being plugged in does not steal control
        int found = -1;
        if (activeJoystick >= 0 && glfwGetGamepadState(activeJoystick, state)) {
            found = activeJoystick;
        } else {
            for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_16; jid++) {
                if (glfwGetGamepadState(jid, state)) {
                    found = jid;
                    break;
                }
            }
        }

        if (found < 0) {
            if (activeJoystick >= 0) {
                System.out.println("[Gamepad] Disconnected: " + activeName);
            }
            activeJoystick = -1;
            activeName = null;
            clearInputs();
            resolveUnmappedJoysticks();
            return;
        }

        if (found != activeJoystick) {
            activeJoystick = found;
            activeName = glfwGetGamepadName(found);
            System.out.println("[Gamepad] Active controller: " + activeName + " (joystick " + (found + 1) + ")");
        }

        for (int i = 0; i <= GLFW_GAMEPAD_BUTTON_LAST; i++) {
            buttons[i] = state.buttons(i) == GLFW_PRESS;
        }
        for (int i = 0; i < AXIS_COUNT; i++) {
            axes[i] = state.axes(i);
        }
        buttons[BUTTON_LEFT_TRIGGER] = getTrigger(AXIS_LEFT_TRIGGER) > TRIGGER_PRESS_THRESHOLD;
        buttons[BUTTON_RIGHT_TRIGGER] = getTrigger(AXIS_RIGHT_TRIGGER) > TRIGGER_PRESS_THRESHOLD;
    }

    /**
     * GLFW matches mappings by exact GUID, which embeds the firmware version, so a controller update (e.g. Xbox
     * Wireless Controller firmware 5.23) drops a known pad. Reuse the mapping of the same vendor/product at a
     * different version, as SDL does.
     */
    private void resolveUnmappedJoysticks() {
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_16; jid++) {
            if (!glfwJoystickPresent(jid) || glfwJoystickIsGamepad(jid)) continue;
            String guid = glfwGetJoystickGUID(jid);
            if (guid == null || !checkedUnmapped.add(guid)) continue;

            String name = glfwGetJoystickName(jid);
            String variant = findVersionVariant(guid);
            if (variant != null && updateMappings(guid + variant.substring(variant.indexOf(','))) && glfwJoystickIsGamepad(jid)) {
                System.out.println("[Gamepad] '" + name + "' (GUID " + guid + ") mapped using firmware variant " + variant.substring(0, variant.indexOf(',')));
            } else {
                System.err.println("[Gamepad] Joystick " + (jid + 1) + " '" + name + "' (GUID " + guid
                        + ") has no gamepad mapping and is ignored. Add its line to " + USER_DB + " in the working directory.");
            }
        }
    }

    // GUID hex layout: [0,24) bus + vendor + product, [24,28) version, [28,32) padding
    private String findVersionVariant(String guid) {
        if (guid.length() != 32) return null;
        String hardware = guid.substring(0, 24);
        String tail = guid.substring(28);
        String match = null;
        for (String line : platformMappings) {
            if (line.length() > 32 && line.charAt(32) == ',' && line.startsWith(hardware) && line.startsWith(tail, 28)) {
                match = line; // Keep the last entry: the database lists newer firmware further down
            }
        }
        return match;
    }

    public boolean isConnected() { return activeJoystick >= 0; }
    public String getActiveName() { return activeName; }

    /** True while the button is held. Accepts BUTTON_* constants, including the virtual trigger buttons. */
    public boolean isButtonPressed(int button) {
        return button >= 0 && button < BUTTON_COUNT && buttons[button];
    }

    /** True only on the frame the button went down. */
    public boolean isButtonJustPressed(int button) {
        return button >= 0 && button < BUTTON_COUNT && buttons[button] && !prevButtons[button];
    }

    /**
     * Stick axes (AXIS_LEFT_X..AXIS_RIGHT_Y) have a {@value #STICK_DEADZONE} axial deadzone, rescaled so output
     * ramps smoothly from 0 at the deadzone edge to +/-1. Trigger axes are returned raw (-1 released .. 1 pressed).
     */
    public float getAxis(int axis) {
        if (axis < 0 || axis >= AXIS_COUNT) return 0.0f;
        float v = axes[axis];
        if (axis == AXIS_LEFT_TRIGGER || axis == AXIS_RIGHT_TRIGGER) return v;
        float mag = Math.abs(v);
        if (mag < STICK_DEADZONE) return 0.0f;
        return Math.copySign(Math.min(1.0f, (mag - STICK_DEADZONE) / (1.0f - STICK_DEADZONE)), v);
    }

    /** Trigger pull normalized to 0 (released) .. 1 (fully pressed), with a small rest deadzone. */
    public float getTrigger(int axis) {
        if (axis != AXIS_LEFT_TRIGGER && axis != AXIS_RIGHT_TRIGGER) return 0.0f;
        float pull = (axes[axis] + 1.0f) * 0.5f;
        if (pull < TRIGGER_DEADZONE) return 0.0f;
        return Math.min(1.0f, (pull - TRIGGER_DEADZONE) / (1.0f - TRIGGER_DEADZONE));
    }

    public void destroy() {
        if (state != null) {
            state.free();
            state = null;
        }
        activeJoystick = -1;
        activeName = null;
        clearInputs();
        java.util.Arrays.fill(prevButtons, false);
    }

    // Released triggers rest at -1, not 0
    private void clearInputs() {
        java.util.Arrays.fill(buttons, false);
        java.util.Arrays.fill(axes, 0.0f);
        axes[AXIS_LEFT_TRIGGER] = -1.0f;
        axes[AXIS_RIGHT_TRIGGER] = -1.0f;
    }
}
