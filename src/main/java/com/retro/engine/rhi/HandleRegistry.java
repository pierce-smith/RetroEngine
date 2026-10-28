package com.retro.engine.rhi;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Thread-safe opaque 64-bit handle table shared by the render backends for registered GPU assets.
 * A handle packs a slot index (low {@value #SLOT_BITS} bits) with a monotonically increasing sequence
 * (high bits), so a recycled slot never resurrects a stale handle. Lookups are lock-free and allocation-free
 * (no Long boxing), which keeps per-frame resolution off the GC; registration and removal take a lock and
 * may be called from any thread, including AssetStreamer virtual threads.
 */
public final class HandleRegistry<T> {

    public static final long NULL_HANDLE = 0L;

    private static final int SLOT_BITS = 20;
    private static final int MAX_SLOTS = 1 << SLOT_BITS;
    private static final long SLOT_MASK = MAX_SLOTS - 1L;

    private record Entry<T>(long handle, String key, T resource) {}

    private final AtomicLong handleSequence = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile AtomicReferenceArray<Entry<T>> slots = new AtomicReferenceArray<>(64);
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final Map<String, Long> keyToHandle = new HashMap<>();
    private int highWaterSlot = 0;

    /**
     * Returns the handle already registered under {@code assetKey}, or invokes {@code factory} and registers its
     * result. The factory runs under the registry lock so concurrent callers never upload the same asset twice.
     * A null key always creates a fresh, unshared entry. Returns {@link #NULL_HANDLE} if the factory yields null.
     */
    public long register(String assetKey, Supplier<T> factory) {
        lock.lock();
        try {
            if (assetKey != null) {
                Long existing = keyToHandle.get(assetKey);
                if (existing != null) return existing;
            }

            // Reserve the slot first: a resource built for a full table would be dropped without being released
            int slot = acquireSlot();
            if (slot < 0) return NULL_HANDLE;

            T resource;
            try {
                resource = factory.get();
            } catch (RuntimeException e) {
                freeSlots.push(slot);
                throw e;
            }
            if (resource == null) {
                freeSlots.push(slot);
                return NULL_HANDLE;
            }

            long handle = (handleSequence.incrementAndGet() << SLOT_BITS) | slot;
            slots.set(slot, new Entry<>(handle, assetKey, resource));
            if (assetKey != null) keyToHandle.put(assetKey, handle);
            return handle;
        } finally {
            lock.unlock();
        }
    }

    /** Resolves a handle to its resource, or null if the handle is NULL_HANDLE, stale, or unregistered. */
    public T get(long handle) {
        if (handle == NULL_HANDLE) return null;
        int slot = (int) (handle & SLOT_MASK);
        AtomicReferenceArray<Entry<T>> table = slots;
        if (slot >= table.length()) return null;
        Entry<T> entry = table.get(slot);
        return (entry != null && entry.handle == handle) ? entry.resource : null;
    }

    /** Unregisters a handle and returns its resource so the caller can schedule GPU disposal; null if not live. */
    public T remove(long handle) {
        if (handle == NULL_HANDLE) return null;
        lock.lock();
        try {
            int slot = (int) (handle & SLOT_MASK);
            AtomicReferenceArray<Entry<T>> table = slots;
            if (slot >= table.length()) return null;
            Entry<T> entry = table.get(slot);
            if (entry == null || entry.handle != handle) return null;

            table.set(slot, null);
            freeSlots.push(slot);
            if (entry.key != null) keyToHandle.remove(entry.key, handle);
            return entry.resource;
        } finally {
            lock.unlock();
        }
    }

    /** Removes every entry, handing each resource to {@code disposer}. Used at backend shutdown. */
    public void clear(Consumer<T> disposer) {
        lock.lock();
        try {
            AtomicReferenceArray<Entry<T>> table = slots;
            for (int i = 0; i < highWaterSlot; i++) {
                Entry<T> entry = table.getAndSet(i, null);
                if (entry != null && disposer != null) disposer.accept(entry.resource);
            }
            freeSlots.clear();
            keyToHandle.clear();
            highWaterSlot = 0;
        } finally {
            lock.unlock();
        }
    }

    private int acquireSlot() {
        if (!freeSlots.isEmpty()) return freeSlots.pop();
        if (highWaterSlot >= MAX_SLOTS) return -1;

        AtomicReferenceArray<Entry<T>> table = slots;
        if (highWaterSlot >= table.length()) {
            AtomicReferenceArray<Entry<T>> grown = new AtomicReferenceArray<>(Math.min(MAX_SLOTS, table.length() * 2));
            for (int i = 0; i < table.length(); i++) grown.set(i, table.get(i));
            slots = grown;
        }
        return highWaterSlot++;
    }
}
