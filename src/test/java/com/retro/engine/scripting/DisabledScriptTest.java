package com.retro.engine.scripting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.framework.ScriptEvents;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class DisabledScriptTest {

    private static final String SOURCE = """
        package com.retro.engine.scripting.user;
        import com.retro.engine.ecs.Entity;
        import com.retro.engine.scripting.framework.*;
        public class FailsOnSecondFrame extends SimpleEntityScript implements ScriptEventListener {
            private int frames;
            public void onStart() { subscribeEvents(this); }
            public void onUpdate(float dt) { if (++frames == 2) throw new IllegalStateException("script bug"); }
            public void onScriptEvent(String name, Entity source, String text, float number) {}
        }
        """;

    @TempDir Path assets;

    @AfterEach
    void unbindProject() {
        ScriptEvents.clear();
        ScriptEngine.setProjectAssetsRoot(null);
    }

    @Test
    void aScriptDisabledByAnExceptionStopsListening() throws IOException {
        Path file = assets.resolve("Scripts/FailsOnSecondFrame.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        ScriptEngine.setProjectAssetsRoot(assets.toString());

        SceneWorld world = new SceneWorld();
        Entity entity = TestEntities.at("failing", 0, 0, 0);
        entity.addComponent(new ScriptComponent(file.toString(), "FailsOnSecondFrame.java"));
        world.addEntity(entity, -1);
        world.play();
        world.update(TestEntities.FRAME);
        assertEquals(1, ScriptEvents.getListenerCount());
        world.update(TestEntities.FRAME);
        assertEquals(0, ScriptEvents.getListenerCount());
        world.stop();
    }
}
