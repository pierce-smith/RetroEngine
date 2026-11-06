package com.retro.engine.scripting.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.editor.StandaloneExporter;
import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ExportedSaveFileTest {

    @TempDir Path dir;

    @AfterEach
    void unbindProject() {
        ScriptEngine.setProjectAssetsRoot(null);
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
        assertEquals("First_Game.properties", new File(first).getName());
    }

    @Test
    void editorProjectsStillUseTheirFolderName() throws IOException {
        assertEquals("MyFolder.properties", new File(savePathFor("c", "MyFolder", "Another Name")).getName());
    }
}
