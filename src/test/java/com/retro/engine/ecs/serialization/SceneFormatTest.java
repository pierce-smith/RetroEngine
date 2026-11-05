package com.retro.engine.ecs.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.AtomicFile;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.UIText;
import com.retro.engine.ecs.components.UnknownComponent;
import com.retro.engine.ecs.serialization.SceneSerializer.SceneLoadData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** What the scene and prefab files promise: damage is detected, old files keep loading, unknown data survives. */
class SceneFormatTest {

    private static final Path FORMATS = Path.of("src/test/resources/formats");

    @TempDir Path dir;

    private Path scene() {
        return dir.resolve("test.rscene");
    }

    private List<Entity> sampleScene() {
        Entity a = new Entity("a", "Alpha", null);
        a.getTransform().posX = 4.5f;
        Light light = new Light();
        light.intensity = 3.0f;
        a.addComponent(light);
        Entity b = new Entity("b", "Beta", null);
        b.parentId = a.id;
        a.addChildId(b.id);
        return new ArrayList<>(List.of(a, b));
    }

    private byte[] savedSample() throws IOException {
        assertTrue(SceneSerializer.saveScene(scene().toString(), sampleScene()));
        return Files.readAllBytes(scene());
    }

    private SceneLoadData load() {
        return SceneSerializer.loadSceneData(scene().toString());
    }

    // --- Damage detection ---

    @Test
    void cleanFileHasNoError() throws IOException {
        savedSample();
        assertFalse(load().failed());
    }

    @Test
    void everyDamagedByteIsDetected() throws IOException {
        byte[] good = savedSample();
        for (int i = 0; i < good.length; i++) {
            if (i < 8) continue; // Magic and version have their own, more specific errors below
            byte[] bad = good.clone();
            bad[i] ^= 0x40;
            Files.write(scene(), bad);
            assertTrue(load().failed(), "byte " + i + " of " + good.length);
        }
    }

    @Test
    void truncatedFileIsDetectedAtEveryLength() throws IOException {
        byte[] good = savedSample();
        for (int length = 0; length < good.length; length++) {
            Files.write(scene(), Arrays.copyOf(good, length));
            assertTrue(load().failed(), "truncated to " + length);
        }
    }

    @Test
    void wrongFileTypeIsReported() throws IOException {
        Files.writeString(scene(), "this is not a scene file at all");
        assertTrue(load().error().contains("not a RetroEngine scene"), load().error());
    }

    @Test
    void newerFormatIsReportedInsteadOfMisread() throws IOException {
        byte[] bytes = savedSample();
        ByteBuffer.wrap(bytes).putInt(4, SceneSerializer.BINARY_VERSION + 1);
        ComponentBinaryCodec.sealChecksum(bytes);
        Files.write(scene(), bytes);
        assertTrue(load().error().contains("newer version"), load().error());
    }

    @Test
    void missingFileIsNotADamagedFile() {
        assertFalse(SceneSerializer.loadSceneData(dir.resolve("nope.rscene").toString()).failed());
    }

    @Test
    void emptySceneRoundTripsWithoutError() {
        assertTrue(SceneSerializer.saveScene(scene().toString(), new ArrayList<>()));
        SceneLoadData data = load();
        assertFalse(data.failed());
        assertTrue(data.entities().isEmpty());
    }

    // --- Strings ---

    private UIText reloadText(String text) {
        UIText ui = new UIText();
        ui.text = text;
        Entity e = new Entity("t", "Text", null);
        e.addComponent(ui);
        assertTrue(SceneSerializer.saveScene(scene().toString(), List.of(e)));
        return load().entities().get(0).getComponent(UIText.class);
    }

    @Test
    void stringsLongerThan64KbSurvive() {
        String huge = "x".repeat(100_000);
        assertEquals(huge, reloadText(huge).text);
    }

    @Test
    void unicodeAndEmptyStringsSurvive() {
        assertEquals("héllo → 世界 🎮", reloadText("héllo → 世界 🎮").text);
        assertEquals("", reloadText("").text);
    }

    @Test
    void nullStringsFallBackToTheFieldDefault() {
        assertEquals(new UIText().text, reloadText(null).text);
    }

    // --- Missing component types ---

    public static class ProbeComponent extends Component {
        @InspectorProperty(order = 0, label = "VALUE") public float value = 1.5f;
        @InspectorProperty(order = 1, label = "NOTE") public String note = "hello";
        @InspectorProperty(order = 2, label = "ICON", type = InspectorProperty.PropertyType.ASSET_PICKER) public String icon = "Assets/Textures/a.png";
        @Override public String getComponentName() { return "Probe"; }
    }

    private void withProbe(Runnable body) {
        ComponentSchema.registerModComponent(ProbeComponent.class, "format_test");
        try {
            body.run();
        } finally {
            ComponentSchema.unregisterModComponents("format_test");
        }
    }

    private List<Entity> sceneWithProbe() {
        List<Entity> entities = sampleScene();
        ProbeComponent probe = new ProbeComponent();
        probe.value = 9.0f;
        probe.note = "keep me";
        Entity first = entities.get(0);
        first.getAllComponents().add(1, probe); // Ahead of the Light, so reading on past it matters
        return entities;
    }

    @Test
    void unknownComponentDoesNotDisturbItsNeighbors() {
        withProbe(() -> assertTrue(SceneSerializer.saveScene(scene().toString(), sceneWithProbe())));
        List<Entity> loaded = load().entities();
        assertEquals(2, loaded.size());
        assertEquals(3.0f, loaded.get(0).getComponent(Light.class).intensity);
        UnknownComponent unknown = loaded.get(0).getComponent(UnknownComponent.class);
        assertEquals("ProbeComponent", unknown.typeName());
        assertTrue(unknown.getComponentName().contains("ProbeComponent"));
    }

    @Test
    void unknownComponentSurvivesRepeatedSavesAndComesBackWhole() {
        withProbe(() -> assertTrue(SceneSerializer.saveScene(scene().toString(), sceneWithProbe())));
        for (int i = 0; i < 3; i++) {
            List<Entity> withoutMod = load().entities();
            // A rebuilt string table must not scramble the kept bytes
            withoutMod.get(0).name = "Renamed " + i;
            assertTrue(SceneSerializer.saveScene(scene().toString(), withoutMod));
        }
        withProbe(() -> {
            ProbeComponent probe = load().entities().get(0).getComponent(ProbeComponent.class);
            assertNotNull(probe);
            assertEquals(9.0f, probe.value);
            assertEquals("keep me", probe.note);
            assertEquals("Assets/Textures/a.png", probe.icon);
        });
    }

    @Test
    void unknownComponentSurvivesDuplicateAndPlay() {
        withProbe(() -> assertTrue(SceneSerializer.saveScene(scene().toString(), sceneWithProbe())));
        SceneWorld world = new SceneWorld();
        world.loadSceneFromFile(scene().toString());
        Entity original = world.getSceneEntities().get(0);
        UnknownComponent kept = original.getComponent(UnknownComponent.class);

        world.play();
        world.update(1.0f / 60.0f);
        world.stop();
        assertEquals(kept, world.getSceneEntities().get(0).getComponent(UnknownComponent.class));

        world.selectEntity(world.getSceneEntities().get(0));
        world.duplicateSelectedEntity();
        Entity copy = world.getSceneEntities().get(world.getSceneEntities().size() - 1);
        assertNotSame(original, copy);
        assertArrayEquals(kept.payload(), copy.getComponent(UnknownComponent.class).payload());
    }

    public static class V1 { public static class Widget extends Component {
        @InspectorProperty(order = 0) public float first = 1.0f;
        @Override public String getComponentName() { return "Widget"; }
    } }

    public static class V2 { public static class Widget extends Component {
        @InspectorProperty(order = 0) public float first = 1.0f;
        @InspectorProperty(order = 1) public float added = 7.0f;
        @Override public String getComponentName() { return "Widget"; }
    } }

    @Test
    void modCanAppendAFieldWithoutBreakingItsOldScenes() {
        V1.Widget old = new V1.Widget();
        old.first = 5.0f;
        Entity e = new Entity("w", "Widget", null);
        e.addComponent(old);
        Entity after = new Entity("after", "After", null);
        ComponentSchema.registerModComponent(V1.Widget.class, "format_test");
        try {
            assertTrue(SceneSerializer.saveScene(scene().toString(), List.of(e, after)));
        } finally {
            ComponentSchema.unregisterModComponents("format_test");
        }

        ComponentSchema.registerModComponent(V2.Widget.class, "format_test");
        try {
            List<Entity> loaded = load().entities();
            assertEquals(2, loaded.size());
            V2.Widget updated = loaded.get(0).getComponent(V2.Widget.class);
            assertEquals(5.0f, updated.first);
            assertEquals(7.0f, updated.added);
        } finally {
            ComponentSchema.unregisterModComponents("format_test");
        }
    }

    // --- Files written by earlier builds ---

    private static void assertSameContent(Entity expected, Entity actual) throws Exception {
        assertEquals(expected.id, actual.id);
        assertEquals(expected.name, actual.name);
        assertEquals(expected.parentId, actual.parentId);
        assertEquals(expected.getAllComponents().size(), actual.getAllComponents().size(), expected.name);
        for (int i = 0; i < expected.getAllComponents().size(); i++) {
            Component a = expected.getAllComponents().get(i), b = actual.getAllComponents().get(i);
            assertEquals(a.getClass(), b.getClass());
            // Blobs like poseData exist only during a save, so encode both sides and compare the content
            a.onBeforeSerialize();
            b.onBeforeSerialize();
            for (ComponentSchema.Property p : ComponentSchema.of(a.getClass()).serializedProperties()) {
                String where = expected.name + "." + a.getClass().getSimpleName() + "." + p.name();
                if (p.kind == ComponentSchema.ValueKind.BYTES) {
                    assertArrayEquals((byte[]) p.get(a), (byte[]) p.get(b), where);
                } else {
                    assertEquals(p.get(a), p.get(b), where);
                }
            }
        }
    }

    /** Every one of these is a file an earlier build really wrote. Never regenerate them. Add one per new format version. */
    @ParameterizedTest
    @ValueSource(strings = { "scene-v8-first-person.rscene", "scene-v8-infinite-driver.rscene", "scene-v9-first-person.rscene" })
    void frozenSceneUpgradesToTheCurrentFormatWithNothingLost(String fixture) throws Exception {
        SceneLoadData old = SceneSerializer.loadSceneData(FORMATS.resolve(fixture).toString());
        assertFalse(old.failed());
        assertFalse(old.entities().isEmpty());

        assertTrue(SceneSerializer.saveScene(scene().toString(), old.entities()));
        SceneLoadData upgraded = load();

        assertFalse(upgraded.failed());
        assertEquals(old.entities().size(), upgraded.entities().size());
        for (int i = 0; i < old.entities().size(); i++) {
            assertSameContent(old.entities().get(i), upgraded.entities().get(i));
        }
    }

    @Test
    void frozenV8SceneHasTheContentWeExpect() {
        List<Entity> entities = SceneSerializer.loadSceneData(FORMATS.resolve("scene-v8-first-person.rscene").toString()).entities();
        assertTrue(entities.stream().anyMatch(e -> "Player".equals(e.name)));
        assertTrue(entities.stream().anyMatch(e -> "AI Chaser".equals(e.name)));
    }

    private void assertCratePrefab(File prefab, boolean ducksBgm) {
        SceneWorld world = new SceneWorld();
        List<Entity> made = PrefabSerializer.instantiatePrefab(prefab.getPath(), world, null);
        assertEquals(2, made.size());
        Entity root = made.get(0);
        assertEquals("Crate Root", root.name);
        assertEquals(1.5f, root.getTransform().posX);
        assertEquals(2.5f, root.getComponent(Light.class).intensity);
        assertEquals(3.0f, root.getComponent(BoxCollider.class).sizeX);
        assertTrue(root.getComponent(BoxCollider.class).isTrigger);
        assertEquals(0.5f, root.getComponent(AudioSource.class).volume);
        assertEquals(ducksBgm, root.getComponent(AudioSource.class).ducksBgm);
        assertEquals(root.id, made.get(1).parentId);
        assertEquals(0.75f, made.get(1).getTransform().posY);
    }

    /** This prefab was saved with ducksBgm on. Version 7 lost it (see ComponentSchema.Property.isPersistedIn). */
    @Test
    void frozenV7PrefabStillLoads() {
        assertCratePrefab(FORMATS.resolve("prefab-v7.prefab.bin").toFile(), false);
    }

    @Test
    void frozenV8PrefabStillLoads() {
        assertCratePrefab(FORMATS.resolve("prefab-v8.prefab.bin").toFile(), true);
    }

    @Test
    void prefabRoundTripsInTheCurrentFormat() {
        SceneWorld world = new SceneWorld();
        List<Entity> made = PrefabSerializer.instantiatePrefab(FORMATS.resolve("prefab-v7.prefab.bin").toString(), world, null);
        File saved = dir.resolve("crate.prefab.bin").toFile();
        made.get(0).getComponent(AudioSource.class).ducksBgm = true;
        assertTrue(PrefabSerializer.savePrefab(saved.getPath(), made.get(0), made, null));
        assertCratePrefab(saved, true);
    }

    @Test
    void damagedPrefabLoadsNothing() throws IOException {
        SceneWorld world = new SceneWorld();
        List<Entity> made = PrefabSerializer.instantiatePrefab(FORMATS.resolve("prefab-v7.prefab.bin").toString(), world, null);
        File saved = dir.resolve("crate.prefab.bin").toFile();
        assertTrue(PrefabSerializer.savePrefab(saved.getPath(), made.get(0), made, null));
        byte[] bytes = Files.readAllBytes(saved.toPath());
        bytes[bytes.length / 2] ^= 0x40;
        Files.write(saved.toPath(), bytes);

        SceneWorld fresh = new SceneWorld();
        assertTrue(PrefabSerializer.instantiatePrefab(saved.getPath(), fresh, null).isEmpty());
        assertTrue(fresh.getSceneEntities().isEmpty());
    }

    // --- Backups and atomic writes ---

    private List<String> backupContents() throws IOException {
        List<String> found = new ArrayList<>();
        File sceneFile = scene().toFile();
        File newest = SceneBackups.newestFor(sceneFile);
        if (newest.isFile()) found.add(Files.readString(newest.toPath()));
        for (int age = 1; age < 10; age++) {
            File older = new File(sceneFile.getPath() + "." + age + ".bak");
            if (older.isFile()) found.add(Files.readString(older.toPath()));
        }
        return found;
    }

    @Test
    void backupsRotateNewestFirstAndStopAtTheLimit() throws IOException {
        for (int i = 1; i <= 8; i++) {
            Files.writeString(scene(), "version " + i);
            SceneBackups.backUpBeforeSave(scene().toFile());
        }
        assertEquals(List.of("version 8", "version 7", "version 6", "version 5", "version 4"), backupContents());
        assertEquals(SceneBackups.KEEP, backupContents().size());
    }

    @Test
    void savingWithoutChangesDoesNotUseUpBackups() throws IOException {
        Files.writeString(scene(), "old work");
        SceneBackups.backUpBeforeSave(scene().toFile());
        Files.writeString(scene(), "new work");
        for (int i = 0; i < 10; i++) SceneBackups.backUpBeforeSave(scene().toFile());
        assertEquals(List.of("new work", "old work"), backupContents());
    }

    @Test
    void nothingToBackUpIsFine() throws IOException {
        SceneBackups.backUpBeforeSave(scene().toFile());
        assertTrue(backupContents().isEmpty());
    }

    @Test
    void atomicWriteReplacesTheFileAndLeavesNoTempBehind() throws IOException {
        Path target = dir.resolve("nested/folder/data.bin");
        AtomicFile.write(target.toFile(), new byte[] { 1 });
        AtomicFile.write(target.toFile(), new byte[] { 2, 3 });
        assertArrayEquals(new byte[] { 2, 3 }, Files.readAllBytes(target));
        assertEquals(0, target.getParent().toFile().list((d, n) -> n.endsWith(".tmp")).length);
    }

    @Test
    void failedAtomicWriteLeavesNoTempBehind() throws IOException {
        Path folder = Files.createDirectory(dir.resolve("in_the_way"));
        Files.writeString(folder.resolve("occupied.txt"), "not empty");
        assertThrows(IOException.class, () -> AtomicFile.write(folder.toFile(), new byte[] { 1 }));
        assertFalse(Files.exists(dir.resolve("in_the_way.tmp")));
        assertTrue(Files.isDirectory(folder));
    }
}
