package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
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
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.testing.KnownIssue;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * What collision is supposed to do. The @KnownIssue tests are the physics section of the 1.0 diagnostic: they turn
 * into plain regression tests as the solver work lands.
 */
class PhysicsCollisionTest {

    private static final float DOWN = -1.0f;

    private static boolean rayDownHits(PhysicsSystem physics, float x) {
        return physics.raycast(x, 10.0f, 0.0f, 0.0f, DOWN, 0.0f, 50.0f).hit;
    }

    @Test
    void boxesFarApartDoNotInteract() {
        Entity a = box(0, 0, 0, 1, 1, 1, STATIC);
        Entity b = box(5, 0, 0, 1, 1, 1, DYNAMIC);
        rigidBody(b).useGravity = false;
        List<Entity> scene = scene(a, b);
        step(physics(scene), scene, 5);
        assertEquals(5.0f, b.getTransform().posX);
    }

    @Test
    void droppedBoxComesToRestOnTheFloor() {
        Entity floor = box(0, -0.5f, 0, 20, 1, 20, STATIC);
        Entity crate = box(0, 3, 0, 1, 1, 1, DYNAMIC);
        List<Entity> scene = scene(floor, crate);
        step(physics(scene), scene, 300);
        assertEquals(0.5f, crate.getTransform().posY, 0.05f);
    }

    @Test
    void stackOfBoxesStaysStable() {
        List<Entity> scene = scene(box(0, -0.5f, 0, 20, 1, 20, STATIC));
        for (int i = 0; i < 5; i++) scene.add(box(0, 0.5f + i, 0, 1, 1, 1, DYNAMIC));
        step(physics(scene), scene, 600);
        for (int i = 0; i < 5; i++) {
            assertEquals(0.5f + i, scene.get(i + 1).getTransform().posY, 0.05f, "box " + i);
        }
    }

    @Test
    void rayHitsAnUnrotatedBoxAndMissesBesideIt() {
        List<Entity> scene = scene(box(0, 0, 0, 2, 2, 2, STATIC));
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        assertTrue(rayDownHits(physics, 0.0f));
        assertFalse(rayDownHits(physics, 1.5f));
    }

    @Test
    void sameSceneSimulatesIdenticallyTwice() {
        assertEquals(finalPositions(), finalPositions());
    }

    /** Exact bits of every body after a messy pile-up. */
    private static List<Integer> finalPositions() {
        List<Entity> scene = scene(box(0, -0.5f, 0, 40, 1, 40, STATIC));
        for (int i = 0; i < 100; i++) {
            scene.add(box((i % 5) * 0.9f, 2 + (i / 25) * 1.1f, ((i / 5) % 5) * 0.9f, 1, 1, 1, DYNAMIC));
        }
        step(physics(scene), scene, 300);
        List<Integer> bits = new ArrayList<>();
        for (Entity e : scene) {
            Transform t = e.getTransform();
            bits.add(Float.floatToIntBits(t.posX));
            bits.add(Float.floatToIntBits(t.posY));
            bits.add(Float.floatToIntBits(t.posZ));
        }
        return bits;
    }

    @Test
    @KnownIssue("Spheres collide as boxes (diagnostic 5.3)")
    void spheresThatDoNotTouchDoNotCollide() {
        Entity a = sphere(0, 0, 0, 0.5f, STATIC);
        Entity b = sphere(0.8f, 0.7f, 0, 0.5f, DYNAMIC); // centers 1.06 apart, radii sum to 1.0
        List<Entity> scene = scene(a, b);
        step(physics(scene), scene, 3);
        assertEquals(0.8f, b.getTransform().posX, 0.001f);
        assertEquals(0.7f, b.getTransform().posY, 0.001f);
    }

    @Test
    @KnownIssue("Equal X and Y overlap picks the Z axis and teleports the body (diagnostic section 4)")
    void tiedOverlapDoesNotTeleportAlongAnUninvolvedAxis() {
        Entity a = sphere(0, 0, 0, 0.5f, STATIC);
        Entity b = sphere(0.8f, 0.8f, 0, 0.5f, DYNAMIC);
        List<Entity> scene = scene(a, b);
        step(physics(scene), scene, 3);
        assertEquals(0.0f, b.getTransform().posZ, 0.001f);
    }

    @Test
    @KnownIssue("Collider rotation is ignored (diagnostic 5.3)")
    void rayMissesPastTheEdgeOfARotatedPlate() {
        Entity plate = box(0, 0, 0, 4, 0.2f, 4, STATIC);
        plate.getTransform().rotZ = 45.0f; // true half-width on X is now about 1.48
        List<Entity> scene = scene(plate);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        assertFalse(rayDownHits(physics, 1.8f));
    }

    @Test
    @KnownIssue("Bodies read local Transform values, not world ones (diagnostic 5.3)")
    void parentedColliderIsFoundAtItsWorldPosition() {
        SceneWorld world = new SceneWorld();
        Entity parent = world.spawnEntity("parent", "parent", null);
        Entity child = world.spawnEntity("child", "child", null);
        child.addComponent(new BoxCollider());
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = STATIC;
        child.addComponent(body);
        assertTrue(world.setParent(child, parent));
        parent.getTransform().posX = 10.0f;
        assertEquals(10.0f, world.getComputedWorldTransform(child).px(), 0.001f, "test setup");

        world.play();
        world.update(TestEntities.FRAME);
        try {
            assertTrue(world.raycastExcluding(null, 10, 10, 0, 0, DOWN, 0, 50).hit, "should hit at x=10");
            assertFalse(world.raycastExcluding(null, 0, 10, 0, 0, DOWN, 0, 50).hit, "should miss at x=0");
        } finally {
            world.stop();
        }
    }

    @Test
    @KnownIssue("Collider offsets are ignored (diagnostic 5.3)")
    void colliderOffsetMovesTheCollider() {
        Entity e = box(0, 0, 0, 1, 1, 1, STATIC);
        e.getComponent(BoxCollider.class).offsetX = 5.0f;
        List<Entity> scene = scene(e);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        assertTrue(rayDownHits(physics, 5.0f), "should hit at the offset");
        assertFalse(rayDownHits(physics, 0.0f), "should miss at the origin");
    }

    @Test
    @KnownIssue("Contacts ignore mass and transfer no momentum (diagnostic 5.3)")
    void heavyBodyPlowsThroughALightOne() {
        Entity heavy = box(0, 0, 0, 1, 1, 1, DYNAMIC);
        Entity light = box(1.2f, 0, 0, 1, 1, 1, DYNAMIC);
        for (Entity e : List.of(heavy, light)) {
            rigidBody(e).useGravity = false;
            rigidBody(e).linearDrag = 0.0f;
        }
        rigidBody(heavy).mass = 1000.0f;
        List<Entity> scene = scene(heavy, light);
        PhysicsSystem physics = physics(scene);
        physics.getBody(heavy).vx = 5.0f;
        step(physics, scene, 30);
        assertTrue(physics.getBody(heavy).vx > 4.0f, "heavy vx=" + physics.getBody(heavy).vx);
        assertTrue(physics.getBody(light).vx > 5.0f, "light vx=" + physics.getBody(light).vx);
    }

    @Test
    @KnownIssue("RigidBodyComponent.friction is never read (diagnostic 5.3)")
    void frictionStopsASlidingBox() {
        Entity floor = box(0, -0.5f, 0, 200, 1, 200, STATIC);
        Entity slider = box(0, 0.5f, 0, 1, 1, 1, DYNAMIC);
        rigidBody(slider).friction = 1.0f;
        rigidBody(slider).linearDrag = 0.0f;
        List<Entity> scene = scene(floor, slider);
        PhysicsSystem physics = physics(scene);
        physics.getBody(slider).vx = 5.0f;
        step(physics, scene, 120);
        assertEquals(0.0f, physics.getBody(slider).vx, 0.5f);
    }

    @Test
    @KnownIssue("No continuous collision, so fast bodies tunnel (diagnostic 5.3)")
    void fastBodyDoesNotPassThroughAThinWall() {
        Entity wall = box(0, 0, 0, 0.2f, 10, 10, STATIC);
        Entity bullet = sphere(-5.5f, 0, 0, 0.05f, DYNAMIC); // 1 m per step, so no step lands inside the wall
        List<Entity> scene = scene(wall, bullet);
        PhysicsSystem physics = physics(scene);
        physics.getBody(bullet).vx = 60.0f;
        step(physics, scene, 12);
        assertTrue(bullet.getTransform().posX < 0.0f, "x=" + bullet.getTransform().posX);
    }
}
