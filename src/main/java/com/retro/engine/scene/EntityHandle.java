package com.retro.engine.scene;

/**
 * 64-bit Generational Entity Handle (32-bit Index | 32-bit Generation).
 * Provides lifecycle-safe entity referencing without dangling pointers across
 * entity deletion, scene clearing, and play state simulation restoration.
 */
public record EntityHandle(int index, int generation) {
    public static final EntityHandle NULL = new EntityHandle(-1, 0);

    public long toLong() {
        return (((long) generation) << 32) | (index & 0xFFFFFFFFL);
    }

    public static EntityHandle fromLong(long rawHandle) {
        int gen = (int) (rawHandle >>> 32);
        int idx = (int) (rawHandle & 0xFFFFFFFFL);
        return new EntityHandle(idx, gen);
    }

    public boolean isValid() {
        return index >= 0 && generation > 0;
    }

    @Override
    public String toString() {
        return "EntityHandle[" + index + ":" + generation + "]";
    }
}
