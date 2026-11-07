package com.retro.engine.navigation;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.KINEMATIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.ecs.components.NavMeshGate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Closing parts of the NavMesh during play. */
class NavMeshGateTest {

    private static final float DT = 1.0f / 60.0f;

    @TempDir Path dir;

    private final float[] corners = new float[NavMeshAgent.MAX_CORNERS * 3];

    /** A floor split along z = 0 by a wall, with a doorway 2 m wide at each x listed. */
    private static List<Entity> room(float... doorways) {
        List<Entity> scene = new ArrayList<>();
        scene.add(box(0, -0.5f, 0, 40, 1, 40, STATIC));
        float x = -20.0f;
        for (float door : doorways) {
            float end = door - 1.0f;
            scene.add(box((x + end) * 0.5f, 1.5f, 0, end - x, 3, 0.5f, STATIC));
            x = door + 1.0f;
        }
        scene.add(box((x + 20.0f) * 0.5f, 1.5f, 0, 20.0f - x, 3, 0.5f, STATIC));
        return scene;
    }

    private static Entity gate(List<Entity> scene, float x, float z) {
        Entity e = at("gate", x, 1.5f, z);
        NavMeshGate gate = new NavMeshGate();
        gate.sizeX = 2.0f;
        e.addComponent(gate);
        scene.add(e);
        return e;
    }

    private static NavMesh bake(List<Entity> scene) {
        NavMeshBaker.Result result = NavMeshBaker.bake(scene, null, new NavMeshBaker.Settings());
        assertNotNull(result.mesh(), result.message());
        return result.mesh();
    }

    private boolean reaches(NavMeshQuery query, float x0, float z0, float x1, float z1) {
        assertTrue(query.findPath(x0, 0, z0, x1, 0, z1, corners, NavMeshAgent.MAX_CORNERS) > 0);
        return !query.lastPathPartial;
    }

    @Test
    void closedGateBlocksThePath() {
        List<Entity> scene = room(0);
        gate(scene, 0, 0);
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        assertTrue(reaches(query, 0, -8, 0, 8), "open");
        query.setGateOpen(0, false);
        assertFalse(reaches(query, 0, -8, 0, 8), "closed");
    }

    @Test
    void closingAGateBlocksOnlyItsOwnBox() {
        List<Entity> scene = room(0);
        gate(scene, 0, 0);
        NavMesh mesh = bake(scene);
        for (int p = 0; p < mesh.polyCount; p++) {
            if (mesh.polyGate[p] < 0) continue;
            assertTrue(mesh.polyMinX[p] >= -1.3f && mesh.polyMaxX[p] <= 1.3f, "x " + mesh.polyMinX[p] + " to " + mesh.polyMaxX[p]);
            assertTrue(mesh.polyMinZ[p] >= -0.6f && mesh.polyMaxZ[p] <= 0.6f, "z " + mesh.polyMinZ[p] + " to " + mesh.polyMaxZ[p]);
        }
        NavMeshQuery query = new NavMeshQuery(mesh);
        query.setGateOpen(0, false);
        assertTrue(reaches(query, 0, -1.5f, 10, -1.5f), "right in front of the door");
        assertTrue(reaches(query, -10, 5, 10, 5), "the far room");
    }

    @Test
    void routeTakesAnotherDoorWhenOneCloses() {
        List<Entity> scene = room(-8, 8);
        gate(scene, -8, 0);
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        query.setGateOpen(0, false);
        assertTrue(reaches(query, -8, -5, -8, 5));
        boolean viaOtherDoor = false;
        int n = query.findPath(-8, 0, -5, -8, 0, 5, corners, NavMeshAgent.MAX_CORNERS);
        for (int i = 0; i < n; i++) viaOtherDoor |= corners[i * 3] > 5.0f;
        assertTrue(viaOtherDoor);
    }

    @Test
    void pathStartingInsideAClosedGateCanLeave() {
        List<Entity> scene = room(0);
        gate(scene, 0, 0);
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        query.setGateOpen(0, false);
        assertTrue(reaches(query, 0, 0, 0, 8), "out the far side");
        assertTrue(reaches(query, 0, 0, 0, -8), "out the near side");
    }

    @Test
    void thinGateStillClaimsCells() {
        List<Entity> scene = room(0);
        gate(scene, 0, 0).getComponent(NavMeshGate.class).sizeZ = 0.02f;
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        query.setGateOpen(0, false);
        assertFalse(reaches(query, 0, -8, 0, 8));
    }

    @Test
    void gateTurnsWithItsEntity() {
        List<Entity> scene = new ArrayList<>();
        scene.add(box(0, -0.5f, 0, 40, 1, 40, STATIC));
        scene.add(box(0, 1.5f, -10.5f, 0.5f, 3, 19, STATIC)); // A wall along z, with a 2 m doorway at the origin
        scene.add(box(0, 1.5f, 10.5f, 0.5f, 3, 19, STATIC));
        gate(scene, 0, 0).getTransform().rotY = 90;
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        query.setGateOpen(0, false);
        assertFalse(reaches(query, -8, 0, 8, 0));
    }

    @Test
    void doorColliderStaysOutOfTheBake() {
        List<Entity> scene = room(0);
        Entity door = gate(scene, 0, 0);
        door.addComponent(new com.retro.engine.ecs.components.BoxCollider());
        door.getComponent(com.retro.engine.ecs.components.BoxCollider.class).sizeX = 2;
        door.getComponent(com.retro.engine.ecs.components.BoxCollider.class).sizeY = 3;
        door.getComponent(com.retro.engine.ecs.components.BoxCollider.class).sizeZ = 0.5f;
        assertTrue(reaches(new NavMeshQuery(bake(scene)), 0, -8, 0, 8));
    }

    @Test
    void bridgeDeckBakesAndCloses() {
        List<Entity> scene = new ArrayList<>();
        scene.add(box(-12, -0.5f, 0, 20, 1, 20, STATIC));
        scene.add(box(12, -0.5f, 0, 20, 1, 20, STATIC));
        Entity deck = box(0, -0.5f, 0, 4.4f, 1, 6, KINEMATIC);
        scene.add(deck);
        assertFalse(reaches(new NavMeshQuery(bake(scene)), -8, 0, 8, 0), "a moving deck alone doesn't bake");

        NavMeshGate gate = new NavMeshGate();
        gate.sizeX = 4.0f; gate.sizeY = 2.0f; gate.sizeZ = 6.0f;
        gate.bakeColliders = true;
        deck.addComponent(gate);
        NavMeshQuery query = new NavMeshQuery(bake(scene));
        assertTrue(reaches(query, -8, 0, 8, 0), "lowered");
        query.setGateOpen(0, false);
        assertFalse(reaches(query, -8, 0, 8, 0), "raised");
    }

    @Test
    void gateOverNothingIsReported() {
        List<Entity> scene = room(0);
        gate(scene, 0, 0);
        gate(scene, 0, 0).getTransform().posY = 50;
        String message = NavMeshBaker.bake(scene, null, new NavMeshBaker.Settings()).message();
        assertTrue(message.contains("covers no walkable ground"), message);
    }

    private static NavMeshSystem system(NavMesh mesh) {
        NavMeshSystem system = new NavMeshSystem();
        system.setMesh(mesh);
        return system;
    }

    private static Entity agent(List<Entity> scene, float x, float z) {
        Entity e = at("hunter", x, 0, z);
        e.addComponent(new NavMeshAgent());
        scene.add(e);
        return e;
    }

    private static void run(NavMeshSystem system, List<Entity> scene, float seconds) {
        for (int i = 0; i < (int) (seconds / DT); i++) system.update(DT, scene);
    }

    @Test
    void agentStopsAtAClosingDoorAndCarriesOnWhenItOpens() {
        List<Entity> scene = room(0);
        NavMeshGate door = gate(scene, 0, 0).getComponent(NavMeshGate.class);
        NavMeshSystem system = system(bake(scene));
        Entity hunter = agent(scene, 0, -8);
        hunter.getComponent(NavMeshAgent.class).setDestination(0, 0, 8);

        run(system, scene, 0.5f);
        door.setOpen(false);
        run(system, scene, 6.0f);
        assertTrue(hunter.getTransform().posZ < 0.0f, "z=" + hunter.getTransform().posZ);
        assertTrue(hunter.getTransform().posZ > -1.5f, "z=" + hunter.getTransform().posZ);
        assertTrue(hunter.getComponent(NavMeshAgent.class).awaitingRoute);

        door.setOpen(true);
        run(system, scene, 6.0f);
        assertEquals(8.0f, hunter.getTransform().posZ, 0.2f);
    }

    @Test
    void agentRoutesAroundADoorThatClosesAhead() {
        List<Entity> scene = room(-8, 8);
        NavMeshGate door = gate(scene, -8, 0).getComponent(NavMeshGate.class);
        NavMeshSystem system = system(bake(scene));
        Entity hunter = agent(scene, -8, -5);
        hunter.getComponent(NavMeshAgent.class).setDestination(-8, 0, 5);
        run(system, scene, 0.2f);
        door.setOpen(false);
        run(system, scene, 14.0f);
        assertEquals(-8.0f, hunter.getTransform().posX, 0.2f);
        assertEquals(5.0f, hunter.getTransform().posZ, 0.2f);
    }

    @Test
    void newOrdersCancelTheWait() {
        List<Entity> scene = room(0);
        NavMeshGate door = gate(scene, 0, 0).getComponent(NavMeshGate.class);
        door.startOpen = false;
        NavMeshSystem system = system(bake(scene));
        Entity hunter = agent(scene, 0, -3);
        NavMeshAgent agent = hunter.getComponent(NavMeshAgent.class);
        agent.setDestination(0, 0, 8);
        run(system, scene, 3.0f);
        assertTrue(agent.awaitingRoute);
        agent.stop();
        float z = hunter.getTransform().posZ;
        door.setOpen(true);
        run(system, scene, 1.0f);
        assertEquals(z, hunter.getTransform().posZ, 0.3f);
    }

    @Test
    void startOpenSetsTheFirstStateAndPlayResetRestoresIt() {
        List<Entity> scene = room(0);
        NavMeshGate door = gate(scene, 0, 0).getComponent(NavMeshGate.class);
        door.startOpen = false;
        assertFalse(door.isOpen());
        door.setOpen(true);
        assertTrue(door.isOpen());
        NavMeshSystem system = system(bake(scene));
        system.resetAgents(scene);
        assertFalse(door.isOpen());
    }

    @Test
    void gatesSurviveSaveAndLoad() throws IOException {
        List<Entity> scene = room(0);
        Entity door = gate(scene, 0, 0);
        NavMesh mesh = bake(scene);
        File file = dir.resolve("Level.navmesh").toFile();
        mesh.save(file);
        NavMesh loaded = NavMesh.load(file);
        assertNotNull(loaded);
        assertEquals(door.id, loaded.gateIds[0]);
        assertEquals(java.util.Arrays.toString(mesh.polyGate), java.util.Arrays.toString(loaded.polyGate));
    }

    @Test
    void version1FilesStillLoad() throws IOException {
        File file = dir.resolve("Old.navmesh").toFile();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file.toPath()))) {
            out.writeInt(0x524E4156);
            out.writeInt(1);
            for (int i = 0; i < 6; i++) out.writeFloat(1.0f);
            out.writeInt(1); // One poly
            for (float v : new float[] {0, 0, 1, 1, 0}) out.writeFloat(v);
            out.writeInt(0); out.writeInt(0); // linkStart
            out.writeInt(0); // No links
            out.writeFloat(0); out.writeFloat(0);
            out.writeInt(1); out.writeInt(1);
            out.writeInt(0); out.writeInt(1); // colStart
            out.writeInt(1); out.writeInt(0); // colPolys
        }
        NavMesh loaded = NavMesh.load(file);
        assertNotNull(loaded);
        assertEquals(-1, loaded.polyGate[0]);
        assertEquals(0, loaded.gateIds.length);
        assertTrue(new NavMeshQuery(loaded).findPath(0.2f, 0, 0.2f, 0.8f, 0, 0.8f, new float[30], 10) > 0);
    }
}
