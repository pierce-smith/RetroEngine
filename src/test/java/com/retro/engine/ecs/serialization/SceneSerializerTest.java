package com.retro.engine.ecs.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.ComponentSchema.Property;
import com.retro.engine.ecs.ComponentSchemaTest;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AudioReverbZone;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.SplineComponent;
import com.retro.engine.ecs.components.Transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class SceneSerializerTest {

    @TempDir Path dir;

    private Path scenePath() {
        return dir.resolve("test.rscene");
    }

    private List<Entity> saveAndLoad(List<Entity> entities) {
        assertTrue(SceneSerializer.saveScene(scenePath().toString(), entities));
        return SceneSerializer.loadSceneData(scenePath().toString()).entities();
    }

    public static Stream<Class<? extends Component>> persistentComponents() throws ReflectiveOperationException {
        return ComponentSchemaTest.builtInComponents().stream().filter(t -> ComponentSchema.of(t).isPersistent());
    }

    /**
     * Gives every persisted field a non-default value the codec has to carry. Numbers stay inside the field's declared
     * range, because components clamp on load. Blobs are owned by their components.
     */
    private static void fillWithSentinels(Component c) throws IllegalAccessException {
        int i = 0;
        for (Property p : ComponentSchema.of(c.getClass()).serializedProperties()) {
            i++;
            float inRange = p.meta.min() + (p.meta.max() - p.meta.min()) * 0.37f;
            switch (p.kind) {
                case FLOAT -> p.set(c, inRange);
                case INT -> p.set(c, Math.round(inRange));
                case BOOLEAN -> p.set(c, !(Boolean) p.get(c));
                case ENUM -> {
                    Object[] constants = p.field.getType().getEnumConstants();
                    p.set(c, constants[constants.length - 1]);
                }
                case STRING -> p.set(c, "text_" + i);
                case ASSET_PATH -> p.set(c, "Assets/Textures/asset_" + i + ".png");
                case STRING_MAP -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("key_" + i, "value_" + i);
                    p.set(c, map);
                }
                default -> { }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("persistentComponents")
    void everyPersistedFieldSurvivesARoundTrip(Class<? extends Component> type) throws Exception {
        Component original = ComponentSchema.of(type).newInstance();
        fillWithSentinels(original);
        Entity entity = new Entity("e1", "Sample", null);
        if (type == Transform.class) {
            entity.removeComponent(Transform.class);
        }
        entity.addComponent(original);

        Entity loaded = saveAndLoad(List.of(entity)).get(0);

        Component copy = loaded.getComponent(type);
        assertNotNull(copy);
        for (Property p : ComponentSchema.of(type).serializedProperties()) {
            if (p.kind == ComponentSchema.ValueKind.BYTES) continue;
            assertEquals(p.get(original), p.get(copy), type.getSimpleName() + "." + p.name());
        }
    }

    @ParameterizedTest
    @MethodSource("persistentComponents")
    void everyPersistedFieldSurvivesAPrefabRoundTrip(Class<? extends Component> type) throws Exception {
        Component original = ComponentSchema.of(type).newInstance();
        fillWithSentinels(original);
        Entity entity = new Entity("e1", "Sample", null);
        if (type == Transform.class) entity.removeComponent(Transform.class);
        entity.addComponent(original);
        Path prefab = dir.resolve("sample.prefab.bin");
        assertTrue(PrefabSerializer.savePrefab(prefab.toString(), entity, List.of(entity), null));

        Entity loaded = PrefabSerializer.instantiatePrefab(prefab.toString(), new SceneWorld(), null).get(0);

        Component copy = loaded.getComponent(type);
        assertNotNull(copy);
        for (Property p : ComponentSchema.of(type).serializedProperties()) {
            if (p.kind == ComponentSchema.ValueKind.BYTES) continue;
            assertEquals(p.get(original), p.get(copy), type.getSimpleName() + "." + p.name());
        }
    }

    @Test
    void outOfRangeValuesAreClampedOnLoad() {
        AudioReverbZone zone = new AudioReverbZone();
        zone.wetLevel = 8.0f; // A hand-edited or corrupt file must not reach OpenAL
        Entity entity = new Entity("e1", "Zone", null);
        entity.addComponent(zone);
        assertEquals(1.0f, saveAndLoad(List.of(entity)).get(0).getComponent(AudioReverbZone.class).wetLevel);
    }

    @Test
    void splineKnotsSurviveARoundTrip() {
        SplineComponent spline = new SplineComponent();
        spline.onBeforeSerialize();
        byte[] original = spline.splineData;
        Entity entity = new Entity("e1", "Spline", null);
        entity.addComponent(spline);

        SplineComponent copy = saveAndLoad(List.of(entity)).get(0).getComponent(SplineComponent.class);

        copy.onBeforeSerialize();
        assertArrayEquals(original, copy.splineData);
    }

    @Test
    void identityAndHierarchySurvive() {
        Entity parent = new Entity("parent_id", "Parent", null);
        Entity child = new Entity("child_id", "Child", null);
        child.parentId = parent.id;
        parent.addChildId(child.id);

        List<Entity> loaded = saveAndLoad(List.of(parent, child));

        assertEquals(2, loaded.size());
        assertEquals("parent_id", loaded.get(0).id);
        assertEquals("Parent", loaded.get(0).name);
        assertEquals("parent_id", loaded.get(1).parentId);
        assertTrue(loaded.get(0).hasChild("child_id"));
    }

    @Test
    void sceneExtensionIsAddedWhenMissing() {
        assertEquals("level.rscene", SceneSerializer.sanitizeScenePath("level.dat"));
        assertEquals("level.rscene", SceneSerializer.sanitizeScenePath("level"));
    }

    @Test
    void assetPathsAreStoredProjectRelative() {
        assertEquals("Assets/Textures/a.png", SceneSerializer.sanitizeAssetPath("C:\\Games\\Proj\\Assets\\Textures\\a.png"));
        assertEquals("Assets/Textures/a.png", SceneSerializer.sanitizeAssetPath("/home/me/proj/Assets/Textures/a.png"));
    }

    /** Files written by earlier builds must keep loading. Add a sample here whenever the format version changes. */
    @ParameterizedTest
    @MethodSource("committedScenes")
    void committedScenesStillLoad(Path scene) {
        assertFalse(SceneSerializer.loadSceneData(scene.toString()).entities().isEmpty(), scene.toString());
    }

    public static Stream<Path> committedScenes() {
        return Stream.of(Path.of("Assets/Scenes/MainLevel.rscene"), Path.of("Assets/Scenes/test.rscene"), Path.of("Assets/Scenes/RenderCheck.rscene"));
    }

    public static class ProbeComponent extends Component {
        @InspectorProperty(order = 0, label = "VALUE") public float value = 1.5f;
        @InspectorProperty(order = 1, label = "NOTE") public String note = "hello";
        @Override public String getComponentName() { return "Probe"; }
    }

    @Test
    void removingAModKeepsTheSceneAndItsModData() {
        List<Entity> entities = new ArrayList<>();
        Entity first = new Entity("first", "First", null);
        first.addComponent(new ProbeComponent());
        first.addComponent(new Light());
        entities.add(first);
        for (int i = 0; i < 3; i++) entities.add(new Entity("after_" + i, "After", null));

        ComponentSchema.registerModComponent(ProbeComponent.class, "serializer_test");
        try {
            assertTrue(SceneSerializer.saveScene(scenePath().toString(), entities));
        } finally {
            ComponentSchema.unregisterModComponents("serializer_test");
        }

        List<Entity> withoutMod = SceneSerializer.loadSceneData(scenePath().toString()).entities();
        assertEquals(4, withoutMod.size(), "entities after the mod was removed");
        assertNotNull(withoutMod.get(0).getComponent(Light.class), "component after the unknown one");

        assertTrue(SceneSerializer.saveScene(scenePath().toString(), withoutMod));
        ComponentSchema.registerModComponent(ProbeComponent.class, "serializer_test");
        try {
            Entity restored = SceneSerializer.loadSceneData(scenePath().toString()).entities().get(0);
            assertNotNull(restored.getComponent(ProbeComponent.class), "mod data after a save without the mod");
        } finally {
            ComponentSchema.unregisterModComponents("serializer_test");
        }
    }

    /** Stands in for anything that stops a save partway: a crash, a full disk, a bad component. */
    public static class ExplodingComponent extends Component {
        @Override public String getComponentName() { return "Exploding"; }
        @Override public void onBeforeSerialize() { throw new IllegalStateException("save interrupted"); }
    }

    @Test
    void interruptedSaveLeavesTheOriginalFileIntact() throws Exception {
        assertTrue(SceneSerializer.saveScene(scenePath().toString(), List.of(new Entity("keep", "Keep", null))));
        byte[] original = Files.readAllBytes(scenePath());

        Entity bad = new Entity("bad", "Bad", null);
        bad.addComponent(new ExplodingComponent());
        assertFalse(SceneSerializer.saveScene(scenePath().toString(), List.of(bad)));

        assertArrayEquals(original, Files.readAllBytes(scenePath()));
    }
}
