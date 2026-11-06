package com.retro.engine.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Every AudioSource on an entity is driven, not only the first: layered loops share one entity. */
class MultipleAudioSourcesTest {

    /** Records what the simulation asks of it, so this runs without an OpenAL device. */
    private static final class Probe extends AudioSource {
        int plays, pauses, disposals;

        Probe(boolean playOnStart) { this.playOnStart = playOnStart; }

        @Override public void play() { plays++; state = PlaybackState.PLAYING; }
        @Override public void pause() { pauses++; state = PlaybackState.PAUSED; }
        @Override public void dispose() { disposals++; state = PlaybackState.STOPPED; }
    }

    @TempDir Path dir;

    @Test
    void playOnStartReachesEverySourceThatAsksForIt() {
        Probe first = new Probe(true), second = new Probe(true), silent = new Probe(false);
        SceneWorld world = worldWith(first, second, silent);
        world.play();
        assertEquals(1, first.plays);
        assertEquals(1, second.plays);
        assertEquals(0, silent.plays);
        world.stop();
    }

    @Test
    void pauseAndResumeCoverEveryPlayingSource() {
        Probe first = new Probe(true), second = new Probe(true), silent = new Probe(false);
        SceneWorld world = worldWith(first, second, silent);
        world.play();
        world.pause();
        assertEquals(1, first.pauses);
        assertEquals(1, second.pauses);
        assertEquals(0, silent.pauses);
        world.play();
        assertEquals(2, first.plays);
        assertEquals(2, second.plays);
        world.stop();
    }

    @Test
    void deletingAnEntityDisposesEverySource() {
        Probe first = new Probe(false), second = new Probe(false);
        SceneWorld world = worldWith(first, second);
        world.deleteEntity(world.getSceneEntities().get(0));
        assertEquals(1, first.disposals);
        assertEquals(1, second.disposals);
    }

    @Test
    void everySourceSurvivesASaveAndLoad() {
        Entity entity = TestEntities.at("layered", 0, 0, 0);
        AudioSource idle = new AudioSource("Audio/idle.wav"), cut = new AudioSource("Audio/cut.wav");
        cut.loop = true;
        entity.addComponent(idle);
        entity.addComponent(cut);
        String scene = dir.resolve("Layered.rscene").toString();
        assertTrue(SceneSerializer.saveScene(scene, new ArrayList<>(List.of(entity)), new ArrayList<>(), null));

        SceneWorld world = new SceneWorld();
        world.requestSceneLoad(scene);
        world.update(TestEntities.FRAME);
        List<AudioSource> loaded = world.getSceneEntities().get(0).getComponents(AudioSource.class);
        assertEquals(2, loaded.size());
        assertTrue(loaded.get(1).loop);
    }

    private static SceneWorld worldWith(AudioSource... sources) {
        Entity entity = TestEntities.at("layered", 0, 0, 0);
        for (AudioSource source : sources) entity.addComponent(source);
        SceneWorld world = new SceneWorld();
        world.addEntity(entity, -1);
        return world;
    }
}
