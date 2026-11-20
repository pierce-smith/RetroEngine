package com.retro.engine.graphics.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The bookkeeping inside one pooled VkDeviceMemory block, and the allocator's sizing rules. No GPU needed. */
class BlockSuballocatorTest {

    @Test
    void allocationsAreAlignedAndNeverOverlap() {
        BlockSuballocator block = new BlockSuballocator(1024);
        long a = block.allocate(10, 1);
        long b = block.allocate(100, 256);
        long c = block.allocate(4, 16);
        assertEquals(0, a);
        assertEquals(256, b);
        assertEquals(16, c); // the padding before b stays free and is reused
        assertEquals(114, block.used());
    }

    @Test
    void freedNeighboursMergeBackIntoOneRange() {
        BlockSuballocator block = new BlockSuballocator(1000);
        long a = block.allocate(100, 1), b = block.allocate(100, 1), c = block.allocate(100, 1);
        block.free(a, 100);
        block.free(c, 100); // merges with the tail
        assertEquals(2, block.freeRangeCount());
        block.free(b, 100); // joins both sides
        assertEquals(1, block.freeRangeCount());
        assertEquals(1000, block.largestFreeRange());
        assertTrue(block.isEmpty());
    }

    @Test
    void aFullBlockSaysSoInsteadOfOverlapping() {
        BlockSuballocator block = new BlockSuballocator(256);
        assertEquals(0, block.allocate(200, 1));
        assertEquals(-1, block.allocate(100, 1));
        assertEquals(-1, block.allocate(56, 64)); // 56 bytes are free, but not at a 64-byte boundary with room
        assertEquals(200, block.allocate(56, 8));
    }

    @Test
    void doubleFreeIsCaught() {
        BlockSuballocator block = new BlockSuballocator(512);
        long a = block.allocate(64, 1);
        block.allocate(64, 1);
        block.free(a, 64);
        assertThrows(IllegalStateException.class, () -> block.free(a, 64));
        assertThrows(IllegalArgumentException.class, () -> block.free(500, 64));
    }

    @Test
    void randomChurnKeepsTheBookkeepingExact() {
        Random random = new Random(42);
        BlockSuballocator block = new BlockSuballocator(1 << 20);
        List<long[]> live = new ArrayList<>();
        for (int step = 0; step < 20_000; step++) {
            if (live.isEmpty() || random.nextInt(3) > 0) {
                long size = 1 + random.nextInt(8192);
                long align = 1L << random.nextInt(9);
                long offset = block.allocate(size, align);
                if (offset < 0) continue;
                assertEquals(0, offset % align);
                for (long[] other : live) {
                    assertFalse(offset < other[0] + other[1] && other[0] < offset + size, "overlap at step " + step);
                }
                live.add(new long[] { offset, size });
            } else {
                long[] victim = live.remove(random.nextInt(live.size()));
                block.free(victim[0], victim[1]);
            }
        }
        for (long[] range : live) block.free(range[0], range[1]);
        assertTrue(block.isEmpty());
        assertEquals(1, block.freeRangeCount()); // everything coalesced back
    }

    @Test
    void blockSizeFitsTheHeap() {
        assertEquals(VulkanMemoryAllocator.MAX_BLOCK_SIZE, VulkanMemoryAllocator.blockSizeForHeap(8L << 30)); // 8 GB card
        assertEquals(32L << 20, VulkanMemoryAllocator.blockSizeForHeap(256L << 20)); // 256 MB BAR heap
        assertEquals(VulkanMemoryAllocator.MIN_BLOCK_SIZE, VulkanMemoryAllocator.blockSizeForHeap(8L << 20));
    }

    @Test
    void bigRequestsAndRenderTargetsGetTheirOwnMemory() {
        long block = 64L << 20;
        assertFalse(VulkanMemoryAllocator.wantsDedicated(VulkanMemoryAllocator.Kind.BUFFER, 1L << 20, block));
        assertFalse(VulkanMemoryAllocator.wantsDedicated(VulkanMemoryAllocator.Kind.IMAGE, 32L << 20, block));
        assertTrue(VulkanMemoryAllocator.wantsDedicated(VulkanMemoryAllocator.Kind.IMAGE, (32L << 20) + 1, block));
        assertTrue(VulkanMemoryAllocator.wantsDedicated(VulkanMemoryAllocator.Kind.DEDICATED, 4096, block));
    }
}
