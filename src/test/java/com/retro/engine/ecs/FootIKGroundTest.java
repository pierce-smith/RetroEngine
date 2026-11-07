package com.retro.engine.ecs;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.allocatedBytes;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.scene;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.animation.PoseEditor;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.FootIK;
import com.retro.engine.ecs.components.MeshCollider;
import com.retro.engine.physics.PhysicsSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@link FootIK} standing on real colliders. The biped's ankles rest 0.1 above its origin, at x = +-0.1 and z = 0. */
class FootIKGroundTest {

    private static final float DT = 1.0f / 60.0f;
    private static final float ANKLE = 0.1f;

    @TempDir Path dir;

    private final HierarchySystem hierarchy = new HierarchySystem();
    private final IKSystem system = new IKSystem();
    private final Entity rig = IKSystemTest.rig(IKSystemTest.biped());
    private final AnimatorComponent animator = rig.getComponent(AnimatorComponent.class);
    private final FootIK ik = new FootIK();

    private List<Entity> world;
    private PhysicsSystem physics;

    private void standOn(Entity... ground) {
        rig.addComponent(ik);
        world = scene(ground);
        world.add(rig);
        physics = physics(world);
        step(physics, world, 1);
    }

    /** A plane of height = slope * x, as a mesh collider. */
    private Entity ramp(float slope) throws IOException {
        Path file = dir.resolve("ramp.obj");
        Files.writeString(file, "v -20 " + (-20 * slope) + " -20\nv 20 " + (20 * slope) + " -20\nv -20 " + (-20 * slope) + " 20\nv 20 " + (20 * slope) + " 20\n"
                + "f 1 3 2\nf 2 3 4\n");
        Entity e = com.retro.engine.testing.TestEntities.at("ramp", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    private static Entity floor() {
        return box(0, -0.5f, 0, 40, 1, 40, STATIC);
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            for (int b = 0; b < animator.skeleton.getBoneCount(); b++) {
                float[] m = animator.bonePaletteMatrices[b];
                java.util.Arrays.fill(m, 0.0f);
                m[0] = m[5] = m[10] = m[15] = 1.0f;
            }
            animator.poseRevision++;
            system.update(world, hierarchy, physics, DT);
        }
    }

    private double[] joint(String bone) {
        double[] out = new double[3];
        PoseEditor.jointPosition(animator, animator.skeleton.getBindJointPositions(), animator.skeleton.getBoneIndex(bone), out);
        return out;
    }

    private double[] footUp(String bone) {
        double[] out = new double[3];
        PoseEditor.boneDirection(animator, animator.skeleton.getBoneIndex(bone), 0, 1, 0, out);
        return out;
    }

    @Test
    void flatGroundLeavesTheClipExactlyAsAuthored() {
        standOn(floor());
        frames(120);
        for (int b = 0; b < animator.skeleton.getBoneCount(); b++) {
            assertArrayEquals(new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, animator.bonePaletteMatrices[b], 1e-5f, "bone " + b);
        }
    }

    @Test
    void feetStandOnASlopeAndTiltWithIt() throws IOException {
        float slope = 0.5f;
        standOn(ramp(slope));
        frames(120);

        double normalY = 1.0 / Math.sqrt(1.0 + slope * slope);
        for (String foot : List.of("Foot_L", "Foot_R")) {
            double[] ankle = joint(foot);
            assertEquals(ankle[0] * slope + ANKLE / normalY, ankle[1], 0.01, foot + " height");
            double[] normal = {-slope * normalY, normalY, 0};
            assertEquals(0.0, Math.toDegrees(PoseEditor.angleBetween(footUp(foot), normal)), 1.0, foot + " tilt");
        }
        assertTrue(joint("Pelvis")[1] < 0.95 - 0.03, "the pelvis dropped for the downhill leg: " + joint("Pelvis")[1]);
    }

    @Test
    void aSteepSlopeStopsTheFootAtMaxFootAngle() throws IOException {
        ik.maxFootAngle = 20.0f;
        standOn(ramp(1.0f));
        frames(120);
        assertEquals(20.0, Math.toDegrees(PoseEditor.angleBetween(footUp("Foot_L"), new double[] {0, 1, 0})), 1.0);
    }

    @Test
    void oneFootStandsOnAStairAndTheOtherStaysDown() {
        standOn(floor(), box(1.0f, 0.1f, 0, 2.0f, 0.2f, 4, STATIC)); // top at 0.2, from x = 0 outward
        frames(120);
        assertEquals(0.2 + ANKLE, joint("Foot_L")[1], 0.01);
        assertEquals(ANKLE, joint("Foot_R")[1], 0.01);
        assertEquals(0.95, joint("Pelvis")[1], 0.01);
    }

    @Test
    void theToeFindsAStairTheAnkleHasNotReached() {
        standOn(floor(), box(0, 0.1f, 1.1f, 4, 0.2f, 2.0f, STATIC)); // top at 0.2, from z = 0.1 outward
        frames(120);
        assertEquals(0.2 + ANKLE, joint("Foot_L")[1], 0.01);
    }

    @Test
    void aWallBesideTheFootIsNotAStair() {
        standOn(floor(), box(1.0f, 1.5f, 0, 2.0f, 3.0f, 4, STATIC));
        frames(120);
        assertEquals(ANKLE, joint("Foot_L")[1], 0.01);
    }

    @Test
    void theBodyEasesThroughTheJumpOfSteppingUpAStair() {
        standOn(floor(), box(21.0f, 0.1f, 0, 2.0f, 0.2f, 4, STATIC));
        frames(30);
        double worldBefore = joint("Pelvis")[1];

        rig.getTransform().posX = 21.0f;
        rig.getTransform().posY = 0.2f;
        rig.getTransform().markDirty();
        hierarchy.invalidateEntityIndex();
        frames(1);
        double worldAfter = joint("Pelvis")[1] + 0.2;
        assertTrue(worldAfter - worldBefore < 0.08, "the root rose 0.2 but the pelvis only " + (worldAfter - worldBefore));

        frames(60);
        assertEquals(0.95, joint("Pelvis")[1], 0.005);
    }

    @Test
    void feetFollowTheClipInTheAir() throws IOException {
        // As a real character is built: the capsule and controller on a parent, the rig below it with its origin at the soles
        Entity player = com.retro.engine.testing.TestEntities.at("Player", 0, 0.9f, 0);
        com.retro.engine.ecs.components.CapsuleCollider capsule = new com.retro.engine.ecs.components.CapsuleCollider();
        capsule.radius = 0.35f;
        capsule.height = 1.8f;
        player.addComponent(capsule);
        CharacterControllerComponent controller = new CharacterControllerComponent();
        player.addComponent(controller);
        rig.parentId = player.id;
        player.addChildId(rig.id);
        rig.getTransform().posY = -0.9f;

        standOn(ramp(0.5f), player);
        controller.isGrounded = true;
        frames(60);
        assertTrue(Math.abs(joint("Foot_L")[1] - ANKLE) > 0.02, "planted on the slope first, through the player's own capsule");

        controller.isGrounded = false;
        frames(60);
        assertEquals(ANKLE, joint("Foot_L")[1], 0.002);
    }

    @Test
    void solvingAllocatesNothing() throws IOException {
        standOn(ramp(0.5f));
        frames(200);
        long before = allocatedBytes();
        frames(200);
        // One object a frame would be at least 3200 bytes. Anything less is the JVM warming up
        assertTrue(allocatedBytes() - before < 200 * 16, "allocated " + (allocatedBytes() - before));
    }
}
