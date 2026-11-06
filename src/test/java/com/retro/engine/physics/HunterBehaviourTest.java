package com.retro.engine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.ecs.components.RigidBodyComponent.MotionType;
import com.retro.engine.navigation.NavMeshBaker;
import com.retro.engine.scripting.Input;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What stealth AI relies on: agents face where they walk, triggers aren't solid, and hunters ignore the keyboard. */
class HunterBehaviourTest {

    private static final int KEY_W = 87;
    private SceneWorld world;
    private Entity floor;

    @BeforeEach
    void buildFloor() {
        world = new SceneWorld();
        floor = TestEntities.box(0, -0.5f, 0, 100, 1, 100, MotionType.STATIC);
        world.addEntity(floor, -1);
    }

    @AfterEach
    void stop() {
        Input.setKeyState(KEY_W, false);
        world.stop();
    }

    private void run(int frames) {
        for (int i = 0; i < frames; i++) world.update(TestEntities.FRAME);
    }

    private Entity character(String name, float x, float z) {
        Entity e = TestEntities.at(name, x, 0.95f, z);
        CapsuleCollider capsule = new CapsuleCollider();
        capsule.radius = 0.4f;
        capsule.height = 1.8f;
        e.addComponent(capsule);
        e.addComponent(new CharacterControllerComponent());
        world.addEntity(e, -1);
        return e;
    }

    @ParameterizedTest
    @CsvSource({ "10, 0", "-10, 0", "0, 10", "0, -10" })
    void agentsFaceTheWayTheyWalk(float x, float z) {
        Entity agent = TestEntities.at("agent", 0, 0, 0);
        NavMeshAgent nav = new NavMeshAgent();
        agent.addComponent(nav);
        world.addEntity(agent, -1);
        world.getNavigation().setMesh(NavMeshBaker.bake(world.getSceneEntities(), world, new NavMeshBaker.Settings()).mesh());
        world.play();
        nav.setDestination(x, 0, z);
        run(45);

        // Models face +Z at yaw 0, the same forward EntityScript.getForward reports
        double yaw = Math.toRadians(agent.getTransform().rotY);
        double along = -Math.sin(yaw) * x / 10.0 + Math.cos(yaw) * z / 10.0;
        assertTrue(along > 0.9, "facing along the path by " + along);
    }

    private Entity trigger(float x, float y, float z, float sizeY) {
        Entity e = TestEntities.at("trigger", x, y, z);
        BoxCollider box = new BoxCollider();
        box.sizeY = sizeY;
        box.isTrigger = true;
        e.addComponent(box);
        world.addEntity(e, -1);
        return e;
    }

    @Test
    void raysPassThroughTriggers() {
        trigger(3, 0.3f, 0, 0.6f);
        world.play();
        run(2);
        assertSame(floor, world.getPhysicsWorld().raycast(3, 5, 0, 0, -1, 0, 10).entity);
    }

    @Test
    void walkingThroughATriggerDoesNotLiftACharacter() {
        trigger(3, 0.3f, 0, 0.6f);
        Entity player = character("Player", 0, 0);
        world.play();
        run(10);
        float standing = player.getTransform().posY;
        CharacterControllerComponent cc = player.getComponent(CharacterControllerComponent.class);
        for (int i = 0; i < 90; i++) {
            cc.velocityX = 3.0f;
            run(1);
            assertEquals(standing, player.getTransform().posY, 0.01f);
        }
    }

    private float movedByHoldingW(Entity watched) {
        float start = watched.getTransform().posZ;
        Input.setKeyState(KEY_W, true);
        run(30);
        Input.setKeyState(KEY_W, false);
        return Math.abs(watched.getTransform().posZ - start);
    }

    @Test
    void onlyTheFirstCharacterAnswersTheKeyboard() {
        Entity player = character("Player", 0, 0);
        Entity hunter = character("Hunter", 10, 0);
        world.play();
        run(10);
        float hunterStart = hunter.getTransform().posZ;
        assertTrue(movedByHoldingW(player) > 1.0f);
        assertEquals(hunterStart, hunter.getTransform().posZ, 0.001f);
    }

    @Test
    void theCharacterWithTheCameraOwnsTheInputWhereverItIsInTheScene() {
        Entity hunter = character("Hunter", 10, 0);
        Entity player = character("Player", 0, 0);
        Entity camera = TestEntities.at("Main Camera", 0, 2, 4);
        camera.addComponent(new Camera());
        camera.parentId = player.id;
        world.addEntity(camera, -1);
        world.play();
        run(10);
        float hunterStart = hunter.getTransform().posZ;
        assertTrue(movedByHoldingW(player) > 1.0f);
        assertEquals(hunterStart, hunter.getTransform().posZ, 0.001f);
    }

    @Test
    void aNewCharacterTakesOverWhenThePlayerIsGone() {
        Entity player = character("Player", 0, 0);
        Entity next = character("Respawned", 10, 0);
        world.play();
        run(10);
        world.requestEntityDestroy(player);
        world.getPhysicsWorld().removeRuntimeEntity(player);
        run(2);
        assertTrue(movedByHoldingW(next) > 1.0f);
    }
}
