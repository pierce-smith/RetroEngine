package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.templates.TemplateManager;
import com.retro.engine.tools.ThirdPersonScene;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/** Plays the prefab and script a Third Person Example project ships with, using simulated input. */
class ThirdPersonControllerTest {

    private static final float DT = 1.0f / 60.0f;
    private static final int W = 87, D = 68, SHIFT = 340, CTRL = 341;

    @TempDir static Path dir;
    private static EditorWorkspace workspace;
    private static File project;

    private SceneWorld world;
    private Entity player, hero, camera;
    private AnimatorComponent animator;

    @BeforeAll
    static void createProject() throws IOException {
        workspace = new EditorWorkspace();
        project = ProjectWorkspace.createProject(workspace, dir.toFile(), "Game", TemplateManager.THIRD_PERSON_EXAMPLE);
    }

    @AfterAll
    static void closeProject() {
        ScriptEngine.setProjectAssetsRoot(null);
    }

    @BeforeEach
    void spawn() {
        Input.clearState();
        world = new SceneWorld();
        solid("Floor", 0, -0.5f, 0, 200, 1, 200);
        List<Entity> prefab = PrefabSerializer.instantiatePrefab(
                new File(project, ThirdPersonScene.PREFAB).getPath(), world, workspace.getAssetDatabase());
        assertEquals(3, prefab.size());
        player = prefab.get(0);
        player.getTransform().posY = 0.95f;
        camera = world.findEntityByName("Main Camera");
        hero = world.findEntityByName("Hero");
        animator = hero.getComponent(AnimatorComponent.class);
        assertNotNull(animator, "the prefab's Hero should come up rigged");
    }

    @AfterEach
    void stop() {
        Input.clearState();
        world.stop();
    }

    private void solid(String name, float x, float y, float z, float sx, float sy, float sz) {
        Entity e = new Entity("solid_" + name, name, null);
        Transform t = e.getTransform();
        t.posX = x; t.posY = y; t.posZ = z;
        t.scaleX = sx; t.scaleY = sy; t.scaleZ = sz;
        e.addComponent(new BoxCollider());
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = RigidBodyComponent.MotionType.STATIC;
        e.addComponent(body);
        world.addEntity(e, -1);
    }

    private void run(float seconds) {
        for (int i = 0, n = Math.round(seconds / DT); i < n; i++) world.update(DT);
    }

    private void hold(int... keys) {
        Input.clearState();
        for (int key : keys) Input.setKeyState(key, true);
    }

    private String shownState() {
        return animator.transitioning ? animator.targetStateId : animator.currentStateId;
    }

    private float speedOver(float seconds) {
        float x = player.getTransform().posX, z = player.getTransform().posZ;
        run(seconds);
        float dx = player.getTransform().posX - x, dz = player.getTransform().posZ - z;
        return (float) Math.sqrt(dx * dx + dz * dz) / seconds;
    }

    private float phase() {
        return animator.currentTime / animator.getCurrentState().clip.durationSeconds % 1.0f;
    }

    @Test
    void startsIdleOnTheGround() {
        world.play();
        run(1.0f);
        assertEquals("anim_idle", shownState());
        assertEquals(0.9f, player.getTransform().posY, 0.02f);
    }

    @Test
    void eachInputReachesItsStateAtItsSpeed() {
        world.play();
        run(0.5f);

        hold(W);
        run(0.6f);
        assertEquals("anim_walk", shownState());
        assertEquals(1.4f, speedOver(1.0f), 0.05f);
        assertEquals(1.4f / 1.02f, animator.playbackSpeed, 0.08f);

        hold(W, SHIFT);
        run(0.6f);
        assertEquals("anim_sprint", shownState());
        assertEquals(4.5f, speedOver(1.0f), 0.1f);

        hold(W, CTRL);
        run(0.6f);
        assertEquals("anim_sneak", shownState());
        assertEquals(0.6f, speedOver(1.0f), 0.05f);

        hold(W, CTRL, SHIFT);
        run(0.6f);
        assertEquals("anim_sneak", shownState(), "sneak wins over sprint");
        assertEquals(0.6f, speedOver(1.0f), 0.05f);

        hold();
        run(1.0f);
        assertEquals("anim_idle", shownState());
    }

    @Test
    void walkToSprintKeepsTheStridePhase() {
        world.play();
        hold(W);
        run(1.0f);
        assertFalse(animator.transitioning);
        hold(W, SHIFT);
        for (int i = 0; i < 60 && !animator.transitioning; i++) world.update(DT);
        assertTrue(animator.transitioning, "should start fading into sprint");
        while (animator.transitioning) {
            float from = phase();
            float to = animator.targetTime / animator.getTargetState().clip.durationSeconds % 1.0f;
            float gap = Math.abs(from - to);
            assertTrue(Math.min(gap, 1.0f - gap) < 0.03f, "phases drifted: " + from + " vs " + to);
            world.update(DT);
        }
        assertEquals("anim_sprint", animator.currentStateId);
    }

    @Test
    void stoppingMidSneakHoldsTheCrouchWithBothFeetDown() {
        world.play();
        hold(W, CTRL);
        run(1.3f);
        hold(CTRL);
        run(2.0f);
        assertEquals("anim_sneak", shownState());
        assertEquals(0.0f, animator.playbackSpeed);
        float held = animator.currentTime;
        run(0.5f);
        assertEquals(held, animator.currentTime);
        float p = phase();
        assertTrue((p >= 0.06f && p < 0.16f) || (p >= 0.56f && p < 0.66f), "held at phase " + p);

        hold(W, CTRL);
        run(0.5f);
        assertNotEquals(held, animator.currentTime, "moving again resumes the clip");
    }

    @Test
    void heroTurnsTowardTravelWhileTheRootStaysPut() {
        world.play();
        hold(W);
        run(0.5f);
        assertEquals(180.0f, Math.abs(hero.getTransform().rotY), 2.0f);
        hold(D);
        run(0.5f);
        assertEquals(-90.0f, hero.getTransform().rotY, 2.0f);
        assertEquals(0.0f, player.getTransform().rotY);
    }

    /** Banks look input in the solver as mouse motion does; only a real window can produce mouse deltas. */
    private void look(float yawDegrees, float pitchDegrees) throws ReflectiveOperationException {
        Field solverField = world.getPhysicsWorld().getClass().getDeclaredField("characterSolver");
        solverField.setAccessible(true);
        Object solver = solverField.get(world.getPhysicsWorld());
        Field yaw = solver.getClass().getDeclaredField("pendingLookYaw");
        Field pitch = solver.getClass().getDeclaredField("pendingLookPitch");
        yaw.setAccessible(true);
        pitch.setAccessible(true);
        yaw.setFloat(solver, yawDegrees);
        pitch.setFloat(solver, pitchDegrees);
        run(0.05f);
    }

    @Test
    void cameraOrbitsThePlayerAndMovementFollowsIt() throws ReflectiveOperationException {
        Transform view = camera.getTransform();
        world.play();
        run(0.2f);

        look(90.0f, 0.0f);
        assertEquals(90.0f, view.rotY, 0.01f);
        assertTrue(view.posX < -2.5f, "a quarter orbit should swing the camera round to -X, x = " + view.posX);
        assertEquals(0.0f, view.posZ, 0.2f);
        float trailing = view.posX;

        float startX = player.getTransform().posX, startZ = player.getTransform().posZ;
        hold(W);
        run(1.0f);
        assertTrue(player.getTransform().posX - startX > 1.0f, "W should head where the camera now looks (+X)");
        assertEquals(startZ, player.getTransform().posZ, 0.05f);
        assertEquals(-90.0f, hero.getTransform().rotY, 2.0f);
        assertEquals(90.0f, view.rotY, 0.01f, "walking must not swing the camera");
        assertEquals(trailing, view.posX, 0.01f, "the camera should still trail the player");
    }

    @Test
    void lookingUpKeepsTheCameraAboveTheFloor() throws ReflectiveOperationException {
        world.play();
        run(0.2f);
        look(0.0f, -60.0f);
        float worldY = player.getTransform().posY + camera.getTransform().posY;
        assertTrue(worldY > 0.1f, "camera went under the floor, y = " + worldY);
    }

    @Test
    void cameraFollowsAndPullsInFrontOfAWall() {
        Transform view = camera.getTransform();
        solid("Wall", 0, 2, 2.0f, 20, 4, 0.5f);
        world.play();
        run(0.2f);
        assertTrue(view.posZ < 1.75f, "camera should stop short of the wall, local z = " + view.posZ);
        assertTrue(view.posZ > 0.2f, "camera should stay behind the player, local z = " + view.posZ);
    }
}
