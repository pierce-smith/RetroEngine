package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.rigidBody;
import static com.retro.engine.testing.TestEntities.scene;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.MeshCollider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Boxes against mesh triangles where no corner of either shape lies inside the other. */
class BoxMeshEdgeTest {

    @TempDir Path dir;

    private Entity mesh(String obj) throws IOException {
        Path file = dir.resolve("mesh" + System.nanoTime() + ".obj");
        Files.writeString(file, obj);
        Entity e = at("mesh", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    /** A steep ridge along z, with its top at y = 0, too narrow for any box corner to sit above it. */
    private Entity ridge() throws IOException {
        return mesh("v -0.4 -6 -20\nv 0 0 -20\nv 0 0 20\nv -0.4 -6 20\nv 0.4 -6 -20\nv 0.4 -6 20\n"
                + "f 1 4 3\nf 1 3 2\nf 2 3 6\nf 2 6 5\n");
    }

    /** Lowest the box gets in the frames around first contact, when it hasn't had time to topple off. */
    private static float lowestAfterLanding(Entity crate, List<Entity> scene, PhysicsSystem physics, int from, int to) {
        step(physics, scene, from);
        float lowest = Float.MAX_VALUE;
        for (int i = from; i < to; i++) {
            physics.step(1.0f / 60.0f, scene);
            lowest = Math.min(lowest, crate.getTransform().posY);
        }
        return lowest;
    }

    @Test
    void boxEdgeCrossingARidgeStopsOnIt() throws IOException {
        Entity crate = box(0, 1.5f, 0, 6, 1, 1, DYNAMIC);
        crate.getTransform().rotX = 45; // Lowest edge runs along x, square across the ridge
        List<Entity> scene = scene(ridge(), crate);
        float lowest = lowestAfterLanding(crate, scene, physics(scene), 20, 45);
        assertTrue(lowest > 0.5f, "y=" + lowest);
    }

    @Test
    void flatBoxLandingOnARidgeStopsOnIt() throws IOException {
        Entity crate = box(0, 1.5f, 0, 6, 1, 6, DYNAMIC);
        List<Entity> scene = scene(ridge(), crate);
        float lowest = lowestAfterLanding(crate, scene, physics(scene), 20, 45);
        assertTrue(lowest > 0.3f, "y=" + lowest);
    }

    @Test
    void boxEdgeLandsOnATriangleSmallerThanTheBox() throws IOException {
        Entity post = mesh("v -0.3 0 -0.2\nv 0 0 0.4\nv 0.3 0 -0.2\nf 1 2 3\n");
        Entity crate = box(0, 1.5f, 0, 6, 1, 1, DYNAMIC);
        crate.getTransform().rotX = 45;
        List<Entity> scene = scene(post, crate);
        float lowest = lowestAfterLanding(crate, scene, physics(scene), 20, 45);
        assertTrue(lowest > 0.5f, "y=" + lowest);
    }

    /** A flat grid of small triangles, so internal edges cross under the box all the time. */
    private Entity grid() throws IOException {
        StringBuilder obj = new StringBuilder();
        int cells = 40, side = cells + 1;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) obj.append("v ").append(x - 20).append(" 0 ").append(z - 20).append('\n');
        }
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int a = z * side + x + 1, b = a + 1, c = a + side, d = c + 1;
                obj.append("f ").append(a).append(' ').append(c).append(' ').append(b).append('\n');
                obj.append("f ").append(b).append(' ').append(c).append(' ').append(d).append('\n');
            }
        }
        return mesh(obj.toString());
    }

    @Test
    void boxSlidesOverInternalEdgesWithoutCatching() throws IOException {
        Entity crate = box(-15, 0.25f, 0.3f, 0.5f, 0.5f, 0.5f, DYNAMIC);
        crate.getTransform().rotY = 30;
        rigidBody(crate).friction = 0.0f;
        rigidBody(crate).linearDrag = 0.0f;
        List<Entity> scene = scene(grid(), crate);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 10);
        physics.getBody(crate).vx = 4.0f;
        step(physics, scene, 240);
        assertEquals(0.25f, crate.getTransform().posY, 0.02f);
        assertTrue(physics.getBody(crate).vx > 3.9f, "vx=" + physics.getBody(crate).vx);
        assertEquals(0.3f, crate.getTransform().posZ, 0.1f);
    }

    @Test
    void tumblingBoxSettlesOnTerrain() throws IOException {
        Entity crate = box(0.4f, 2, 0.3f, 0.5f, 0.3f, 0.7f, DYNAMIC);
        crate.getTransform().rotX = 35;
        crate.getTransform().rotZ = 20;
        List<Entity> scene = scene(grid(), crate);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 240);
        assertTrue(crate.getTransform().posY > 0.1f && crate.getTransform().posY < 0.5f, "y=" + crate.getTransform().posY);
        PhysicsSystem.PhysicsBody body = physics.getBody(crate);
        assertTrue(Math.abs(body.vx) + Math.abs(body.vy) + Math.abs(body.vz) < 0.2f, "still moving");
    }
}
