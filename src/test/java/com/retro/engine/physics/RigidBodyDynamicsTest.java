package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.KINEMATIC;
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
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Behaviour that only exists because bodies have real shapes, rotation and mass. */
class RigidBodyDynamicsTest {

    private static Entity floor() {
        return box(0, -0.5f, 0, 200, 1, 200, STATIC);
    }

    /** A static ramp rising toward +X at the given angle, with its top surface through the origin. */
    private static Entity ramp(float degrees) {
        Entity ramp = box(0, 0, 0, 40, 1, 20, STATIC);
        ramp.getTransform().rotZ = -degrees; // Engine rotations run opposite to the right-hand rule
        double rad = Math.toRadians(degrees);
        ramp.getTransform().posX = (float) (0.5 * Math.sin(rad));
        ramp.getTransform().posY = (float) (-0.5 * Math.cos(rad));
        return ramp;
    }

    private static Entity capsule(float x, float y, float z, float radius, float height, RigidBodyComponent.MotionType motion) {
        Entity e = at("capsule", x, y, z);
        CapsuleCollider c = new CapsuleCollider();
        c.radius = radius;
        c.height = height;
        e.addComponent(c);
        RigidBodyComponent rb = new RigidBodyComponent();
        rb.motionType = motion;
        e.addComponent(rb);
        return e;
    }

    private static Entity character(float x, float y, float z) {
        Entity e = capsule(x, y, z, 0.4f, 1.8f, STATIC);
        e.removeComponent(RigidBodyComponent.class);
        e.addComponent(new CharacterControllerComponent());
        return e;
    }

    private static CharacterControllerComponent cc(Entity e) {
        return e.getComponent(CharacterControllerComponent.class);
    }

    @Test
    void rampIsWhereTheTestThinksItIs() {
        List<Entity> scene = scene(ramp(30));
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        RaycastHit hit = physics.raycast(5, 50, 0, 0, -1, 0, 100);
        assertTrue(hit.hit);
        assertEquals(5 * Math.tan(Math.toRadians(30)), hit.pointY, 0.01f);
        assertEquals(Math.cos(Math.toRadians(30)), hit.normalY, 0.01f);
        assertTrue(hit.normalX < -0.4f, "normal should lean downhill, nx=" + hit.normalX);
    }

    @Test
    void ballRollsDownARamp() {
        Entity ball = sphere(5, 4, 0, 0.5f, DYNAMIC);
        rigidBody(ball).useGravity = true;
        List<Entity> scene = scene(ramp(30), ball);
        step(physics(scene), scene, 120);
        assertTrue(ball.getTransform().posX < 3.0f, "x=" + ball.getTransform().posX);
    }

    @Test
    void highFrictionBoxStaysOnAGentleRamp() {
        double rad = Math.toRadians(15);
        float x = 3.0f, surface = (float) (x * Math.tan(rad));
        Entity crate = box(x - 0.13f, surface + 0.49f, 0, 1, 1, 1, DYNAMIC);
        crate.getTransform().rotZ = -15.0f;
        rigidBody(crate).friction = 1.0f;
        List<Entity> scene = scene(ramp(15), crate);
        step(physics(scene), scene, 180);
        assertEquals(x - 0.13f, crate.getTransform().posX, 0.15f);
    }

    @Test
    void frictionlessBoxSlidesDownARamp() {
        double rad = Math.toRadians(15);
        float x = 6.0f, surface = (float) (x * Math.tan(rad));
        Entity crate = box(x - 0.13f, surface + 0.49f, 0, 1, 1, 1, DYNAMIC);
        crate.getTransform().rotZ = -15.0f;
        rigidBody(crate).friction = 0.0f;
        List<Entity> scene = scene(ramp(15), crate);
        step(physics(scene), scene, 120);
        assertTrue(crate.getTransform().posX < x - 2.0f, "x=" + crate.getTransform().posX);
    }

    @Test
    void boxOverAnEdgeTipsOff() {
        Entity ledge = box(-5, -0.5f, 0, 10, 1, 10, STATIC); // Top surface ends at x=0
        Entity crate = box(0.3f, 0.5f, 0, 1, 1, 1, DYNAMIC); // Centre of mass past the edge
        List<Entity> scene = scene(ledge, crate);
        step(physics(scene), scene, 60);
        assertTrue(Math.abs(crate.getTransform().rotZ) > 5.0f, "rotZ=" + crate.getTransform().rotZ);
        assertTrue(crate.getTransform().posY < 0.4f, "y=" + crate.getTransform().posY);
    }

    @Test
    void droppedTiltedBoxSettlesFlat() {
        Entity crate = box(0, 3, 0, 1, 1, 1, DYNAMIC);
        crate.getTransform().rotX = 25.0f;
        crate.getTransform().rotZ = 40.0f;
        List<Entity> scene = scene(floor(), crate);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 480);
        assertEquals(0.5f, crate.getTransform().posY, 0.03f);
        PhysicsSystem.PhysicsBody body = physics.getBody(crate);
        assertTrue(Math.abs(body.vy) < 0.1f && Math.abs(body.avx) + Math.abs(body.avz) < 0.2f, "still moving");
    }

    @Test
    void capsuleLyingDownRestsOnItsSide() {
        Entity log = capsule(0, 1, 0, 0.3f, 2, DYNAMIC);
        log.getTransform().rotZ = 90.0f;
        List<Entity> scene = scene(floor(), log);
        step(physics(scene), scene, 240);
        assertEquals(0.3f, log.getTransform().posY, 0.03f);
    }

    @Test
    void equalBallsSwapVelocity() {
        Entity moving = sphere(0, 0, 0, 0.5f, DYNAMIC);
        Entity still = sphere(2, 0, 0, 0.5f, DYNAMIC);
        rigidBody(moving).restitution = 1.0f;
        rigidBody(still).restitution = 1.0f;
        List<Entity> scene = scene(moving, still);
        PhysicsSystem physics = physics(scene);
        physics.getBody(moving).vx = 4.0f;
        step(physics, scene, 40);
        assertEquals(0.0f, physics.getBody(moving).vx, 0.1f);
        assertEquals(4.0f, physics.getBody(still).vx, 0.1f);
    }

    @Test
    void bounceLosesEnergy() {
        Entity ball = sphere(0, 5, 0, 0.5f, DYNAMIC);
        rigidBody(ball).useGravity = true;
        rigidBody(ball).restitution = 0.8f;
        List<Entity> scene = scene(floor(), ball);
        PhysicsSystem physics = physics(scene);
        float peakAfterBounce = 0.0f;
        boolean bounced = false;
        for (int i = 0; i < 180; i++) {
            step(physics, scene, 1);
            if (physics.getBody(ball).vy > 0.0f) bounced = true;
            if (bounced) peakAfterBounce = Math.max(peakAfterBounce, ball.getTransform().posY);
        }
        assertTrue(bounced, "never bounced");
        assertTrue(peakAfterBounce > 2.0f && peakAfterBounce < 4.5f, "peak=" + peakAfterBounce);
    }

    @Test
    void offsetColliderLandsOnItsOffset() {
        Entity crate = box(0, 3, 0, 1, 1, 1, DYNAMIC);
        crate.getComponent(BoxCollider.class).offsetY = -1.0f; // Collider hangs a metre below the pivot
        List<Entity> scene = scene(floor(), crate);
        step(physics(scene), scene, 240);
        assertEquals(1.5f, crate.getTransform().posY, 0.05f);
    }

    @Test
    void rayHitsARotatedBoxOnItsTrueSurface() {
        Entity diamond = box(0, 0, 0, 2, 2, 2, STATIC);
        diamond.getTransform().rotZ = 45.0f;
        List<Entity> scene = scene(diamond);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        RaycastHit hit = physics.raycast(0, 10, 0, 0, -1, 0, 50);
        assertEquals(Math.sqrt(2.0), hit.pointY, 0.01f);
    }

    @Test
    void rayHitsACapsuleOnItsRoundedEnd() {
        Entity pill = capsule(0, 0, 0, 0.5f, 3, STATIC);
        List<Entity> scene = scene(pill);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 1);
        assertEquals(1.5f, physics.raycast(0, 10, 0, 0, -1, 0, 50).pointY, 0.01f);
        assertEquals(0.5f, physics.raycast(5, 0, 0, -1, 0, 0, 50).pointX, 0.01f);
        assertFalse(physics.raycast(0.45f, 10, 0.45f, 0, -1, 0, 50).hit, "past the rounded corner");
    }

    @Test
    void kinematicPlatformCarriesFrictionToARider() {
        Entity platform = box(0, 0, 0, 20, 1, 20, KINEMATIC);
        Entity crate = box(0, 1.0f, 0, 1, 1, 1, DYNAMIC);
        rigidBody(crate).friction = 1.0f;
        rigidBody(platform).friction = 1.0f;
        List<Entity> scene = scene(platform, crate);
        PhysicsSystem physics = physics(scene);
        for (int i = 0; i < 120; i++) {
            platform.getTransform().posX += 2.0f * TestEntities.FRAME;
            physics.step(TestEntities.FRAME, scene);
        }
        assertTrue(crate.getTransform().posX > 2.5f, "x=" + crate.getTransform().posX);
    }

    @Test
    void parentedDynamicBodyFallsInWorldSpace() {
        SceneWorld world = new SceneWorld();
        Entity parent = world.spawnEntity("parent", "parent", null);
        Entity crate = world.spawnEntity("crate", "crate", null);
        Entity ground = world.spawnEntity("ground", "ground", null);
        crate.addComponent(new BoxCollider());
        crate.addComponent(new RigidBodyComponent());
        BoxCollider slab = new BoxCollider();
        slab.sizeX = 50; slab.sizeZ = 50;
        ground.addComponent(slab);
        ground.getTransform().posY = -0.5f;
        assertTrue(world.setParent(crate, parent));
        parent.getTransform().posX = 10.0f;
        parent.getTransform().posY = 2.0f;
        crate.getTransform().posY = 1.0f;

        world.play();
        try {
            for (int i = 0; i < 240; i++) world.update(TestEntities.FRAME);
            SceneWorld.WorldTransform wt = world.getComputedWorldTransform(crate);
            assertEquals(10.0f, wt.px(), 0.05f);
            assertEquals(0.5f, wt.py(), 0.05f);
            assertEquals(-1.5f, crate.getTransform().posY, 0.05f, "local Y");
        } finally {
            world.stop();
        }
    }

    @Test
    void characterWalksUpARampItCanClimb() {
        Entity hero = character(2, 2.5f, 0);
        List<Entity> scene = scene(ramp(30), hero);
        PhysicsSystem physics = physics(scene);
        for (int i = 0; i < 180; i++) {
            cc(hero).velocityX = 3.0f;
            physics.step(TestEntities.FRAME, scene);
        }
        float x = hero.getTransform().posX;
        assertTrue(x > 6.0f, "x=" + x);
        assertTrue(cc(hero).isGrounded, "should stay grounded");
        // Centre sits half a height above the surface, plus what the rounded base needs on a slope
        float surface = (float) (x * Math.tan(Math.toRadians(30)));
        assertEquals(surface + 0.9f, hero.getTransform().posY, 0.2f);
    }

    @Test
    void characterStandsStillOnAWalkableRamp() {
        Entity hero = character(5, 4, 0);
        List<Entity> scene = scene(ramp(30), hero);
        step(physics(scene), scene, 180);
        assertEquals(5.0f, hero.getTransform().posX, 0.02f);
        assertTrue(cc(hero).isGrounded);
    }

    @Test
    void characterSlidesOffASlopePastItsLimit() {
        Entity hero = character(5, 10, 0);
        List<Entity> scene = scene(ramp(60), hero);
        step(physics(scene), scene, 240);
        assertTrue(hero.getTransform().posX < 4.5f, "x=" + hero.getTransform().posX);
        assertFalse(cc(hero).isGrounded);
    }

    @Test
    void characterSlidesAlongARotatedWallWithoutCrossingIt() {
        Entity wall = box(5, 1, 0, 1, 4, 20, STATIC);
        wall.getTransform().rotY = 30.0f;
        Entity hero = character(0, 0.9f, 0);
        List<Entity> scene = scene(floor(), wall, hero);
        PhysicsSystem physics = physics(scene);
        float nx = (float) Math.cos(Math.toRadians(30)), nz = (float) Math.sin(Math.toRadians(30)); // Wall's local X
        for (int i = 0; i < 120; i++) {
            cc(hero).velocityX = 5.0f;
            physics.step(TestEntities.FRAME, scene);
            float depth = (hero.getTransform().posX - 5.0f) * nx + hero.getTransform().posZ * nz;
            assertTrue(depth < -0.85f, "frame " + i + " reached the wall's plane, depth=" + depth);
        }
        assertTrue(Math.abs(hero.getTransform().posZ) > 1.0f, "should slide, z=" + hero.getTransform().posZ);
    }

    @Test
    void walkingIntoASteepSlopeDoesNotClimbIt() {
        Entity hero = character(1, 0.9f, 0);
        List<Entity> scene = scene(floor(), ramp(70), hero);
        hero.getTransform().posX = -2.0f;
        PhysicsSystem physics = physics(scene);
        for (int i = 0; i < 240; i++) {
            cc(hero).velocityX = 4.0f;
            physics.step(TestEntities.FRAME, scene);
        }
        assertEquals(0.9f, hero.getTransform().posY, 0.05f);
    }

    @Test
    void characterPushesACrate() {
        Entity crate = box(2, 0.5f, 0, 1, 1, 1, DYNAMIC);
        Entity hero = character(0, 0.9f, 0);
        List<Entity> scene = scene(floor(), crate, hero);
        PhysicsSystem physics = physics(scene);
        for (int i = 0; i < 90; i++) {
            cc(hero).velocityX = 3.0f;
            physics.step(TestEntities.FRAME, scene);
        }
        assertTrue(crate.getTransform().posX > 3.0f, "x=" + crate.getTransform().posX);
    }

    @Test
    void wallPiecesSideBySideDoNotPushACharacterTwice() {
        Entity left = box(1, 1, -1, 1, 4, 2, STATIC);
        Entity right = box(1, 1, 1, 1, 4, 2, STATIC);
        Entity hero = character(0.2f, 0.9f, 0); // Overlaps both by 0.1
        List<Entity> scene = scene(floor(), left, right, hero);
        step(physics(scene), scene, 1);
        assertEquals(0.1f, hero.getTransform().posX, 0.01f);
    }
}
