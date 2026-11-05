package com.retro.engine.modding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.SplineComponent;
import com.retro.engine.modding.SceneDeltaPatcher.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

class SceneDeltaPatcherTest {

    @TempDir Path dir;

    private final List<Entity> scene = new ArrayList<>();
    private Entity sun, crate, lid;
    private SceneDeltaPatcher patcher;

    @BeforeEach
    void buildScene() {
        sun = new Entity("light_1", "Sun", null);
        sun.addComponent(new Light());
        crate = new Entity("crate", "Crate", null);
        lid = new Entity("lid", "Lid", null);
        lid.parentId = crate.id;
        crate.addChildId(lid.id);
        scene.addAll(List.of(sun, crate, lid));
        patcher = new SceneDeltaPatcher(ModVirtualFileSystem.empty(dir.toFile()));
    }

    private SceneDeltaPatcher.Patch patch(String operations) throws IOException {
        Path file = dir.resolve("test.rpatch");
        Files.writeString(file, "{ \"format\": \"rpatch\", \"version\": 1, \"target_scene\": \"Assets/Scenes/Main.rscene\",\n"
                + "  \"operations\": [ " + operations + " ] }");
        return SceneDeltaPatcher.parse(file.toFile(), "test_mod");
    }

    private Result apply(String operations) throws IOException {
        return patcher.apply(patch(operations), scene);
    }

    private Entity find(String id) {
        return scene.stream().filter(e -> id.equals(e.id)).findFirst().orElse(null);
    }

    @Test
    void parseReadsTheHeaderAndAllowsComments() throws IOException {
        Path file = dir.resolve("commented.rpatch");
        Files.writeString(file, """
                {
                  // a comment
                  "format": "rpatch", "version": 1,
                  "target_scene": "Assets/Scenes/Main.rscene",
                  "operations": [ { "op": "REMOVE_ENTITY", "entity": "crate" } ]
                }""");
        SceneDeltaPatcher.Patch parsed = SceneDeltaPatcher.parse(file.toFile(), "test_mod");
        assertEquals("test_mod", parsed.modId());
        assertEquals(1, parsed.operations().size());
    }

    @Test
    void parseRejectsANewerFormatVersion() throws IOException {
        Path file = dir.resolve("future.rpatch");
        Files.writeString(file, "{ \"format\": \"rpatch\", \"version\": 99, \"target_scene\": \"a.rscene\", \"operations\": [] }");
        assertThrows(IOException.class, () -> SceneDeltaPatcher.parse(file.toFile(), "test_mod"));
    }

    @Test
    void shippedExamplePatchParses() throws IOException {
        File example = new File("ModSDK/StarterPack/example_mod/Patches/example.rpatch");
        assertFalse(SceneDeltaPatcher.parse(example, "example_mod").operations().isEmpty());
    }

    @Test
    void addEntityCreatesItWithComponents() throws IOException {
        Result result = apply("""
                { "op": "ADD_ENTITY", "id": "test_mod.chest", "name": "Chest", "parent": "crate", "components": [
                  { "type": "Transform", "properties": { "posX": 2.5 } },
                  { "type": "BoxCollider", "properties": { "sizeX": 3.0 } } ] }""");
        assertEquals(new Result(1, 0), result);
        Entity chest = find("test_mod.chest");
        assertNotNull(chest);
        assertEquals("Chest", chest.name);
        assertEquals("crate", chest.parentId);
        assertTrue(crate.hasChild(chest.id));
        assertEquals(2.5f, chest.getTransform().posX);
        assertEquals(1, chest.getComponents(com.retro.engine.ecs.components.Transform.class).size());
        assertEquals(3.0f, chest.getComponent(BoxCollider.class).sizeX);
    }

    @Test
    void removeEntityTakesItsDescendants() throws IOException {
        assertEquals(new Result(1, 0), apply("{ \"op\": \"REMOVE_ENTITY\", \"entity\": \"crate\" }"));
        assertNull(find("crate"));
        assertNull(find("lid"));
        assertNotNull(find("light_1"));
    }

    @Test
    void modifyEntityRenamesAndReparents() throws IOException {
        assertEquals(new Result(1, 0), apply("{ \"op\": \"MODIFY_ENTITY\", \"entity\": \"lid\", \"name\": \"Top\", \"parent\": null }"));
        assertEquals("Top", lid.name);
        assertNull(lid.parentId);
        assertFalse(crate.hasChild("lid"));
    }

    @Test
    void modifyComponentMatchesFieldNamesAndLabelsInAnyCase() throws IOException {
        assertEquals(new Result(1, 0), apply("{ \"op\": \"MODIFY_COMPONENT\", \"entity\": \"light_1\", \"component\": \"Light\", \"properties\": { \"intensity\": 2.0 } }"));
        assertEquals(2.0f, sun.getComponent(Light.class).intensity);
        assertEquals(new Result(1, 0), apply("{ \"op\": \"MODIFY_COMPONENT\", \"entity\": \"light_1\", \"component\": \"Light\", \"properties\": { \"INTENSITY\": 3.0 } }"));
        assertEquals(3.0f, sun.getComponent(Light.class).intensity);
    }

    @Test
    void entitiesCanBeAddressedByName() throws IOException {
        assertEquals(new Result(1, 0), apply("{ \"op\": \"MODIFY_COMPONENT\", \"entity_name\": \"Sun\", \"component\": \"Light\", \"properties\": { \"intensity\": 4.0 } }"));
        assertEquals(4.0f, sun.getComponent(Light.class).intensity);
    }

    @Test
    void attachAndDetachComponents() throws IOException {
        assertEquals(new Result(1, 0), apply("{ \"op\": \"ATTACH_COMPONENT\", \"entity\": \"crate\", \"component\": \"BoxCollider\", \"properties\": { \"isTrigger\": true } }"));
        assertTrue(crate.getComponent(BoxCollider.class).isTrigger);
        assertEquals(new Result(1, 0), apply("{ \"op\": \"DETACH_COMPONENT\", \"entity\": \"crate\", \"component\": \"BoxCollider\" }"));
        assertNull(crate.getComponent(BoxCollider.class));
    }

    @Test
    void badOperationsAreSkippedAndTheRestStillApply() throws IOException {
        Result result = apply("""
                { "op": "REMOVE_ENTITY", "entity": "no_such_entity" },
                { "op": "NOT_A_REAL_OP" },
                { "op": "MODIFY_COMPONENT", "entity": "light_1", "component": "Light", "properties": { "noSuchField": 1 } },
                { "op": "MODIFY_COMPONENT", "entity": "light_1", "component": "Light", "properties": { "intensity": "bright" } },
                { "op": "ATTACH_COMPONENT", "entity": "crate", "component": "NoSuchComponent" },
                { "op": "MODIFY_COMPONENT", "entity": "light_1", "component": "Light", "properties": { "intensity": 7.0 } }""");
        assertEquals(new Result(1, 5), result);
        assertEquals(7.0f, sun.getComponent(Light.class).intensity);
    }

    @Test
    void laterPatchesSeeEarlierOnes() throws IOException {
        apply("{ \"op\": \"ADD_ENTITY\", \"id\": \"a.thing\", \"name\": \"Thing\" }");
        assertEquals(new Result(1, 0), apply("{ \"op\": \"MODIFY_ENTITY\", \"entity\": \"a.thing\", \"name\": \"Renamed\" }"));
        assertEquals("Renamed", find("a.thing").name);
    }

    @Test
    void binaryPropertiesTravelAsBase64() throws IOException {
        SplineComponent spline = new SplineComponent();
        spline.onBeforeSerialize();
        byte[] knots = spline.splineData;
        String base64 = Base64.getEncoder().encodeToString(knots);
        // Line breaks inside the value are documented as ignored
        String wrapped = base64.substring(0, 8) + "\\n" + base64.substring(8);

        assertEquals(new Result(1, 0), apply("{ \"op\": \"ATTACH_COMPONENT\", \"entity\": \"crate\", \"component\": \"SplineComponent\", "
                + "\"properties\": { \"splineData\": \"" + wrapped + "\" } }"));
        assertArrayEquals(knots, crate.getComponent(SplineComponent.class).splineData);
    }

    @Test
    void invalidBase64FailsTheOperation() throws IOException {
        assertEquals(new Result(0, 1), apply("{ \"op\": \"ATTACH_COMPONENT\", \"entity\": \"crate\", \"component\": \"SplineComponent\", "
                + "\"properties\": { \"splineData\": \"not*valid\" } }"));
    }

    @Test
    void scenePathsNormalizeSoPatchesFindTheirTarget() {
        assertEquals(SceneDeltaPatcher.normalizeScenePath("Assets/Scenes/Main.rscene"),
                SceneDeltaPatcher.normalizeScenePath("Assets\\Scenes\\Main.rscene"));
    }
}
