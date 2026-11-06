package com.retro.engine.physics;

import java.util.Arrays;

/** Open-addressing map from a body pair to its manifold's index in last step's array. Allocates only when it grows. */
final class ContactCache {

    private long[] keys = new long[256];
    private int[] values = new int[256];

    /** Never zero, because zero marks an empty slot. Body ids start at 1. */
    static long key(int idA, int idB) {
        return ((long) idA << 32) | (idB & 0xFFFFFFFFL);
    }

    private static int hash(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    void clear() {
        Arrays.fill(keys, 0L);
    }

    void rebuild(ContactManifold[] manifolds, int count) {
        if (count * 2 > keys.length) {
            int size = keys.length;
            while (count * 2 > size) size *= 2;
            keys = new long[size];
            values = new int[size];
        } else {
            clear();
        }
        int mask = keys.length - 1;
        for (int i = 0; i < count; i++) {
            long key = manifolds[i].key;
            int slot = hash(key, mask);
            while (keys[slot] != 0L) slot = (slot + 1) & mask;
            keys[slot] = key;
            values[slot] = i;
        }
    }

    /** Index of the manifold for this pair, or -1. */
    int find(long key) {
        int mask = keys.length - 1;
        int slot = hash(key, mask);
        while (keys[slot] != 0L) {
            if (keys[slot] == key) return values[slot];
            slot = (slot + 1) & mask;
        }
        return -1;
    }
}
