package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.MeshCollider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A 0.35 radius capsule on the default stepOffset, on stairs of every proportion and the things that mustn't count as one. */
class CharacterStairsTest {

    private static final float HALF_HEIGHT = 0.9f, SPEED = 1.2f, RISE = 0.18f;

    @TempDir Path dir;

    private Entity player;
    private CharacterControllerComponent controller;
    private List<Entity> world;
    private PhysicsSystem physics;

    private void start(List<Entity> ground, float z, float feet) {
        player = at("Player", 0, feet + HALF_HEIGHT + 0.02f, z);
        CapsuleCollider capsule = new CapsuleCollider();
        capsule.radius = 0.35f;
        capsule.height = HALF_HEIGHT * 2.0f;
        player.addComponent(capsule);
        controller = new CharacterControllerComponent();
        player.addComponent(controller);
        world = new ArrayList<>(ground);
        world.add(player);
        physics = physics(world);
        step(physics, world, 5);
    }

    private float feet() {
        return player.getTransform().posY - HALF_HEIGHT;
    }

    private float z() {
        return player.getTransform().posZ;
    }

    /** Walks along Z until past {@code toZ} or out of time, calling {@code each} after every step. */
    private void walk(float direction, float toZ, Consumer<Float> each) {
        for (int frame = 0; frame < 1500 && (z() - toZ) * direction < 0.0f; frame++) {
            controller.velocityZ = SPEED * direction;
            step(physics, world, 1);
            each.accept(feet());
        }
    }

    private static List<Entity> stairs(int count, float run) {
        List<Entity> ground = new ArrayList<>();
        ground.add(box(0, -0.5f, 0, 10, 1, 60, STATIC));
        for (int i = 0; i < count; i++) {
            float height = RISE * (i + 1);
            ground.add(box(0, height * 0.5f, -2.0f - run * (i + 0.5f), 6, height, run, STATIC));
        }
        float top = RISE * count;
        ground.add(box(0, top * 0.5f, -2.0f - run * count - 5.0f, 6, top, 10, STATIC));
        return ground;
    }

    private Entity mesh(String name, String obj) throws IOException {
        Path file = dir.resolve(name + ".obj");
        Files.writeString(file, obj);
        Entity e = at(name, 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    /** One mesh: treads and risers climbing toward -Z from z = -2, as modelled stairs are built. */
    private Entity meshStairs(int count, float run) throws IOException {
        StringBuilder obj = new StringBuilder();
        int v = 0;
        float y = 0.0f, z = 30.0f;
        for (int i = 0; i <= count; i++) {
            float end = i == count ? -40.0f : -2.0f - run * i;
            obj.append("v -6 ").append(y).append(' ').append(z).append("\nv 6 ").append(y).append(' ').append(z).append('\n');
            obj.append("v -6 ").append(y).append(' ').append(end).append("\nv 6 ").append(y).append(' ').append(end).append('\n');
            obj.append("f ").append(v + 1).append(' ').append(v + 2).append(' ').append(v + 3).append("\nf ").append(v + 2).append(' ').append(v + 4).append(' ').append(v + 3).append('\n');
            v += 4;
            if (i == count) break;
            float next = y + RISE;
            obj.append("v -6 ").append(y).append(' ').append(end).append("\nv 6 ").append(y).append(' ').append(end).append('\n');
            obj.append("v -6 ").append(next).append(' ').append(end).append("\nv 6 ").append(next).append(' ').append(end).append('\n');
            obj.append("f ").append(v + 1).append(' ').append(v + 2).append(' ').append(v + 3).append("\nf ").append(v + 2).append(' ').append(v + 4).append(' ').append(v + 3).append('\n');
            v += 4;
            y = next;
            z = end;
        }
        return mesh("stairs", obj.toString());
    }

    @Test
    void climbsTreadsOfEveryDepth() {
        for (float run : new float[] {0.2f, 0.25f, 0.28f, 0.3f, 0.34f, 0.36f, 0.4f, 0.6f}) {
            start(stairs(8, run), 0.0f, 0.0f);
            walk(-1.0f, -2.0f - run * 8.0f - 1.0f, y -> {});
            assertEquals(RISE * 8, feet(), 0.01f, "tread " + run);
        }
    }

    @Test
    void climbsModelledStairs() throws IOException {
        start(List.of(meshStairs(8, 0.28f)), 0.0f, 0.0f);
        walk(-1.0f, -2.0f - 0.28f * 8.0f - 1.0f, y -> {});
        assertEquals(RISE * 8, feet(), 0.01f);
    }

    @Test
    void comesDownNarrowTreadsWithoutLeavingTheGround() {
        float top = -2.0f - 0.28f * 8.0f;
        start(stairs(8, 0.28f), top - 1.0f, RISE * 8);
        boolean[] airborne = {false};
        walk(1.0f, 0.0f, y -> airborne[0] |= !controller.isGrounded);
        assertEquals(0.0f, feet(), 0.01f);
        assertTrue(!airborne[0], "it should stick to the stairs");
    }

    @Test
    void standsStillWhenItStopsPartWayUp() {
        start(stairs(8, 0.28f), 0.0f, 0.0f);
        walk(-1.0f, -3.0f, y -> {});
        controller.velocityZ = 0.0f;
        step(physics, world, 30);

        float y = feet(), z = z();
        for (int i = 0; i < 120; i++) {
            step(physics, world, 1);
            assertEquals(y, feet(), 0.001f, "frame " + i);
            assertEquals(z, z(), 0.001f, "frame " + i);
        }
    }

    @Test
    void turnsRoundPartWayUp() {
        start(stairs(8, 0.28f), 0.0f, 0.0f);
        walk(-1.0f, -3.0f, y -> {});
        walk(1.0f, 0.0f, y -> {});
        assertEquals(0.0f, feet(), 0.01f);
    }

    @Test
    void aRiserTooTallIsStillAWall() {
        start(List.of(box(0, -0.5f, 0, 10, 1, 60, STATIC), box(0, 0.25f, -7.0f, 6, 0.5f, 10, STATIC)), 0.0f, 0.0f);
        walk(-1.0f, -3.0f, y -> {});
        assertEquals(0.0f, feet(), 0.01f);
        assertTrue(z() > -2.0f, "stopped at the wall, z=" + z());
    }

    @Test
    void aRampIsNotAStair() throws IOException {
        float slope = 0.45f, end = -32.0f, top = 30.0f * slope;
        start(List.of(mesh("ramp", "v -6 0 40\nv 6 0 40\nv -6 0 -2\nv 6 0 -2\nv -6 " + top + " " + end + "\nv 6 " + top + " " + end + "\nf 1 2 3\nf 2 4 3\nf 3 4 5\nf 4 6 5\n")), 0.0f, 0.0f);
        // Feet follow the surface under the capsule's centre from the flat, through the join, and up the slope
        walk(-1.0f, -8.0f, y -> {
            float surface = Math.max(0.0f, (-2.0f - z()) * slope);
            assertEquals(surface, y, 0.05f, "at z=" + z());
        });
    }
}
