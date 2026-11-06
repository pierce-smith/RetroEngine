package com.retro.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class AudioClipPathTest {

    @TempDir Path assets;
    private Path clip;

    @BeforeEach
    void bindProject() throws IOException {
        clip = assets.resolve("Audio/shot.ogg");
        Files.createDirectories(clip.getParent());
        Files.write(clip, new byte[] { 1 });
        ScriptEngine.setProjectAssetsRoot(assets.toString());
    }

    @AfterEach
    void unbindProject() {
        ScriptEngine.setProjectAssetsRoot(null);
    }

    @Test
    void documentedAssetsPathResolvesInTheProject() {
        assertEquals(clip.toString(), AudioEngine.resolveClipPath("Assets/Audio/shot.ogg"));
    }

    @Test
    void assetsRelativePathResolvesInTheProject() {
        assertEquals(clip.toString(), AudioEngine.resolveClipPath("Audio/shot.ogg"));
    }

    @Test
    void absolutePathIsLeftAlone() {
        assertEquals(clip.toString(), AudioEngine.resolveClipPath(clip.toString()));
    }

    @Test
    void missingClipIsLeftAloneSoTheErrorNamesWhatTheScriptAskedFor() {
        assertEquals("Audio/nope.ogg", AudioEngine.resolveClipPath("Audio/nope.ogg"));
    }
}
