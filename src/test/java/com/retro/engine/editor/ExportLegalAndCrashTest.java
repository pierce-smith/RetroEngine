package com.retro.engine.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.editor.StandaloneExporter.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

class ExportLegalAndCrashTest {

    @TempDir File dir;

    @Test
    void exportsCarryEveryNoticeAndTheFullLicense() throws IOException {
        StandaloneExporter.writeLegalNotices(dir);
        String text = Files.readString(new File(dir, StandaloneExporter.LEGAL_NOTICES_FILE).toPath());
        for (String name : List.of("LWJGL", "GLFW", "SDL", "Assimp", "stb", "tinyfiledialogs", "Janino", "MoltenVK",
                "SDL_GameControllerDB", "OpenAL Soft", "OpenJDK", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
                "END OF TERMS AND CONDITIONS")) {
            assertTrue(text.contains(name), "missing: " + name);
        }
    }

    @Test
    void crashFilesGoToEachPlatformsLogFolder() {
        assertTrue(joined(Platform.MACOS).contains("-XX:ErrorFile=$HOME/Library/Logs/RetroEngine/Game_crash_%p.log"));
        assertTrue(joined(Platform.WINDOWS).contains("-XX:ErrorFile=$LOCALAPPDATA/RetroEngine/logs/Game_crash_%p.log"));
        assertTrue(joined(Platform.LINUX).contains("-XX:HeapDumpPath=$HOME/.local/state/retroengine/Game_oom.hprof"));
    }

    private static String joined(Platform platform) {
        return String.join(" ", StandaloneExporter.crashFileOptions(platform, "Game"));
    }
}
