package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.retro.engine.core.UserFolders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The player decides which mods may run code; asset-only mods never ask. */
class ModTrustTest {

    private static final String GAME = "Trust Test Game";

    @TempDir Path dir;
    private String previousUserData;
    private final List<List<String>> asked = new ArrayList<>();

    @BeforeEach
    void isolateUserData() {
        previousUserData = System.getProperty(UserFolders.OVERRIDE_PROPERTY);
        System.setProperty(UserFolders.OVERRIDE_PROPERTY, dir.resolve("userdata").toString());
        ModTrust.resetSession();
        ModTrust.setTrustAll(false);
        ModTrust.setPrompter(null);
    }

    @AfterEach
    void restore() {
        if (previousUserData == null) System.clearProperty(UserFolders.OVERRIDE_PROPERTY);
        else System.setProperty(UserFolders.OVERRIDE_PROPERTY, previousUserData);
        ModTrust.resetSession();
        ModTrust.setTrustAll(false);
        ModTrust.setPrompter(null);
    }

    private ModManifest mod(String id, boolean withCode) throws IOException {
        Path root = dir.resolve("Mods").resolve(id);
        Files.createDirectories(root.resolve("Assets"));
        Files.writeString(root.resolve(ModManifest.FILE_NAME), "{ \"mod_id\": \"" + id + "\", \"name\": \"" + id + "\", \"author\": \"Someone\" }");
        Files.write(root.resolve("Assets/skin.png"), new byte[] { 1, 2, 3 });
        if (withCode) {
            Files.createDirectories(root.resolve("Scripts"));
            Files.writeString(root.resolve("Scripts/Hack.java"), "public class Hack {}");
        }
        return ModManifest.read(root.toFile());
    }

    private static List<String> ids(List<ModManifest> mods) {
        return mods.stream().map(m -> m.id).toList();
    }

    private List<String> filter(List<ModManifest> mods) {
        return ids(ModTrust.filter(mods, GAME, List.of()));
    }

    private void answer(boolean yes) {
        ModTrust.setPrompter((game, mods) -> {
            asked.add(ids(mods));
            return yes;
        });
    }

    @Test
    void assetOnlyModsNeverAsk() throws IOException {
        ModTrust.setPrompter((game, mods) -> fail("should not ask about an asset-only mod"));
        assertEquals(List.of("textures"), filter(List.of(mod("textures", false))));
    }

    @Test
    void withNobodyToAskCodeModsAreSkipped() throws IOException {
        assertEquals(List.of("textures"), filter(List.of(mod("cheats", true), mod("textures", false))));
    }

    @Test
    void allowedModsAreRememberedAcrossRuns() throws IOException {
        List<ModManifest> mods = List.of(mod("cheats", true), mod("textures", false));
        answer(true);
        assertEquals(List.of("cheats", "textures"), filter(mods));
        assertEquals(List.of(List.of("cheats")), asked);
        assertTrue(ModTrust.storeFile(GAME).isFile());

        ModTrust.resetSession(); // a new launch
        ModTrust.setPrompter((game, m) -> fail("an allowed, unchanged mod should not ask again"));
        assertEquals(List.of("cheats", "textures"), filter(mods));
    }

    @Test
    void changedCodeAsksAgain() throws IOException {
        ModManifest cheats = mod("cheats", true);
        answer(true);
        filter(List.of(cheats));
        Files.writeString(cheats.root.toPath().resolve("Scripts/Hack.java"), "public class Hack { /* now steals saves */ }");
        filter(List.of(cheats));
        assertEquals(2, asked.size());
    }

    @Test
    void editingAnAssetDoesNotAskAgain() throws IOException {
        ModManifest cheats = mod("cheats", true);
        answer(true);
        filter(List.of(cheats));
        Files.write(cheats.root.toPath().resolve("Assets/skin.png"), new byte[] { 9, 9 });
        filter(List.of(cheats));
        assertEquals(1, asked.size());
    }

    @Test
    void noIsRememberedForTheSessionButNotForever() throws IOException {
        List<ModManifest> mods = List.of(mod("cheats", true));
        answer(false);
        assertEquals(List.of(), filter(mods));
        assertEquals(List.of(), filter(mods)); // reloading the mod set doesn't nag
        assertEquals(1, asked.size());
        assertFalse(ModTrust.storeFile(GAME).isFile());

        ModTrust.resetSession(); // next launch asks again
        filter(mods);
        assertEquals(2, asked.size());
    }

    @Test
    void mountedWorkspaceFoldersAndTrustAllSkipThePrompt() throws IOException {
        ModManifest cheats = mod("cheats", true);
        ModTrust.setPrompter((game, mods) -> fail("should not ask"));
        assertEquals(List.of("cheats"), ids(ModTrust.filter(List.of(cheats), GAME, List.of(dir.resolve("Mods").toFile()))));
        ModTrust.setTrustAll(true);
        assertEquals(List.of("cheats"), filter(List.of(cheats)));
    }

    @Test
    void hashCoversCodeOnly() throws IOException {
        assertNull(ModTrust.codeHash(mod("textures", false).root));
        File root = mod("cheats", true).root;
        String before = ModTrust.codeHash(root);
        Files.writeString(root.toPath().resolve("Scripts/Extra.class"), "x");
        assertNotEquals(before, ModTrust.codeHash(root));
        Files.createDirectories(root.toPath().resolve(".git"));
        String withClass = ModTrust.codeHash(root);
        Files.writeString(root.toPath().resolve(".git/Hidden.java"), "ignored like discovery ignores dot folders");
        assertEquals(withClass, ModTrust.codeHash(root));
    }

    @Test
    void promptNamesEachModPlainly() throws IOException {
        String text = ModTrust.promptText(GAME, List.of(mod("cheats", true)));
        assertTrue(text.startsWith("This mod contains code, which runs with the same access to your computer as Trust Test Game"));
        assertTrue(text.contains("  cheats") && text.contains("by Someone"), text);
        assertTrue(text.endsWith("Load it?"));
    }
}
