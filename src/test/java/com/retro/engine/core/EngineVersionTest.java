package com.retro.engine.core;

import com.retro.engine.modding.ModLoader;
import com.retro.engine.modding.ModManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The engine version comes from Gradle's generated properties, and mods for another major version are refused. */
class EngineVersionTest {

    @TempDir Path project;

    @Test
    void versionComesFromTheGradleBuild() {
        assertNotNull(EngineVersion.class.getResource(EngineVersion.RESOURCE), "generateEngineVersion output is not on the classpath");
        assertNotEquals(EngineVersion.UNKNOWN_VERSION, EngineVersion.VERSION);
        assertNotEquals(EngineVersion.UNKNOWN_BUILD, EngineVersion.BUILD_ID);
        String expected = System.getProperty("retro.test.engineVersion");
        if (expected != null) assertEquals(expected, EngineVersion.VERSION);
        assertSame(EngineVersion.VERSION, ProjectWorkspace.ENGINE_VERSION);
        assertTrue(EngineVersion.MAJOR >= 1, EngineVersion.VERSION);
        assertTrue(EngineVersion.describe().contains(EngineVersion.BUILD_ID));
    }

    @Test
    void parsesVersionParts() {
        assertEquals(1, EngineVersion.major("1.0.0-SNAPSHOT"));
        assertEquals(12, EngineVersion.major("12.3"));
        assertEquals(0, EngineVersion.major("beta"));
        assertEquals(0, EngineVersion.major(null));
        assertArrayEquals(new int[] {1, 2, 3}, EngineVersion.parts("1.2.3-beta.5"));
        assertArrayEquals(new int[] {2, 0, 0}, EngineVersion.parts("2"));
        assertArrayEquals(new int[] {1, 0, 7}, EngineVersion.parts("1.x.7"));
    }

    @Test
    void compatibilityIsByMajorVersion() {
        int major = EngineVersion.MAJOR;
        assertTrue(EngineVersion.isCompatible(""));
        assertTrue(EngineVersion.isCompatible(null));
        assertTrue(EngineVersion.isCompatible(major + ".0.0"));
        assertTrue(EngineVersion.isCompatible(major + ".9.4-beta"));
        assertFalse(EngineVersion.isCompatible((major + 1) + ".0.0"));
        assertFalse(EngineVersion.isCompatible((major - 1) + ".5.0"));
    }

    @Test
    void modsForAnotherMajorVersionAreSkipped() throws IOException {
        int major = EngineVersion.MAJOR;
        writeMod("same_major", major + ".4.0");
        writeMod("no_target", null);
        writeMod("next_major", (major + 1) + ".0.0");
        writeMod("old_major", (major - 1) + ".0.0");

        List<String> ids = ModLoader.discoverInstalled(project.toFile()).stream().map(m -> m.id).toList();
        assertEquals(List.of("no_target", "same_major"), ids);
    }

    private void writeMod(String id, String targetEngine) throws IOException {
        Path dir = project.resolve(ModLoader.MODS_DIR).resolve(id);
        Files.createDirectories(dir);
        String target = targetEngine == null ? "" : ", \"target_engine\": \"" + targetEngine + "\"";
        Files.writeString(dir.resolve(ModManifest.FILE_NAME), "{ \"mod_id\": \"" + id + "\", \"name\": \"" + id + "\"" + target + " }");
    }
}
