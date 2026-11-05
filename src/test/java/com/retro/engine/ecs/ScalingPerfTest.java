package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.graphics.SceneRenderPipeline;
import com.retro.engine.testing.KnownIssue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

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

    /** Calls the private method directly, because extractSnapshot only reaches it for meshes loaded onto a GPU. */
    @Test
    @KnownIssue("Each visible object scans every entity for the ground below it (diagnostic 5.4)")
    void groundShadowScanFitsTheFrameBudget() throws Exception {
        EditorWorkspace workspace = new EditorWorkspace();
        SceneWorld world = workspace.getSceneManager();
        int count = 1000;
        for (int i = 0; i < count; i++) {
            Entity e = world.spawnEntity("ground_" + i, "ground", "mesh.obj");
            e.getTransform().posX = i % 100;
            e.getTransform().posZ = i / 100;
        }
        SceneRenderPipeline pipeline = new SceneRenderPipeline();
        Method scan = SceneRenderPipeline.class.getDeclaredMethod("findGroundSurfaceY", Entity.class,
                SceneWorld.WorldTransform.class, List.class, EditorWorkspace.class);
        scan.setAccessible(true);

        List<Entity> entities = world.getSceneEntities();
        double best = Double.MAX_VALUE;
        for (int frame = 0; frame < 5; frame++) {
            long start = System.nanoTime();
            for (Entity e : entities) scan.invoke(pipeline, e, world.getComputedWorldTransform(e), entities, workspace);
            best = Math.min(best, (System.nanoTime() - start) / 1e6);
        }
        assertTrue(best < 1.0, count + " visible objects took " + best + " ms");
    }
}
