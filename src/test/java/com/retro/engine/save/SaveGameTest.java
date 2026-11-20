package com.retro.engine.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.core.UserFolders;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Checkpoint;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.ScriptEngine;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Save games through real Play sessions: a compiled script with @Save fields, a prefab spawned during play, two levels,
 * and "quit and relaunch" (Stop, then a brand-new world loading the save).
 */
class SaveGameTest {

    @TempDir Path dir;
    private Path assets;
    private String levelA, levelB, prefab, script;
    private SceneWorld world;
    private String previousHome;

    @BeforeEach
    void buildProject() throws IOException {
        System.setProperty(UserFolders.OVERRIDE_PROPERTY, dir.resolve("appdata").toString());
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.resolve("home").toString());
        Path project = Files.createDirectories(dir.resolve("Proj"));
        ProjectWorkspace.writeSettings(project.toFile(), new ProjectSettings("Save Test", ""));
        assets = Files.createDirectories(project.resolve("Assets"));
        ScriptEngine.setProjectAssetsRoot(assets.toString());

        Path scriptFile = Files.createDirectories(assets.resolve("Scripts")).resolve("Counter.java");
        Files.writeString(scriptFile, """
                import com.retro.engine.save.Save;
                import com.retro.engine.save.SaveRecord;
                public class Counter extends com.retro.engine.scripting.framework.SimpleEntityScript {
                    @Save public int coins = 0;
                    @Save public String mood = "calm";
                    @Save public float[] lastSeen = {0f, 0f};
                    public String note = "";
                    public int loads = 0;
                    public void onStart() { coins = 1; }
                    public void onSave(SaveRecord d) { d.set("note", note); }
                    public void onLoad(SaveRecord d) { note = d.getString("note", ""); loads++; }
                }
                """);
        script = scriptFile.toString();

        Path scenes = Files.createDirectories(assets.resolve("Scenes"));
        levelA = scenes.resolve("A.rscene").toString();
        levelB = scenes.resolve("B.rscene").toString();
        writeLevelA(false);
        assertTrue(SceneSerializer.saveScene(levelB, new ArrayList<>(List.of(TestEntities.at("b_floor", 0, 0, 0))), new ArrayList<>(), null));

        Entity orb = TestEntities.at("orb", 0, 0, 0);
        Entity glow = TestEntities.at("glow", 0, 1, 0);
        glow.parentId = orb.id;
        orb.addChildId(glow.id);
        prefab = Files.createDirectories(assets.resolve("Prefabs")).resolve("Orb.rprefab").toString();
        assertTrue(PrefabSerializer.savePrefab(prefab, orb, new ArrayList<>(List.of(orb, glow)), null));
    }

    private void writeLevelA(boolean withLamp) {
        List<Entity> a = new ArrayList<>();
        Entity crate = new Entity("crate", "crate", null);
        Entity key = new Entity("key", "key", null);
        Entity player = new Entity("player", "player", null);
        player.addComponent(new ScriptComponent(script, "Counter.java"));
        Entity light = new Entity("light", "light", null);
        light.addComponent(new Light());
        a.add(crate);
        a.add(key);
        a.add(player);
        a.add(light);
        if (withLamp) a.add(new Entity("lamp", "lamp", null));
        assertTrue(SceneSerializer.saveScene(levelA, a, new ArrayList<>(), null));
    }

    @AfterEach
    void cleanUp() {
        if (world != null) world.stop();
        ScriptEngine.setProjectAssetsRoot(null);
        System.clearProperty(UserFolders.OVERRIDE_PROPERTY);
        System.setProperty("user.home", previousHome);
    }

    // --- helpers ---

    private void launch(String scene) {
        if (world != null) world.stop();
        world = new SceneWorld();
        assertFalse(world.loadSceneFromFile(scene).failed());
        world.play();
        frames(2);
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) world.update(TestEntities.FRAME);
    }

    private Entity find(String name) { return world.findEntityByName(name); }

    private void destroy(String name) {
        world.requestEntityDestroy(find(name));
        frames(1);
    }

    private void goTo(String scene) {
        world.requestSceneLoad(scene);
        frames(2);
    }

    private void loadSlot(String slot) {
        assertTrue(SaveGame.load(slot));
        frames(2); // the level swaps at the end of the first frame; scripts start on the next one
    }

    private static Object field(EntityScript s, String name) throws ReflectiveOperationException {
        Field f = s.getClass().getField(name);
        return f.get(s);
    }

    private static void setField(EntityScript s, String name, Object value) throws ReflectiveOperationException {
        s.getClass().getField(name).set(s, value);
    }

    private EntityScript counter() {
        EntityScript s = find("player").getComponent(ScriptComponent.class).getScript();
        assertNotNull(s, "Counter script compiled and started");
        return s;
    }

    // --- tests ---

    @Test
    void loadingPutsTheLevelBackAsItWasSaved() throws Exception {
        launch(levelA);
        assertEquals(1, field(counter(), "coins"), "onStart ran");
        find("crate").getTransform().posX = 4.5f;
        find("light").getComponent(Light.class).intensity = 0.25f;
        destroy("key");
        Entity orb = world.instantiatePrefabRuntime(prefab);
        orb.getTransform().posZ = -3f;
        String orbId = orb.id;
        setField(counter(), "coins", 7);
        setField(counter(), "mood", "angry");
        setField(counter(), "lastSeen", new float[]{1.5f, -2f});
        setField(counter(), "note", "remember me");
        SaveGame.values().set("met_ferryman", true);
        assertTrue(SaveGame.save("slot1", "Docks"));

        // Play on and change everything again
        find("crate").getTransform().posX = 99f;
        destroy("orb");
        setField(counter(), "coins", 0);
        SaveGame.values().set("met_ferryman", false);

        loadSlot("slot1");
        assertEquals(4.5f, find("crate").getTransform().posX);
        assertEquals(0.25f, find("light").getComponent(Light.class).intensity);
        assertNull(find("key"), "destroyed entity stays destroyed");
        Entity orbBack = world.findEntityById(orbId);
        assertNotNull(orbBack, "spawned prefab comes back under its id");
        assertEquals(-3f, orbBack.getTransform().posZ);
        Entity glowBack = find("glow");
        assertNotNull(glowBack);
        assertEquals(orbId, glowBack.parentId, "prefab hierarchy comes back");
        assertEquals(7, field(counter(), "coins"), "@Save field wins over onStart");
        assertEquals("angry", field(counter(), "mood"));
        assertEquals(-2f, ((float[]) field(counter(), "lastSeen"))[1]);
        assertEquals("remember me", field(counter(), "note"), "onSave / onLoad record");
        assertEquals(1, field(counter(), "loads"));
        assertTrue(SaveGame.values().getBool("met_ferryman", false));
    }

    @Test
    void aSaveSurvivesQuittingAndRelaunching() throws Exception {
        launch(levelA);
        find("crate").getTransform().posY = 2f;
        destroy("key");
        setField(counter(), "coins", 12);
        assertTrue(SaveGame.save("slot1"));
        world.stop(); // quit
        world = null;

        launch(levelA); // relaunch: a fresh world from the scene file
        assertNotNull(find("key"));
        loadSlot("slot1");
        assertEquals(2f, find("crate").getTransform().posY);
        assertNull(find("key"));
        assertEquals(12, field(counter(), "coins"));
        assertTrue(Files.isRegularFile(dir.resolve("appdata/Proj/Saves/slot1.rsave")), "slot in the OS save folder");
    }

    @Test
    void levelsRememberWhatHappenedWhenThePlayerReturns() {
        launch(levelA);
        destroy("key");
        goTo(levelB);
        assertNotNull(find("b_floor"));
        goTo(levelA);
        assertNull(find("key"), "level A remembered");
        assertNotNull(find("crate"));
    }

    @Test
    void withRememberLevelsOffALevelResetsWhenLeft() {
        launch(levelA);
        SaveGame.setRememberLevels(false);
        destroy("key");
        goTo(levelB);
        goTo(levelA);
        assertNotNull(find("key"));
    }

    @Test
    void theProjectSettingTurnsLevelMemoryOff() throws IOException {
        ProjectSettings s = ProjectWorkspace.readSettings(dir.resolve("Proj").toFile());
        s.rememberLevels = false;
        ProjectWorkspace.writeSettings(dir.resolve("Proj").toFile(), s);
        launch(levelA);
        assertFalse(SaveGame.isRememberingLevels());
        destroy("key");
        goTo(levelB);
        goTo(levelA);
        assertNotNull(find("key"));
    }

    @Test
    void reloadingTheCurrentLevelRestartsIt() {
        launch(levelA);
        destroy("key");
        goTo(levelA);
        assertNotNull(find("key"));
    }

    @Test
    void aSaveCarriesLevelsThePlayerLeft() {
        launch(levelA);
        destroy("key");
        goTo(levelB);
        assertTrue(SaveGame.save("slot1"));
        world.stop();
        world = null;

        launch(levelA);
        loadSlot("slot1");
        assertNotNull(find("b_floor"), "load switched to the saved level");
        goTo(levelA);
        assertNull(find("key"), "level A's state came from the save");
    }

    @Test
    void anOldSaveLoadsIntoAPatchedLevel() {
        launch(levelA);
        find("crate").getTransform().posX = 3f;
        destroy("key");
        assertTrue(SaveGame.save("slot1"));
        world.stop();
        world = null;

        writeLevelA(true); // the game was updated: level A gained a lamp
        launch(levelA);
        loadSlot("slot1");
        assertNotNull(find("lamp"), "new content in the patched level appears");
        assertEquals(3f, find("crate").getTransform().posX);
        assertNull(find("key"));
    }

    @Test
    void newGameForgetsEverything() {
        launch(levelA);
        destroy("key");
        SaveGame.values().set("chapter", 3);
        goTo(levelB);
        assertTrue(SaveGame.newGame(levelA));
        frames(2);
        assertNotNull(find("key"));
        assertEquals(0, SaveGame.values().getInt("chapter", 0));
    }

    @Test
    void aCheckpointSavesOnceAndStaysUsedAfterLoading() {
        launch(levelA);
        Entity gate = find("crate");
        BoxCollider trigger = new BoxCollider();
        trigger.isTrigger = true;
        gate.addComponent(trigger);
        Checkpoint cp = new Checkpoint();
        cp.slot = "auto";
        cp.displayName = "Crate Gate";
        gate.addComponent(cp);
        Entity runner = find("player");
        runner.addComponent(new CharacterControllerComponent());

        ScriptEngine.dispatchTriggerEnter(gate, runner);
        frames(1);
        assertTrue(SaveGame.exists("auto"));
        assertEquals("Crate Gate", SaveGame.getSlotInfo("auto").displayName());
        long first = SaveGame.getSlotInfo("auto").savedAtMillis();
        ScriptEngine.dispatchTriggerEnter(gate, find("key"));
        frames(1);
        assertEquals(first, SaveGame.getSlotInfo("auto").savedAtMillis(), "the key is not the player");

        loadSlot("auto");
        Checkpoint loaded = find("crate").getComponent(Checkpoint.class);
        assertNotNull(loaded, "component added during play comes back");
        assertTrue(loaded.reached, "used checkpoint stays used");
    }

    @Test
    void aDamagedSaveIsRefusedAndTheLevelKeepsRunning() throws IOException {
        launch(levelA);
        assertTrue(SaveGame.save("slot1"));
        Path file = dir.resolve("appdata/Proj/Saves/slot1.rsave");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(file, bytes);
        destroy("key");
        assertFalse(SaveGame.load("slot1"));
        frames(2);
        assertNull(find("key"), "nothing was reloaded");
        assertNull(SaveGame.getSlotInfo("slot1"));
    }

    @Test
    void slotsAreListedNewestFirstAndCanBeDeleted() throws InterruptedException {
        launch(levelA);
        assertTrue(SaveGame.save("first", "One"));
        Thread.sleep(5);
        assertTrue(SaveGame.save("second", "Two"));
        List<SaveGame.SlotInfo> slots = SaveGame.listSlots();
        assertEquals(2, slots.size());
        assertEquals("second", slots.get(0).slot());
        assertEquals("Assets/Scenes/A.rscene", slots.get(0).scenePath());
        assertTrue(SaveGame.delete("first"));
        assertFalse(SaveGame.exists("first"));
    }

    @Test
    void stopForgetsTheSessionButKeepsFiles() {
        launch(levelA);
        SaveGame.values().set("x", 1);
        assertTrue(SaveGame.save("slot1"));
        world.stop();
        assertEquals(0, SaveGame.values().getInt("x", 0));
        assertTrue(SaveGame.exists("slot1"));
        assertFalse(SaveGame.save("slot2"), "nothing running");
    }
}
