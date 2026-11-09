package com.retro.engine.editor.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.audio.AudioBus;
import com.retro.engine.audio.AudioEngine;
import com.retro.engine.audio.AudioMixerService;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.ScriptEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

/** Plays the scene a new Infinite Driver project opens on. Its chase camera lives in CarDriverScript.onLateUpdate. */
class InfiniteDriverTemplateTest {

    private static final float DT = 1.0f / 60.0f;

    @TempDir Path dir;
    private SceneWorld world;
    private Entity car, camera;

    @BeforeEach
    void createProject() throws IOException {
        Input.clearState();
        EditorWorkspace workspace = new EditorWorkspace();
        ProjectWorkspace.createProject(workspace, dir.toFile(), "Game", TemplateManager.INFINITE_DRIVER);
        world = workspace.getSceneManager();
        car = world.findEntityByName("Car");
        camera = world.findEntityByName("Main Camera");
        assertNotNull(car);
        assertNotNull(camera);
    }

    @AfterEach
    void stop() {
        world.stop();
        Input.clearState();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    @Test
    void chaseCameraRunsInLateUpdate() {
        Camera lens = camera.getComponent(Camera.class);
        assertEquals(65.0f, lens.fov, 1e-4f, "the scene authors FOV 65");
        int failuresBefore = ScriptEngine.getFailureCount();
        world.play();
        for (int i = 0; i < 120; i++) world.update(DT);

        assertEquals(failuresBefore, ScriptEngine.getFailureCount(), "the template's scripts must run clean");
        // onLateUpdate sets fov = baseFov (62) + speedFov * speed, so 65 exactly means it never ran
        assertNotEquals(65.0f, lens.fov, 1e-4f);
        assertEquals(car.getTransform().posZ + 8.0f, camera.getTransform().posZ, 1.0f, "the camera sits cameraDistance behind the car");
    }

    @Test
    void engineLoopKeepsPlayingAfterTheStartUpClip() {
        AudioSource engine = car.getComponent(AudioSource.class);
        assertNotNull(engine);
        assertTrue(engine.loop && engine.playOnStart, "the engine note is a looping source that starts with Play");
        world.play();
        for (int i = 0; i < 300; i++) world.update(DT);
        if (AudioEngine.isAvailable()) {
            assertEquals(AudioSource.PlaybackState.PLAYING, engine.state, "the engine loop must still be running after 5 s");
            assertTrue(engine.getSourceHandle() != 0);
        }
    }

    @Test
    void soundtrackLoopsOnTheBgmBusAndMTogglesIt() {
        Entity music = world.findEntityByName("Music");
        assertNotNull(music, "the template ships a soundtrack");
        AudioSource track = music.getComponent(AudioSource.class);
        assertTrue(track.loop && track.playOnStart);
        assertEquals(AudioBus.BGM, track.bus, "on BGM, so the M key and the crash duck reach it");
        assertTrue(new java.io.File(track.audioAssetPath).isFile(), "the clip is copied into the new project");

        int failuresBefore = ScriptEngine.getFailureCount();
        world.play();
        for (int i = 0; i < 60; i++) world.update(DT);
        assertEquals(failuresBefore, ScriptEngine.getFailureCount());
        if (AudioEngine.isAvailable()) assertEquals(AudioSource.PlaybackState.PLAYING, track.state);

        Input.setKeyState(77, true); // M
        world.update(DT);
        Input.setKeyState(77, false);
        assertTrue(AudioMixerService.isBusMuted(AudioBus.BGM), "M mutes the soundtrack");
    }
}
