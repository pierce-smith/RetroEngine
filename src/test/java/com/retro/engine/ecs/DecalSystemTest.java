package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.assets.MeshBufferData;
import com.retro.engine.ecs.components.Decal;
import com.retro.engine.graphics.DecalSpec;
import com.retro.engine.graphics.DecalTextures;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class DecalSystemTest {

    private static final int STRIDE = MeshBufferData.STATIC_FLOATS_PER_VERTEX;
    private static final String FLOOR = "floor.obj";

    private final SceneWorld world = new SceneWorld();
    private final Map<String, MeshBufferData> meshes = new HashMap<>();
    private final Function<String, MeshBufferData> lookup = meshes::get;
    private final List<String> released = new ArrayList<>();

    private static MeshBufferData floorMesh() {
        float[] v = new float[6 * STRIDE];
        float[][] corners = {{-5, -5}, {5, -5}, {5, 5}, {-5, -5}, {5, 5}, {-5, 5}};
        for (int i = 0; i < 6; i++) {
            v[i * STRIDE] = corners[i][0];
            v[i * STRIDE + 2] = corners[i][1];
            v[i * STRIDE + 4] = 1.0f;
        }
        return new MeshBufferData(v, 6);
    }

    private Entity addFloor(String name) {
        meshes.put(FLOOR, floorMesh());
        Entity floor = new Entity(name, name, FLOOR);
        world.addEntity(floor, world.getSceneEntities().size());
        return floor;
    }

    private DecalSpec hole() {
        return new DecalSpec(DecalTextures.BULLET_HOLE, 0.5f).setPosition(0, 0, 0, 0, 1, 0);
    }

    private void sync() {
        world.getDecals().sync(world, lookup, released::add);
    }

    @Test
    void aSpawnLandsOnTheMeshUnderIt() {
        Entity floor = addFloor("floor");
        DecalSystem decals = world.getDecals();
        decals.spawn(hole());
        assertEquals(0, decals.getLiveCount(), "built at the next extraction");
        sync();
        assertEquals(1, decals.getLiveCount());
        assertEquals(1, decals.getLive(0).pieces.size());
        assertEquals(floor, world.getEntityByHandle(decals.getLive(0).pieces.get(0).target));
    }

    @Test
    void aGrowingDecalSpreadsFromNothingAndSlowsToAStop() {
        addFloor("floor");
        DecalSystem decals = world.getDecals();
        DecalSpec spec = hole();
        spec.growTime = 2.0f;
        decals.spawn(spec);
        sync();
        DecalSystem.Live live = decals.getLive(0);
        assertTrue(live.growth() > 0.0f && live.growth() < 0.01f, "starts as nothing, but never zero: shaders divide by it");

        decals.update(1.0f);
        assertEquals(0.75f, live.growth(), 1e-5f, "past the halfway size at half time, because it slows");
        decals.update(5.0f);
        assertEquals(1.0f, live.growth());
    }

    @Test
    void aDecalWithNoGrowTimeAppearsAtFullSize() {
        addFloor("floor");
        DecalSystem decals = world.getDecals();
        decals.spawn(hole());
        sync();
        assertEquals(1.0f, decals.getLive(0).growth());
    }

    @Test
    void aDecalInMidairLeavesNothingBehind() {
        addFloor("floor");
        DecalSpec spec = hole();
        spec.posY = 50.0f;
        world.getDecals().spawn(spec);
        sync();
        assertEquals(0, world.getDecals().getLiveCount());
    }

    @Test
    void oneDecalCanLandOnSeveralMeshes() {
        addFloor("a");
        Entity b = addFloor("b");
        b.getTransform().posX = 10.0f;
        DecalSpec spec = hole();
        spec.posX = 5.0f; // on the seam
        world.getDecals().spawn(spec);
        sync();
        assertEquals(2, world.getDecals().getLive(0).pieces.size());
    }

    @Test
    void excludeAndTargetChooseTheReceivers() {
        Entity a = addFloor("a");
        Entity b = addFloor("b");
        DecalSpec spec = hole();
        spec.exclude = a;
        world.getDecals().spawn(spec);
        spec.exclude = null;
        spec.target = a;
        world.getDecals().spawn(spec);
        sync();
        assertEquals(b, world.getEntityByHandle(world.getDecals().getLive(0).pieces.get(0).target));
        assertEquals(1, world.getDecals().getLive(0).pieces.size());
        assertEquals(a, world.getEntityByHandle(world.getDecals().getLive(1).pieces.get(0).target));
        assertEquals(1, world.getDecals().getLive(1).pieces.size());
    }

    @Test
    void theSpecIsCopiedSoCallersCanReuseIt() {
        addFloor("floor");
        DecalSpec spec = hole();
        world.getDecals().spawn(spec);
        spec.posY = 50.0f;
        sync();
        assertEquals(1, world.getDecals().getLiveCount());
    }

    @Test
    void theBudgetEvictsTheOldestAndFreesItsGeometry() {
        addFloor("floor");
        DecalSystem decals = world.getDecals();
        decals.setMaxDecals(2);
        for (int i = 0; i < 3; i++) {
            DecalSpec spec = hole();
            spec.posX = i;
            decals.spawn(spec);
            sync();
        }
        assertEquals(2, decals.getLiveCount());
        assertEquals(1.0f, decals.getLive(0).spec.posX, "the first decal went");
        assertEquals(1, released.size());
    }

    @Test
    void aLifetimeFadesThenRemoves() {
        addFloor("floor");
        DecalSystem decals = world.getDecals();
        DecalSpec spec = hole();
        spec.lifetime = 3.0f;
        spec.fadeTime = 1.0f;
        decals.spawn(spec);
        sync();
        DecalSystem.Live live = decals.getLive(0);
        decals.update(1.0f);
        assertEquals(1.0f, live.opacity(), 1e-6f);
        decals.update(1.5f);
        assertEquals(0.5f, live.opacity(), 1e-5f);
        decals.update(1.0f);
        assertEquals(0, decals.getLiveCount());
        sync();
        assertEquals(1, released.size());
    }

    @Test
    void aFrameBuildsOnlySoManySpawnsAndTheRestWait() {
        addFloor("floor");
        DecalSystem decals = world.getDecals();
        for (int i = 0; i < 40; i++) decals.spawn(hole());
        sync();
        assertTrue(decals.getLiveCount() < 40);
        sync();
        assertEquals(40, decals.getLiveCount());
    }

    @Test
    void stoppingPlayClearsSpawnedDecalsButNotAuthoredOnes() {
        addFloor("floor");
        Entity marker = new Entity("stain", "Stain", null);
        marker.addComponent(new Decal());
        world.addEntity(marker, world.getSceneEntities().size());
        DecalSystem decals = world.getDecals();
        decals.spawn(hole());
        sync();
        assertEquals(2, decals.getLiveCount());
        decals.clearSpawned();
        assertEquals(1, decals.getLiveCount());
    }

    @Test
    void anAuthoredDecalProjectsDownFromItsEntityAndFollowsEdits() {
        addFloor("floor");
        Entity marker = new Entity("stain", "Stain", null);
        Decal decal = new Decal();
        marker.addComponent(decal);
        marker.getTransform().posY = 0.2f;
        world.addEntity(marker, world.getSceneEntities().size());
        DecalSystem decals = world.getDecals();

        sync();
        assertEquals(1, decals.getLive(0).pieces.size());
        MeshBufferData first = decals.getLive(0).pieces.get(0).mesh;
        sync();
        assertEquals(first, decals.getLive(0).pieces.get(0).mesh, "unchanged decals don't rebuild");

        decal.width = 2.0f;
        sync();
        assertFalse(first == decals.getLive(0).pieces.get(0).mesh);
        assertEquals(1, released.size());

        marker.getTransform().posY = 50.0f;
        marker.getTransform().markDirty();
        world.getComputedWorldTransform(marker);
        sync();
        assertTrue(decals.getLive(0).pieces.isEmpty(), "moved out of reach");
    }

    @Test
    void anAuthoredDecalWaitsForMeshesStillStreamingIn() {
        Entity floor = new Entity("floor", "floor", FLOOR);
        world.addEntity(floor, 0);
        Entity marker = new Entity("stain", "Stain", null);
        marker.addComponent(new Decal());
        world.addEntity(marker, 1);
        DecalSystem decals = world.getDecals();

        sync();
        assertTrue(decals.getLive(0).pieces.isEmpty());
        meshes.put(FLOOR, floorMesh());
        sync();
        assertEquals(1, decals.getLive(0).pieces.size());
    }

    @Test
    void anAuthoredDecalRebuildsWhenItsTargetGetsANewHandle() {
        Entity floor = addFloor("floor");
        Entity marker = new Entity("stain", "Stain", null);
        marker.addComponent(new Decal());
        world.addEntity(marker, world.getSceneEntities().size());
        sync();

        // What the Stop restore does when it rebinds the scene
        world.unregisterEntityHandle(floor);
        world.registerEntityHandle(floor);
        sync();
        assertEquals(floor, world.getEntityByHandle(world.getDecals().getLive(0).pieces.get(0).target));
    }

    @Test
    void removingTheComponentRemovesTheDecal() {
        addFloor("floor");
        Entity marker = new Entity("stain", "Stain", null);
        marker.addComponent(new Decal());
        world.addEntity(marker, world.getSceneEntities().size());
        sync();
        world.deleteEntity(marker);
        sync();
        assertEquals(0, world.getDecals().getLiveCount());
        assertEquals(1, released.size());
    }

    @Test
    void builtInImagesHaveShapeAndATransparentBorder() {
        for (String path : new String[] {DecalTextures.BLOOD, DecalTextures.BULLET_HOLE, DecalTextures.SCORCH}) {
            var image = DecalTextures.generate(path);
            int solid = 0;
            for (int i = 3; i < image.pixels.length; i += 4) {
                if ((image.pixels[i] & 0xFF) > 128) solid++;
            }
            assertTrue(solid > 500, path + " has a visible shape");
            assertEquals(0, image.pixels[3] & 0xFF, path + " corner is clear");
            int topMiddle = (image.width / 2) * 4 + 3;
            assertEquals(0, image.pixels[topMiddle] & 0xFF, path + " edge is clear so the repeat wrap can't show");
        }
    }
}
