package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** mod.json parsing and .rmod extraction: both read files written by strangers. */
class ModPackageTest {

    @TempDir Path dir;

    private File modWith(String manifest) throws IOException {
        Path root = Files.createDirectories(dir.resolve("mod"));
        Files.writeString(root.resolve(ModManifest.FILE_NAME), manifest);
        return root.toFile();
    }

    private File rmod(String... namesAndContents) throws IOException {
        Path file = dir.resolve("test" + ".rmod");
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file.toFile();
    }

    @Test
    void shippedExampleModIsValid() throws IOException {
        ModManifest manifest = ModManifest.read(new File("ModSDK/StarterPack/example_mod"));
        assertEquals("example_mod", manifest.id);
        assertFalse(manifest.entryPoint.isEmpty());
    }

    @Test
    void manifestAppliesDefaultsAndAcceptsAliases() throws IOException {
        ModManifest manifest = ModManifest.read(modWith("{ \"id\": \"my_mod\", \"priority\": 5 }"));
        assertEquals("my_mod", manifest.id);
        assertEquals(5, manifest.loadPriority);
        assertEquals("0.0.0", manifest.version);
        assertTrue(manifest.assetOverlays.contains("Assets/"));
    }

    @Test
    void manifestReadsBothDependencyForms() throws IOException {
        ModManifest manifest = ModManifest.read(modWith("""
                { "mod_id": "my_mod", "dependencies": [ "core_lib", { "mod_id": "hd", "version": "1.2.0", "optional": true } ] }"""));
        assertEquals(new ModManifest.Dependency("core_lib", "", false), manifest.dependencies.get(0));
        assertEquals(new ModManifest.Dependency("hd", "1.2.0", true), manifest.dependencies.get(1));
    }

    @Test
    void invalidManifestsAreRejected() {
        assertThrows(IOException.class, () -> ModManifest.read(modWith("{ \"mod_id\": \"Bad Id!\" }")));
        assertThrows(IOException.class, () -> ModManifest.read(modWith("{ \"mod_id\": \"my_mod\", \"entry_point\": \"a.B\" }")));
        assertThrows(IOException.class, () -> ModManifest.read(modWith("{ \"mod_id\": \"my_mod\", \"dependencies\": [ 5 ] }")));
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(ModManifest.compareVersions("1.10.0", "1.9.2") > 0);
        assertTrue(ModManifest.compareVersions("1.2", "1.2.1") < 0);
        assertEquals(0, ModManifest.compareVersions("1.2.0", "1.2"));
        assertEquals(0, ModManifest.compareVersions("1.2.0-beta", "1.2.0"));
    }

    @Test
    void packageExtractsAndIsReusedFromCache() throws IOException {
        File rmod = rmod("mod.json", "{ \"mod_id\": \"my_mod\" }", "Assets/a.txt", "hello");
        File cache = dir.resolve("cache").toFile();
        File extracted = ModArchive.extractCached(rmod, cache);
        assertEquals("hello", Files.readString(extracted.toPath().resolve("Assets/a.txt")));

        Files.writeString(extracted.toPath().resolve("marker.txt"), "still here");
        File again = ModArchive.extractCached(rmod, cache);
        assertTrue(new File(again, "marker.txt").isFile(), "an unchanged package is not extracted twice");
    }

    @Test
    void packageCannotWriteOutsideItsFolder() throws IOException {
        File rmod = rmod("mod.json", "{}", "../../escaped.txt", "evil");
        File cache = dir.resolve("cache").toFile();
        assertThrows(IOException.class, () -> ModArchive.extractCached(rmod, cache));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void packageNeedsAManifestAtItsRoot() throws IOException {
        File rmod = rmod("nested/mod.json", "{}");
        assertThrows(IOException.class, () -> ModArchive.extractCached(rmod, dir.resolve("cache").toFile()));
    }
}
