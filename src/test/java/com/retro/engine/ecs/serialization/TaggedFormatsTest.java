package com.retro.engine.ecs.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimationClipIO;
import com.retro.engine.animation.KeyframeTrack;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.core.ProjectFile;
import com.retro.engine.core.ProjectSettings;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.serialization.SceneSerializer.SceneLoadData;
import com.retro.engine.navigation.NavMesh;
import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The format freeze: files written by every released build must keep loading. Scenes and prefabs have every
 * inspector field of every component set to a distinct value; projects carry settings, the asset directory and a
 * texture referenced by UUID (Beta 1 and 3 in the pre-.rproj layout); navmeshes and animation clips cover the rest.
 * Each fixture's {@code .expected} file is what that same build read back, so this test says: the current engine
 * reads every released file exactly as the build that wrote it did, and saving it again in the current format loses
 * nothing.
 *
 * <p>Made by {@code src/test/fixturegen/freeze-formats.sh <tag>}. Never regenerate an existing fixture; add the new
 * ones for each release tag. ({@code beta2} is the same commit as {@code beta1}.)
 */
class TaggedFormatsTest {

    private static final Path TAGGED = Path.of("src/test/resources/formats/tagged");

    private static final String[] SCENES = {
        "scene-v5-beta.rscene", "scene-v6-beta1.rscene", "scene-v8-beta3.rscene", "scene-v10-beta4.rscene",
    };
    private static final String[] PREFABS = {
        "prefab-v4-beta.prefab.bin", "prefab-v5-beta1.prefab.bin", "prefab-v7-beta3.prefab.bin", "prefab-v9-beta4.rprefab",
    };

    /**
     * Deliberate differences from what the old build read, keyed by "fixture|line key". Each needs a reason a
     * player would accept; anything not listed here is a regression.
     */
    private static final Map<String, String> INTENDED_CHANGES = Map.of(
        "scene-v5-beta.rscene|F|AudioSource|AudioSource|volume",
            "Beta clamped volume to 1.0 on load; the range is now 0-2, and Beta's editor could never store more than 1",
        "prefab-v4-beta.prefab.bin|F|AudioSource|AudioSource|volume",
            "Beta clamped volume to 1.0 on load; the range is now 0-2, and Beta's editor could never store more than 1");

    /** Whole project folders: settings, the asset directory, and a scene that references a texture by UUID. */
    private static final String[] PROJECTS = { "project-beta1", "project-beta3", "project-beta4" };

    private static final String TEXTURE = "Assets/Textures/Brick.png";
    private static final String SCENE = "Assets/Scenes/Main.rscene";

    /** A floor and a block, plus a gate from v2 on; recorded as the polygon under each point of a grid. */
    private static final String[] NAVMESHES = { "navmesh-v1-beta3.navmesh", "navmesh-v2-beta4.navmesh" };

    private static final String[] CLIPS = { "clip-v1-beta4.ranim" };

    @TempDir Path dir;

    static String[] projects() { return PROJECTS; }

    static String[] navMeshes() { return NAVMESHES; }

    static String[] clips() { return CLIPS; }

    static String[] scenes() { return SCENES; }

    static String[] prefabs() { return PREFABS; }

    @ParameterizedTest
    @MethodSource("scenes")
    void releasedSceneReadsBackExactly(String fixture) throws IOException {
        SceneLoadData data = SceneSerializer.loadSceneData(TAGGED.resolve(fixture).toString());
        assertFalse(data.failed(), data.error());
        assertMatches(fixture, false, dump(data.entities(), true));

        Path resaved = dir.resolve("resaved.rscene");
        assertTrue(SceneSerializer.saveScene(resaved.toString(), data.entities()));
        SceneLoadData again = SceneSerializer.loadSceneData(resaved.toString());
        assertFalse(again.failed(), again.error());
        assertMatches(fixture, true, dump(again.entities(), true));
    }

    @ParameterizedTest
    @MethodSource("prefabs")
    void releasedPrefabReadsBackExactly(String fixture) throws IOException {
        List<Entity> made = PrefabSerializer.instantiatePrefab(TAGGED.resolve(fixture).toString(), new SceneWorld(), null);
        assertFalse(made.isEmpty(), fixture + " instantiated nothing");
        assertMatches(fixture, false, dump(made, false));

        Path resaved = dir.resolve("resaved" + ProjectFile.PREFAB_EXTENSION);
        assertTrue(PrefabSerializer.savePrefab(resaved.toString(), made.get(0), made, null));
        List<Entity> again = PrefabSerializer.instantiatePrefab(resaved.toString(), new SceneWorld(), null);
        assertMatches(fixture, true, dump(again, false));
    }

    /** What the editor does on open: migrate once, then read settings and bind the asset directory. */
    @ParameterizedTest
    @MethodSource("projects")
    void releasedProjectOpensInTheEditorWithNothingLost(String fixture) throws IOException {
        Path project = copyProject(fixture);
        Map<String, String> expected = expected(fixture);

        ProjectFile.migrateLegacy(project.toFile());
        assertNotNull(ProjectFile.find(project.toFile()), "no " + ProjectFile.EXTENSION + " after migration");
        assertFalse(Files.exists(project.resolve(ProjectFile.LEGACY_SETTINGS_FILE)), "legacy settings left in place");
        assertMatches(fixture, false, dumpSettings(ProjectSettings.read(project.toFile())));

        String scriptRoot = ScriptEngine.getProjectAssetsRoot();
        AssetDatabase db = new AssetDatabase();
        try {
            assertTrue(db.openProject(project.toString()));
            assertEquals(expected.get("U|" + TEXTURE), "=" + db.resolveUuidByPath(TEXTURE), "texture UUID changed");
            assertSceneUsesTexture(project, SceneSerializer.loadSceneData(project.resolve(SCENE).toString(), db));
        } finally {
            db.clear();
            ScriptEngine.setProjectAssetsRoot(scriptRoot);
        }

        // Opening again must not migrate twice or lose anything the first open kept
        ProjectFile.migrateLegacy(project.toFile());
        assertMatches(fixture, true, dumpSettings(ProjectSettings.read(project.toFile())));
    }

    /** What an exported game does: read the old files where they are and write nothing (it may be read-only). */
    @ParameterizedTest
    @MethodSource("projects")
    void releasedProjectReadsInPlaceWithoutWriting(String fixture) throws IOException {
        Path project = copyProject(fixture);
        Map<String, byte[]> before = snapshot(project);

        assertMatches(fixture, false, dumpSettings(ProjectSettings.read(project.toFile())));
        assertEquals(expected(fixture).get("U|" + TEXTURE),
                "=" + AssetDatabase.lookupManifestUuid(project.resolve(TEXTURE).toFile()), "texture UUID");

        Map<String, byte[]> after = snapshot(project);
        assertEquals(before.keySet(), after.keySet(), "files appeared or vanished");
        for (String f : before.keySet()) assertArrayEquals(before.get(f), after.get(f), f + " was rewritten");
    }

    private Path copyProject(String fixture) throws IOException {
        Path from = TAGGED.resolve(fixture), to = dir.resolve(fixture);
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
        return to;
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) files.put(root.relativize(p).toString(), Files.readAllBytes(p));
        }
        return files;
    }

    /** Loaded paths are resolved to the file on disk, so compare files, not text. */
    private static void assertSceneUsesTexture(Path project, SceneLoadData scene) throws IOException {
        assertFalse(scene.failed(), scene.error());
        Entity wall = scene.entities().stream().filter(e -> "Wall".equals(e.name)).findFirst().orElseThrow();
        String path = wall.getComponent(Material.class).textureAssetPath;
        assertNotNull(path, "the wall lost its texture");
        assertTrue(Files.isSameFile(project.resolve(TEXTURE), Path.of(path)), path);
    }

    /** Settings lines, as src/test/fixturegen/FixtureGen.java#project writes them. */
    private static String dumpSettings(ProjectSettings settings) {
        StringBuilder sb = new StringBuilder();
        for (Field f : ProjectSettings.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || !Modifier.isPublic(m)) continue;
            Object v;
            try {
                v = f.get(settings);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            String r = v instanceof Long l ? l.toString()
                     : v instanceof float[] fa ? Arrays.toString(fa)
                     : v instanceof boolean[] ba ? Arrays.toString(ba)
                     : repr(v);
            if (r != null) sb.append("S|").append(f.getName()).append('=').append(r).append('\n');
        }
        return sb.toString();
    }

    @ParameterizedTest
    @MethodSource("navMeshes")
    void releasedNavMeshFindsTheSamePolygons(String fixture) throws IOException {
        NavMesh mesh = NavMesh.load(TAGGED.resolve(fixture).toFile());
        assertNotNull(mesh, fixture + " did not load");
        StringBuilder sb = new StringBuilder();
        sb.append("N|links=").append(mesh.getLinkCount()).append('\n');
        for (float x = -9; x <= 9; x += 1.5f) {
            for (float z = -9; z <= 9; z += 1.5f) {
                sb.append("P|").append(x).append(',').append(z).append('=').append(mesh.findPoly(x, 0.1f, z, 1.0f)).append('\n');
            }
        }
        assertMatches(fixture, false, sb.toString());
    }

    @ParameterizedTest
    @MethodSource("clips")
    void releasedClipReadsBackExactly(String fixture) throws IOException {
        AnimationClip clip = AnimationClipIO.load(TAGGED.resolve(fixture).toFile());
        assertMatches(fixture, false, describe(clip));

        Path resaved = dir.resolve("resaved" + AnimationClipIO.EXTENSION);
        AnimationClipIO.save(resaved.toFile(), clip);
        assertMatches(fixture, true, describe(AnimationClipIO.load(resaved.toFile())));
    }

    /** Same text as src/test/fixturegen/ExtrasGen.java#describe. */
    private static String describe(AnimationClip c) {
        StringBuilder sb = new StringBuilder();
        sb.append("A|name=").append(c.name).append('\n');
        sb.append("A|duration=").append(c.durationSeconds).append('\n');
        sb.append("A|ticks=").append(c.ticksPerSecond).append('\n');
        sb.append("A|loop=").append(c.loopMode.name()).append('\n');
        List<KeyframeTrack> tracks = new ArrayList<>(c.getTracks());
        tracks.sort((a, b) -> a.boneName.compareTo(b.boneName));
        for (KeyframeTrack t : tracks) {
            sb.append("T|").append(t.boneName).append("|pos=").append(Arrays.toString(t.positionTimes)).append(Arrays.toString(t.positions)).append('\n');
            sb.append("T|").append(t.boneName).append("|rot=").append(Arrays.toString(t.rotationTimes)).append(Arrays.toString(t.rotations)).append('\n');
            sb.append("T|").append(t.boneName).append("|scale=").append(Arrays.toString(t.scaleTimes)).append(Arrays.toString(t.scales)).append('\n');
        }
        return sb.toString();
    }

    /** The lists above must name every fixture on disk, so a new one can't be added and then never tested. */
    @Test
    void everyFixtureOnDiskIsTested() throws IOException {
        List<String> listed = new ArrayList<>(List.of(SCENES));
        listed.addAll(List.of(PREFABS));
        listed.addAll(List.of(PROJECTS));
        listed.addAll(List.of(NAVMESHES));
        listed.addAll(List.of(CLIPS));
        try (Stream<Path> files = Files.list(TAGGED)) {
            List<String> onDisk = files.map(p -> p.getFileName().toString())
                    .filter(n -> !n.endsWith(".expected")).sorted().toList();
            assertEquals(listed.stream().sorted().toList(), onDisk);
        }
        for (String f : listed) assertTrue(Files.isRegularFile(TAGGED.resolve(f + ".expected")), f + ".expected");
    }

    // --- Comparison ---

    private static Map<String, String> expected(String fixture) throws IOException {
        return parse(Files.readString(TAGGED.resolve(fixture + ".expected")));
    }

    private static Map<String, String> parse(String dump) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String line : dump.split("\\R")) { // \R: Windows checkouts turn the .expected files into CRLF
            if (line.isEmpty()) continue;
            int eq = line.startsWith("E|") ? line.indexOf("|parent=") : line.indexOf('=');
            lines.put(line.substring(0, eq), line.substring(eq));
        }
        return lines;
    }

    /** Every recorded value must come back the same. New fields and new components are fine. */
    private static void assertMatches(String fixture, boolean resaved, String actualDump) throws IOException {
        Map<String, String> actual = parse(actualDump);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expected(fixture).entrySet()) {
            if (e.getKey().startsWith("U|")) continue; // Asset UUIDs are checked against the asset directory
            if (INTENDED_CHANGES.containsKey(fixture + "|" + e.getKey())) continue;
            String got = actual.get(e.getKey());
            if (got == null) problems.add("missing " + e.getKey() + e.getValue());
            else if (!sameValue(e.getValue(), got)) problems.add(e.getKey() + ": wrote " + e.getValue() + ", read " + got);
        }
        if (!problems.isEmpty()) {
            fail(fixture + (resaved ? " (after saving in the current format)" : "") + ": " + problems.size()
                    + " differences\n  " + String.join("\n  ", problems));
        }
    }

    /**
     * A missing string and an empty one mean the same thing everywhere in the engine (no texture, no target), and
     * saving writes a missing one as empty, so the two compare equal.
     */
    private static boolean sameValue(String expected, String actual) {
        if (expected.equals(actual)) return true;
        return isNoText(expected) && isNoText(actual);
    }

    private static boolean isNoText(String v) {
        return v.equals("=null") || v.equals("=\"\"");
    }

    /** Same layout as src/test/fixturegen/FixtureGen.java#dump; keep the two in step. */
    private static String dump(List<Entity> entities, boolean withIds) {
        Map<String, String> idToName = new TreeMap<>();
        for (Entity e : entities) idToName.put(e.id, e.name);
        StringBuilder sb = new StringBuilder();
        for (Entity e : entities) {
            String parent = e.parentId == null ? "-" : idToName.getOrDefault(e.parentId, "?" + e.parentId);
            sb.append("E|").append(e.name).append("|parent=").append(parent);
            if (withIds) sb.append("|id=").append(e.id);
            sb.append('\n');
            List<Component> all = new ArrayList<>();
            all.add(e.getTransform());
            for (Component c : e.getAllComponents()) if (c != e.getTransform()) all.add(c);
            for (Component c : all) {
                for (Field f : fields(c.getClass())) {
                    String v;
                    try {
                        v = repr(f.get(c));
                    } catch (IllegalAccessException ex) {
                        throw new IllegalStateException(ex);
                    }
                    if (v == null) continue;
                    sb.append("F|").append(e.name).append('|').append(c.getClass().getSimpleName()).append('|')
                      .append(f.getName()).append('=').append(v).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Every instance field, not only inspector ones, so a field that stopped being annotated is still compared. */
    private static List<Field> fields(Class<?> c) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return fields;
    }

    private static String repr(Object v) {
        if (v == null) return "null";
        if (v instanceof Float || v instanceof Integer || v instanceof Boolean) return v.toString();
        if (v instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\n", "\\n") + '"';
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Map<?, ?> m) return new TreeMap<>(m).toString();
        return null;
    }
}
