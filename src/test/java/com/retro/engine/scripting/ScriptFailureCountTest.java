package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.scripting.framework.ScriptEventListener;
import com.retro.engine.scripting.framework.ScriptEvents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Headless runs exit on this count, so anything a script got wrong has to reach it. */
class ScriptFailureCountTest {

    @AfterEach
    void clearListeners() {
        ScriptEvents.clear();
    }

    @Test
    void aThrowingListenerCounts() {
        ScriptEventListener broken = (name, source, text, number) -> { throw new IllegalStateException("listener bug"); };
        ScriptEvents.subscribe(broken);
        int before = ScriptEngine.getFailureCount();
        ScriptEvents.publish("tick", null);
        assertEquals(before + 1, ScriptEngine.getFailureCount());
    }

    @Test
    void aMissingPrefabCounts() {
        int before = ScriptEngine.getFailureCount();
        new SceneWorld().instantiatePrefabRuntime("Prefabs/Nope.rprefab");
        assertEquals(before + 1, ScriptEngine.getFailureCount());
    }

    @Test
    void loadingAMissingSceneCounts() {
        SceneManager.bind(new SceneWorld());
        int before = ScriptEngine.getFailureCount();
        SceneManager.loadScene("Scenes/Nope.rscene");
        assertEquals(before + 1, ScriptEngine.getFailureCount());
    }
}
