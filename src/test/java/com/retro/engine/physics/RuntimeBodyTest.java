package com.retro.engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.RigidBodyComponent.MotionType;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.scripting.ScriptEngine;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class RuntimeBodyTest {

    private static final String HEADER = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.ecs.Entity;
        import com.retro.engine.scripting.framework.*;
        """;

    @TempDir Path assets;
    private SceneWorld world;

    @BeforeEach
    void bindProject() {
        ScriptEngine.setProjectAssetsRoot(assets.toString());
        world = new SceneWorld();
    }

    @AfterEach
    void stop() {
        world.stop();
        System.clearProperty("launched");
        System.clearProperty("hits");
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private ScriptComponent script(String name, String body) throws IOException {
        Path file = assets.resolve("Scripts/" + name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, HEADER + "public class " + name + " extends SimpleEntityScript {\n" + body + "\n}\n");
        return new ScriptComponent(file.toString(), name + ".java");
    }

    private void run(int frames) {
        for (int i = 0; i < frames; i++) world.update(TestEntities.FRAME);
    }

    /** A ball that launches itself in onStart, spawned by a script: the way a thrown object is written. */
    private Path ballPrefab() throws IOException {
        Entity ball = TestEntities.at("Ball", 0, 0, 0);
        ball.addComponent(new BoxCollider());
        RigidBodyComponent body = new RigidBodyComponent();
        body.useGravity = false;
        body.linearDrag = 0.0f;
        ball.addComponent(body);
        ball.addComponent(script("Launch", """
            public void onStart() { System.setProperty("launched", String.valueOf(setVelocity(5.0f, 0.0f, 0.0f))); }
            """));
        Path prefab = assets.resolve("ball.prefab.bin");
        List<Entity> list = new ArrayList<>(List.of(ball));
        assertTrue(PrefabSerializer.savePrefab(prefab.toString(), ball, list, null));
        return prefab;
    }

    private void assertLaunched() {
        assertEquals("true", System.getProperty("launched"));
        Entity ball = world.findEntityByName("Ball");
        assertNotNull(ball);
        assertEquals(5.0f, ball.getTransform().posX, 0.2f);
    }

    @Test
    void aSpawnedBodyCanBeLaunchedFromOnStart() throws IOException {
        Path prefab = ballPrefab();
        world.addEntity(TestEntities.box(0, -5, 0, 1, 1, 1, MotionType.STATIC), -1);
        world.play();
        run(1);
        world.instantiatePrefabRuntime(prefab.toString());
        run(61);
        assertLaunched();
    }

    @Test
    void theFirstBodyInAnEmptyWorldKeepsItsLaunch() throws IOException {
        Path prefab = ballPrefab();
        world.addEntity(TestEntities.at("Empty", 0, 0, 0), -1);
        world.play();
        run(1);
        world.instantiatePrefabRuntime(prefab.toString());
        run(61);
        assertLaunched();
    }

    @Test
    void aBodyDestroyedBeforeItJoinsTheSimulationNeverJoins() throws IOException {
        Path prefab = ballPrefab();
        world.addEntity(TestEntities.box(0, -5, 0, 1, 1, 1, MotionType.STATIC), -1);
        world.play();
        run(1);
        Entity ball = world.instantiatePrefabRuntime(prefab.toString());
        world.getPhysicsWorld().removeRuntimeEntity(ball);
        world.deleteEntity(ball);
        run(3);
        assertEquals(null, world.getPhysicsWorld().getBody(ball));
    }

    @Test
    void physicsKeepsScriptTimeAtTwentyFps() {
        Entity mover = TestEntities.box(0, 0, 0, 1, 1, 1, MotionType.DYNAMIC);
        RigidBodyComponent body = mover.getComponent(RigidBodyComponent.class);
        body.useGravity = false;
        body.linearDrag = 0.0f;
        world.addEntity(mover, -1);
        world.play();
        run(1);
        world.getPhysicsWorld().getBody(mover).vx = 1.0f;
        for (int i = 0; i < 120; i++) world.update(0.05f); // 6 s
        assertEquals(6.0f, mover.getTransform().posX, 0.1f);
    }

    @Test
    void setPositionFromOnPhysicsUpdateMovesADynamicBody() throws IOException {
        Entity crate = TestEntities.box(0, 0, 0, 1, 1, 1, MotionType.DYNAMIC);
        crate.getComponent(RigidBodyComponent.class).useGravity = false;
        crate.addComponent(script("Teleport", """
            private boolean done;
            public void onPhysicsUpdate(float dt) { if (!done) { done = true; setPosition(50.0f, 0.0f, 0.0f); } }
            """));
        world.addEntity(crate, -1);
        world.play();
        run(5);
        assertEquals(50.0f, crate.getTransform().posX, 0.01f);
    }

    @Test
    void overlappingSceneryRaisesNoCollisionEvents() throws IOException {
        Entity wall = TestEntities.at("Wall", 0, 0, 0);
        wall.addComponent(new BoxCollider());
        wall.addComponent(script("CountHits", """
            public void onCollisionEnter(Entity other) { System.setProperty("hits", "yes"); }
            """));
        Entity other = TestEntities.at("Other", 0.5f, 0, 0);
        other.addComponent(new BoxCollider());
        world.addEntity(wall, -1);
        world.addEntity(other, -1);
        world.play();
        run(5);
        assertFalse(System.getProperties().containsKey("hits"));
    }
}
