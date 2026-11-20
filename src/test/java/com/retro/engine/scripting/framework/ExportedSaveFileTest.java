package com.retro.engine.scripting.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.core.UserFolders;
import com.retro.engine.editor.StandaloneExporter;
import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ExportedSaveFileTest {

    @TempDir Path dir;
    private Path dataRoot;
    private String previousHome;

    @BeforeEach
    void redirectUserFolders() throws IOException {
        dataRoot = Files.createDirectories(dir.resolve("appdata"));
        System.setProperty(UserFolders.OVERRIDE_PROPERTY, dataRoot.toString());
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(dir.resolve("home")).toString());
    }

    @AfterEach
    void unbindProject() {
        ScriptEngine.setProjectAssetsRoot(null);
        System.clearProperty(UserFolders.OVERRIDE_PROPERTY);
        System.setProperty("user.home", previousHome);
    }

    private String savePathFor(String parent, String folder, String projectName) throws IOException {
        Path project = Files.createDirectories(dir.resolve(parent).resolve(folder));
        ProjectWorkspace.writeSettings(project.toFile(), new ProjectSettings(projectName, ""));
        ScriptEngine.setProjectAssetsRoot(project.resolve("Assets").toString());
        return ScriptSaveData.getSaveFilePath();
    }

    @Test
    void twoExportedGamesKeepSeparateSaves() throws IOException {
        String first = savePathFor("a", StandaloneExporter.GAME_DATA_DIR, "First Game");
        String second = savePathFor("b", StandaloneExporter.GAME_DATA_DIR, "Second Game");
        assertNotEquals(first, second);
        assertEquals(dataRoot.resolve("First Game").resolve("Saves").resolve("save.properties").toString(), first);
    }

    @Test
    void editorProjectsStillUseTheirFolderName() throws IOException {
        String path = savePathFor("c", "MyFolder", "Another Name");
        assertEquals("MyFolder", new File(path).getParentFile().getParentFile().getName());
        assertEquals(new File(path).getParent(), ScriptSaveData.getSaveFolder());
    }

    @Test
    void oldSaveIsCopiedToTheNewFolder() throws IOException {
        Path legacy = Files.createDirectories(dir.resolve("home/.retroengine/saves")).resolve("Old_Game.properties");
        Files.writeString(legacy, "best_lap=42.5\n");
        String path = savePathFor("d", StandaloneExporter.GAME_DATA_DIR, "Old Game");
        assertEquals(42.5f, ScriptSaveData.getFloat("best_lap", 0f));
        assertTrue(new File(path).isFile(), "old save copied to the OS folder");
        assertTrue(Files.isRegularFile(legacy), "old save left in place");
    }

    @Test
    void folderNamesAreValidOnEveryOs() {
        assertEquals("Hollow Beacon", UserFolders.folderName("Hollow Beacon"));
        assertEquals("A_B_C", UserFolders.folderName("A:B?C"));
        assertEquals("Trail", UserFolders.folderName("Trail. "));
        assertEquals("CON_", UserFolders.folderName("con").toUpperCase());
        assertEquals("Game", UserFolders.folderName("  "));
    }
}
