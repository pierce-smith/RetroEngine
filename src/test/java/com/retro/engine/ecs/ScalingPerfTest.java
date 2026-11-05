package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.testing.KnownIssue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Timing checks, run with {@code ./gradlew perfTest} on a quiet machine. Limits are loose on purpose. */
@Tag("perf")
class ScalingPerfTest {

    @Test
    @KnownIssue("spawnEntity scans the scene for a duplicate id and scans for a free slot, so it is quadratic (diagnostic 5.1)")
    void spawnCostGrowsLinearly() {
        spawnMillis(5_000); // warm up
        double small = Math.min(spawnMillis(5_000), spawnMillis(5_000));
        double large = spawnMillis(40_000);
        // 8x the entities: linear growth gives about 8x and quadratic about 64x, so 20x separates them despite noise
        assertTrue(large / small < 20.0, "8x the entities took " + large / small + "x as long");
    }

    private static double spawnMillis(int count) {
        SceneWorld world = new SceneWorld();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) world.spawnEntity("spawn_" + i, "spawn", null);
        return (System.nanoTime() - start) / 1e6;
    }
}
