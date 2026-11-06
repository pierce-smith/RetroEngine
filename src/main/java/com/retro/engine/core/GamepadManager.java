package com.retro.engine.core;

import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLGamepad.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLStdinc.SDL_free;

/**
 * GamepadManager polls GLFW_JOYSTICK_1..GLFW_JOYSTICK_16 through the SDL-style gamepad mapping database
 * (glfwGetGamepadState), tracks the first connected gamepad as the active controller, and exposes
 * held / just-pressed buttons and deadzoned axes. Hot-plugging is handled implicitly by per-frame polling.
 * Triggers are additionally exposed as virtual buttons ({@link #BUTTON_LEFT_TRIGGER}, {@link #BUTTON_RIGHT_TRIGGER}).
 * <p>
 * GLFW has no haptics API, so rumble goes through SDL3's gamepad subsystem, used only for output. SDL is started and
 * the active controller opened on the first vibration request, so games that never rumble never load it (and exports
 * may leave its natives out); a per-frame countdown stops each burst.
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

    // Rumble (SDL3): opened gamepad handle (0 = none), the GLFW joystick it was matched to, and the active burst
    private boolean sdlInitAttempted = false;
    private boolean sdlAvailable = false;
    private long sdlGamepad = 0L;
    private int sdlBoundJoystick = -1;
    private float vibrationRemaining = 0.0f;
    private long lastUpdateNanos = 0L;

    public GamepadManager() {
        clearInputs();
        loadBundledMappings();
        loadUserMappings();
    }

    /** Rumble is optional: SDL left out of the build or a failed init only disables vibration. Tried once. */
    private void initRumble() {
        sdlInitAttempted = true;
        try {
            sdlAvailable = SDL_InitSubSystem(SDL_INIT_GAMEPAD);
            if (!sdlAvailable) {
                Log.warn("Gamepad", "SDL gamepad init failed, vibration disabled: " + SDL_GetError());
            }
        } catch (LinkageError e) {
            Log.info("Gamepad", "SDL is not part of this build; vibration disabled.");
        } catch (Throwable t) {
            Log.warn("Gamepad", "SDL unavailable, vibration disabled: " + t);
        }
    }

    /** Bundled SDL_GameControllerDB (zlib license), newer than the copy compiled into GLFW. */
    private void loadBundledMappings() {
        try (java.io.InputStream in = GamepadManager.class.getResourceAsStream(BUNDLED_DB)) {
            if (in == null) return;
            applyMappings(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), "bundled " + BUNDLED_DB);
        } catch (java.io.IOException e) {
            Log.warn("Gamepad", "Could not read bundled controller mappings: " + e.getMessage());
        }
    }

    /** An SDL-format gamecontrollerdb.txt in the working directory adds or overrides mappings. */
    private void loadUserMappings() {
        java.io.File db = new java.io.File(System.getProperty("user.dir"), USER_DB);
        if (!db.isFile()) return;
        try {
            applyMappings(java.nio.file.Files.readString(db.toPath()), db.toString());
        } catch (java.io.IOException e) {
            Log.warn("Gamepad", "Could not read " + db + ": " + e.getMessage());
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
            Log.info("Gamepad", "Loaded controller mappings from " + source);
        } else {
            Log.warn("Gamepad", "Failed to parse controller mappings in " + source);
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
        updateVibration();

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
                Log.info("Gamepad", "Disconnected: " + activeName);
            }
            activeJoystick = -1;
            activeName = null;
            clearInputs();
            stopVibration();
            resolveUnmappedJoysticks();
            return;
        }

        if (found != activeJoystick) {
            stopVibration(); // A burst belongs to the controller that was active when it started
            activeJoystick = found;
            activeName = glfwGetGamepadName(found);
            Log.info("Gamepad", "Active controller: " + activeName + " (joystick " + (found + 1) + ")");
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
                Log.info("Gamepad", "'" + name + "' (GUID " + guid + ") mapped using firmware variant " + variant.substring(0, variant.indexOf(',')));
            } else {
                Log.warn("Gamepad", "Joystick " + (jid + 1) + " '" + name + "' (GUID " + guid
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

    /**
     * Drives the dual rumble motors of the active controller: {@code lowFrequency} is the heavy left motor,
     * {@code highFrequency} the light right motor, both 0..1. A new call replaces any burst in progress; the burst
     * stops by itself after {@code durationSeconds} (counted down in {@link #update()}), and a zero duration or zero
     * strength stops it immediately. Silently does nothing without a connected, rumble-capable controller.
     */
    public void setVibration(float lowFrequency, float highFrequency, float durationSeconds) {
        float low = clamp01(lowFrequency);
        float high = clamp01(highFrequency);
        if (durationSeconds <= 0.0f || (low == 0.0f && high == 0.0f)) {
            stopVibration();
            return;
        }
        long pad = bindRumbleGamepad();
        if (pad == 0L) return;
        // Uint16 motor strengths; the cast keeps the bit pattern for values above Short.MAX_VALUE
        int durationMs = (int) Math.ceil(durationSeconds * 1000.0f);
        if (SDL_RumbleGamepad(pad, (short) Math.round(low * 0xFFFF), (short) Math.round(high * 0xFFFF), durationMs)) {
            vibrationRemaining = durationSeconds;
        }
    }

    /** Stops both motors right away. */
    public void stopVibration() {
        if (vibrationRemaining <= 0.0f) return;
        vibrationRemaining = 0.0f;
        haltMotors();
    }

    private void haltMotors() {
        if (sdlGamepad != 0L) {
            SDL_RumbleGamepad(sdlGamepad, (short) 0, (short) 0, 0);
        }
    }

    public boolean isVibrating() { return vibrationRemaining > 0.0f; }

    /** Counts the active burst down by real frame time, so it ends even if gameplay is paused or the frame rate drops. */
    private void updateVibration() {
        long now = System.nanoTime();
        float dt = lastUpdateNanos != 0L ? (now - lastUpdateNanos) / 1_000_000_000.0f : 0.0f;
        lastUpdateNanos = now;
        if (vibrationRemaining <= 0.0f) return;
        vibrationRemaining -= dt;
        if (vibrationRemaining <= 0.0f) {
            vibrationRemaining = 0.0f;
            haltMotors();
        }
    }

    /**
     * SDL handle for the GLFW-active controller, opened on demand. The two libraries number devices differently, so
     * the pad is matched by the USB vendor / product ids embedded in GLFW's GUID, falling back to SDL's first gamepad.
     */
    private long bindRumbleGamepad() {
        if (activeJoystick < 0) return 0L;
        if (!sdlInitAttempted) initRumble();
        if (!sdlAvailable) return 0L;
        if (sdlGamepad != 0L && sdlBoundJoystick == activeJoystick && SDL_GamepadConnected(sdlGamepad)) return sdlGamepad;
        closeRumbleGamepad();

        SDL_UpdateGamepads(); // Refresh SDL's device list; nothing pumps SDL events in this engine
        IntBuffer ids = SDL_GetGamepads();
        if (ids == null) return 0L;
        int chosen = 0;
        try {
            String guid = glfwGetJoystickGUID(activeJoystick);
            int vendor = guidWord(guid, 8), product = guidWord(guid, 16);
            for (int i = 0; i < ids.remaining(); i++) {
                int id = ids.get(i);
                if (chosen == 0) chosen = id;
                if (vendor >= 0 && (SDL_GetGamepadVendorForID(id) & 0xFFFF) == vendor
                        && (SDL_GetGamepadProductForID(id) & 0xFFFF) == product) {
                    chosen = id;
                    break;
                }
            }
        } finally {
            SDL_free(ids);
        }
        if (chosen == 0) return 0L;

        sdlGamepad = SDL_OpenGamepad(chosen);
        if (sdlGamepad == 0L) {
            Log.warn("Gamepad", "Could not open '" + activeName + "' for vibration: " + SDL_GetError());
            return 0L;
        }
        sdlBoundJoystick = activeJoystick;
        return sdlGamepad;
    }

    private void closeRumbleGamepad() {
        if (sdlGamepad != 0L) {
            SDL_CloseGamepad(sdlGamepad);
            sdlGamepad = 0L;
        }
        sdlBoundJoystick = -1;
    }

    /** Little-endian 16-bit word at hex offset {@code at} of a 32-char GUID, or -1 when the GUID is malformed. */
    private static int guidWord(String guid, int at) {
        if (guid == null || guid.length() != 32) return -1;
        try {
            int lo = Integer.parseInt(guid.substring(at, at + 2), 16);
            int hi = Integer.parseInt(guid.substring(at + 2, at + 4), 16);
            return lo | (hi << 8);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float clamp01(float v) {
        return v > 0.0f ? Math.min(1.0f, v) : 0.0f; // NaN maps to 0
    }

    public void destroy() {
        stopVibration();
        closeRumbleGamepad();
        if (sdlAvailable) {
            SDL_QuitSubSystem(SDL_INIT_GAMEPAD);
            sdlAvailable = false;
        }
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
