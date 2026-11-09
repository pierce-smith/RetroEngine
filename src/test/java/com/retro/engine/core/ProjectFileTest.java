package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.ecs.serialization.PrefabSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

class ProjectFileTest {

    @TempDir File dir;

    @Test
    void settingsLiveInOneRprojNamedAfterTheProject() throws IOException {
        new ProjectSettings("My Game", "Assets/Scenes/Main.rscene").write(dir);
        File file = ProjectFile.find(dir);
        assertNotNull(file);
        assertEquals("My Game.rproj", file.getName());
        assertEquals("Assets/Scenes/Main.rscene", ProjectSettings.read(dir).startupScene);
        assertTrue(ProjectFile.isProjectDirectory(dir));
        assertNoBinFiles(dir);
    }

    @Test
    void writingOneSectionKeepsTheOthers() throws IOException {
        new ProjectSettings("Game", "").write(dir);
        byte[] unknown = { 1, 2, 3 };
        ProjectFile.writeSection(dir, 0x58585858, unknown, "ignored");
        ProjectSettings s = ProjectSettings.read(dir);
        s.maxHeapMb = 4096;
        s.write(dir);
        assertArrayEquals(unknown, ProjectFile.readSection(dir, 0x58585858));
        assertEquals(4096, ProjectSettings.read(dir).maxHeapMb);
        assertEquals(1, dir.listFiles((d, n) -> n.endsWith(ProjectFile.EXTENSION)).length);
    }

    @Test
    void damageIsDetected() throws IOException {
        new ProjectSettings("Game", "").write(dir);
        File file = ProjectFile.find(dir);
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[20] ^= 0x40;
        Files.write(file.toPath(), bytes);
        IOException e = assertThrows(IOException.class, () -> ProjectSettings.read(dir));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
    }

    @Test
    void namesThatFileSystemsRejectAreReplaced() {
        assertEquals("a_b_c.rproj", ProjectFile.fileNameFor("a/b:c", dir));
        assertEquals(dir.getName() + ".rproj", ProjectFile.fileNameFor("  ", dir));
    }

    @Test
    void theFolderNamedFileWinsWhenThereAreSeveral() throws IOException {
        new ProjectSettings("Zed", "").write(dir);
        File folderNamed = new File(dir, dir.getName() + ".rproj");
        Files.copy(ProjectFile.find(dir).toPath(), folderNamed.toPath());
        assertEquals(folderNamed.getName(), ProjectFile.find(dir).getName());
    }

    @Test
    void legacyProjectIsReadInPlaceUntilMigrated() throws IOException {
        writeLegacyProject("Old Game", null);
        assertTrue(ProjectFile.isProjectDirectory(dir));
        assertEquals("Old Game", ProjectSettings.read(dir).projectName);
        assertNull(ProjectFile.find(dir), "reading must not migrate");
    }

    @Test
    void migrationWrapsTheOldFilesAndKeepsBackups() throws IOException {
        UUID prefabUuid = UUID.randomUUID();
        writeLegacyProject("Old Game", prefabUuid);

        ProjectFile.Migration m = ProjectFile.migrateLegacy(dir);
        assertTrue(m.changed());
        assertEquals("Old Game.rproj", m.projectFile().getName());
        assertEquals("Old Game", ProjectSettings.read(dir).projectName);
        assertTrue(new File(dir, "ProjectSettings.bin.bak").isFile());
        assertTrue(new File(dir, ".retro/asset_registry.bin.bak").isFile());
        assertFalse(new File(dir, "ProjectSettings.bin").exists());

        File prefab = new File(dir, "Assets/Prefabs/Crate.rprefab");
        assertTrue(prefab.isFile());
        assertFalse(new File(dir, "Assets/Prefabs/Crate.prefab.bin").exists());
        // The asset directory still says Crate.prefab.bin; the renamed file must answer to the same UUID
        assertEquals(prefabUuid.toString(), AssetDatabase.lookupManifestUuid(prefab));

        assertFalse(ProjectFile.migrateLegacy(dir).changed(), "a second run has nothing to do");
    }

    @Test
    void migrationLeavesAPrefabAloneWhenItsNewNameIsTaken() throws IOException {
        writeLegacyProject("Game", null);
        Files.writeString(dir.toPath().resolve("Assets/Prefabs/Crate.rprefab"), "newer");
        ProjectFile.migrateLegacy(dir);
        assertTrue(new File(dir, "Assets/Prefabs/Crate.prefab.bin").isFile());
        assertEquals("newer", Files.readString(dir.toPath().resolve("Assets/Prefabs/Crate.rprefab")));
    }

    @Test
    void prefabNamesConvertBothWays() {
        assertEquals("Prefabs/Coin.rprefab", ProjectFile.toCurrentPrefabName("Prefabs/Coin.prefab.bin"));
        assertEquals("Prefabs/Coin.rprefab", ProjectFile.toCurrentPrefabName("Prefabs/Coin.PREFAB.BIN"));
        assertEquals("Prefabs/Coin.prefab.bin", ProjectFile.toLegacyPrefabName("Prefabs/Coin.rprefab"));
        assertEquals("a.rscene", ProjectFile.toCurrentPrefabName("a.rscene"));
    }

    @Test
    void prefabsAlwaysSaveAsRprefab() {
        assertEquals("P/A.rprefab", PrefabSerializer.sanitizePrefabPath("P/A.prefab.bin"));
        assertEquals("P/A.rprefab", PrefabSerializer.sanitizePrefabPath("P/A.prefab"));
        assertEquals("P/A.rprefab", PrefabSerializer.sanitizePrefabPath("P/A.rprefab"));
        assertEquals("P/A.rprefab", PrefabSerializer.sanitizePrefabPath("P/A"));
        assertEquals("P.d/A.rprefab", PrefabSerializer.sanitizePrefabPath("P.d/A"));
    }

    @Test
    void anOldPrefabPathFindsTheRenamedFileAndViceVersa() throws IOException {
        Path renamed = dir.toPath().resolve("Coin.rprefab");
        Files.writeString(renamed, "x");
        assertEquals(renamed.toFile(), PrefabSerializer.resolvePrefabFile(dir + "/Coin.prefab.bin"));
        Path old = dir.toPath().resolve("Mod.prefab.bin");
        Files.writeString(old, "x");
        assertEquals(old.toFile(), PrefabSerializer.resolvePrefabFile(dir + "/Mod.rprefab"));
    }

    /** A Beta 4 era project: ProjectSettings.bin, .retro/asset_registry.bin and one prefab under its old name. */
    private void writeLegacyProject(String name, UUID prefabUuid) throws IOException {
        ProjectSettings s = new ProjectSettings(name, "");
        Files.write(dir.toPath().resolve(ProjectFile.LEGACY_SETTINGS_FILE), s.toPayload(dir));

        Path prefabs = Files.createDirectories(dir.toPath().resolve("Assets/Prefabs"));
        Files.writeString(prefabs.resolve("Crate.prefab.bin"), "prefab");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x52524547); // 'RREG'
            out.writeInt(3);
            out.writeUTF("");
            out.writeInt(prefabUuid != null ? 1 : 0);
            if (prefabUuid != null) {
                out.writeLong(prefabUuid.getMostSignificantBits());
                out.writeLong(prefabUuid.getLeastSignificantBits());
                out.writeUTF("Crate.prefab.bin");
                out.writeUTF("Assets/Prefabs/Crate.prefab.bin");
                out.writeUTF("PREFAB");
                out.writeUTF("Prefabs");
            }
        }
        Path internal = Files.createDirectories(dir.toPath().resolve(ProjectFile.LEGACY_INTERNAL_DIR));
        Files.write(internal.resolve(ProjectFile.LEGACY_ASSET_REGISTRY_FILE), bytes.toByteArray());
    }

    private static void assertNoBinFiles(File dir) throws IOException {
        try (var walk = Files.walk(dir.toPath())) {
            assertTrue(walk.noneMatch(p -> p.getFileName().toString().endsWith(".bin")), "found a .bin file");
        }
    }
}
