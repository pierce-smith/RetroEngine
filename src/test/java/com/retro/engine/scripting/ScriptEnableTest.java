package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ScriptEnableTest {

    private static final String SOURCE = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.scripting.framework.*;
        public class Recorder extends SimpleEntityScript {
            public String log = "";
            public boolean failNextUpdate;
            public void onAwake() { log += "awake "; }
            public void onEnable() { log += "enable "; }
            public void onStart() { log += "start "; }
            public void onUpdate(float dt) {
                if (failNextUpdate) throw new IllegalStateException("script bug");
            }
            public void onDisable() { log += "disable "; }
            public void onDestroy() { log += "destroy "; }
        }
        """;

    @TempDir Path assets;
    private SceneWorld world;
    private ScriptComponent script;
    private Object instance;

    @BeforeEach
    void play() throws IOException {
        Path file = assets.resolve("Scripts/Recorder.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
        Entity entity = TestEntities.at("recorded", 0, 0, 0);
        script = new ScriptComponent(file.toString(), "Recorder.java");
        entity.addComponent(script);
        world.addEntity(entity, -1);
        world.play();
        instance = script.activeScriptInstance;
    }

    @AfterEach
    void stop() {
        world.stop();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private String log() throws ReflectiveOperationException {
        return (String) instance.getClass().getField("log").get(instance);
    }

    private void tick(int frames) {
        for (int i = 0; i < frames; i++) world.update(TestEntities.FRAME);
    }

    @Test
    void enableRunsBetweenAwakeAndStart() throws ReflectiveOperationException {
        tick(3);
        assertEquals("awake enable start ", log());
    }

    @Test
    void togglingCallsEachHookOnce() throws ReflectiveOperationException {
        tick(1);
        script.enabled = false;
        tick(3);
        assertEquals("awake enable start disable ", log());
        script.enabled = true;
        tick(3);
        assertEquals("awake enable start disable enable ", log());
    }

    @Test
    void stoppingDisablesBeforeDestroying() throws ReflectiveOperationException {
        world.stop();
        assertEquals("awake enable start disable destroy ", log());
    }

    @Test
    void aScriptStoppedWhileDisabledIsNotDisabledTwice() throws ReflectiveOperationException {
        script.enabled = false;
        tick(1);
        world.stop();
        assertEquals("awake enable start disable destroy ", log());
    }

    @Test
    void aScriptThatThrewGetsNoDisable() throws ReflectiveOperationException {
        instance.getClass().getField("failNextUpdate").setBoolean(instance, true);
        tick(2);
        assertFalse(script.enabled);
        assertEquals("awake enable start ", log().replace("destroy ", ""));
    }
}
