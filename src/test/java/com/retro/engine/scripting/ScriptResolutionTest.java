package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class ScriptResolutionTest {

    private static final String KEY = "scriptResolutionTest.";

    @TempDir Path assets;
    private SceneWorld world;

    @BeforeEach
    void bindProject() {
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
    }

    @AfterEach
    void stop() {
        world.stop();
        System.getProperties().keySet().removeIf(k -> k.toString().startsWith(KEY));
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private Path source(String relative, String className, String declaredPackage, String body) throws IOException {
        Path file = assets.resolve(relative);
        Files.createDirectories(file.getParent());
        String pkg = declaredPackage != null ? "package " + declaredPackage + ";\n" : "";
        Files.writeString(file, pkg + "public class " + className + " extends com.retro.engine.scripting.framework.SimpleEntityScript {\n" + body + "\n}\n");
        return file;
    }

    private static String mark(String key, String value) {
        return "public void onStart() { System.setProperty(\"" + KEY + key + "\", " + value + "); }";
    }

    private Entity attach(String name, String path, String script) {
        Entity e = TestEntities.at(name, 0, 0, 0);
        e.addComponent(new ScriptComponent(path, script));
        world.addEntity(e, -1);
        return e;
    }

    private void play() {
        world.play();
        world.update(TestEntities.FRAME);
    }

    private static String seen(String key) {
        return System.getProperty(KEY + key);
    }

    @Test
    void aScriptMayShareAnEngineClassName() throws IOException {
        Path file = source("Scripts/SceneManager.java", "SceneManager", "com.retro.engine.scripting.user", mark("ran", "\"yes\""));
        attach("Manager", file.toString(), "SceneManager.java");
        play();
        assertEquals("yes", seen("ran"));
    }

    @Test
    void aScriptWithNoPackageMayShareAnEngineClassName() throws IOException {
        Path file = source("Scripts/Input.java", "Input", null, mark("ran", "\"yes\""));
        attach("Reader", file.toString(), "Input.java");
        play();
        assertEquals("yes", seen("ran"));
    }

    @Test
    void scriptsInDifferentFoldersMayShareAName() throws IOException {
        Path boss = source("Scripts/Boss/Brain.java", "Brain", "com.retro.engine.scripting.user", mark("boss", "\"yes\""));
        Path rat = source("Scripts/Rat/Brain.java", "Brain", "com.retro.engine.scripting.user", mark("rat", "\"yes\""));
        attach("Boss", boss.toString(), "Brain.java");
        attach("Rat", rat.toString(), "Brain.java");
        play();
        assertEquals("yes", seen("boss"));
        assertEquals("yes", seen("rat"));
    }

    @Test
    void aStaleAbsolutePathStillFindsTheRightFolderInAMovedProject() throws IOException {
        source("Scripts/Boss/Brain.java", "Brain", "com.retro.engine.scripting.user", mark("boss", "\"yes\""));
        source("Scripts/Rat/Brain.java", "Brain", "com.retro.engine.scripting.user", mark("rat", "\"yes\""));
        attach("Boss", "/old/machine/Game/Assets/Scripts/Boss/Brain.java", "Brain.java");
        attach("Rat", "/old/machine/Game/Assets/Scripts/Rat/Brain.java", "Brain.java");
        play();
        assertEquals("yes", seen("boss"));
        assertEquals("yes", seen("rat"));
    }

    @Test
    void overridesAreVisibleInOnAwake() throws IOException {
        Path file = source("Scripts/Tuned.java", "Tuned", "com.retro.engine.scripting.user",
                "public int hp = 3;\npublic void onAwake() { System.setProperty(\"" + KEY + "hp\", String.valueOf(hp)); }");
        Entity e = attach("Tuned", file.toString(), "Tuned.java");
        e.getComponent(ScriptComponent.class).propertyOverrides.put("hp", "99");
        play();
        assertEquals("99", seen("hp"));
    }

    @Test
    void isAliveTurnsFalseOnceAnEntityIsDestroyed() throws IOException {
        Entity victim = TestEntities.at("Victim", 0, 0, 0);
        world.addEntity(victim, -1);
        Path file = source("Scripts/Watcher.java", "Watcher", "com.retro.engine.scripting.user", """
            private int frames;
            public void onUpdate(float dt) {
                com.retro.engine.ecs.Entity victim = findEntity("Victim");
                if (++frames == 1) { System.setProperty("%1$sbefore", String.valueOf(isAlive(victim))); held = victim; destroy(victim); }
                if (frames == 2) System.setProperty("%1$safter", String.valueOf(isAlive(held)));
            }
            private com.retro.engine.ecs.Entity held;
            """.formatted(KEY));
        attach("Watcher", file.toString(), "Watcher.java");
        play();
        world.update(TestEntities.FRAME);
        assertEquals("true", seen("before"));
        assertEquals("false", seen("after"));
    }
}
