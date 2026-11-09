package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;

/**
 * Allocation-free timer / task scheduler for gameplay scripts.
 *
 * <p>Timers live in a pool of preallocated slots, so scheduling, firing and cancelling never create objects. The pool
 * only grows (doubling) when more timers are pending at once than its capacity; size it in the constructor to avoid
 * that during gameplay.</p>
 *
 * <p>Each schedule call returns an int handle that stays valid until the timer finishes or is cancelled; a stale
 * handle is safely ignored by {@link #cancel(int)} and reported as not scheduled by {@link #isScheduled(int)}.
 * {@link #INVALID_HANDLE} (0) is never a valid handle.</p>
 *
 * <p>Time is game time: {@link #update(float)} is driven by the script's frame delta, so timers freeze while the
 * simulation is paused. {@code SimpleEntityScript} owns one queue and ticks it automatically before onUpdate.</p>
 */
@PublicApi
public final class ScriptTaskQueue {

    public static final int INVALID_HANDLE = 0;

    private static final int SLOT_BITS = 16;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int MAX_SLOTS = 1 << SLOT_BITS;
    private static final int MAX_GENERATION = 0x7FFF;

    // Parallel arrays rather than entry objects: one allocation per array, none per timer
    private ScriptTask[] tasks;
    private float[] remaining;
    private float[] interval;
    private boolean[] repeating;
    private boolean[] active;
    private int[] generation;
    private int[] scheduledOnTick;

    private int highWater = 0;   // Slots [0, highWater) have been used at least once
    private int activeCount = 0;
    private int tickSerial = 0;
    private boolean ticking = false;

    public ScriptTaskQueue() {
        this(16);
    }

    public ScriptTaskQueue(int initialCapacity) {
        int cap = Math.max(1, Math.min(initialCapacity, MAX_SLOTS));
        tasks = new ScriptTask[cap];
        remaining = new float[cap];
        interval = new float[cap];
        repeating = new boolean[cap];
        active = new boolean[cap];
        generation = new int[cap];
        scheduledOnTick = new int[cap];
        for (int i = 0; i < cap; i++) generation[i] = 1;
    }

    /** Runs {@code task} once after {@code delaySeconds} of game time (0 = on the next tick). */
    public int runAfter(float delaySeconds, ScriptTask task) {
        return schedule(task, Math.max(0.0f, delaySeconds), 0.0f, false);
    }

    /** Runs {@code task} every {@code intervalSeconds}, first after one interval. Fires at most once per tick. */
    public int runEvery(float intervalSeconds, ScriptTask task) {
        float iv = Math.max(0.0f, intervalSeconds);
        return schedule(task, iv, iv, true);
    }

    /** Runs {@code task} every {@code intervalSeconds}, the first time after {@code initialDelaySeconds}. */
    public int runEvery(float intervalSeconds, float initialDelaySeconds, ScriptTask task) {
        return schedule(task, Math.max(0.0f, initialDelaySeconds), Math.max(0.0f, intervalSeconds), true);
    }

    /** Stops a pending timer. Returns false when the handle is stale (already fired, cancelled, or invalid). */
    public boolean cancel(int handle) {
        int slot = slotOf(handle);
        if (slot < 0) return false;
        release(slot);
        return true;
    }

    /** True while the timer behind {@code handle} is still pending. */
    public boolean isScheduled(int handle) {
        return slotOf(handle) >= 0;
    }

    /** Seconds until the timer behind {@code handle} next fires, or -1 when it is not scheduled. */
    public float getTimeRemaining(int handle) {
        int slot = slotOf(handle);
        return slot >= 0 ? Math.max(0.0f, remaining[slot]) : -1.0f;
    }

    /** Restarts a pending timer's countdown (its current delay, or its interval for a repeating timer). */
    public boolean restart(int handle, float delaySeconds) {
        int slot = slotOf(handle);
        if (slot < 0) return false;
        remaining[slot] = Math.max(0.0f, delaySeconds);
        return true;
    }

    public void cancelAll() {
        for (int i = 0; i < highWater; i++) {
            if (active[i]) release(i);
        }
    }

    public int getActiveCount() {
        return activeCount;
    }

    /**
     * Advances every timer by {@code deltaTime} and runs the ones that came due. Timers scheduled from inside a task
     * during this tick start counting on the next tick. An exception thrown by a task propagates (the ScriptEngine
     * then disables the script); the throwing one-shot timer is already released.
     */
    public void update(float deltaTime) {
        if (activeCount == 0) return;
        tickSerial++;
        ticking = true;
        try {
            // highWater may grow while tasks schedule more timers; those are skipped by their tick stamp
            for (int i = 0; i < highWater; i++) {
                if (!active[i] || scheduledOnTick[i] == tickSerial) continue;
                remaining[i] -= deltaTime;
                if (remaining[i] > 0.0f) continue;

                ScriptTask task = tasks[i];
                if (repeating[i]) {
                    // Carry the overshoot so the average rate holds, but never queue a backlog of catch-up fires
                    remaining[i] = Math.max(0.0f, remaining[i] + interval[i]);
                } else {
                    release(i);
                }
                if (task != null) task.run();
            }
        } finally {
            ticking = false;
        }
    }

    private int schedule(ScriptTask task, float delay, float iv, boolean repeat) {
        if (task == null) return INVALID_HANDLE;
        int slot = findFreeSlot();
        if (slot < 0) {
            System.err.println("[ScriptTaskQueue] Timer limit (" + MAX_SLOTS + ") reached; task not scheduled.");
            return INVALID_HANDLE;
        }
        tasks[slot] = task;
        remaining[slot] = delay;
        interval[slot] = iv;
        repeating[slot] = repeat;
        active[slot] = true;
        scheduledOnTick[slot] = ticking ? tickSerial : tickSerial - 1;
        activeCount++;
        return (generation[slot] << SLOT_BITS) | slot;
    }

    private int findFreeSlot() {
        for (int i = 0; i < highWater; i++) {
            if (!active[i]) return i;
        }
        if (highWater < tasks.length) return highWater++;
        if (tasks.length >= MAX_SLOTS) return -1;
        grow();
        return highWater++;
    }

    private void grow() {
        int oldCap = tasks.length;
        int cap = Math.min(oldCap * 2, MAX_SLOTS);
        ScriptTask[] t = new ScriptTask[cap];
        float[] r = new float[cap];
        float[] iv = new float[cap];
        boolean[] rep = new boolean[cap];
        boolean[] act = new boolean[cap];
        int[] gen = new int[cap];
        int[] stamp = new int[cap];
        System.arraycopy(tasks, 0, t, 0, oldCap);
        System.arraycopy(remaining, 0, r, 0, oldCap);
        System.arraycopy(interval, 0, iv, 0, oldCap);
        System.arraycopy(repeating, 0, rep, 0, oldCap);
        System.arraycopy(active, 0, act, 0, oldCap);
        System.arraycopy(generation, 0, gen, 0, oldCap);
        System.arraycopy(scheduledOnTick, 0, stamp, 0, oldCap);
        for (int i = oldCap; i < cap; i++) gen[i] = 1;
        tasks = t;
        remaining = r;
        interval = iv;
        repeating = rep;
        active = act;
        generation = gen;
        scheduledOnTick = stamp;
    }

    private void release(int slot) {
        if (!active[slot]) return;
        active[slot] = false;
        tasks[slot] = null;
        generation[slot] = (generation[slot] >= MAX_GENERATION) ? 1 : generation[slot] + 1;
        activeCount--;
    }

    /** Slot of a live handle, or -1 when the handle is invalid or stale. */
    private int slotOf(int handle) {
        if (handle == INVALID_HANDLE) return -1;
        int slot = handle & SLOT_MASK;
        int gen = handle >>> SLOT_BITS;
        if (slot >= highWater || !active[slot] || generation[slot] != gen) return -1;
        return slot;
    }
}
