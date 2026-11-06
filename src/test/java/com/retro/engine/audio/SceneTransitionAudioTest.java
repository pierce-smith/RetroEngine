package com.retro.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class SceneTransitionAudioTest {

    @TempDir Path dir;
    private SceneWorld world;
    private String nextScene;

    @BeforeEach
    void startPlaying() {
        List<Entity> entities = new ArrayList<>(List.of(TestEntities.at("next", 0, 0, 0)));
        nextScene = dir.resolve("Next.rscene").toString();
        assertTrue(SceneSerializer.saveScene(nextScene, entities, new ArrayList<>(), null));
        world = new SceneWorld();
        world.addEntity(TestEntities.at("first", 0, 0, 0), -1);
        world.play();
    }

    @AfterEach
    void resetMix() {
        world.stop();
        AudioMixerService.resetRuntime();
    }

    private void transition() {
        world.requestSceneLoad(nextScene);
        world.update(TestEntities.FRAME);
        assertEquals("next", world.getSceneEntities().get(0).name);
    }

    @Test
    void scriptVolumesSurviveASceneTransition() {
        AudioMixerService.setBusVolume(AudioBus.BGM, 0.35f);
        AudioMixerService.setBusMuted(AudioBus.SFX, true);
        transition();
        assertEquals(0.35f, AudioMixerService.getBusVolume(AudioBus.BGM));
        assertTrue(AudioMixerService.isBusMuted(AudioBus.SFX));
    }

    @Test
    void stoppingPlayStillResetsThemAfterATransition() {
        AudioMixerService.setBusVolume(AudioBus.BGM, 0.35f);
        AudioMixerService.setBusMuted(AudioBus.SFX, true);
        transition();
        world.stop();
        assertEquals(1.0f, AudioMixerService.getBusVolume(AudioBus.BGM));
        assertFalse(AudioMixerService.isBusMuted(AudioBus.SFX));
    }

    @Test
    void aTransitionReleasesTheDuck() {
        AudioMixerService.reportDuckTrigger();
        AudioMixerService.endFrame(1.0f);
        assertTrue(AudioMixerService.getDuckGain() < 1.0f);
        transition();
        assertEquals(1.0f, AudioMixerService.getDuckGain());
    }
}
