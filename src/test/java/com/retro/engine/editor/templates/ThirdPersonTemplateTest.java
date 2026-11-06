package com.retro.engine.editor.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/** Plays the scene a new Third Person Example project opens on. */
class ThirdPersonTemplateTest {

    private static final float DT = 1.0f / 60.0f;

    @TempDir Path dir;
    private SceneWorld world;
    private Entity player, thug;
    private AnimatorComponent animator;

    @BeforeEach
    void createProject() throws IOException {
        Input.clearState();
        EditorWorkspace workspace = new EditorWorkspace();
        ProjectWorkspace.createProject(workspace, dir.toFile(), "Game", TemplateManager.THIRD_PERSON_EXAMPLE);
        world = workspace.getSceneManager();
        player = world.findEntityByName("Player");
        thug = world.findEntityByName("Thug");
        assertNotNull(player);
        assertNotNull(thug);
        animator = thug.getComponent(AnimatorComponent.class);
        assertNotNull(animator, "the Thug should come up rigged");
    }

    @AfterEach
    void stop() {
        world.stop();
        Input.clearState();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    private void run(float seconds) {
        for (int i = 0, n = Math.round(seconds / DT); i < n; i++) world.update(DT);
    }

    private String shownState() {
        return animator.transitioning ? animator.targetStateId : animator.currentStateId;
    }

    private float flatDistance() {
        Transform a = player.getTransform(), b = thug.getTransform();
        float dx = a.posX - b.posX, dz = a.posZ - b.posZ;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void bothCharactersStandOnTheVisibleFloor() {
        world.play();
        run(1.0f);
        assertEquals(0.9f, player.getTransform().posY, 0.02f);
        assertEquals(0.0f, thug.getTransform().posY, 0.02f);
    }

    @Test
    void thugWalksThePatrolFacingWhereItGoes() {
        float startX = thug.getTransform().posX;
        world.play();
        run(3.0f);
        assertEquals("npc_patrol", shownState());
        assertTrue(thug.getTransform().posX - startX > 1.5f, "should be heading out along +X, x = " + thug.getTransform().posX);
        assertEquals(90.0f, Math.abs(thug.getTransform().rotY), 5.0f);
    }

    @Test
    void thugTurnsBackAtTheFarEnd() {
        float startX = thug.getTransform().posX;
        world.play();
        run(11.0f);
        assertTrue(thug.getTransform().posX - startX > 7.0f, "should have reached the far end, x = " + thug.getTransform().posX);
        run(5.0f);
        assertTrue(thug.getTransform().posX - startX < 6.5f, "should be walking back, x = " + thug.getTransform().posX);
    }

    @Test
    void thugBacksAwayStillFacingThePlayer() {
        world.play();
        run(0.5f);
        Transform body = thug.getTransform();
        player.getTransform().setPosition(body.posX, player.getTransform().posY, body.posZ + 2.5f);
        run(0.2f);
        float close = flatDistance();
        run(1.5f);
        assertEquals("npc_panic", shownState());
        assertTrue(flatDistance() > close + 1.0f, "should have opened the gap from " + close + " to " + flatDistance());

        // Yaw 0 faces +Z, which is where the player stands: the clip walks backward, so it mustn't turn to its travel
        assertEquals(0.0f, body.rotY, 5.0f);
    }

    @Test
    void thugCalmsDownOnceThePlayerLeaves() {
        world.play();
        run(0.5f);
        Transform body = thug.getTransform();
        player.getTransform().setPosition(body.posX, player.getTransform().posY, body.posZ + 2.5f);
        run(1.0f);
        assertEquals("npc_panic", shownState());
        player.getTransform().setPosition(body.posX, player.getTransform().posY, body.posZ + 9.0f);
        run(3.0f);
        assertTrue("npc_patrol".equals(shownState()) || "npc_idle".equals(shownState()), "still " + shownState());
    }
}
