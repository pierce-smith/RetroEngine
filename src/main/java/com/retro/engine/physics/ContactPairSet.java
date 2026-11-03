package com.retro.engine.physics;

import com.retro.engine.ecs.Entity;

import java.util.Arrays;

/**
 * Set of unordered entity pairs (A,B == B,A) for collision and trigger bookkeeping. Pairs iterate in insertion
 * order and adding never allocates once the arrays have grown to the scene's peak contact count, unlike a
 * {@code HashSet} of key objects, which allocated a key and a map node per contact per physics step.
 * Entities are compared by identity.
 */
final class ContactPairSet {

    private Entity[] firsts = new Entity[64];
    private Entity[] seconds = new Entity[64];
    private int size;
    // Open-addressing slots holding (pair index + 1); 0 marks an empty slot. Kept at most half full.
    private int[] slots = new int[128];

    int size() { return size; }

    Entity first(int i) { return firsts[i]; }

    Entity second(int i) { return seconds[i]; }

    void add(Entity a, Entity b) {
        if (a == null || b == null) return;
        int mask = slots.length - 1;
        for (int s = hash(a, b) & mask; ; s = (s + 1) & mask) {
            int stored = slots[s];
            if (stored == 0) {
                if (size == firsts.length) {
                    firsts = Arrays.copyOf(firsts, size * 2);
                    seconds = Arrays.copyOf(seconds, size * 2);
                }
                firsts[size] = a;
                seconds[size] = b;
                slots[s] = ++size;
                if (size * 2 > slots.length) rehash(slots.length * 2);
                return;
            }
            if (matches(stored - 1, a, b)) return;
        }
    }

    boolean contains(Entity a, Entity b) {
        if (a == null || b == null) return false;
        int mask = slots.length - 1;
        for (int s = hash(a, b) & mask; ; s = (s + 1) & mask) {
            int stored = slots[s];
            if (stored == 0) return false;
            if (matches(stored - 1, a, b)) return true;
        }
    }

    void clear() {
        if (size == 0) return;
        Arrays.fill(slots, 0);
        Arrays.fill(firsts, 0, size, null);
        Arrays.fill(seconds, 0, size, null);
        size = 0;
    }

    private boolean matches(int i, Entity a, Entity b) {
        Entity f = firsts[i], s = seconds[i];
        return (f == a && s == b) || (f == b && s == a);
    }

    private void rehash(int capacity) {
        slots = new int[capacity];
        int mask = capacity - 1;
        for (int i = 0; i < size; i++) {
            int s = hash(firsts[i], seconds[i]) & mask;
            while (slots[s] != 0) s = (s + 1) & mask;
            slots[s] = i + 1;
        }
    }

    // Symmetric in a and b, so both orders land in the same probe sequence
    private static int hash(Entity a, Entity b) {
        int h = (System.identityHashCode(a) ^ System.identityHashCode(b)) * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
}
