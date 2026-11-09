package com.retro.engine.ecs;

import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.KeyframeTrack;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.testing.TestEntities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The SceneWorld calls scripts use for runtime physics entities and hand-driven poses. */
class ScriptApiTest {

    private SceneWorld world;

    @BeforeEach
    void playing() {
        world = new SceneWorld();
        world.play();
    }

    @AfterEach
    void stop() {
        world.stop();
    }

    @Test
    void spawnedEntityGetsABodyAfterRefresh() {
        Entity gib = world.spawnEntity(null, "Gib", null);
        assertNotNull(gib);
        gib.addComponent(new BoxCollider());
        RigidBodyComponent body = new RigidBodyComponent();
        body.useGravity = false;
        body.linearDrag = 0.0f;
        gib.addComponent(body);
        assertFalse(world.setVelocity(gib, 1, 0, 0), "no body before refreshComponents");

        world.refreshComponents(gib);
        world.refreshComponents(gib); // harmless twice
        assertTrue(world.setVelocity(gib, 6.0f, 0.0f, 0.0f));
        assertTrue(world.setAngularVelocity(gib, 0.0f, 90.0f, 0.0f));
        for (int i = 0; i < 30; i++) world.update(TestEntities.FRAME);

        float[] v = new float[3];
        assertTrue(world.getVelocity(gib, v));
        assertEquals(6.0f, v[0], 0.05f);
        assertTrue(gib.getTransform().posX > 2.0f, "moved under its velocity: x=" + gib.getTransform().posX);
    }

    @Test
    void velocityNeedsADynamicBody() {
        Entity wall = world.spawnEntity(null, "Wall", null);
        wall.addComponent(new BoxCollider());
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = RigidBodyComponent.MotionType.KINEMATIC;
        wall.addComponent(body);
        world.refreshComponents(wall);
        assertFalse(world.setVelocity(wall, 1, 0, 0));
        assertFalse(world.setVelocity(null, 1, 0, 0));
        assertFalse(world.getVelocity(world.spawnEntity(null, "Empty", null), new float[3]));
    }

    private static Skeleton oneBone() {
        Skeleton skeleton = new Skeleton();
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        skeleton.addBone("Root", -1, identity, identity);
        return skeleton;
    }

    /** One second sliding the root bone from x=0 to x=10. */
    private static AnimationClip slide() {
        AnimationClip clip = new AnimationClip("Slide", 1.0f);
        KeyframeTrack track = new KeyframeTrack("Root");
        track.positionTimes = new float[] {0.0f, 1.0f};
        track.setPositions(new float[] {0, 0, 0, 10, 0, 0});
        clip.addTrack(track);
        return clip;
    }

    private AnimatorComponent spawnRig(AnimatorComponent animator) {
        Entity rig = world.spawnEntity(null, "Rig", null);
        rig.addComponent(animator);
        world.refreshComponents(rig);
        world.update(TestEntities.FRAME); // enters the controller's entry state
        return animator;
    }

    @Test
    void sampledControllerPoseIsHeld() {
        AnimatorController controller = new AnimatorController("Rig");
        controller.setEntryState(AnimationSystem.assignClipToState(controller, slide()).name);
        AnimatorComponent animator = spawnRig(new AnimatorComponent(oneBone(), controller));
        assertNotNull(animator.getCurrentState());

        world.sampleAnimatorPose(animator, 0.25f);
        assertEquals(2.5f, animator.bonePaletteMatrices[0][12], 0.01f);
        for (int i = 0; i < 5; i++) world.update(TestEntities.FRAME);
        assertEquals(2.5f, animator.bonePaletteMatrices[0][12], 0.01f, "the engine kept the sampled pose");
    }

    @Test
    void sampledPoseIsHeldWhileStopped() {
        AnimatorComponent animator = spawnRig(new AnimatorComponent(oneBone(), slide()));

        world.sampleAnimatorPose(animator, 0.5f);
        assertFalse(animator.isPlaying, "left stopped");
        assertEquals(5.0f, animator.bonePaletteMatrices[0][12], 0.01f);
        for (int i = 0; i < 5; i++) world.update(TestEntities.FRAME);
        assertEquals(5.0f, animator.bonePaletteMatrices[0][12], 0.01f, "the engine kept the sampled pose");

        world.sampleAnimatorPose(animator, 1.0f - 1e-3f);
        assertEquals(10.0f, animator.bonePaletteMatrices[0][12], 0.05f);
    }

    @Test
    void scriptComponentExposesItsInstance() {
        ScriptComponent sc = new ScriptComponent();
        assertNull(sc.getScript());
    }
}
