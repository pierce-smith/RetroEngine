package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.RigidBodyComponent.MotionType;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.graphics.SceneRenderPipeline;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** onMouseEnter/Exit/Down/Up: a ray from the Main Camera through the cursor picks the first solid collider. */
class MouseHooksTest {

    private static final float VIEW_W = 800.0f, VIEW_H = 600.0f;
    private static final float FOV = 50.0f;

    private static final String SOURCE = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.scripting.framework.*;
        public class MouseRecorder extends SimpleEntityScript {
            public String log = "";
            public boolean failOnEnter;
            public void onMouseEnter() {
                if (failOnEnter) throw new IllegalStateException("hover bug");
                log += "E";
            }
            public void onMouseExit() { log += "X"; }
            public void onMouseDown() { log += "D"; }
            public void onMouseUp() { log += "U"; }
        }
        """;

    @TempDir Path assets;
    private Path scriptFile;
    private SceneWorld world;
    private Entity camera;

    @BeforeEach
    void setUp() throws IOException {
        Input.clearState();
        scriptFile = assets.resolve("Scripts/MouseRecorder.java");
        Files.createDirectories(scriptFile.getParent());
        Files.writeString(scriptFile, SOURCE);
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
        camera = TestEntities.at("Main Camera", 0, 0, 0);
        camera.addComponent(new Camera(FOV, 0.1f, 1000.0f));
        world.addEntity(camera, -1);
    }

    @AfterEach
    void tearDown() {
        world.stop();
        Input.clearState();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private ScriptComponent target(float x, float y, float z) {
        return target(x, y, z, 2.0f);
    }

    private ScriptComponent target(float x, float y, float z, float size) {
        Entity box = TestEntities.box(x, y, z, size, size, size, MotionType.STATIC);
        ScriptComponent sc = new ScriptComponent(scriptFile.toString(), "MouseRecorder.java");
        box.addComponent(sc);
        world.addEntity(box, -1);
        return sc;
    }

    private static String log(ScriptComponent sc) throws ReflectiveOperationException {
        return (String) sc.activeScriptInstance.getClass().getField("log").get(sc.activeScriptInstance);
    }

    private static void pointAt(float x, float y) {
        Input.updatePointer(x, y, 0.0f, 0.0f, VIEW_W, VIEW_H, true);
    }

    private void tick(int frames) {
        for (int i = 0; i < frames; i++) world.update(TestEntities.FRAME);
    }

    @Test
    void enterDownUpExitInOrder() throws ReflectiveOperationException {
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        pointAt(0, 0); // top-left corner: empty sky
        tick(1);
        assertEquals("", log(sc));

        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(2);
        assertEquals("E", log(sc), "enter once, not every frame");

        Input.setMouseButtonState(0, true);
        tick(2);
        assertEquals("ED", log(sc), "down on the press frame only");
        Input.setMouseButtonState(0, false);
        tick(1);
        assertEquals("EDU", log(sc));

        pointAt(0, 0);
        tick(1);
        assertEquals("EDUX", log(sc));
    }

    @Test
    void upGoesToThePressedEntityAfterDraggingOff() throws ReflectiveOperationException {
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        Input.setMouseButtonState(0, true);
        tick(1);
        pointAt(0, 0);
        tick(1);
        Input.setMouseButtonState(0, false);
        tick(1);
        assertEquals("EDXU", log(sc));
    }

    @Test
    void pressAndReleaseInOneFrameIsAFullClick() throws ReflectiveOperationException {
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(1);
        Input.setMouseButtonState(0, true);
        Input.setMouseButtonState(0, false);
        tick(1);
        assertEquals("EDU", log(sc));
    }

    @Test
    void leavingTheViewExits() throws ReflectiveOperationException {
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(1);
        Input.updatePointer(VIEW_W / 2, VIEW_H / 2, 0.0f, 0.0f, VIEW_W, VIEW_H, false); // e.g. a popup covers it
        tick(1);
        assertEquals("EX", log(sc));
        assertFalse(Input.isMouseInView());
    }

    @Test
    void movingBetweenEntitiesExitsOneAndEntersTheOther() throws ReflectiveOperationException {
        ScriptComponent left = target(0, 0, -10);
        ScriptComponent right = target(3, 0, -10);
        world.play();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(1);
        float[] px = project(3, 0, -10);
        pointAt(px[0], px[1]);
        tick(1);
        assertEquals("EX", log(left));
        assertEquals("E", log(right));
    }

    @Test
    void triggersDoNotBlockTheRay() throws ReflectiveOperationException {
        Entity trigger = TestEntities.box(0, 0, -5, 2, 2, 2, MotionType.STATIC);
        trigger.getComponent(BoxCollider.class).isTrigger = true;
        world.addEntity(trigger, -1);
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(1);
        assertEquals("E", log(sc));
    }

    /** The ray must invert the renderer's own view and projection, for any camera pose and cursor position. */
    @Test
    void rayMatchesTheRenderedImageForARotatedCamera() throws ReflectiveOperationException {
        camera.getTransform().posX = 2.0f;
        camera.getTransform().posY = 3.0f;
        camera.getTransform().rotX = 20.0f;
        camera.getTransform().rotY = 35.0f;
        camera.getTransform().rotZ = 5.0f;
        // In front of the camera, off to one side
        float[] point = alongView(4.0f, -2.5f, -12.0f);
        // Small enough that a wrong sign or axis in the ray misses it
        ScriptComponent sc = target(point[0], point[1], point[2], 0.2f);
        world.play();
        float[] px = project(point[0], point[1], point[2]);
        assertTrue(Math.abs(px[0] - VIEW_W / 2) > 50.0f, "target must not sit at the view centre");
        pointAt(px[0], px[1]);
        tick(1);
        assertEquals("E", log(sc));
    }

    @Test
    void scriptThatThrowsIsDisabledAndNotRetried() throws ReflectiveOperationException {
        ScriptComponent sc = target(0, 0, -10);
        world.play();
        Object instance = sc.activeScriptInstance;
        instance.getClass().getField("failOnEnter").setBoolean(instance, true);
        int failuresBefore = ScriptEngine.getFailureCount();
        pointAt(VIEW_W / 2, VIEW_H / 2);
        tick(3);
        assertNull(sc.activeScriptInstance);
        assertEquals(failuresBefore + 1, ScriptEngine.getFailureCount());
    }

    @Test
    void cursorIsMeasuredFromTheViewsTopLeft() {
        Input.updatePointer(150.0, 120.0, 100.0f, 20.0f, 640.0f, 360.0f, true);
        assertEquals(50.0f, Input.getMouseX(), 1e-4f);
        assertEquals(100.0f, Input.getMouseY(), 1e-4f);
        assertTrue(Input.isMouseInView());
        Input.updatePointer(50.0, 120.0, 100.0f, 20.0f, 640.0f, 360.0f, true);
        assertFalse(Input.isMouseInView(), "left of the view");
        Input.updatePointer(0.0, 0.0, 0.0f, 0.0f, 0.0f, 0.0f, true);
        assertFalse(Input.isMouseInView(), "no view at all (headless)");
    }

    // --- Forward projection with the renderer's matrices: the test's independent reference ---

    private float[] viewMatrix() {
        float[] view = new float[16];
        SceneRenderPipeline.computeCameraView(world.getComputedWorldTransform(camera), view);
        return view;
    }

    /** World point for a camera-space offset (x right, y up, -z forward). */
    private float[] alongView(float x, float y, float z) {
        float[] v = viewMatrix();
        float[] c = {x - v[12], y - v[13], z - v[14]};
        // Inverse of a rigid view: R^T applied to (camera point - translation)
        return new float[] {
            v[0] * c[0] + v[1] * c[1] + v[2] * c[2],
            v[4] * c[0] + v[5] * c[1] + v[6] * c[2],
            v[8] * c[0] + v[9] * c[1] + v[10] * c[2]};
    }

    /** Game-view pixel of a world point, the way the renderer projects it. */
    private float[] project(float x, float y, float z) {
        float[] v = viewMatrix();
        float vx = v[0] * x + v[4] * y + v[8] * z + v[12];
        float vy = v[1] * x + v[5] * y + v[9] * z + v[13];
        float vz = v[2] * x + v[6] * y + v[10] * z + v[14];
        float f = (float) (1.0 / Math.tan(Math.toRadians(FOV) / 2.0));
        float aspect = VIEW_W / VIEW_H;
        float ndcX = (f / aspect) * vx / -vz;
        float ndcY = f * vy / -vz;
        return new float[] {(ndcX + 1.0f) * 0.5f * VIEW_W, (1.0f - ndcY) * 0.5f * VIEW_H};
    }
}
