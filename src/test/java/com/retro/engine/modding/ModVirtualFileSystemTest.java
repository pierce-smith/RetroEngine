package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class ModVirtualFileSystemTest {

    @TempDir Path dir;

    private File base, lowMod, highMod;
    private ModVirtualFileSystem vfs;

    private void write(File root, String relative) throws IOException {
        Path file = root.toPath().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, relative);
    }

    @BeforeEach
    void mountTwoMods() throws IOException {
        base = dir.resolve("Assets").toFile();
        lowMod = dir.resolve("Mods/low").toFile();
        highMod = dir.resolve("Mods/high").toFile();
        write(base, "Textures/Wall.png");
        write(base, "Textures/Floor.png");
        write(new File(lowMod, "Assets"), "Textures/Wall.png");
        write(new File(lowMod, "Assets"), "Textures/OnlyLow.png");
        write(new File(highMod, "Assets"), "Textures/Wall.png");
        write(highMod, "Extras/Secret.txt");
        write(dir.toFile(), "Outside.txt");

        // Layers go in bottom-up, the way ModLoader builds them
        vfs = new ModVirtualFileSystem(base, List.of(
                new ModVirtualFileSystem.Layer("low", 100, new File(lowMod, "Assets")),
                new ModVirtualFileSystem.Layer("high", 200, new File(highMod, "Assets"))),
                Map.of("low", lowMod, "high", highMod));
    }

    private File inHigh(String relative) {
        return new File(new File(highMod, "Assets"), relative);
    }

    @Test
    void highestPriorityLayerWins() {
        assertEquals(inHigh("Textures/Wall.png").getAbsolutePath(), vfs.resolveOverride("Assets/Textures/Wall.png"));
        assertEquals("high", vfs.layers().get(0).modId());
    }

    @Test
    void lowerLayersStillProvideWhatHigherOnesLack() {
        assertEquals(new File(lowMod, "Assets/Textures/OnlyLow.png").getAbsolutePath(), vfs.resolveOverride("Assets/Textures/OnlyLow.png"));
    }

    @Test
    void pathsNoModOverridesComeBackUnchanged() {
        assertEquals("Assets/Textures/Floor.png", vfs.resolveOverride("Assets/Textures/Floor.png"));
    }

    @Test
    void everyPathSpellingFindsTheOverride() {
        String expected = inHigh("Textures/Wall.png").getAbsolutePath();
        assertEquals(expected, vfs.resolveOverride("Textures/Wall.png"));
        assertEquals(expected, vfs.resolveOverride("./Assets/Textures/Wall.png"));
        assertEquals(expected, vfs.resolveOverride("Assets\\Textures\\Wall.png"));
        assertEquals(expected, vfs.resolveOverride(new File(base, "Textures/Wall.png").getAbsolutePath()));
    }

    @Test
    void pathsOutsideAssetsAreNotOverridden() {
        String outside = dir.resolve("Outside.txt").toString();
        assertEquals(outside, vfs.resolveOverride(outside));
        assertEquals("../Outside.txt", vfs.resolveOverride("../Outside.txt"));
    }

    @Test
    void nullAndEmptyPathsPassThrough() {
        assertNull(vfs.resolveOverride(null));
        assertEquals("", vfs.resolveOverride(""));
    }

    @Test
    void modUrisResolveInsideTheirOwnFolder() throws IOException {
        File f = vfs.resolveModUri("mod://high/Extras/Secret.txt");
        assertNotNull(f);
        assertEquals(new File(highMod, "Extras/Secret.txt").getCanonicalFile(), f);
    }

    @Test
    void modUrisCannotEscapeTheModFolder() {
        assertNull(vfs.resolveModUri("mod://high/../../Outside.txt"));
    }

    @Test
    void unknownOrMalformedModUrisAreRejected() {
        assertNull(vfs.resolveModUri("mod://nobody/File.txt"));
        assertNull(vfs.resolveModUri("mod://high"));
        assertEquals("mod://nobody/File.txt", vfs.resolveOverride("mod://nobody/File.txt"));
    }

    @Test
    void emptyStackHasNoOverlays() {
        ModVirtualFileSystem empty = ModVirtualFileSystem.empty(base);
        assertFalse(empty.hasOverlays());
        assertEquals("Assets/Textures/Wall.png", empty.resolveOverride("Assets/Textures/Wall.png"));
        assertTrue(vfs.hasOverlays());
    }
}
