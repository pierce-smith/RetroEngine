package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.ecs.serialization.SceneBackups;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.editor.AutoSaveSystem;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.templates.TemplateManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The user-facing promises: a bad file never costs you the scene you have open, or the file itself. */
class SceneWorldDataSafetyTest {

    @TempDir Path dir;

    private final SceneWorld world = new SceneWorld();
    private final List<String> errors = new ArrayList<>();
    private Path good, damaged;
    private byte[] damagedBytes;

    @BeforeEach
    void writeScenes() throws IOException {
        world.setDataErrorListener(errors::add);
        good = dir.resolve("good.rscene");
        damaged = dir.resolve("damaged.rscene");
        assertTrue(SceneSerializer.saveScene(good.toString(), List.of(new Entity("keep", "Keep Me", null))));
        assertTrue(SceneSerializer.saveScene(damaged.toString(), List.of(new Entity("lost", "Precious Work", null))));
        damagedBytes = Files.readAllBytes(damaged);
        damagedBytes[damagedBytes.length - 3] ^= 0x40;
        Files.write(damaged, damagedBytes);
        world.loadSceneFromFile(good.toString());
    }

    private String firstEntityName() {
        return world.getSceneEntities().get(0).name;
    }

    @Test
    void openingADamagedSceneKeepsTheOpenSceneAndTellsTheUser() {
        String activeBefore = world.getActiveScenePath();
        assertTrue(world.loadSceneFromFile(damaged.toString()).failed());

        assertEquals("Keep Me", firstEntityName());
        assertEquals(activeBefore, world.getActiveScenePath());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("damaged.rscene"), errors.get(0));
    }

    @Test
    void aSceneThatFailedToLoadCannotBeSavedOver() throws IOException {
        world.loadSceneFromFile(damaged.toString());
        errors.clear();

        world.saveActiveScene(damaged.toString(), List.of());

        assertArrayEquals(damagedBytes, Files.readAllBytes(damaged));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Not saved"), errors.get(0));
    }

    @Test
    void savingUnderANewNameStillWorksAfterAFailedLoad() {
        world.loadSceneFromFile(damaged.toString());
        Path rescue = dir.resolve("rescue.rscene");
        world.saveActiveScene(rescue.toString(), List.of());
        assertTrue(Files.isRegularFile(rescue));
    }

    @Test
    void repairingTheFileLiftsTheProtection() throws IOException {
        world.loadSceneFromFile(damaged.toString());
        Files.copy(good, damaged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertFalse(world.loadSceneFromFile(damaged.toString()).failed());
        errors.clear();

        world.getSceneEntities().get(0).name = "Edited";
        world.saveActiveScene(damaged.toString(), List.of());

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("Edited", SceneSerializer.loadSceneData(damaged.toString()).entities().get(0).name);
    }

    public static class ExplodingComponent extends Component {
        @Override public String getComponentName() { return "Exploding"; }
        @Override public void onBeforeSerialize() { throw new IllegalStateException("save failed"); }
    }

    @Test
    void aFailedSaveIsReportedAndTheFileIsUntouched() throws IOException {
        byte[] before = Files.readAllBytes(good);
        String activeBefore = world.getActiveScenePath();
        world.getSceneEntities().get(0).addComponent(new ExplodingComponent());

        world.saveActiveScene(good.toString(), List.of());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Could not save"), errors.get(0));
        assertArrayEquals(before, Files.readAllBytes(good));
        assertEquals(activeBefore, world.getActiveScenePath());
    }

    @Test
    void savingKeepsThePreviousVersionAsABackup() throws IOException {
        byte[] before = Files.readAllBytes(good);
        world.getSceneEntities().get(0).name = "Second Version";
        world.saveActiveScene(good.toString(), List.of());

        assertArrayEquals(before, Files.readAllBytes(SceneBackups.newestFor(good.toFile()).toPath()));
        assertEquals("Second Version", SceneSerializer.loadSceneData(good.toString()).entities().get(0).name);
    }

    @Test
    void playModeTransitionToADamagedSceneKeepsTheGameRunning() {
        world.play();
        world.update(1.0f / 60.0f);
        world.requestSceneLoad(damaged.toString());
        world.update(1.0f / 60.0f);

        assertEquals(PlayState.SIMULATING, world.getPlayState());
        assertEquals("Keep Me", firstEntityName());
        world.stop();
    }

    @Test
    void playModeTransitionToAGoodSceneStillWorks() throws IOException {
        Path next = dir.resolve("next.rscene");
        assertTrue(SceneSerializer.saveScene(next.toString(), List.of(new Entity("n", "Next Level", null))));
        world.play();
        world.requestSceneLoad(next.toString());
        world.update(1.0f / 60.0f);

        assertEquals(PlayState.SIMULATING, world.getPlayState());
        assertEquals("Next Level", firstEntityName());
        world.stop();
        assertEquals("Keep Me", firstEntityName()); // Stop returns to the scene Play started in
    }

    private static void forceAutosave(AutoSaveSystem autoSave) throws ReflectiveOperationException {
        Field due = AutoSaveSystem.class.getDeclaredField("saveDue");
        due.setAccessible(true);
        ((AtomicBoolean) due.get(autoSave)).set(true);
        autoSave.markDirty();
        autoSave.tick();
    }

    @Test
    void autosaveKeepsARotatingHistory() throws Exception {
        EditorWorkspace workspace = new EditorWorkspace();
        File project = ProjectWorkspace.createProject(workspace, dir.toFile(), "Auto", TemplateManager.STARTER_CONTENT);
        AutoSaveSystem autoSave = workspace.getAutoSaveSystem();
        File scene = new File(project, ProjectWorkspace.STARTER_SCENE);
        byte[] sceneBefore = Files.readAllBytes(scene.toPath());
        Entity first = workspace.getSceneManager().getSceneEntities().get(0);

        first.name = "Autosave One";
        forceAutosave(autoSave);
        first.name = "Autosave Two";
        forceAutosave(autoSave);

        File newest = SceneBackups.newestFor(scene);
        File older = new File(scene.getPath() + ".1.bak");
        assertEquals("Autosave Two", SceneSerializer.loadSceneData(newest.getPath()).entities().get(0).name);
        assertEquals("Autosave One", SceneSerializer.loadSceneData(older.getPath()).entities().get(0).name);
        assertArrayEquals(sceneBefore, Files.readAllBytes(scene.toPath()));
    }
}
