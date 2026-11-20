package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneSettings;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.serialization.SceneSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A mod changes a vanilla scene's Remember When Left through its .rpatch, written and loaded the way the editor does it. */
class SceneSettingsPatchTest {

    @TempDir Path project;
    private File scene;
    private Path modRoot;

    @BeforeEach
    void projectWithAMod() throws IOException {
        Path scenes = Files.createDirectories(project.resolve("Assets/Scenes"));
        scene = scenes.resolve("Main.rscene").toFile();
        assertTrue(SceneSerializer.saveScene(scene.getPath(), new ArrayList<>(List.of(new Entity("crate", "Crate", null))), new ArrayList<>(), null));
        modRoot = Files.createDirectories(project.resolve(ModLoader.MODS_DIR).resolve("my_mod"));
        Files.writeString(modRoot.resolve(ModManifest.FILE_NAME), "{ \"mod_id\": \"my_mod\", \"name\": \"My Mod\" }");
    }

    @AfterEach
    void unload() {
        ModLoader.unloadActive();
    }

    private SceneSettings loadScene() {
        SceneWorld world = new SceneWorld();
        assertFalse(world.loadSceneFromFile(scene.getPath()).failed());
        return world.getSceneSettings();
    }

    @Test
    void aShippedPatchTurnsRememberOffWhenTheSceneLoads() throws IOException {
        Path patches = Files.createDirectories(modRoot.resolve("Patches"));
        Files.writeString(patches.resolve("main.rpatch"), """
                { "format": "rpatch", "version": 1, "target_scene": "Assets/Scenes/Main.rscene",
                  "operations": [ { "op": "MODIFY_SCENE", "properties": { "rememberWhenLeft": false } } ] }
                """);
        assertNotNull(ModLoader.loadForProject(project.toFile()));
        assertFalse(loadScene().rememberWhenLeft);

        ModLoader.unloadActive();
        assertTrue(loadScene().rememberWhenLeft, "the vanilla scene file itself is untouched");
    }

    @Test
    void theModWorkspaceWritesTheChangeAndItLoadsBack() throws Exception {
        ModLoader loader = ModLoader.loadForProject(project.toFile());
        assertNotNull(loader);
        SceneSettings edited = new SceneSettings();
        edited.rememberWhenLeft = false;
        List<Entity> current = new ArrayList<>(List.of(new Entity("crate", "Crate", null)));

        File written = ModWorkspace.writeScenePatch(loader, ModManifest.read(modRoot.toFile()), scene, current, edited, null);
        assertNotNull(written, "a settings-only change still writes a patch");
        assertTrue(Files.readString(written.toPath()).contains("MODIFY_SCENE"));
        assertFalse(loadScene().rememberWhenLeft);

        // Switching it back to the vanilla value leaves nothing to patch
        edited.rememberWhenLeft = true;
        assertTrue(ModWorkspace.writeScenePatch(loader, ModManifest.read(modRoot.toFile()), scene, current, edited, null) == null);
        assertFalse(written.exists());
        assertTrue(loadScene().rememberWhenLeft);
    }
}
