package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.*;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.UIButton;
import com.retro.engine.ecs.components.UICanvasComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.testing.TestEntities;
import com.retro.engine.ui.GameUIRenderer;
import com.retro.engine.ui.UINavigation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** UIButton: mouse, keyboard and code presses, focus movement, and the onButtonClick hook. */
class UIButtonTest {

    private static final float VIEW_W = 1280.0f, VIEW_H = 720.0f;

    private static final String SOURCE = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.scripting.framework.*;
        import com.retro.engine.ecs.components.UIButton;
        public class ClickRecorder extends SimpleEntityScript {
            public String log = "";
            public void onButtonClick(UIButton button) { log += button.label; }
        }
        """;

    @TempDir Path assets;
    private Path scriptFile;
    private SceneWorld world;

    @BeforeEach
    void setUp() throws IOException {
        Input.clearState();
        scriptFile = assets.resolve("Scripts/ClickRecorder.java");
        Files.createDirectories(scriptFile.getParent());
        Files.writeString(scriptFile, SOURCE);
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
        Input.updatePointer(-1, -1, 0, 0, VIEW_W, VIEW_H, false);
    }

    @AfterEach
    void tearDown() {
        world.stop();
        Input.clearState();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    /** A button on screen at view fractions (u0, v0)-(u1, v1), origin top-left, as the renderer would record it. */
    private UIButton button(String label, float u0, float v0, float u1, float v1) {
        Entity e = TestEntities.at(label, 0, 0, 0);
        UIButton b = new UIButton();
        b.label = label;
        e.addComponent(b);
        world.addEntity(e, -1);
        b.setScreenRect(u0, v0, u1, v1);
        return b;
    }

    private ScriptComponent recorder(UIButton b) {
        Entity owner = null;
        for (Entity e : world.getSceneEntities()) if (e.getAllComponents().contains(b)) owner = e;
        ScriptComponent sc = new ScriptComponent(scriptFile.toString(), "ClickRecorder.java");
        owner.addComponent(sc);
        return sc;
    }

    private static String log(ScriptComponent sc) throws ReflectiveOperationException {
        return (String) sc.activeScriptInstance.getClass().getField("log").get(sc.activeScriptInstance);
    }

    private void tick() {
        world.update(TestEntities.FRAME);
    }

    private void press(int key) {
        Input.setKeyState(key, true);
        tick();
        Input.setKeyState(key, false);
    }

    @Test
    void arrowKeysMoveFocusToTheNearestButtonInThatDirection() {
        UIButton play = button("PLAY", 0.4f, 0.3f, 0.6f, 0.4f);
        UIButton options = button("OPTIONS", 0.4f, 0.45f, 0.6f, 0.55f);
        UIButton quit = button("QUIT", 0.4f, 0.6f, 0.6f, 0.7f);
        world.play();
        tick();
        assertFalse(play.isFocused(), "nothing is focused until the player navigates");

        press(GLFW_KEY_DOWN);
        assertTrue(play.isFocused(), "the first move focuses the top button");
        press(GLFW_KEY_DOWN);
        assertTrue(options.isFocused());
        press(GLFW_KEY_DOWN);
        assertTrue(quit.isFocused());
        press(GLFW_KEY_DOWN);
        assertTrue(quit.isFocused(), "arrows stop at the edge");
        press(GLFW_KEY_UP);
        assertTrue(options.isFocused());
    }

    @Test
    void focusFirstWinsAndTabWraps() {
        UIButton a = button("A", 0.1f, 0.1f, 0.3f, 0.2f);
        UIButton b = button("B", 0.4f, 0.1f, 0.6f, 0.2f);
        b.focusFirst = true;
        world.play();
        press(GLFW_KEY_TAB);
        assertTrue(b.isFocused());
        press(GLFW_KEY_TAB);
        assertTrue(a.isFocused(), "Tab wraps to the start");
    }

    @Test
    void enterPressesTheFocusedButtonAndCallsTheHook() throws ReflectiveOperationException {
        UIButton play = button("PLAY", 0.4f, 0.3f, 0.6f, 0.4f);
        ScriptComponent sc = recorder(play);
        world.play();
        press(GLFW_KEY_DOWN);
        Input.setKeyState(GLFW_KEY_ENTER, true);
        tick();
        assertTrue(play.wasClicked());
        assertTrue(play.isPressed());
        assertEquals("PLAY", log(sc));
        tick();
        assertFalse(play.wasClicked(), "wasClicked lasts one frame");
        Input.setKeyState(GLFW_KEY_ENTER, false);
        tick();
        assertFalse(play.isPressed());
        assertEquals("PLAY", log(sc), "holding Enter doesn't repeat");
    }

    @Test
    void mouseClickNeedsPressAndReleaseOverTheButton() {
        UIButton b = button("OK", 0.4f, 0.4f, 0.6f, 0.6f);
        world.play();
        Input.updatePointer(10, 10, 0, 0, VIEW_W, VIEW_H, true);
        tick();
        Input.updatePointer(VIEW_W * 0.5f, VIEW_H * 0.5f, 0, 0, VIEW_W, VIEW_H, true);
        tick();
        assertTrue(b.isHovered());
        assertTrue(b.isFocused(), "hovering focuses");
        assertTrue(UINavigation.isPointerOverButton(), "the button hides the 3D world from the mouse hooks");

        Input.setMouseButtonState(0, true);
        tick();
        assertFalse(b.wasClicked(), "a click completes on release");
        Input.setMouseButtonState(0, false);
        tick();
        assertTrue(b.wasClicked());

        // Press over it, drag off, release: not a click
        Input.setMouseButtonState(0, true);
        tick();
        Input.updatePointer(10, 10, 0, 0, VIEW_W, VIEW_H, true);
        tick();
        Input.setMouseButtonState(0, false);
        tick();
        assertFalse(b.wasClicked());
    }

    @Test
    void aCursorRestingOnAButtonWhenPlayStartsDoesNotTakeFocus() {
        UIButton top = button("TOP", 0.4f, 0.1f, 0.6f, 0.2f);
        UIButton under = button("UNDER", 0.4f, 0.4f, 0.6f, 0.6f);
        Input.updatePointer(VIEW_W * 0.5f, VIEW_H * 0.5f, 0, 0, VIEW_W, VIEW_H, true);
        world.play();
        tick();
        assertTrue(under.isHovered());
        assertFalse(under.isFocused(), "the resting cursor didn't move");
        press(GLFW_KEY_DOWN);
        assertTrue(top.isFocused(), "the first keyboard move starts at the top");
        Input.updatePointer(VIEW_W * 0.5f + 3, VIEW_H * 0.5f, 0, 0, VIEW_W, VIEW_H, true);
        tick();
        assertTrue(under.isFocused(), "moving the mouse over it does");
    }

    @Test
    void greyedOutButtonsCanNotBeFocusedOrPressed() {
        UIButton off = button("OFF", 0.4f, 0.3f, 0.6f, 0.4f);
        off.interactable = false;
        UIButton on = button("ON", 0.4f, 0.5f, 0.6f, 0.6f);
        world.play();
        press(GLFW_KEY_DOWN);
        assertTrue(on.isFocused());
        press(GLFW_KEY_UP);
        assertTrue(on.isFocused(), "skips the greyed-out button");
        off.click();
        tick();
        assertFalse(off.wasClicked());
    }

    @Test
    void clickFromCodeAndFocusFromCode() throws ReflectiveOperationException {
        UIButton a = button("A", 0.1f, 0.1f, 0.3f, 0.2f);
        UIButton b = button("B", 0.4f, 0.1f, 0.6f, 0.2f);
        ScriptComponent sc = recorder(b);
        world.play();
        b.focus();
        tick();
        assertTrue(b.isFocused());
        assertFalse(a.isFocused());
        b.click();
        tick();
        assertTrue(b.wasClicked());
        assertEquals("B", log(sc));
    }

    @Test
    void theRendererRecordsWhereEachButtonLanded() {
        Entity cam = TestEntities.at("Main Camera", 0, 0, 0);
        cam.addComponent(new Camera(60.0f, 0.1f, 100.0f));
        UICanvasComponent canvas = new UICanvasComponent();
        cam.addComponent(canvas);
        UIButton b = new UIButton();
        b.width = 256.0f;
        b.height = 72.0f;
        cam.addComponent(b);
        world.addEntity(cam, -1);

        new GameUIRenderer().build(world.getSceneEntities(), world, -1, -1, 1, 1, (int) VIEW_W, (int) VIEW_H, null, VIEW_W / VIEW_H);
        assertTrue(b.hasScreenRect());
        assertEquals(0.4f, b.rectU0(), 1e-4f);
        assertEquals(0.6f, b.rectU1(), 1e-4f);
        assertEquals(0.45f, b.rectV0(), 1e-4f);
        assertEquals(0.55f, b.rectV1(), 1e-4f);

        b.visible = false;
        new GameUIRenderer().build(world.getSceneEntities(), world, -1, -1, 1, 1, (int) VIEW_W, (int) VIEW_H, null, VIEW_W / VIEW_H);
        assertFalse(b.hasScreenRect(), "a hidden button can't be hovered or focused");
    }
}
