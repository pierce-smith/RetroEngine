package com.retro.engine.physics;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.KINEMATIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.scene;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.MeshCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Characters riding whatever they stand on. */
class CharacterCarryTest {

    private static final float FRAME = TestEntities.FRAME;

    @TempDir Path dir;

    private static Entity hero(float x, float y, float z) {
        Entity e = at("hero", x, y, z);
        CapsuleCollider c = new CapsuleCollider();
        c.radius = 0.4f;
        c.height = 1.8f;
        e.addComponent(c);
        e.addComponent(new CharacterControllerComponent());
        return e;
    }

    /** A 40 m kinematic deck with its top at the origin's height. */
    private static Entity deck() {
        return box(0, -0.5f, 0, 40, 1, 40, KINEMATIC);
    }

    private Entity meshDeck() throws IOException {
        Path file = dir.resolve("deck.obj");
        Files.writeString(file, "v -20 0 -20\nv -20 0 20\nv 20 0 20\nv 20 0 -20\nf 1 2 3\nf 1 3 4\n");
        Entity e = at("deck", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = KINEMATIC;
        e.addComponent(body);
        return e;
    }

    private static void drive(PhysicsSystem physics, List<Entity> scene, Entity deck, float vx, float vy, float yawSpeed, int frames) {
        Transform t = deck.getTransform();
        for (int i = 0; i < frames; i++) {
            t.posX += vx * FRAME;
            t.posY += vy * FRAME;
            t.rotY += yawSpeed * FRAME;
            physics.step(FRAME, scene);
        }
    }

    @Test
    void rideSideways() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 2, 0, 0, 120);
        assertEquals(3.0f + deck.getTransform().posX, hero.getTransform().posX, 0.05f);
    }

    @Test
    void rideAMeshSideways() throws IOException {
        Entity deck = meshDeck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 2, 0, 0, 120);
        assertEquals(3.0f + deck.getTransform().posX, hero.getTransform().posX, 0.05f);
    }

    @Test
    void turnWithTheDeck() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        float startYaw = hero.getTransform().rotY;
        drive(physics, scene, deck, 0, 0, 45, 120); // A quarter turn
        float[] r = new float[9];
        PhysicsMath.eulerToMatrix(0, 90, 0, r);
        assertEquals(r[0] * 3.0f, hero.getTransform().posX, 0.1f);
        assertEquals(r[6] * 3.0f, hero.getTransform().posZ, 0.1f);
        assertEquals(startYaw + 90.0f, hero.getTransform().rotY, 0.5f);
    }

    @Test
    void turnCarriesAcrossTheWrapAtFullCircle() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        deck.getTransform().rotY = 179.0f;
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        float startYaw = hero.getTransform().rotY;
        deck.getTransform().rotY = -179.0f; // The short way round is 2 degrees
        physics.step(FRAME, scene);
        assertEquals(startYaw + 2.0f, hero.getTransform().rotY, 0.01f);
    }

    @Test
    void stayOnAFastLiftGoingDown() throws IOException {
        Entity deck = meshDeck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 0, -30, 0, 60);
        assertTrue(hero.getComponent(CharacterControllerComponent.class).isGrounded);
        assertEquals(deck.getTransform().posY + 0.9f, hero.getTransform().posY, 0.05f);
    }

    @Test
    void walkingAddsToTheRide() {
        Entity deck = deck(), hero = hero(0, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        CharacterControllerComponent cc = hero.getComponent(CharacterControllerComponent.class);
        for (int i = 0; i < 120; i++) {
            cc.velocityX = 3.0f;
            drive(physics, scene, deck, 2, 0, 0, 1);
        }
        assertEquals(deck.getTransform().posX + 6.0f, hero.getTransform().posX, 0.2f);
    }

    @Test
    void scriptTeleportIsKept() {
        Entity deck = deck(), hero = hero(0, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        hero.getTransform().posX = 10.0f;
        drive(physics, scene, deck, 2, 0, 0, 60);
        assertEquals(10.0f + deck.getTransform().posX, hero.getTransform().posX, 0.1f);
    }

    @Test
    void aWallTakesTheRiderOffTheDeck() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        Entity wall = box(8, 3.0f, 0, 2, 4, 40, STATIC);
        List<Entity> scene = scene(deck, hero, wall);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 2, 0, 0, 240);
        assertEquals(6.6f, hero.getTransform().posX, 0.1f);
    }

    private static void jump(Entity hero, float speed) {
        CharacterControllerComponent cc = hero.getComponent(CharacterControllerComponent.class);
        cc.velocityY = speed;
        cc.isGrounded = false;
    }

    @Test
    void jumpComesDownInTheSameSpotOnTheDeck() {
        Entity deck = deck(), hero = hero(0, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 6, 0, 0, 30);
        jump(hero, 5.0f);
        drive(physics, scene, deck, 6, 0, 0, 10);
        assertFalse(hero.getComponent(CharacterControllerComponent.class).isGrounded);
        drive(physics, scene, deck, 6, 0, 0, 80);
        assertTrue(hero.getComponent(CharacterControllerComponent.class).isGrounded);
        assertEquals(deck.getTransform().posX, hero.getTransform().posX, 0.15f);
    }

    @Test
    void jumpOffARisingLiftGoesHigher() throws IOException {
        Entity deck = meshDeck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 0, 6, 0, 30);
        jump(hero, 5.0f);
        drive(physics, scene, deck, 0, 6, 0, 1);
        assertEquals(11.0f, hero.getComponent(CharacterControllerComponent.class).velocityY, 0.5f);
    }

    @Test
    void wallStopsTheSpeedKeptFromTheDeck() {
        Entity deck = deck(), hero = hero(0, 1.0f, 0);
        Entity wall = box(5, 3.0f, 0, 2, 4, 40, STATIC);
        List<Entity> scene = scene(deck, hero, wall);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 6, 0, 0, 20);
        jump(hero, 6.0f);
        drive(physics, scene, deck, 6, 0, 0, 45);
        assertEquals(3.6f, hero.getTransform().posX, 0.1f);
        assertEquals(0.0f, physics.getBody(hero).airVx, 0.01f);
    }

    @Test
    void landingOnStillGroundEndsTheKeptSpeed() {
        Entity deck = deck(), floor = box(0, -3.5f, 0, 200, 1, 200, STATIC), hero = hero(0, 1.0f, 0);
        List<Entity> scene = new java.util.ArrayList<>(scene(deck, floor, hero));
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 6, 0, 0, 30);
        jump(hero, 2.0f);
        physics.step(FRAME, scene);
        physics.removeRuntimeEntity(deck);
        scene.remove(deck);
        step(physics, scene, 90);
        float landed = hero.getTransform().posX;
        assertTrue(landed > 1.0f, "x=" + landed);
        step(physics, scene, 30);
        assertEquals(landed, hero.getTransform().posX, 0.001f);
    }

    @Test
    void staticColliderCarriesNothing() {
        Entity deck = box(0, -0.5f, 0, 40, 1, 40, STATIC), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 2, 0, 0, 60);
        assertEquals(3.0f, hero.getTransform().posX, 0.01f);
    }

    @Test
    void framesTooShortForAPhysicsStepStillCarry() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        float dt = 1.0f / 240.0f;
        for (int i = 0; i < 240; i++) {
            deck.getTransform().posX += 2.0f * dt;
            physics.step(dt, scene);
            assertEquals(3.0f + deck.getTransform().posX, hero.getTransform().posX, 0.01f, "frame " + i);
        }
    }

    @Test
    void rideADynamicRaft() {
        Entity raft = box(0, -0.5f, 0, 40, 1, 40, com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC);
        raft.getComponent(RigidBodyComponent.class).useGravity = false;
        Entity hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(raft, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        float offset = hero.getTransform().posX - raft.getTransform().posX;
        for (int i = 0; i < 120; i++) {
            physics.getBody(raft).vx = 3.0f;
            physics.step(FRAME, scene);
        }
        assertTrue(raft.getTransform().posX > 4.0f, "x=" + raft.getTransform().posX);
        assertEquals(offset, hero.getTransform().posX - raft.getTransform().posX, 0.1f);
    }

    @Test
    void rideAVehicleThroughATurn() {
        Entity floor = box(0, -0.5f, 0, 400, 1, 400, STATIC);
        Entity boat = box(0, 0.5f, 0, 8, 1, 8, STATIC);
        boat.removeComponent(RigidBodyComponent.class);
        VehicleControllerComponent vehicle = new VehicleControllerComponent();
        boat.addComponent(vehicle);
        Entity hero = hero(2, 2.0f, 0);
        List<Entity> scene = scene(floor, boat, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        for (int i = 0; i < 180; i++) {
            vehicle.currentSpeed = 8.0f;
            vehicle.currentSteering = 2.0f;
            physics.step(FRAME, scene);
        }
        Transform b = boat.getTransform(), h = hero.getTransform();
        assertTrue(Math.abs(b.rotY) > 5.0f, "rotY=" + b.rotY);
        assertTrue(Math.abs(b.posZ) > 10.0f, "z=" + b.posZ);
        float[] r = new float[9];
        PhysicsMath.eulerToMatrix(b.rotX, b.rotY, b.rotZ, r);
        assertEquals(b.posX + r[0] * 2.0f, h.posX, 0.25f);
        assertEquals(b.posZ + r[6] * 2.0f, h.posZ, 0.25f);
    }

    private Entity thinWall(float x, RigidBodyComponent.MotionType type) throws IOException {
        Path file = dir.resolve("wall" + System.nanoTime() + ".obj");
        Files.writeString(file, "v 0 0 -10\nv 0 4 -10\nv 0 4 10\nv 0 0 10\nf 1 2 3\nf 1 3 4\n");
        Entity e = at("wall", x, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = type;
        e.addComponent(body);
        return e;
    }

    private void wallHits(Entity wall, float speed, float frame) {
        Entity floor = box(0, -0.5f, 0, 300, 1, 300, STATIC), hero = hero(0, 1.0f, 0);
        List<Entity> scene = scene(floor, wall, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 20);
        float travelled = 0.0f;
        while (travelled < 20.0f) {
            wall.getTransform().posX += speed * frame;
            travelled += speed * frame;
            physics.step(frame, scene);
        }
        assertTrue(hero.getTransform().posX > wall.getTransform().posX, "at " + speed + " m/s the wall passed the hero: wall "
                + wall.getTransform().posX + ", hero " + hero.getTransform().posX);
    }

    @Test
    void fastThinWallPushesInsteadOfPassingThrough() throws IOException {
        for (float speed : new float[] {25, 30, 40, 49}) {
            wallHits(thinWall(-5, KINEMATIC), speed, FRAME);
        }
    }

    @Test
    void fastThinWallStillPushesAtLowFrameRates() throws IOException {
        wallHits(thinWall(-5, KINEMATIC), 20, 1.0f / 30.0f);
    }

    @Test
    void fastBoxPushesInsteadOfPassingThrough() {
        wallHits(box(-5, 2, 0, 0.2f, 4, 20, KINEMATIC), 40, FRAME);
    }

    @Test
    void fastVehiclePushesInsteadOfPassingThrough() {
        Entity floor = box(0, -0.5f, 0, 300, 1, 300, STATIC), hero = hero(0, 1.0f, 0);
        Entity car = box(0, 0.5f, -10, 3, 1, 0.3f, STATIC);
        car.removeComponent(RigidBodyComponent.class);
        VehicleControllerComponent vehicle = new VehicleControllerComponent();
        car.addComponent(vehicle);
        List<Entity> scene = scene(floor, car, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 20);
        for (int i = 0; i < 60; i++) {
            vehicle.currentSpeed = 40.0f;
            physics.step(FRAME, scene);
        }
        assertTrue(car.getTransform().posZ > 15.0f, "z=" + car.getTransform().posZ);
        assertTrue(hero.getTransform().posZ > car.getTransform().posZ, "car " + car.getTransform().posZ + ", hero " + hero.getTransform().posZ);
    }

    @Test
    void pushedCharacterStopsAtWhatIsBehindIt() throws IOException {
        Entity floor = box(0, -0.5f, 0, 300, 1, 300, STATIC), hero = hero(0, 1.0f, 0);
        Entity mover = thinWall(-5, KINEMATIC), stop = thinWall(3, STATIC);
        List<Entity> scene = scene(floor, mover, stop, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 20);
        for (int i = 0; i < 60; i++) {
            mover.getTransform().posX += 30.0f * FRAME;
            physics.step(FRAME, scene);
        }
        assertTrue(hero.getTransform().posX < 3.0f, "x=" + hero.getTransform().posX);
    }

    @Test
    void teleportPushesNothing() throws IOException {
        Entity floor = box(0, -0.5f, 0, 300, 1, 300, STATIC), hero = hero(0, 1.0f, 0);
        Entity wall = thinWall(-5, KINEMATIC);
        List<Entity> scene = scene(floor, wall, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 20);
        wall.getTransform().posX = 100.0f;
        step(physics, scene, 5);
        assertEquals(0.0f, hero.getTransform().posX, 0.01f);
    }

    @Test
    void fastRiderStopsAtAThinWall() throws IOException {
        Entity deck = deck(), hero = hero(0, 1.0f, 0), wall = thinWall(8, STATIC);
        wall.getTransform().posY = 0.2f;
        List<Entity> scene = scene(deck, hero, wall);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 20);
        drive(physics, scene, deck, 35, 0, 0, 30);
        assertTrue(hero.getTransform().posX < 8.0f, "x=" + hero.getTransform().posX);
    }

    @Test
    void platformMovedInOnUpdateBringsItsRiderTheSameFrame() {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        for (int i = 0; i < 60; i++) {
            physics.step(FRAME, scene);
            deck.getTransform().posX += 2.0f * FRAME; // As onUpdate would, after physics
            physics.carryAfterScripts();
            assertEquals(3.0f + deck.getTransform().posX, hero.getTransform().posX, 0.001f, "frame " + i);
        }
    }

    @Test
    void lookingAfterATurnDoesNotSnapBack() throws ReflectiveOperationException {
        Entity deck = deck(), hero = hero(3, 1.0f, 0);
        hero.addComponent(new com.retro.engine.ecs.components.Camera());
        List<Entity> scene = scene(deck, hero);
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        drive(physics, scene, deck, 0, 0, 45, 120);
        float turned = hero.getTransform().rotY;
        assertEquals(90.0f, turned, 0.5f);
        java.lang.reflect.Field solverField = PhysicsSystem.class.getDeclaredField("characterSolver");
        solverField.setAccessible(true);
        java.lang.reflect.Field look = CharacterControllerSolver.class.getDeclaredField("pendingLookYaw");
        look.setAccessible(true);
        look.setFloat(solverField.get(physics), 10.0f);
        physics.step(FRAME, scene);
        assertEquals(turned + 10.0f, hero.getTransform().rotY, 0.5f);
    }

    @Test
    void removedDeckLetsGo() {
        Entity deck = deck(), floor = box(0, -3.5f, 0, 40, 1, 40, STATIC), hero = hero(3, 1.0f, 0);
        List<Entity> scene = new java.util.ArrayList<>(scene(deck, floor, hero));
        PhysicsSystem physics = physics(scene);
        step(physics, scene, 30);
        physics.removeRuntimeEntity(deck);
        scene.remove(deck);
        step(physics, scene, 60);
        assertFalse(hero.getTransform().posY > 0.0f, "y=" + hero.getTransform().posY);
        assertEquals(3.0f, hero.getTransform().posX, 0.01f);
    }
}
