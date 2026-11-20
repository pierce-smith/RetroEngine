package com.retro.engine.graphics.vulkan;

import java.util.Map;
import java.util.TreeMap;

/**
 * Free-range bookkeeping for one {@code VkDeviceMemory} block: first fit with alignment, and neighbours merge on free
 * so the block does not fragment into slivers. Pure Java so it can be tested without a GPU. Not thread-safe; the
 * owning {@link VulkanMemoryAllocator} serializes access.
 */
final class BlockSuballocator {

    private final long capacity;
    // offset -> size of each free range, sorted by offset so neighbours are one lookup away
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private long used;

    BlockSuballocator(long capacity) {
        if (capacity <= 0L) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.capacity = capacity;
        free.put(0L, capacity);
    }

    /**
     * Reserves {@code size} bytes at an offset that is a multiple of {@code alignment} (a power of two, as Vulkan
     * guarantees). Returns the offset, or -1 when no free range fits. Alignment padding stays free.
     */
    long allocate(long size, long alignment) {
        if (size <= 0L) throw new IllegalArgumentException("size must be positive: " + size);
        long align = Math.max(1L, alignment);
        for (Map.Entry<Long, Long> range : free.entrySet()) {
            long start = range.getKey();
            long end = start + range.getValue();
            long offset = (start + align - 1) / align * align;
            if (offset + size > end) continue;

            free.remove(start);
            if (offset > start) free.put(start, offset - start);
            if (offset + size < end) free.put(offset + size, end - (offset + size));
            used += size;
            return offset;
        }
        return -1L;
    }

    /** Returns a range handed out by {@link #allocate}; merges it with free neighbours on both sides. */
    void free(long offset, long size) {
        if (offset < 0L || size <= 0L || offset + size > capacity) {
            throw new IllegalArgumentException("range outside the block: " + offset + "+" + size + " of " + capacity);
        }
        long start = offset, end = offset + size;

        Map.Entry<Long, Long> before = free.floorEntry(offset);
        if (before != null) {
            long beforeEnd = before.getKey() + before.getValue();
            if (beforeEnd > offset) throw new IllegalStateException("double free at offset " + offset);
            if (beforeEnd == offset) {
                start = before.getKey();
                free.remove(before.getKey());
            }
        }
        Map.Entry<Long, Long> after = free.ceilingEntry(offset);
        if (after != null) {
            if (after.getKey() < end) throw new IllegalStateException("double free at offset " + offset);
            if (after.getKey() == end) {
                end = after.getKey() + after.getValue();
                free.remove(after.getKey());
            }
        }
        free.put(start, end - start);
        used -= size;
    }

    long capacity() { return capacity; }
    long used() { return used; }
    boolean isEmpty() { return used == 0L; }
    int freeRangeCount() { return free.size(); }

    long largestFreeRange() {
        long largest = 0L;
        for (long size : free.values()) largest = Math.max(largest, size);
        return largest;
    }
}
