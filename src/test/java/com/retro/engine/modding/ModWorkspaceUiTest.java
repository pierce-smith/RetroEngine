package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.ModWorkspaceBanner;
import com.retro.engine.editor.templates.TemplateManager;
import com.retro.engine.modding.ModWorkspace.Change;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The mod workspace's visible differences: banner, orange accent, NEW / EDITED tags, and imports landing in the mod. */
class ModWorkspaceUiTest {

    @TempDir Path dir;
    private EditorWorkspace workspace;
    private File project, mod;

    @BeforeEach
    void openTheExampleModWorkspace() throws IOException {
        workspace = new EditorWorkspace();
        project = ProjectWorkspace.createProject(workspace, dir.toFile(), "ModUi", TemplateManager.STARTER_CONTENT);
        mod = new File(project, ModLoader.MODS_DIR + "/example_mod");
        copyTree(Path.of("ModSDK/StarterPack/example_mod"), mod.toPath());
        ModWorkspace.open(workspace, mod);
    }

    @AfterEach
    void closeIt() throws IOException {
        if (ModWorkspace.isActive()) ModWorkspace.close(workspace);
        ModLoader.unloadActive();
        EditorTheme.setModWorkspaceTint(false);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
    }

    private SceneWorld world() { return workspace.getSceneManager(); }

    @Test
    void theBannerShowsAndPushesThePanelsDown() {
        assertTrue(ModWorkspaceBanner.isVisible());
        assertEquals(DockSpace.AREA_Y1 - ModWorkspaceBanner.HEIGHT, DockSpace.areaTop(), 1e-6);
    }

    @Test
    void theAccentTurnsOrangeAndBackToBlue() {
        EditorTheme.setModWorkspaceTint(ModWorkspace.isActive());
        assertEquals(EditorTheme.MOD_ACCENT, EditorTheme.ACCENT);
        EditorTheme.setModWorkspaceTint(false);
        assertEquals(EditorTheme.SYSTEM_BLUE, EditorTheme.ACCENT);
    }

    @Test
    void entitiesTheModAddsOrChangesAreTagged() {
        assertNotNull(world().findEntityById("example_mod.floating_cube"));
        assertEquals(Change.ADDED, ModWorkspace.changeOf("example_mod.floating_cube"), "added and then edited still reads NEW");
        assertEquals(Change.EDITED, ModWorkspace.changeOf(world().findEntityByName("Cube").id));
        assertNull(ModWorkspace.changeOf(world().findEntityByName("Ground").id), "untouched vanilla entity");
    }

    @Test
    void savingTagsWhatYouJustChanged() {
        Entity ground = world().findEntityByName("Ground");
        ground.getTransform().posY = 0.5f;
        assertTrue(ModWorkspace.saveScene(world(), world().getActiveScenePath(), workspace.getAssetDatabase()));
        assertEquals(Change.EDITED, ModWorkspace.changeOf(ground.id));
        assertTrue(new File(mod, "Patches").listFiles((d, n) -> n.endsWith(ModWorkspace.EDITOR_PATCH_SUFFIX)).length == 1);
    }

    @Test
    void importsGoIntoTheModAndNeverShadowAGameFile() throws IOException {
        Files.writeString(project.toPath().resolve("Assets/notes.txt"), "vanilla");
        Path outside = Files.createDirectories(dir.resolve("outside")).resolve("notes.txt");
        Files.writeString(outside, "mod");

        List<String> imported = workspace.getAssetDatabase().onFilesDropped(List.of(outside.toString()));
        File landed = new File(imported.get(0));
        assertTrue(landed.isFile());
        assertTrue(landed.getCanonicalPath().startsWith(new File(mod, "Assets").getCanonicalPath()), landed.toString());
        assertEquals("notes (2).txt", landed.getName(), "a vanilla Assets/notes.txt exists, so the mod copy may not take its name");
        assertEquals("vanilla", Files.readString(project.toPath().resolve("Assets/notes.txt")));
    }

    @Test
    void closingRestoresTheNormalEditor() throws IOException {
        ModWorkspace.close(workspace);
        assertFalse(ModWorkspaceBanner.isVisible());
        assertEquals(DockSpace.AREA_Y1, DockSpace.areaTop(), 1e-6);
        assertNull(ModWorkspace.changeOf("example_mod.floating_cube"));

        Path outside = Files.createDirectories(dir.resolve("outside2")).resolve("plain.txt");
        Files.writeString(outside, "x");
        File landed = new File(workspace.getAssetDatabase().onFilesDropped(List.of(outside.toString())).get(0));
        assertTrue(landed.getCanonicalPath().startsWith(new File(project, "Assets").getCanonicalPath()), "back to the game's Assets/");
    }
}
