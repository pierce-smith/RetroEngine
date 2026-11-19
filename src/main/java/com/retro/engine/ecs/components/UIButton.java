package com.retro.engine.ecs.components;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * A pressable button: a filled, optionally rounded and bordered rectangle with a centered label. Works with the mouse,
 * the keyboard and a gamepad during Play.
 *
 * <ul>
 *   <li>Mouse: hovering focuses the button; a left click (press and release over it) presses it.</li>
 *   <li>Keyboard: arrow keys move focus to the nearest button in that direction, Tab / Shift+Tab step through buttons
 *   in reading order, Enter or Space presses the focused one.</li>
 *   <li>Gamepad: D-pad or left stick moves focus, A presses.</li>
 * </ul>
 * Focus only moves between visible, {@link #interactable} buttons whose canvas is on screen. The first keyboard or
 * gamepad move focuses the button marked {@link #focusFirst} (else the top-left one).
 *
 * <p>React to a press either with {@code onButtonClick(UIButton)} in a script on the button's entity, or by checking
 * {@link #wasClicked()} from any script that holds the button.
 */
@PublicApi
public class UIButton extends UIElement {

    @InspectorProperty(order = 12, label = "LABEL", type = PropertyType.TEXT)
    public String label = "BUTTON";

    /** Label em height in reference pixels. */
    @InspectorProperty(order = 13, label = "FONT SIZE", min = 4.0f, max = 400.0f, step = 1.0f)
    public float fontSize = 28.0f;

    @InspectorProperty(order = 14, label = "LABEL RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float labelR = 1.0f;

    @InspectorProperty(order = 15, label = "LABEL GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float labelG = 1.0f;

    @InspectorProperty(order = 16, label = "LABEL BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float labelB = 1.0f;

    /** Fill color while the button is focused or hovered. */
    @InspectorProperty(order = 17, label = "FOCUS RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float focusR = 0.85f;

    @InspectorProperty(order = 18, label = "FOCUS GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float focusG = 0.55f;

    @InspectorProperty(order = 19, label = "FOCUS BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float focusB = 0.15f;

    @InspectorProperty(order = 20, label = "CORNER RADIUS", min = 0.0f, max = 500.0f, step = 1.0f)
    public float cornerRadius = 6.0f;

    /** Border width in reference pixels; the border is drawn in the label color while focused. 0 = no border. */
    @InspectorProperty(order = 21, label = "BORDER", min = 0.0f, max = 50.0f, step = 1.0f)
    public float borderThickness = 2.0f;

    /** False greys the button out: it can't be focused or pressed. */
    @InspectorProperty(order = 22, label = "INTERACTABLE", type = PropertyType.TOGGLE)
    public boolean interactable = true;

    /** The button that gets focus on the first keyboard or gamepad move (a menu's default choice). */
    @InspectorProperty(order = 23, label = "FOCUS FIRST", type = PropertyType.TOGGLE)
    public boolean focusFirst = false;

    /** Font file (.ttf / .otf) for the label, as for {@link UIText#font}; empty uses the engine's default game font. */
    @InspectorProperty(order = 24, label = "FONT", type = PropertyType.ASSET_PICKER, assetType = "font")
    public String font = "";

    // --- Runtime state, written by the engine each Play frame; never saved ---

    /** Last drawn rectangle as fractions of the game view, origin top-left (valid while rectValid). */
    private float rectU0, rectV0, rectU1, rectV1;
    private boolean rectValid;
    private boolean hovered, focused, pressed;
    private boolean clicked, clickRequested;

    public UIButton() {
        width = 260.0f;
        height = 56.0f;
        colorR = 0.12f; colorG = 0.12f; colorB = 0.15f;
        alpha = 0.92f;
    }

    @Override
    public String getComponentName() {
        return "UIButton";
    }

    /** True while the mouse is over the button. */
    public boolean isHovered() { return hovered; }

    /** True while the button has keyboard / gamepad focus (hovering with the mouse also focuses it). */
    public boolean isFocused() { return focused; }

    /** True while the button is held down (mouse button, Enter / Space or gamepad A). */
    public boolean isPressed() { return pressed; }

    /** True for the one frame in which the button was pressed; scripts read it in onUpdate. */
    public boolean wasClicked() { return clicked; }

    /** Moves keyboard / gamepad focus to this button on the next frame (if it is visible and interactable). */
    public void focus() { com.retro.engine.ui.UINavigation.requestFocus(this); }

    /** Presses the button from code on the next frame, exactly as a click would. */
    public void click() { clickRequested = true; }

    // --- Engine side ---

    @Internal
    public void setScreenRect(float u0, float v0, float u1, float v1) {
        rectU0 = u0; rectV0 = v0; rectU1 = u1; rectV1 = v1;
        rectValid = true;
    }

    @Internal public void invalidateScreenRect() { rectValid = false; }
    @Internal public boolean hasScreenRect() { return rectValid; }
    @Internal public float rectU0() { return rectU0; }
    @Internal public float rectV0() { return rectV0; }
    @Internal public float rectU1() { return rectU1; }
    @Internal public float rectV1() { return rectV1; }

    /** True when (u, v), as fractions of the game view with origin top-left, lies on the last drawn rectangle. */
    @Internal
    public boolean contains(float u, float v) {
        return rectValid && u >= rectU0 && u < rectU1 && v >= rectV0 && v < rectV1;
    }

    @Internal
    public void setRuntimeState(boolean hovered, boolean focused, boolean pressed, boolean clicked) {
        this.hovered = hovered;
        this.focused = focused;
        this.pressed = pressed;
        this.clicked = clicked;
    }

    /** Returns and clears a pending {@link #click()} request. */
    @Internal
    public boolean consumeClickRequest() {
        boolean r = clickRequested;
        clickRequested = false;
        return r;
    }

    /** Clears all runtime state (Play started or stopped). */
    @Internal
    public void resetRuntimeState() {
        hovered = focused = pressed = clicked = clickRequested = false;
        rectValid = false;
    }
}
