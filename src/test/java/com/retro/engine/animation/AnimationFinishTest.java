package com.retro.engine.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A single-shot animation that reaches its end holds its last frame instead of snapping to the T-pose. */
class AnimationFinishTest {

    private SceneWorld world;

    @BeforeEach
    void play() {
        world = new SceneWorld();
        world.play();
    }

    @AfterEach
    void stop() {
        world.stop();
    }

    private static Skeleton oneBone() {
        Skeleton skeleton = new Skeleton();
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        skeleton.addBone("Root", -1, identity, identity);
        return skeleton;
    }

    /** One second sliding the root bone from x=0 to x=10. */
    private static AnimationClip slide(String name) {
        AnimationClip clip = new AnimationClip(name, 1.0f);
        KeyframeTrack track = new KeyframeTrack("Root");
        track.positionTimes = new float[] {0.0f, 1.0f};
        track.setPositions(new float[] {0, 0, 0, 10, 0, 0});
        clip.addTrack(track);
        return clip;
    }

    private AnimatorComponent rig(AnimatorComponent animator) {
        Entity e = world.spawnEntity(null, "Rig", null);
        e.addComponent(animator);
        world.refreshComponents(e);
        return animator;
    }

    private void seconds(float s) {
        for (int i = 0; i < Math.round(s / TestEntities.FRAME); i++) world.update(TestEntities.FRAME);
    }

    private static float rootX(AnimatorComponent a) {
        return a.bonePaletteMatrices[0][12];
    }

    private AnimatorComponent singleShotController(AnimatorState.FinishBehavior onFinish) {
        AnimatorController controller = new AnimatorController("Rig");
        AnimatorState state = AnimationSystem.assignClipToState(controller, slide("Die"));
        state.isLooping = false;
        state.onFinish = onFinish;
        controller.setEntryState(state.name);
        return rig(new AnimatorComponent(oneBone(), controller));
    }

    @Test
    void aFinishedSingleShotStateHoldsItsLastFrame() {
        AnimatorComponent a = singleShotController(AnimatorState.FinishBehavior.HOLD_LAST_FRAME);
        seconds(2.0f);
        assertTrue(a.isCurrentStateFinished(), "scripts and exit-time transitions still see it as finished");
        assertEquals(10.0f, rootX(a), 0.01f, "last frame, not the bind pose");
        seconds(1.0f);
        assertEquals(10.0f, rootX(a), 0.01f, "and it stays there");
    }

    @Test
    void bindPoseIsStillAvailable() {
        AnimatorComponent a = singleShotController(AnimatorState.FinishBehavior.BIND_POSE);
        seconds(2.0f);
        assertTrue(a.isCurrentStateFinished());
        assertEquals(0.0f, rootX(a), 0.01f);
    }

    @Test
    void crossFadingIntoASingleShotStateHoldsItsEnd() {
        AnimatorController controller = new AnimatorController("Rig");
        AnimatorState idle = AnimationSystem.assignClipToState(controller, slide("Idle"));
        AnimationClip fall = new AnimationClip("Fall", 0.5f);
        KeyframeTrack track = new KeyframeTrack("Root");
        track.positionTimes = new float[] {0.0f, 0.5f};
        track.setPositions(new float[] {0, 0, 0, -4, 0, 0});
        fall.addTrack(track);
        AnimatorState fallState = AnimationSystem.assignClipToState(controller, fall);
        fallState.isLooping = false;
        controller.setEntryState(idle.name);
        AnimatorComponent a = rig(new AnimatorComponent(oneBone(), controller));
        seconds(0.3f);

        assertTrue(a.crossFade("Fall", 0.2f));
        seconds(1.5f);
        assertTrue(a.isCurrentStateFinished());
        assertEquals(-4.0f, rootX(a), 0.01f);
    }

    @Test
    void aDirectOnceClipHoldsItsLastFrameAndSaysSo() {
        AnimationClip clip = slide("Die");
        clip.loopMode = AnimationClip.LoopMode.ONCE;
        AnimatorComponent a = rig(new AnimatorComponent(oneBone(), clip));
        seconds(0.5f);
        assertFalse(a.isClipFinished());
        seconds(1.5f);
        assertTrue(a.isClipFinished());
        assertTrue(a.isPlaying, "still playing, holding the end");
        assertEquals(10.0f, rootX(a), 0.01f);
    }

    @Test
    void aDirectOnceClipCanStillDropToTheBindPose() {
        AnimationClip clip = slide("Die");
        clip.loopMode = AnimationClip.LoopMode.ONCE;
        AnimatorComponent a = new AnimatorComponent(oneBone(), clip);
        a.directClipFinish = AnimatorState.FinishBehavior.BIND_POSE;
        rig(a);
        seconds(2.0f);
        assertEquals(0.0f, rootX(a), 0.01f);
    }

    @Test
    void anAnimationAScriptStartsInOnStartIsNotOverwrittenByTheEntryState(@org.junit.jupiter.api.io.TempDir java.nio.file.Path assets) throws Exception {
        world.stop();
        com.retro.engine.scripting.ScriptEngine.setProjectAssetsRoot(assets.toString());
        try {
            java.nio.file.Path script = assets.resolve("StartDie.java");
            java.nio.file.Files.writeString(script, """
                    public class StartDie extends com.retro.engine.scripting.framework.SimpleEntityScript {
                        public void onStart() { getAnimator().playAnimation("Die"); }
                    }
                    """);
            AnimatorController controller = new AnimatorController("Rig");
            AnimatorState idle = AnimationSystem.assignClipToState(controller, slide("Idle"));
            AnimatorState die = AnimationSystem.assignClipToState(controller, slide("Die"));
            controller.setEntryState(idle.name);
            world = new SceneWorld();
            Entity e = world.spawnEntity(null, "Rig", null);
            AnimatorComponent a = new AnimatorComponent(oneBone(), controller);
            e.addComponent(a);
            e.addComponent(new com.retro.engine.ecs.components.ScriptComponent(script.toString(), "StartDie.java"));
            world.refreshComponents(e);
            world.play();
            seconds(0.2f);
            assertEquals("Die", a.getCurrentState().name, "onStart's choice survives Play starting");
        } finally {
            com.retro.engine.scripting.ScriptEngine.setProjectAssetsRoot(null);
        }
    }
}
