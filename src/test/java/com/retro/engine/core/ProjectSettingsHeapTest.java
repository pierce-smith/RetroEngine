package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.graphics.TextureFiltering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

class ProjectSettingsHeapTest {

    @TempDir File dir;

    @Test
    void maxHeapSurvivesASaveAndLoad() throws IOException {
        ProjectSettings settings = new ProjectSettings("Game", "");
        settings.maxHeapMb = 4096;
        settings.write(dir);
        assertEquals(4096, ProjectSettings.read(dir).maxHeapMb);
    }

    @Test
    void textureFilteringSurvivesASaveAndLoad() throws IOException {
        ProjectSettings settings = new ProjectSettings("Game", "");
        settings.textureFiltering = TextureFiltering.BILINEAR;
        settings.write(dir);
        assertEquals(TextureFiltering.BILINEAR, ProjectSettings.read(dir).textureFiltering);
    }

    @Test
    void framePacingSurvivesASaveAndLoad() throws IOException {
        ProjectSettings settings = new ProjectSettings("Game", "");
        settings.vsync = false;
        settings.frameRateCap = 144;
        settings.write(dir);
        ProjectSettings loaded = ProjectSettings.read(dir);
        assertEquals(false, loaded.vsync);
        assertEquals(144, loaded.frameRateCap);
    }

    @Test
    void frameRateCapIsClampedButZeroMeansNone() {
        assertEquals(0, ProjectSettings.sanitizeFrameRateCap(0));
        assertEquals(0, ProjectSettings.sanitizeFrameRateCap(-5));
        assertEquals(ProjectSettings.MIN_FRAME_RATE_CAP, ProjectSettings.sanitizeFrameRateCap(5));
        assertEquals(ProjectSettings.MAX_FRAME_RATE_CAP, ProjectSettings.sanitizeFrameRateCap(100_000));
    }

    @Test
    void outOfRangeValuesAreClamped() {
        ProjectSettings settings = new ProjectSettings("Game", "");
        settings.maxHeapMb = 1;
        settings.sanitize();
        assertEquals(ProjectSettings.MIN_MAX_HEAP_MB, settings.maxHeapMb);
        settings.maxHeapMb = Integer.MAX_VALUE;
        settings.sanitize();
        assertEquals(ProjectSettings.MAX_MAX_HEAP_MB, settings.maxHeapMb);
    }

    @Test
    void olderFilesGetTheDefault() throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.toPath().resolve(ProjectFile.LEGACY_SETTINGS_FILE)))) {
            out.writeInt(ProjectSettings.MAGIC);
            out.writeInt(1);
            out.writeUTF("Old Game");
            out.writeUTF("0.1");
            out.writeLong(0L);
            out.writeLong(0L);
            out.writeUTF("");
        }
        ProjectSettings loaded = ProjectSettings.read(dir);
        assertEquals(ProjectSettings.DEFAULT_MAX_HEAP_MB, loaded.maxHeapMb);
        assertEquals(true, loaded.vsync);
        assertEquals(0, loaded.frameRateCap);
    }
}
