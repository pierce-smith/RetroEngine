package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.animation.PoseEditor;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.components.AimIK;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.FootIK;
import com.retro.engine.ecs.components.LookAtIK;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Solver behaviour on a small biped. Physics-driven ground is covered by {@link FootIKGroundTest}. */
class IKSystemTest {

    private static final float DT = 1.0f / 60.0f;

    private final HierarchySystem hierarchy = new HierarchySystem();
    private final IKSystem system = new IKSystem();

    private static float[] translation(float x, float y, float z) {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1.0f;
        m[12] = x; m[13] = y; m[14] = z;
        return m;
    }

    /** A bone at a bind-pose model position, its parent given by index. */
    private static int bone(Skeleton skeleton, String name, int parent, float x, float y, float z, float px, float py, float pz) {
        return skeleton.addBone(name, parent, translation(x - px, y - py, z - pz), translation(-x, -y, -z));
    }

    static Skeleton biped() {
        Skeleton s = new Skeleton();
        int pelvis = bone(s, "Pelvis", -1, 0, 0.95f, 0, 0, 0, 0);
        int spine = bone(s, "Spine", pelvis, 0, 1.1f, 0, 0, 0.95f, 0);
        int neck = bone(s, "Neck", spine, 0, 1.5f, 0, 0, 1.1f, 0);
        bone(s, "Head", neck, 0, 1.6f, 0, 0, 1.5f, 0);
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? 0.1f : -0.1f;
            String tag = side == 0 ? "_L" : "_R";
            int thigh = bone(s, "Thigh" + tag, pelvis, x, 0.95f, 0, 0, 0.95f, 0);
            // A slightly bent knee, as clips have it
            int calf = bone(s, "Calf" + tag, thigh, x, 0.52f, 0.03f, x, 0.95f, 0);
            bone(s, "Foot" + tag, calf, x, 0.1f, 0, x, 0.52f, 0.03f);
        }
        return s;
    }

    static Entity rig(Skeleton skeleton) {
        Entity entity = new Entity("rig_id", "Rig", null);
        AnimatorComponent animator = new AnimatorComponent();
        animator.skeleton = skeleton;
        entity.addComponent(animator);
        return entity;
    }

    private static AnimatorComponent animator(Entity entity) {
        return entity.getComponent(AnimatorComponent.class);
    }

    /** Stands in for animation producing a fresh bind pose this frame. */
    private static void animate(AnimatorComponent animator) {
        for (int i = 0; i < animator.skeleton.getBoneCount(); i++) {
            float[] m = animator.bonePaletteMatrices[i];
            java.util.Arrays.fill(m, 0.0f);
            m[0] = m[5] = m[10] = m[15] = 1.0f;
        }
        animator.poseRevision++;
    }

    private void frames(Entity entity, List<Entity> scene, int count) {
        for (int i = 0; i < count; i++) {
            animate(animator(entity));
            system.update(scene, hierarchy, null, DT);
        }
    }

    private static double[] joint(AnimatorComponent animator, String bone) {
        double[] out = new double[3];
        PoseEditor.jointPosition(animator, animator.skeleton.getBindJointPositions(), animator.skeleton.getBoneIndex(bone), out);
        return out;
    }

    private static double[] headForward(AnimatorComponent animator) {
        double[] out = new double[3];
        PoseEditor.boneDirection(animator, animator.skeleton.getBoneIndex("Head"), 0, 0, 1, out);
        return out;
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    @Test
    void twoBoneReachesTargetAndKeepsBoneLengths() {
        Entity entity = rig(biped());
        AnimatorComponent a = animator(entity);
        Skeleton s = a.skeleton;
        double[] hipBefore = joint(a, "Thigh_L");
        double upper = distance(hipBefore, joint(a, "Calf_L")), lower = distance(joint(a, "Calf_L"), joint(a, "Foot_L"));

        double[] target = {0.15, 0.3, 0.1};
        new PoseEditor().solveTwoBone(a, s.getBoneIndex("Thigh_L"), s.getBoneIndex("Calf_L"), s.getBoneIndex("Foot_L"), target, new double[] {0, 0, 1});

        assertArrayEquals(target, joint(a, "Foot_L"), 1e-4);
        assertArrayEquals(hipBefore, joint(a, "Thigh_L"), 1e-5);
        assertEquals(upper, distance(joint(a, "Thigh_L"), joint(a, "Calf_L")), 1e-4);
        assertEquals(lower, distance(joint(a, "Calf_L"), joint(a, "Foot_L")), 1e-4);
        assertTrue(joint(a, "Calf_L")[2] > 0.1, "the knee keeps bending forward");
    }

    @Test
    void aTargetOutOfReachStraightensTheLimbTowardIt() {
        Entity entity = rig(biped());
        AnimatorComponent a = animator(entity);
        Skeleton s = a.skeleton;
        new PoseEditor().solveTwoBone(a, s.getBoneIndex("Thigh_L"), s.getBoneIndex("Calf_L"), s.getBoneIndex("Foot_L"), new double[] {0.1, -5, 0}, new double[] {0, 0, 1});
        double[] foot = joint(a, "Foot_L");
        assertEquals(0.1, foot[0], 1e-3);
        assertEquals(0.0, foot[2], 1e-3);
        assertTrue(foot[1] < 0.1, "the leg stretched down past its animated length");
    }

    @Test
    void lookAtTurnsTheHeadOntoTheTargetAndSharesItWithTheChain() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.setTargetPoint(2.0f, 1.6f, 2.0f);
        entity.addComponent(look);
        List<Entity> scene = new ArrayList<>(List.of(entity));
        frames(entity, scene, 240);

        AnimatorComponent a = animator(entity);
        double[] toTarget = {2.0 - joint(a, "Head")[0], 1.6 - joint(a, "Head")[1], 2.0 - joint(a, "Head")[2]};
        PoseEditor.normalize(toTarget);
        assertEquals(0.0, PoseEditor.angleBetween(headForward(a), toTarget), Math.toRadians(1.0));

        double[] spineForward = new double[3];
        PoseEditor.boneDirection(a, a.skeleton.getBoneIndex("Spine"), 0, 0, 1, spineForward);
        double spineTurn = Math.toDegrees(PoseEditor.angleBetween(spineForward, new double[] {0, 0, 1}));
        assertTrue(spineTurn > 1.0 && spineTurn < 20.0, "the spine helps a little: " + spineTurn);
    }

    @Test
    void lookAtStopsAtMaxAngle() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.maxAngle = 30.0f;
        look.setTargetPoint(10.0f, 1.6f, 5.0f); // about 63 degrees off, inside the give-up margin
        entity.addComponent(look);
        frames(entity, new ArrayList<>(List.of(entity)), 240);
        assertEquals(30.0, Math.toDegrees(PoseEditor.angleBetween(headForward(animator(entity)), new double[] {0, 0, 1})), 1.0);
    }

    @Test
    void aTargetBehindTheCharacterEasesBackToTheClip() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.setTargetPoint(1.0f, 1.6f, 3.0f);
        entity.addComponent(look);
        List<Entity> scene = new ArrayList<>(List.of(entity));
        frames(entity, scene, 120);
        look.setTargetPoint(0.0f, 1.6f, -3.0f);
        frames(entity, scene, 240);
        assertEquals(0.0, PoseEditor.angleBetween(headForward(animator(entity)), new double[] {0, 0, 1}), Math.toRadians(0.5));
    }

    @Test
    void aimTurnsTheChainAndLookStillFindsItsOwnTarget() {
        Entity entity = rig(biped());
        AimIK aim = new AimIK();
        aim.aimBone = "Neck";
        aim.setTargetPoint(-3.0f, 1.5f, 3.0f);
        LookAtIK look = new LookAtIK();
        look.chain = "Neck,Head";
        look.maxAngle = 120.0f; // the limit is measured from the pose it's handed, which aim has already turned
        look.setTargetPoint(3.0f, 1.6f, 3.0f);
        entity.addComponent(aim);
        entity.addComponent(look);
        frames(entity, new ArrayList<>(List.of(entity)), 240);

        AnimatorComponent a = animator(entity);
        double[] spine = new double[3];
        PoseEditor.boneDirection(a, a.skeleton.getBoneIndex("Spine"), 0, 0, 1, spine);
        assertTrue(spine[0] < -0.5, "aim turned the spine toward -X");
        double[] toTarget = {3.0 - joint(a, "Head")[0], 1.6 - joint(a, "Head")[1], 3.0 - joint(a, "Head")[2]};
        PoseEditor.normalize(toTarget);
        assertEquals(0.0, PoseEditor.angleBetween(headForward(a), toTarget), Math.toRadians(1.0), "look turned the head back onto its own target");
    }

    @Test
    void aHeldPoseIsNeverSolvedTwice() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.setTargetPoint(2.0f, 1.6f, 2.0f);
        entity.addComponent(look);
        List<Entity> scene = new ArrayList<>(List.of(entity));
        frames(entity, scene, 120);

        AnimatorComponent a = animator(entity);
        double[] solved = headForward(a);
        for (int i = 0; i < 10; i++) system.update(scene, hierarchy, null, DT); // paused: animation made no new pose
        assertArrayEquals(solved, headForward(a), 1e-9);
    }

    @Test
    void aStoppedAnimatorIsLeftExactlyAsAScriptPosedIt() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.setTargetPoint(2.0f, 1.6f, 2.0f);
        entity.addComponent(look);
        entity.addComponent(new FootIK());
        AnimatorComponent a = animator(entity);
        List<Entity> scene = new ArrayList<>(List.of(entity));

        // What ExecutionScript does: evaluates a pose itself, stops the animator and marks it as settled
        animate(a);
        a.isPlaying = false;
        a.bindPoseApplied = true;
        float[][] posed = new float[a.skeleton.getBoneCount()][];
        for (int i = 0; i < posed.length; i++) posed[i] = a.bonePaletteMatrices[i].clone();

        for (int i = 0; i < 30; i++) system.update(scene, hierarchy, null, DT);

        for (int i = 0; i < posed.length; i++) assertArrayEquals(posed[i], a.bonePaletteMatrices[i], 0.0f);
    }

    @Test
    void aMissingBoneDoesNothing() {
        Entity entity = rig(biped());
        LookAtIK look = new LookAtIK();
        look.chain = "Spine,NoSuchBone";
        look.setTargetPoint(2.0f, 1.6f, 2.0f);
        entity.addComponent(look);
        frames(entity, new ArrayList<>(List.of(entity)), 10);
        assertArrayEquals(new double[] {0, 0, 1}, headForward(animator(entity)), 1e-9);
    }
}
