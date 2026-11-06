package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.BoneSocket;
import com.retro.engine.ecs.components.Transform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class BoneSocketTest {

    private final HierarchySystem hierarchy = new HierarchySystem();
    private final BoneSocketSystem system = new BoneSocketSystem();

    /** Column-major rigid matrix with the engine's Euler rotation. */
    private static float[] rigid(float x, float y, float z, float rx, float ry, float rz) {
        double[] r = new double[9];
        HierarchySystem.rotationMatrix(rx, ry, rz, r);
        float[] m = new float[16];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) m[column * 4 + row] = (float) r[row * 3 + column];
        }
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1.0f;
        return m;
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[column * 4 + k];
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    private static float[] point(float[] m, float x, float y, float z) {
        return new float[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    /** A rig with one bone resting at identity, posed to `pose` through its palette. */
    private static Entity rig(String name, float[] pose) {
        Skeleton skeleton = new Skeleton();
        float[] identity = rigid(0, 0, 0, 0, 0, 0);
        skeleton.addBone("Hand_L", -1, identity, identity);
        Entity entity = new Entity(name + "_id", name, null);
        AnimatorComponent animator = new AnimatorComponent();
        animator.skeleton = skeleton;
        System.arraycopy(pose, 0, animator.bonePaletteMatrices[0], 0, 16);
        entity.addComponent(animator);
        return entity;
    }

    private static BoneSocket socket(String target, String bone) {
        BoneSocket socket = new BoneSocket();
        socket.targetEntity = target;
        socket.boneName = bone;
        return socket;
    }

    /** Where a point on the entity ended up, read back through the hierarchy as the renderer would. */
    private float[] worldPoint(Entity entity, List<Entity> scene, float x, float y, float z) {
        SceneWorld.WorldTransform wt = hierarchy.getComputedWorldTransform(entity, scene);
        float[] p = hierarchy.rotateVector(x, y, z, wt.rx(), wt.ry(), wt.rz(), new float[3]);
        return new float[] {wt.px() + p[0], wt.py() + p[1], wt.pz() + p[2]};
    }

    private void assertLands(float[] expectedWorld, Entity entity, List<Entity> scene) {
        float[][] probes = {{0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {0, 0, 1}};
        for (float[] p : probes) {
            float[] want = point(expectedWorld, p[0], p[1], p[2]);
            float[] got = worldPoint(entity, scene, p[0], p[1], p[2]);
            for (int i = 0; i < 3; i++) assertEquals(want[i], got[i], 1e-3f, "axis " + i + " of probe " + p[0] + "," + p[1] + "," + p[2]);
        }
    }

    @Test
    void landsOnTargetTimesBoneTimesOffset() {
        float[] pose = rigid(0.7f, 1.4f, -0.2f, 30, -50, 110);
        Entity julian = rig("Julian", pose);
        julian.getTransform().setPosition(3, 1, -2);
        julian.getTransform().setRotation(0, 180, 0);
        Entity weapon = new Entity("w", "Weapon", null);
        BoneSocket socket = socket("Julian", "Hand_L");
        socket.offsetPosY = 0.082f; socket.offsetPosZ = -0.03f;
        socket.offsetRotX = 80; socket.offsetRotY = 90;
        weapon.addComponent(socket);
        List<Entity> scene = new ArrayList<>(List.of(julian, weapon));

        system.update(scene, hierarchy);

        float[] expected = multiply(multiply(rigid(3, 1, -2, 0, 180, 0), pose), rigid(0, 0.082f, -0.03f, 80, 90, 0));
        assertLands(expected, weapon, scene);
    }

    @Test
    void blankTargetFollowsTheParentAndWorksThroughItsTransform() {
        float[] pose = rigid(0.5f, 0, 0, 0, 0, 40);
        Entity parent = rig("Parent", pose);
        parent.getTransform().setRotation(20, 35, 50);
        Entity child = new Entity("c", "Child", null);
        child.parentId = parent.id;
        parent.addChildId(child.id);
        child.addComponent(socket("", "Hand_L"));
        List<Entity> scene = new ArrayList<>(List.of(parent, child));

        system.update(scene, hierarchy);

        assertLands(multiply(rigid(0, 0, 0, 20, 35, 50), pose), child, scene);
    }

    @Test
    void inactiveSocketsAndBadReferencesLeaveTheEntityAlone() {
        Entity julian = rig("Julian", rigid(1, 1, 1, 0, 0, 0));
        Entity dropped = new Entity("a", "Dropped", null);
        BoneSocket off = socket("Julian", "Hand_L");
        off.active = false;
        dropped.addComponent(off);
        Entity wrongBone = new Entity("b", "WrongBone", null);
        wrongBone.addComponent(socket("Julian", "L_Hand"));
        Entity wrongTarget = new Entity("c", "WrongTarget", null);
        wrongTarget.addComponent(socket("Nobody", "Hand_L"));
        List<Entity> scene = new ArrayList<>(List.of(julian, dropped, wrongBone, wrongTarget));
        for (int i = 1; i < scene.size(); i++) scene.get(i).getTransform().setPosition(5, 6, 7);

        system.update(scene, hierarchy);

        for (int i = 1; i < scene.size(); i++) {
            Transform t = scene.get(i).getTransform();
            assertEquals(5, t.posX, scene.get(i).name);
            assertEquals(6, t.posY, scene.get(i).name);
            assertEquals(7, t.posZ, scene.get(i).name);
        }
    }

    @Test
    void reattachingToAnotherRigResolvesItsBoneAgain() {
        Entity first = rig("First", rigid(1, 0, 0, 0, 0, 0));
        Entity second = rig("Second", rigid(0, 2, 0, 0, 0, 0));
        Entity weapon = new Entity("w", "Weapon", null);
        BoneSocket socket = socket("First", "Hand_L");
        weapon.addComponent(socket);
        List<Entity> scene = new ArrayList<>(List.of(first, second, weapon));

        system.update(scene, hierarchy);
        assertEquals(1, weapon.getTransform().posX, 1e-5f);

        socket.targetEntity = "Second";
        system.update(scene, hierarchy);
        assertEquals(0, weapon.getTransform().posX, 1e-5f);
        assertEquals(2, weapon.getTransform().posY, 1e-5f);
    }

    @Test
    void onlyRunsDuringPlayAndStopPutsTheEntityBack() {
        SceneWorld world = new SceneWorld();
        Entity julian = rig("Julian", rigid(1, 2, 3, 0, 0, 0));
        Entity weapon = new Entity("w", "Weapon", null);
        weapon.addComponent(socket("Julian", "Hand_L"));
        weapon.getTransform().setPosition(9, 0, 0);
        world.addEntity(julian, -1);
        world.addEntity(weapon, -1);

        world.update(1.0f / 60.0f);
        assertEquals(9, weapon.getTransform().posX, "editing must not touch the authored Transform");

        world.play();
        world.update(1.0f / 60.0f);
        assertEquals(julian.getComponent(AnimatorComponent.class).skeleton,
                weapon.getComponent(BoneSocket.class).resolvedSkeleton, "the socket ran during Play");

        world.stop();
        Entity restored = world.findEntityByName("Weapon");
        assertEquals(9, restored.getTransform().posX, "Stop restores the authored Transform");
    }
}
