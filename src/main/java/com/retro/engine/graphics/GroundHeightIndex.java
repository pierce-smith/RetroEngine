package com.retro.engine.graphics;

import java.util.Arrays;

/**
 * Finds the highest surface below a point, for ground shadow blobs. Each frame the visible meshes are added once, then
 * every draw queries only the objects near it, instead of scanning the whole scene per draw.
 *
 * <p>Objects go in a hashed grid over X/Z, or in a short always-checked list if they're too large for that (floors).
 * Hash collisions are harmless because every candidate passes the same distance test. Storage only grows, so a steady
 * frame allocates nothing.
 */
public final class GroundHeightIndex {

    private static final float CELL_SIZE = 8.0f;
    private static final float LARGE_RADIUS = CELL_SIZE * 2.0f;
    /** A surface can sit this far above the query point and still count as under it. */
    private static final float SURFACE_TOLERANCE = 0.05f;

    private int count;
    private int[] ids = new int[256];
    private float[] xs = new float[256], zs = new float[256], radiiSq = new float[256], radii = new float[256], tops = new float[256];

    private int[] large = new int[16];
    private int largeCount;

    private int[] heads = new int[512];
    private int[] nodeItem = new int[1024], nodeNext = new int[1024];
    private int nodeCount;

    public void reset() {
        count = 0;
        largeCount = 0;
        nodeCount = 0;
    }

    /** Adds an object identified by {@code id}: a disc at (x, z) whose top surface is at {@code topY}. */
    public void add(int id, float x, float z, float radius, float topY) {
        if (count == ids.length) {
            int capacity = count * 2;
            ids = Arrays.copyOf(ids, capacity);
            xs = Arrays.copyOf(xs, capacity);
            zs = Arrays.copyOf(zs, capacity);
            radii = Arrays.copyOf(radii, capacity);
            radiiSq = Arrays.copyOf(radiiSq, capacity);
            tops = Arrays.copyOf(tops, capacity);
        }
        ids[count] = id;
        xs[count] = x;
        zs[count] = z;
        radii[count] = radius;
        radiiSq[count] = radius * radius;
        tops[count] = topY;
        count++;
    }

    /** Call once after the last {@link #add}, before any {@link #highestSurfaceBelow}. */
    public void build() {
        int wanted = Integer.highestOneBit(Math.max(count, 64) * 2) * 2;
        if (heads.length < wanted) heads = new int[wanted];
        Arrays.fill(heads, -1);
        for (int i = 0; i < count; i++) {
            if (radii[i] > LARGE_RADIUS) {
                if (largeCount == large.length) large = Arrays.copyOf(large, largeCount * 2);
                large[largeCount++] = i;
                continue;
            }
            int minX = cell(xs[i] - radii[i]), maxX = cell(xs[i] + radii[i]);
            int minZ = cell(zs[i] - radii[i]), maxZ = cell(zs[i] + radii[i]);
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    if (nodeCount == nodeItem.length) {
                        nodeItem = Arrays.copyOf(nodeItem, nodeCount * 2);
                        nodeNext = Arrays.copyOf(nodeNext, nodeCount * 2);
                    }
                    int slot = slotOf(cx, cz);
                    nodeItem[nodeCount] = i;
                    nodeNext[nodeCount] = heads[slot];
                    heads[slot] = nodeCount++;
                }
            }
        }
    }

    /** Height of the highest surface under (x, y, z), ignoring object {@code selfId}. Never below 0, the world floor. */
    public float highestSurfaceBelow(int selfId, float x, float y, float z) {
        float best = 0.0f;
        for (int i = 0; i < largeCount; i++) best = consider(large[i], selfId, x, y, z, best);
        for (int node = heads[slotOf(cell(x), cell(z))]; node != -1; node = nodeNext[node]) {
            best = consider(nodeItem[node], selfId, x, y, z, best);
        }
        return best;
    }

    private float consider(int i, int selfId, float x, float y, float z, float best) {
        if (ids[i] == selfId) return best;
        float dx = x - xs[i], dz = z - zs[i];
        if (dx * dx + dz * dz > radiiSq[i]) return best;
        float top = tops[i];
        return (top < y + SURFACE_TOLERANCE && top > best) ? top : best;
    }

    private static int cell(float v) {
        return (int) Math.floor(v / CELL_SIZE);
    }

    private int slotOf(int cx, int cz) {
        return (cx * 73856093 ^ cz * 19349663) & (heads.length - 1);
    }
}
