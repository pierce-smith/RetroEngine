package com.retro.engine.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.testing.TestEntities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class GroundHeightIndexTest {

    private record Item(int id, float x, float z, float radius, float top) {}

    /** The per-draw scan this index replaced. Its answers define correct. */
    private static float bruteForce(List<Item> items, int self, float x, float y, float z) {
        float best = 0.0f;
        for (Item i : items) {
            if (i.id == self) continue;
            float dx = x - i.x, dz = z - i.z;
            if (dx * dx + dz * dz <= i.radius * i.radius && i.top < y + 0.05f && i.top > best) best = i.top;
        }
        return best;
    }

    private static GroundHeightIndex indexOf(List<Item> items) {
        GroundHeightIndex index = new GroundHeightIndex();
        index.reset();
        for (Item i : items) index.add(i.id, i.x, i.z, i.radius, i.top);
        index.build();
        return index;
    }

    private static List<Item> randomScene(Random random, int count) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float radius = random.nextInt(10) == 0 ? 5.0f + random.nextFloat() * 60.0f : 0.2f + random.nextFloat() * 3.0f;
            items.add(new Item(i, (random.nextFloat() - 0.5f) * 200.0f, (random.nextFloat() - 0.5f) * 200.0f, radius, random.nextFloat() * 12.0f - 2.0f));
        }
        return items;
    }

    @Test
    void matchesTheBruteForceScanEverywhere() {
        Random random = new Random(1234);
        for (int scene = 0; scene < 20; scene++) {
            List<Item> items = randomScene(random, 1 + random.nextInt(500));
            GroundHeightIndex index = indexOf(items);
            for (int q = 0; q < 2000; q++) {
                int self = random.nextInt(items.size());
                float x = (random.nextFloat() - 0.5f) * 240.0f, z = (random.nextFloat() - 0.5f) * 240.0f, y = random.nextFloat() * 14.0f - 3.0f;
                assertEquals(bruteForce(items, self, x, y, z), index.highestSurfaceBelow(self, x, y, z), "scene " + scene);
            }
        }
    }

    @Test
    void negativeCoordinatesAndCellBordersWork() {
        List<Item> items = List.of(new Item(0, -8.0f, -8.0f, 1.0f, 3.0f), new Item(1, 8.0f, 8.0f, 1.0f, 4.0f));
        GroundHeightIndex index = indexOf(items);
        assertEquals(3.0f, index.highestSurfaceBelow(9, -8.5f, 10.0f, -7.5f));
        assertEquals(4.0f, index.highestSurfaceBelow(9, 7.5f, 10.0f, 8.5f));
    }

    @Test
    void anObjectIsNeverItsOwnGround() {
        GroundHeightIndex index = indexOf(List.of(new Item(0, 0, 0, 2.0f, 1.0f)));
        assertEquals(0.0f, index.highestSurfaceBelow(0, 0, 5.0f, 0));
    }

    @Test
    void surfacesAboveThePointDoNotCount() {
        GroundHeightIndex index = indexOf(List.of(new Item(0, 0, 0, 2.0f, 6.0f), new Item(1, 0, 0, 2.0f, 2.0f)));
        assertEquals(2.0f, index.highestSurfaceBelow(9, 0, 3.0f, 0));
    }

    @Test
    void largeFloorsAreFoundFarFromTheirCenter() {
        GroundHeightIndex index = indexOf(List.of(new Item(0, 0, 0, 500.0f, 1.0f)));
        assertEquals(1.0f, index.highestSurfaceBelow(9, 400.0f, 5.0f, 0));
    }

    @Test
    void reusingTheIndexForgetsTheLastFrame() {
        GroundHeightIndex index = indexOf(List.of(new Item(0, 0, 0, 2.0f, 3.0f)));
        index.reset();
        index.add(1, 50.0f, 50.0f, 2.0f, 1.0f);
        index.build();
        assertEquals(0.0f, index.highestSurfaceBelow(9, 0, 5.0f, 0));
        assertEquals(1.0f, index.highestSurfaceBelow(9, 50.0f, 5.0f, 50.0f));
    }

    @Test
    void steadyFramesDoNotAllocate() {
        List<Item> items = randomScene(new Random(7), 2000);
        GroundHeightIndex index = indexOf(items);
        for (int i = 0; i < 20; i++) runFrame(index, items);
        long before = TestEntities.allocatedBytes();
        for (int i = 0; i < 100; i++) runFrame(index, items);
        assertTrue((TestEntities.allocatedBytes() - before) / 100 < 1024);
    }

    private static float runFrame(GroundHeightIndex index, List<Item> items) {
        index.reset();
        for (int i = 0, n = items.size(); i < n; i++) {
            Item item = items.get(i);
            index.add(item.id, item.x, item.z, item.radius, item.top);
        }
        index.build();
        float sum = 0.0f;
        for (int i = 0, n = items.size(); i < n; i++) {
            Item item = items.get(i);
            sum += index.highestSurfaceBelow(item.id, item.x, 20.0f, item.z);
        }
        return sum;
    }

    @Test
    @Tag("perf")
    void fourThousandObjectsFitEasilyInTheFrameBudget() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 4000; i++) items.add(new Item(i, i % 64, i / 64.0f, 0.55f, 1.0f));
        items.add(new Item(4000, 0, 0, 300.0f, 0.1f));
        GroundHeightIndex index = indexOf(items);
        for (int i = 0; i < 200; i++) runFrame(index, items);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            runFrame(index, items);
            best = Math.min(best, (System.nanoTime() - start) / 1e6);
        }
        assertTrue(best < 2.0, best + " ms");
    }
}
