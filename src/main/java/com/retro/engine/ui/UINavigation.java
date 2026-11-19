package com.retro.engine.ui;

import com.retro.engine.core.GamepadManager;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.UIButton;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.ScriptEngine;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Drives {@link UIButton}s during Play: mouse hover and clicks, keyboard and gamepad focus movement, and presses. Runs
 * once per Play frame before the mouse hooks and onUpdate, using the button rectangles {@link GameUIRenderer} drew last
 * frame (so only buttons actually on screen take part). Clicks set {@link UIButton#wasClicked()} for this frame and
 * call {@code onButtonClick(UIButton)} on the scripts of the button's entity.
 *
 * <p>Single-threaded, like the rest of the script runtime. Holds no state across Play sessions.
 */
public final class UINavigation {

    private static final int LEFT_MOUSE = 0;
    private static final float STICK_THRESHOLD = 0.5f;
    private static final float STICK_FIRST_REPEAT = 0.4f, STICK_REPEAT = 0.15f;
    /** A button off the movement axis costs this much per unit of sideways distance, so straight lines win. */
    private static final float SIDEWAYS_COST = 2.0f;

    private enum Dir { UP, DOWN, LEFT, RIGHT }

    private static final List<UIButton> candidates = new ArrayList<>();
    private static final List<UIButton> allButtons = new ArrayList<>();
    private static final List<Entity> buttonEntities = new ArrayList<>();

    private static UIButton focused, hovered, mouseDown, keyHeld;
    private static UIButton focusRequest;
    private static float lastMouseX = Float.NaN, lastMouseY = Float.NaN;
    private static Dir stickDir;
    private static float stickTimer;
    private static boolean pointerOverButton;

    private UINavigation() {}

    /** {@link UIButton#focus()}: applied on the next update if the button is on screen and interactable. */
    public static void requestFocus(UIButton button) {
        focusRequest = button;
    }

    /** True while the cursor is over a button, so the 3D mouse hooks treat it as pointing at nothing. */
    public static boolean isPointerOverButton() {
        return pointerOverButton;
    }

    /** Forgets all focus and press state without sending hooks: Play started, stopped or changed scene. */
    public static void reset() {
        for (UIButton b : allButtons) b.resetRuntimeState();
        candidates.clear();
        allButtons.clear();
        buttonEntities.clear();
        focused = hovered = mouseDown = keyHeld = focusRequest = null;
        lastMouseX = lastMouseY = Float.NaN;
        stickDir = null;
        stickTimer = 0.0f;
        pointerOverButton = false;
    }

    public static void update(List<Entity> entities, float deltaTime) {
        collect(entities);

        if (focused != null && !candidates.contains(focused)) focused = null;
        if (keyHeld != null && !candidates.contains(keyHeld)) keyHeld = null;
        if (mouseDown != null && !candidates.contains(mouseDown)) mouseDown = null;
        if (focusRequest != null) {
            if (candidates.contains(focusRequest)) focused = focusRequest;
            focusRequest = null;
        }

        UIButton clicked = null;

        // Mouse: hovering focuses (only when the mouse actually moved, so it doesn't fight the keyboard); click = press + release over
        hovered = null;
        if (!Input.isCursorLocked() && Input.isMouseInView() && Input.getViewWidth() > 0.0f && Input.getViewHeight() > 0.0f) {
            float mx = Input.getMouseX(), my = Input.getMouseY();
            hovered = topmostAt(mx / Input.getViewWidth(), my / Input.getViewHeight());
            // The first sample only records where the cursor rests, so a pointer that happens to sit on a button when
            // the menu opens doesn't take focus from the keyboard or gamepad
            boolean moved = !Float.isNaN(lastMouseX) && (mx != lastMouseX || my != lastMouseY);
            lastMouseX = mx;
            lastMouseY = my;
            if (hovered != null && moved) focused = hovered;
            if (hovered != null && Input.isMouseButtonPressed(LEFT_MOUSE)) {
                mouseDown = hovered;
                focused = hovered;
            }
        }
        pointerOverButton = hovered != null;
        if (mouseDown != null && !Input.isMouseButtonDown(LEFT_MOUSE)) {
            if (hovered == mouseDown) clicked = mouseDown;
            mouseDown = null;
        }

        // Keyboard and gamepad movement
        GamepadManager pad = Input.getGamepad();
        boolean padOn = pad != null && pad.isConnected();
        Dir move = null;
        if (Input.isKeyPressed(GLFW_KEY_UP) || (padOn && pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_UP))) move = Dir.UP;
        else if (Input.isKeyPressed(GLFW_KEY_DOWN) || (padOn && pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_DOWN))) move = Dir.DOWN;
        else if (Input.isKeyPressed(GLFW_KEY_LEFT) || (padOn && pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_LEFT))) move = Dir.LEFT;
        else if (Input.isKeyPressed(GLFW_KEY_RIGHT) || (padOn && pad.isButtonJustPressed(GamepadManager.BUTTON_DPAD_RIGHT))) move = Dir.RIGHT;
        if (move == null && padOn) move = stickMove(pad, deltaTime);
        else if (!padOn) stickDir = null;

        int tab = 0;
        if (Input.isKeyPressed(GLFW_KEY_TAB)) {
            tab = (Input.isKeyDown(GLFW_KEY_LEFT_SHIFT) || Input.isKeyDown(GLFW_KEY_RIGHT_SHIFT)) ? -1 : 1;
        }

        if ((move != null || tab != 0) && !candidates.isEmpty()) {
            if (focused == null) focused = firstFocus();
            else if (move != null) focused = neighbour(focused, move);
            else focused = readingOrderStep(focused, tab);
        }

        // Press: Enter / Space / gamepad A act on the focused button as soon as they go down
        boolean submit = Input.isKeyPressed(GLFW_KEY_ENTER) || Input.isKeyPressed(GLFW_KEY_KP_ENTER) || Input.isKeyPressed(GLFW_KEY_SPACE)
                || (padOn && pad.isButtonJustPressed(GamepadManager.BUTTON_A));
        if (submit && focused != null && clicked == null) {
            clicked = focused;
            keyHeld = focused;
        }
        if (keyHeld != null && !(Input.isKeyDown(GLFW_KEY_ENTER) || Input.isKeyDown(GLFW_KEY_KP_ENTER) || Input.isKeyDown(GLFW_KEY_SPACE)
                || (padOn && pad.isButtonPressed(GamepadManager.BUTTON_A)))) {
            keyHeld = null;
        }

        // Publish every button's state first, so a hook sees the whole menu as it is this frame, then send the hooks
        clickedIndices.clear();
        for (int i = 0; i < allButtons.size(); i++) {
            UIButton b = allButtons.get(i);
            boolean isClicked = (b.consumeClickRequest() && b.interactable) || b == clicked;
            b.setRuntimeState(b == hovered, b == focused, b == mouseDown || b == keyHeld, isClicked);
            if (isClicked) clickedIndices.add(i);
        }
        for (int i = 0; i < clickedIndices.size(); i++) {
            int index = clickedIndices.get(i);
            ScriptEngine.dispatchButtonClick(buttonEntities.get(index), allButtons.get(index));
        }
    }

    private static final List<Integer> clickedIndices = new ArrayList<>();

    private static void collect(List<Entity> entities) {
        candidates.clear();
        allButtons.clear();
        buttonEntities.clear();
        if (entities == null) return;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            List<Component> comps = e.getAllComponents();
            for (int c = 0; c < comps.size(); c++) {
                if (!(comps.get(c) instanceof UIButton b)) continue;
                allButtons.add(b);
                buttonEntities.add(e);
                if (b.visible && b.interactable && b.alpha > 0.0f && b.hasScreenRect()) candidates.add(b);
            }
        }
    }

    /** The highest-sorted candidate under (u, v), or null. Later entities win ties, matching draw order. */
    private static UIButton topmostAt(float u, float v) {
        UIButton best = null;
        for (UIButton b : candidates) {
            if (b.contains(u, v) && (best == null || b.sortOrder >= best.sortOrder)) best = b;
        }
        return best;
    }

    private static UIButton firstFocus() {
        for (UIButton b : candidates) if (b.focusFirst) return b;
        UIButton best = null;
        for (UIButton b : candidates) {
            if (best == null || centerV(b) < centerV(best) - 1e-4f
                    || (Math.abs(centerV(b) - centerV(best)) <= 1e-4f && centerU(b) < centerU(best))) best = b;
        }
        return best;
    }

    /** Nearest candidate whose centre lies in direction {@code dir}; stays put when there is none. Distances in view pixels. */
    static UIButton neighbour(UIButton from, Dir dir) {
        float w = Math.max(1.0f, Input.getViewWidth()), h = Math.max(1.0f, Input.getViewHeight());
        float fx = centerU(from) * w, fy = centerV(from) * h;
        UIButton best = null;
        float bestCost = Float.MAX_VALUE;
        for (UIButton b : candidates) {
            if (b == from) continue;
            float dx = centerU(b) * w - fx, dy = centerV(b) * h - fy; // y grows downwards
            float along, sideways;
            switch (dir) {
                case UP -> { along = -dy; sideways = Math.abs(dx); }
                case DOWN -> { along = dy; sideways = Math.abs(dx); }
                case LEFT -> { along = -dx; sideways = Math.abs(dy); }
                default -> { along = dx; sideways = Math.abs(dy); }
            }
            if (along <= 0.5f) continue;
            float cost = along + SIDEWAYS_COST * sideways;
            if (cost < bestCost) {
                bestCost = cost;
                best = b;
            }
        }
        return best != null ? best : from;
    }

    /** Tab order: rows top to bottom, left to right within a row; wraps at both ends. */
    private static UIButton readingOrderStep(UIButton from, int step) {
        List<UIButton> order = new ArrayList<>(candidates);
        order.sort((a, b) -> {
            float rowTolerance = Math.min(a.rectV1() - a.rectV0(), b.rectV1() - b.rectV0()) * 0.5f;
            float dv = centerV(a) - centerV(b);
            if (Math.abs(dv) > rowTolerance) return dv < 0 ? -1 : 1;
            return Float.compare(centerU(a), centerU(b));
        });
        int i = order.indexOf(from);
        int n = order.size();
        return order.get(((i < 0 ? 0 : i + step) % n + n) % n);
    }

    /** Left stick as a direction: one move on tilt, then repeats while held. */
    private static Dir stickMove(GamepadManager pad, float deltaTime) {
        float x = pad.getAxis(GamepadManager.AXIS_LEFT_X), y = pad.getAxis(GamepadManager.AXIS_LEFT_Y);
        Dir dir = null;
        if (Math.abs(x) >= STICK_THRESHOLD || Math.abs(y) >= STICK_THRESHOLD) {
            dir = Math.abs(y) >= Math.abs(x) ? (y < 0.0f ? Dir.UP : Dir.DOWN) : (x < 0.0f ? Dir.LEFT : Dir.RIGHT);
        }
        if (dir == null) {
            stickDir = null;
            return null;
        }
        if (dir != stickDir) {
            stickDir = dir;
            stickTimer = STICK_FIRST_REPEAT;
            return dir;
        }
        stickTimer -= deltaTime;
        if (stickTimer > 0.0f) return null;
        stickTimer = STICK_REPEAT;
        return dir;
    }

    private static float centerU(UIButton b) { return (b.rectU0() + b.rectU1()) * 0.5f; }
    private static float centerV(UIButton b) { return (b.rectV0() + b.rectV1()) * 0.5f; }
}
