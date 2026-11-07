package com.retro.engine.navigation;

import static com.retro.engine.testing.TestEntities.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.MeshCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Baking a NavMesh from Mesh Colliders, read from real OBJ files. */
class NavMeshBakerMeshTest {

    @TempDir Path dir;

    /** Builds an OBJ file one quad at a time. Corners run counter-clockwise seen from the side the face points to. */
    private static final class Obj {
        final StringBuilder text = new StringBuilder();
        int vertices;

        Obj quad(float... corners) {
            for (int i = 0; i < 12; i += 3) text.append("v ").append(corners[i]).append(' ').append(corners[i + 1]).append(' ').append(corners[i + 2]).append('\n');
            int a = vertices + 1, b = a + 1, c = a + 2, d = a + 3;
            text.append("f ").append(a).append(' ').append(b).append(' ').append(c).append('\n');
            text.append("f ").append(a).append(' ').append(c).append(' ').append(d).append('\n');
            vertices += 4;
            return this;
        }

        /** An upward-facing rectangle at height y. */
        Obj floor(float x0, float z0, float x1, float z1, float y) {
            return quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
        }

        /** A downward-facing rectangle at height y. */
        Obj ceiling(float x0, float z0, float x1, float z1, float y) {
            return quad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1);
        }

        /** A paper-thin wall along x at z, facing +z, with nothing on top of it. */
        Obj wall(float x0, float x1, float z, float y0, float y1) {
            return quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        }

        /** A closed box with outward faces. */
        Obj block(float x0, float y0, float z0, float x1, float y1, float z1) {
            floor(x0, z0, x1, z1, y1);
            ceiling(x0, z0, x1, z1, y0);
            quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
            quad(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
            return quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        }
    }

    private Entity entity(Obj obj) throws IOException {
        Path file = dir.resolve("mesh" + System.nanoTime() + ".obj");
        Files.writeString(file, obj.text.toString());
        Entity e = at("mesh", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    private static NavMesh bake(Entity... entities) {
        return NavMeshBaker.bake(List.of(entities), null, new NavMeshBaker.Settings()).mesh();
    }

    private static boolean walkable(NavMesh mesh, float x, float y, float z) {
        return mesh.findPoly(x, y, z, 0.3f) >= 0;
    }

    @Test
    void flatMeshBakesAtItsOwnHeight() throws IOException {
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 2.0f)));
        assertNotNull(mesh);
        int poly = mesh.findPoly(0, 2, 0, 0.3f);
        assertTrue(poly >= 0);
        assertEquals(2.0f, mesh.polyY[poly], 0.01f);
    }

    @Test
    void paperThinWallCutsTheFloorInTwo() throws IOException {
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 0).wall(-10, 10, 0.03f, 0, 3)));
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 0, 0, -5) && walkable(mesh, 0, 0, 5));
        assertFalse(walkable(mesh, 0, 0, 0.03f));
        NavMeshQuery query = new NavMeshQuery(mesh);
        query.findPath(0, 0, -5, 0, 0, 5, new float[3 * 64], 64);
        assertTrue(query.lastPathPartial);
    }

    @Test
    void closedBlockHasNoFloorInside() throws IOException {
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 0)), entity(new Obj().block(-3, 0, -3, 3, 4, 3)));
        assertNotNull(mesh);
        assertFalse(walkable(mesh, 0, 0, 0), "floor inside the block");
        assertTrue(walkable(mesh, 0, 4, 0), "top of the block");
        assertTrue(walkable(mesh, 7, 0, 7), "ground beside the block");
    }

    @Test
    void floorRunsUnderASlabAndOverIt() throws IOException {
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 0).block(-5, 3, -5, 5, 3.3f, 5)));
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 0, 0, 0), "under the slab");
        assertTrue(walkable(mesh, 0, 3.3f, 0), "on the slab");
    }

    @Test
    void lowCeilingBlocksTheAgent() throws IOException {
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 0).ceiling(-5, -5, 5, 5, 1.2f)));
        assertNotNull(mesh);
        assertFalse(walkable(mesh, 0, 0, 0));
        assertTrue(walkable(mesh, 8, 0, 8));
    }

    @Test
    void doubleSidedFloorsStayFloors() throws IOException {
        Obj obj = new Obj().floor(-10, -10, 10, 10, 0).ceiling(-10, -10, 10, 10, 0)
                .floor(-10, -10, 10, 10, 4).ceiling(-10, -10, 10, 10, 4);
        NavMesh mesh = bake(entity(obj));
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 0, 0, 0), "lower floor");
        assertTrue(walkable(mesh, 0, 4, 0), "upper floor");
    }

    @Test
    void downwardFacingSheetIsNotWalkable() throws IOException {
        assertNull(bake(entity(new Obj().ceiling(-10, -10, 10, 10, 0))));
    }

    @Test
    void slopesFollowMaxSlope() throws IOException {
        Obj gentle = new Obj().quad(-10, 0, -10, -10, 5, 10, 10, 5, 10, 10, 0, -10);
        assertNotNull(bake(entity(gentle)));
        Obj steep = new Obj().quad(-10, 0, -10, -10, 40, 10, 10, 40, 10, 10, 0, -10);
        assertNull(bake(entity(steep)));
    }

    @Test
    void meshFollowsItsEntityTransform() throws IOException {
        Entity strip = entity(new Obj().floor(-10, -2, 10, 2, 0));
        strip.getTransform().rotY = 90;
        strip.getTransform().posY = 1;
        strip.getTransform().scaleZ = 2;
        NavMesh mesh = bake(strip);
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 0, 1, 8), "the long side now runs along z");
        assertTrue(walkable(mesh, 3, 1, 0), "scale widened it");
        assertFalse(walkable(mesh, 8, 1, 0));
    }

    @Test
    void mirroredScaleKeepsFloorsWalkable() throws IOException {
        Entity floor = entity(new Obj().floor(-10, -10, 10, 10, 0));
        floor.getTransform().scaleX = -1;
        assertNotNull(bake(floor));
    }

    @Test
    void meshColliderCountsEvenWithADynamicRigidBody() throws IOException {
        Entity floor = entity(new Obj().floor(-10, -10, 10, 10, 0));
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = RigidBodyComponent.MotionType.DYNAMIC;
        floor.addComponent(body);
        assertNotNull(bake(floor));
    }

    @Test
    void missingMeshStopsTheBakeAndNamesIt() throws IOException {
        Entity broken = at("Broken Wall", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = dir.resolve("missing.obj").toString();
        broken.addComponent(collider);
        NavMeshBaker.Result result = NavMeshBaker.bake(List.of(entity(new Obj().floor(-10, -10, 10, 10, 0)), broken), null, new NavMeshBaker.Settings());
        assertNull(result.mesh(), "a good floor doesn't excuse a broken collider");
        assertTrue(result.message().contains("Broken Wall") && result.message().contains("missing.obj"), result.message());
    }

    @Test
    void missingMeshFilterMeshStopsTheBakeToo() {
        Entity broken = at("Broken Wall", 0, 0, 0);
        broken.addComponent(new MeshCollider());
        com.retro.engine.ecs.components.MeshFilter filter = new com.retro.engine.ecs.components.MeshFilter();
        filter.meshAssetPath = dir.resolve("gone.glb").toString();
        broken.addComponent(filter);
        assertNull(NavMeshBaker.bake(List.of(broken), null, new NavMeshBaker.Settings()).mesh());
    }

    @Test
    void corruptMeshStopsTheBake() throws IOException {
        Path file = dir.resolve("corrupt.obj");
        Files.writeString(file, "this is not a mesh");
        Entity broken = at("Corrupt", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        broken.addComponent(collider);
        assertNull(NavMeshBaker.bake(List.of(broken), null, new NavMeshBaker.Settings()).mesh());
    }

    @Test
    void kinematicMeshIsLeftOutBecauseItMoves() throws IOException {
        Entity lift = entity(new Obj().floor(-10, -10, 10, 10, 0));
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = RigidBodyComponent.MotionType.KINEMATIC;
        lift.addComponent(body);
        assertNull(bake(lift));
    }

    @Test
    void colliderWithNoMeshFallsBackToTheEntitysBox() {
        Entity e = at("broken", 0, 0, 0);
        e.addComponent(new MeshCollider());
        BoxCollider box = new BoxCollider();
        box.sizeX = 20; box.sizeY = 1; box.sizeZ = 20;
        e.addComponent(box);
        NavMesh mesh = bake(e);
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 0, 0.5f, 0));
    }

    @Test
    void meshesAndBoxesBakeTogether() throws IOException {
        Entity step = at("box", 8, 0, 0);
        BoxCollider box = new BoxCollider();
        box.sizeX = 4; box.sizeY = 0.4f; box.sizeZ = 4;
        step.addComponent(box);
        NavMesh mesh = bake(entity(new Obj().floor(-10, -10, 10, 10, 0)), step);
        assertNotNull(mesh);
        assertTrue(walkable(mesh, 8, 0.2f, 0));
        assertTrue(walkable(mesh, 0, 0, 0));
    }
}
