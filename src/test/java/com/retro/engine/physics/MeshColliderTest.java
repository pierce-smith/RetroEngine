package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.rigidBody;
import static com.retro.engine.testing.TestEntities.scene;
import static com.retro.engine.testing.TestEntities.sphere;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.MeshCollider;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Mesh colliders, read from real OBJ files, and collision layers. */
class MeshColliderTest {

    @TempDir Path dir;

    /** A grid of quads at y = height(x, z), spanning -size to size. */
    private Entity terrain(float size, int cells, java.util.function.BiFunction<Float, Float, Float> height) throws IOException {
        StringBuilder obj = new StringBuilder();
        int side = cells + 1;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                float wx = -size + 2 * size * x / cells, wz = -size + 2 * size * z / cells;
                obj.append("v ").append(wx).append(' ').append(height.apply(wx, wz)).append(' ').append(wz).append('\n');
            }
        }
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int a = z * side + x + 1, b = a + 1, c = a + side, d = c + 1;
                obj.append("f ").append(a).append(' ').append(c).append(' ').append(b).append('\n');
                obj.append("f ").append(b).append(' ').append(c).append(' ').append(d).append('\n');
            }
        }
        Path file = dir.resolve("terrain" + System.nanoTime() + ".obj");
        Files.writeString(file, obj.toString());
        Entity e = at("terrain", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    private Entity flat() throws IOException {
        return terrain(20, 8, (x, z) -> 0.0f);
    }

    private static Entity character(float x, float y, float z) {
        Entity e = at("hero", x, y, z);
        CapsuleCollider c = new CapsuleCollider();
        c.radius = 0.4f;
        c.height = 1.8f;
        e.addComponent(c);
        e.addComponent(new CharacterControllerComponent());
        return e;
    }

    @Test
    void rayHitsTheTerrainSurface() throws IOException {
        List<Entity> scene = scene(terrain(20, 16, (x, z) -> x * 0.5f));
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        RaycastHit hit = physics.raycast(4, 50, 3, 0, -1, 0, 100);
        assertTrue(hit.hit);
        assertEquals(2.0f, hit.pointY, 0.02f);
        assertTrue(hit.normalY > 0.8f, "normal should face the ray, ny=" + hit.normalY);
    }

    @Test
    void meshFollowsItsEntityTransform() throws IOException {
        Entity ground = flat();
        ground.getTransform().posY = 5.0f;
        ground.getTransform().scaleX = 2.0f;
        List<Entity> scene = scene(ground);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        assertEquals(5.0f, physics.raycast(30, 50, 0, 0, -1, 0, 100).pointY, 0.01f);
    }

    @Test
    void boxRestsOnAMesh() throws IOException {
        Entity crate = box(1.3f, 3, 0.7f, 1, 1, 1, DYNAMIC);
        List<Entity> scene = scene(flat(), crate);
        step(physics(scene), scene, 300);
        assertEquals(0.5f, crate.getTransform().posY, 0.03f);
        assertEquals(0.0f, crate.getTransform().rotX, 2.0f);
    }

    @Test
    void ballRollsDownhillOnAMesh() throws IOException {
        Entity ball = sphere(8, 6, 0, 0.5f, DYNAMIC);
        rigidBody(ball).useGravity = true;
        List<Entity> scene = scene(terrain(20, 16, (x, z) -> x * 0.4f), ball);
        step(physics(scene), scene, 150);
        assertTrue(ball.getTransform().posX < 6.0f, "x=" + ball.getTransform().posX);
    }

    @Test
    void fastBodyDoesNotPassThroughAMesh() throws IOException {
        Entity bullet = sphere(0, 6, 0, 0.05f, DYNAMIC);
        List<Entity> scene = scene(flat(), bullet);
        PhysicsSystem physics = physics(scene);
        physics.getBody(bullet).vy = -90.0f;
        step(physics, scene, 12);
        assertTrue(bullet.getTransform().posY > 0.0f, "y=" + bullet.getTransform().posY);
    }

    @Test
    void characterWalksOverRollingTerrain() throws IOException {
        Entity hero = character(-10, 4, 0);
        CharacterControllerComponent cc = hero.getComponent(CharacterControllerComponent.class);
        List<Entity> scene = scene(terrain(20, 40, (x, z) -> (float) Math.sin(x * 0.5) * 0.8f), hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 60);
        for (int i = 0; i < 240; i++) {
            cc.velocityX = 4.0f;
            physics.step(TestEntities.FRAME, scene);
            float surface = (float) Math.sin(hero.getTransform().posX * 0.5) * 0.8f;
            assertTrue(hero.getTransform().posY - surface > 0.6f, "sank in at frame " + i);
            assertTrue(hero.getTransform().posY - surface < 1.6f, "floated at frame " + i);
        }
        assertTrue(hero.getTransform().posX > 4.0f, "x=" + hero.getTransform().posX);
    }

    @Test
    void characterClimbsAMeshStepButNotAWall() throws IOException {
        // A 0.25 m ledge at x=2, and a 3 m wall at x=6
        Entity level = terrain(10, 80, (x, z) -> x >= 6.0f ? 3.0f : (x >= 2.0f ? 0.25f : 0.0f));
        Entity hero = character(0, 0.9f, 0);
        CharacterControllerComponent cc = hero.getComponent(CharacterControllerComponent.class);
        List<Entity> scene = scene(level, hero);
        PhysicsSystem physics = physics(scene);
        for (int i = 0; i < 240; i++) {
            cc.velocityX = 3.0f;
            physics.step(TestEntities.FRAME, scene);
        }
        assertEquals(1.15f, hero.getTransform().posY, 0.1f);
        assertTrue(hero.getTransform().posX > 3.0f && hero.getTransform().posX < 6.0f, "x=" + hero.getTransform().posX);
    }

    @Test
    void meshColliderStaysPutWhateverItsRigidBodySays() throws IOException {
        Entity ground = flat();
        ground.addComponent(new com.retro.engine.ecs.components.RigidBodyComponent());
        List<Entity> scene = scene(ground);
        step(physics(scene), scene, 60);
        assertEquals(0.0f, ground.getTransform().posY);
    }

    @Test
    void missingMeshFallsBackInsteadOfCrashing() {
        Entity e = at("broken", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = dir.resolve("nope.obj").toString();
        e.addComponent(collider);
        List<Entity> scene = scene(e);
        step(physics(scene), scene, 2);
    }

    @Test
    void layersThatIgnoreEachOtherPassThrough() {
        Entity floor = box(0, -0.5f, 0, 50, 1, 50, STATIC);
        Entity ghost = box(0, 2, 0, 1, 1, 1, DYNAMIC);
        Entity crate = box(3, 2, 0, 1, 1, 1, DYNAMIC);
        BoxCollider ghostBox = ghost.getComponent(BoxCollider.class);
        ghostBox.collisionLayer = 4;
        ghostBox.collidesWith = "4";
        List<Entity> scene = scene(floor, ghost, crate);
        step(physics(scene), scene, 180);
        assertTrue(ghost.getTransform().posY < -5.0f, "ghost y=" + ghost.getTransform().posY);
        assertEquals(0.5f, crate.getTransform().posY, 0.05f);
    }

    @Test
    void oneSidedLayerRuleStillBlocksNeitherWay() {
        // Both sides have to agree, so a body can opt out of a layer that still lists it
        Entity floor = box(0, -0.5f, 0, 50, 1, 50, STATIC);
        Entity crate = box(0, 2, 0, 1, 1, 1, DYNAMIC);
        crate.getComponent(BoxCollider.class).collidesWith = "7";
        List<Entity> scene = scene(floor, crate);
        step(physics(scene), scene, 120);
        assertTrue(crate.getTransform().posY < 0.0f, "y=" + crate.getTransform().posY);
    }

    @Test
    void raycastSeesOnlyTheLayersItAskedFor() {
        Entity near = box(0, 5, 0, 2, 1, 2, STATIC);
        Entity far = box(0, 0, 0, 2, 1, 2, STATIC);
        near.getComponent(BoxCollider.class).collisionLayer = 3;
        List<Entity> scene = scene(near, far);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        RaycastHit hit = new RaycastHit();
        physics.raycastExcludingInPlace(null, 0, 20, 0, 0, -1, 0, 50, hit);
        assertEquals(near, hit.entity);
        physics.raycastExcludingInPlace(null, 0, 20, 0, 0, -1, 0, 50, hit, 1);
        assertEquals(far, hit.entity);
        physics.raycastExcludingInPlace(null, 0, 20, 0, 0, -1, 0, 50, hit, 1 << 9);
        assertFalse(hit.hit);
    }

    @Test
    void sphereColliderReadsItsLayerToo() {
        Entity a = sphere(0, 0, 0, 0.5f, STATIC);
        a.getComponent(SphereCollider.class).collisionLayer = 31;
        List<Entity> scene = scene(a);
        PhysicsSystem physics = physics(scene);
        assertEquals(1 << 31, physics.getBody(a).layerBit);
    }

    @Test
    void layerListParsing() {
        assertEquals(-1, PhysicsSystem.parseLayerMask(""));
        assertEquals(-1, PhysicsSystem.parseLayerMask("  "));
        assertEquals(0b1001, PhysicsSystem.parseLayerMask("0, 3"));
        assertEquals(1, PhysicsSystem.parseLayerMask("0,banana,99"));
    }
}
