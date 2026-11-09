package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.framework.ScriptCameraShake;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** onLateUpdate runs once per Play frame, after onUpdate, before input edges clear and before camera shake goes on. */
class LateUpdateTest {

    private static final int KEY = 65; // GLFW_KEY_A

    private static final String SOURCE = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.ecs.components.Transform;
        import com.retro.engine.scripting.Input;
        import com.retro.engine.scripting.framework.*;
        public class LateRecorder extends SimpleEntityScript {
            public String log = "";
            public boolean sawPress;
            public boolean failLate;
            public boolean driveCamera;
            public void onUpdate(float dt) { log += "u"; }
            public void onLateUpdate(float dt) {
                if (failLate) throw new IllegalStateException("late bug");
                log += "L";
                sawPress = Input.isKeyPressed(65);
                if (driveCamera) {
                    Transform cam = (Transform) findEntity("Main Camera").getComponent(Transform.class);
                    cam.posX = 5.0f;
                }
            }
        }
        """;

    @TempDir Path assets;
    private SceneWorld world;
    private ScriptComponent script;
    private Entity camera;
    private Object instance;

    @BeforeEach
    void play() throws IOException {
        Input.clearState();
        Path file = assets.resolve("Scripts/LateRecorder.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
        camera = TestEntities.at("Main Camera", 0, 0, 0);
        camera.addComponent(new Camera());
        world.addEntity(camera, -1);
        Entity entity = TestEntities.at("recorded", 0, 0, 0);
        script = new ScriptComponent(file.toString(), "LateRecorder.java");
        entity.addComponent(script);
        world.addEntity(entity, -1);
        world.play();
        instance = script.activeScriptInstance;
    }

    @AfterEach
    void stop() {
        world.stop();
        ScriptCameraShake.reset();
        Input.clearState();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private Object field(String name) throws ReflectiveOperationException {
        return instance.getClass().getField(name).get(instance);
    }

    private void set(String name, boolean value) throws ReflectiveOperationException {
        instance.getClass().getField(name).setBoolean(instance, value);
    }

    private void tick(int frames) {
        for (int i = 0; i < frames; i++) world.update(TestEntities.FRAME);
    }

    @Test
    void runsOnceAfterUpdateEveryFrame() throws ReflectiveOperationException {
        tick(3);
        assertEquals("uLuLuL", field("log"));
    }

    @Test
    void pausedWorldSkipsIt() throws ReflectiveOperationException {
        tick(1);
        world.pause();
        tick(3);
        assertEquals("uL", field("log"));
    }

    @Test
    void seesThisFramesKeyPresses() throws ReflectiveOperationException {
        tick(1);
        Input.setKeyState(KEY, true);
        tick(1);
        assertTrue((Boolean) field("sawPress"), "the press edge must survive until onLateUpdate");
        tick(1);
        assertFalse((Boolean) field("sawPress"), "a held key is a press for one frame only");
    }

    @Test
    void aThrowDisablesOnlyThatScript() throws ReflectiveOperationException {
        tick(1);
        int failuresBefore = ScriptEngine.getFailureCount();
        set("failLate", true);
        tick(2);
        assertNull(script.activeScriptInstance);
        assertFalse(script.enabled);
        assertEquals(failuresBefore + 1, ScriptEngine.getFailureCount(), "one throw, logged once");
    }

    @Test
    void cameraShakeLandsOnTopOfALateCameraPoseWithoutDrift() throws ReflectiveOperationException {
        set("driveCamera", true);
        tick(1);
        ScriptCameraShake.shake(1.0f, 0.25f);
        Transform cam = camera.getTransform();
        boolean moved = false;
        for (int i = 0; i < 10; i++) {
            tick(1);
            moved |= Math.abs(cam.posX - 5.0f) > 1e-4f;
        }
        assertTrue(moved, "the shake must be applied after onLateUpdate wrote the pose, or it never shows");
        tick(30); // shake over
        assertEquals(5.0f, cam.posX, 1e-5f, "beginFrame must remove exactly the offset endFrame added");
    }
}
