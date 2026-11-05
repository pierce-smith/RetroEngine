package com.retro.engine.ecs;

import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.DYNAMIC;
import static com.retro.engine.ecs.components.RigidBodyComponent.MotionType.STATIC;
import static com.retro.engine.testing.TestEntities.allocatedBytes;
import static com.retro.engine.testing.TestEntities.box;
import static com.retro.engine.testing.TestEntities.physics;
import static com.retro.engine.testing.TestEntities.scene;
import static com.retro.engine.testing.TestEntities.step;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.components.Light;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Holds the engine to "the frame loop must not feed the garbage collector" for the simulation path. */
class AllocationTest {

    private static final long LIMIT_BYTES_PER_FRAME = 1024;
    private static final int WARMUP_FRAMES = 600;
    private static final int MEASURED_FRAMES = 300;

    @Test
    void physicsStepDoesNotAllocate() {
        List<Entity> scene = scene(box(0, -0.5f, 0, 400, 1, 400, STATIC));
        for (int i = 0; i < 500; i++) {
            scene.add(box((i % 8) * 1.5f, 2 + ((i / 8) % 8) * 1.5f, (i / 64) * 1.5f, 1, 1, 1, DYNAMIC));
        }
        PhysicsSystem physics = physics(scene);
        step(physics, scene, WARMUP_FRAMES);

        long before = allocatedBytes();
        step(physics, scene, MEASURED_FRAMES);
        assertUnderLimit(allocatedBytes() - before);
    }

    @Test
    void sceneTickDoesNotAllocate() {
        SceneWorld world = new SceneWorld();
        for (int i = 0; i < 1000; i++) {
            Entity e = world.spawnEntity("tick_" + i, "tick", null);
            e.getTransform().posX = i;
            e.addComponent(new Light());
        }
        world.play();
        try {
            for (int i = 0; i < WARMUP_FRAMES; i++) world.update(TestEntities.FRAME);
            long before = allocatedBytes();
            for (int i = 0; i < MEASURED_FRAMES; i++) world.update(TestEntities.FRAME);
            assertUnderLimit(allocatedBytes() - before);
        } finally {
            world.stop();
        }
    }

    private static void assertUnderLimit(long totalBytes) {
        long perFrame = totalBytes / MEASURED_FRAMES;
        assertTrue(perFrame < LIMIT_BYTES_PER_FRAME, perFrame + " bytes per frame");
    }
}
