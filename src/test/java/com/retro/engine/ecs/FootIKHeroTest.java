package com.retro.engine.ecs;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.at;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.PoseEditor;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.FootIK;
import com.retro.engine.ecs.components.LookAtIK;
import com.retro.engine.ecs.components.MeshCollider;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.physics.RaycastHit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The template's hero walking a real controller over real colliders on the real walk clip, the whole frame in engine order.
 * Each run is measured with foot IK off and on, so these fail if the solver stops earning its place.
 */
class FootIKHeroTest {

    private static final float DT = 1.0f / 60.0f;
    private static final float WALK_SPEED = 1.02f;
    private static final float CAPSULE_HEIGHT = 1.8f, CAPSULE_RADIUS = 0.35f;
    private static final String HERO = "src/main/resources/templates/third_person_example/Meshes/hero_character.glb";

    @TempDir Path dir;

    /** What a run measured, over the frames spent on the slope or stairs. */
    private record Walk(double sink, double pelvisJump, double climbed) {}

    private Walk walk(List<Entity> ground, boolean useIK, float fromZ, float toZ) {
        return walk(ground, useIK, 0.0f, 0.0f, fromZ, toZ, 0.3f);
    }

    /** Walks from (startZ, startY) along Z, measuring between fromZ and toZ. */
    private Walk walk(List<Entity> ground, boolean useIK, float startZ, float startY, float fromZ, float toZ, float stepOffset) {
        float direction = toZ < startZ ? -1.0f : 1.0f;
        Entity player = at("Player", 0, startY + CAPSULE_HEIGHT * 0.5f + 0.02f, startZ);
        CapsuleCollider capsule = new CapsuleCollider();
        capsule.radius = CAPSULE_RADIUS;
        capsule.height = CAPSULE_HEIGHT;
        player.addComponent(capsule);
        CharacterControllerComponent controller = new CharacterControllerComponent();
        controller.stepOffset = stepOffset;
        player.addComponent(controller);

        Entity hero = at("Hero", 0, -CAPSULE_HEIGHT * 0.5f, 0);
        hero.getTransform().rotY = direction < 0.0f ? 180.0f : 0.0f; // the model faces +Z
        hero.parentId = player.id;
        player.addChildId(hero.id);
        EntitySpawner.attachAnimatorIfRigged(hero, new File(HERO).getAbsolutePath());
        AnimatorComponent animator = hero.getComponent(AnimatorComponent.class);
        if (useIK) hero.addComponent(new FootIK());

        List<Entity> world = new ArrayList<>(ground);
        world.add(player);
        world.add(hero);
        PhysicsSystem physics = physics(world);
        HierarchySystem hierarchy = new HierarchySystem();
        IKSystem ik = new IKSystem();
        AnimationSystem animation = new AnimationSystem();
        animation.setPlayState(SceneWorld.PlayState.SIMULATING);
        animation.enterEntryState(animator);
        assertTrue(animator.playAnimation("anim_walk"), "the template hero has a walk clip");

        int left = animator.skeleton.getBoneIndex("Foot_L"), right = animator.skeleton.getBoneIndex("Foot_R");
        int pelvis = animator.skeleton.getBoneIndex("Pelvis");
        float[] joints = animator.skeleton.getBindJointPositions();
        double ankleRest = joints[left * 3 + 1];
        double[] model = new double[3], rotation = new double[9];
        RaycastHit hit = new RaycastHit();

        double sink = 0.0, pelvisJump = 0.0, lastPelvis = Double.NaN;
        double measuredFromY = Double.NaN;
        for (int frame = 0; frame < 1200 && (player.getTransform().posZ - toZ) * direction < 0.0f; frame++) {
            controller.velocityZ = WALK_SPEED * direction;
            physics.step(DT, world);
            hierarchy.invalidateEntityIndex();
            animation.update(animator, DT);
            ik.update(world, hierarchy, physics, DT);

            if ((player.getTransform().posZ - fromZ) * direction < 0.0f) continue;
            SceneWorld.WorldTransform at = hierarchy.getComputedWorldTransform(hero, world);
            HierarchySystem.rotationMatrix(at.rx(), at.ry(), at.rz(), rotation);
            if (Double.isNaN(measuredFromY)) measuredFromY = at.py();

            for (int bone : new int[] {left, right}) {
                PoseEditor.jointPosition(animator, joints, bone, model);
                PoseEditor.apply(rotation, model, model);
                double x = model[0] + at.px(), y = model[1] + at.py(), z = model[2] + at.pz();
                physics.raycastExcludingInPlace(player, (float) x, at.py() + 1.0f, (float) z, 0, -1, 0, 3.0f, hit);
                if (hit.hit) sink = Math.max(sink, hit.pointY - (y - ankleRest));
            }
            PoseEditor.jointPosition(animator, joints, pelvis, model);
            double pelvisY = model[1] + at.py();
            if (!Double.isNaN(lastPelvis)) pelvisJump = Math.max(pelvisJump, Math.abs(pelvisY - lastPelvis));
            lastPelvis = pelvisY;
        }
        return new Walk(sink, pelvisJump, player.getTransform().posY - CAPSULE_HEIGHT * 0.5f - (Double.isNaN(measuredFromY) ? 0.0 : measuredFromY));
    }

    private static List<Entity> stairs(int count, float rise, float run, float startZ) {
        List<Entity> ground = new ArrayList<>();
        ground.add(box(0, -0.5f, 0, 10, 1, 60, STATIC));
        for (int i = 0; i < count; i++) {
            float height = rise * (i + 1);
            ground.add(box(0, height * 0.5f, startZ - run * (i + 0.5f), 6, height, run, STATIC));
        }
        float top = rise * count;
        ground.add(box(0, top * 0.5f, startZ - run * count - 5.0f, 6, top, 10, STATIC));
        return ground;
    }

    private Entity ramp(float slope, float startZ) throws IOException {
        Path file = dir.resolve("ramp" + System.nanoTime() + ".obj");
        float end = startZ - 30.0f, top = 30.0f * slope;
        Files.writeString(file, "v -6 0 40\nv 6 0 40\nv -6 0 " + startZ + "\nv 6 0 " + startZ + "\nv -6 " + top + " " + end + "\nv 6 " + top + " " + end + "\n"
                + "f 1 2 3\nf 2 4 3\nf 3 4 5\nf 4 6 5\n");
        Entity e = at("ramp", 0, 0, 0);
        MeshCollider collider = new MeshCollider();
        collider.meshPath = file.toString();
        e.addComponent(collider);
        return e;
    }

    @Test
    void walkingUpStairs() {
        float end = -2.0f - 0.4f * 8.0f;
        Walk plain = walk(stairs(8, 0.18f, 0.4f, -2.0f), false, -1.5f, end);
        Walk solved = walk(stairs(8, 0.18f, 0.4f, -2.0f), true, -1.5f, end);
        assertTrue(plain.climbed() > 1.4, "the controller really climbed: " + plain);
        assertTrue(plain.sink() > 0.1 && solved.sink() < 0.03, "feet stay out of the steps: " + plain + " " + solved);
        assertTrue(plain.pelvisJump() > 0.15 && solved.pelvisJump() < 0.08, "the body eases up each step: " + plain + " " + solved);
    }

    @Test
    void walkingDownStairs() {
        float top = -2.0f - 0.4f * 8.0f;
        Walk plain = walk(stairs(8, 0.18f, 0.4f, -2.0f), false, top - 1.5f, 1.44f, top - 0.5f, -1.5f, 0.3f);
        Walk solved = walk(stairs(8, 0.18f, 0.4f, -2.0f), true, top - 1.5f, 1.44f, top - 0.5f, -1.5f, 0.3f);
        assertTrue(plain.climbed() < -1.4, "the controller really came down: " + plain);
        assertTrue(solved.sink() < 0.03, "feet stay out of the steps: " + plain + " " + solved);
        assertTrue(plain.pelvisJump() > 0.15 && solved.pelvisJump() < 0.08, "the body eases down each step: " + plain + " " + solved);
    }

    @Test
    void walkingUpTreadsNarrowerThanTheCapsule() {
        float end = -2.0f - 0.28f * 8.0f;
        Walk solved = walk(stairs(8, 0.18f, 0.28f, -2.0f), true, 0.0f, 0.0f, -1.5f, end, 0.3f);
        assertTrue(solved.climbed() > 1.4, "the controller really climbed: " + solved);
        assertTrue(solved.sink() < 0.04, "feet stay out of the steps: " + solved);
    }

    @Test
    void walkingUpASlope() throws IOException {
        Walk plain = walk(List.of(ramp(0.45f, -2.0f)), false, -3.0f, -8.0f);
        Walk solved = walk(List.of(ramp(0.45f, -2.0f)), true, -3.0f, -8.0f);
        assertTrue(plain.climbed() > 2.0, "the controller really climbed: " + plain);
        assertTrue(plain.sink() > 0.05 && solved.sink() < 0.01, "feet stay out of the slope: " + plain + " " + solved);
    }

    @Test
    void theHeroLooksAtATargetThroughItsIdle() {
        Entity hero = at("Hero", 0, 0, 0);
        EntitySpawner.attachAnimatorIfRigged(hero, new File(HERO).getAbsolutePath());
        AnimatorComponent animator = hero.getComponent(AnimatorComponent.class);
        LookAtIK look = new LookAtIK();
        look.setTargetPoint(1.5f, 1.7f, 2.0f);
        hero.addComponent(look);

        List<Entity> world = new ArrayList<>(List.of(hero));
        HierarchySystem hierarchy = new HierarchySystem();
        IKSystem ik = new IKSystem();
        AnimationSystem animation = new AnimationSystem();
        animation.setPlayState(SceneWorld.PlayState.SIMULATING);
        animation.enterEntryState(animator);
        assertTrue(animator.playAnimation("anim_idle"));
        for (int frame = 0; frame < 240; frame++) {
            animation.update(animator, DT);
            ik.update(world, hierarchy, null, DT);
        }

        int head = animator.skeleton.getBoneIndex("Head");
        double[] eye = new double[3], facing = new double[3];
        PoseEditor.jointPosition(animator, animator.skeleton.getBindJointPositions(), head, eye);
        PoseEditor.boneDirection(animator, head, 0, 0, 1, facing);
        double[] toTarget = {1.5 - eye[0], 1.7 - eye[1], 2.0 - eye[2]};
        PoseEditor.normalize(toTarget);
        double off = Math.toDegrees(PoseEditor.angleBetween(facing, toTarget));
        assertTrue(off < 2.0, "the head is " + off + " degrees off its target");
    }
}
