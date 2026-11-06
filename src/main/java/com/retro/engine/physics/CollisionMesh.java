package com.retro.engine.physics;

import com.retro.engine.assets.MeshBufferData;

import java.util.Arrays;

/** Triangles of a static collider in the body's scaled local space, with a BVH over them. Immutable once built. */
final class CollisionMesh {

    private static final int LEAF_TRIANGLES = 4;
    private static final float MIN_RAY_COSINE = 0.1f;

    /** Growable list of triangle indices, reused between queries. */
    static final class Hits {
        int[] items = new int[64];
        int count;

        void add(int value) {
            if (count == items.length) items = Arrays.copyOf(items, count * 2);
            items[count++] = value;
        }
    }

    final float[] triangles;  // Nine floats each
    final float[] normals;    // Three floats each
    final int triangleCount;
    final float minX, minY, minZ, maxX, maxY, maxZ;

    private final int[] order;
    private float[] nodeBounds = new float[64 * 6];
    private int[] nodeLeft = new int[64];    // Left child index, or -1 for a leaf
    private int[] nodeRight = new int[64];   // Right child index, or the first slot in order for a leaf
    private int[] nodeCount = new int[64];   // Triangles in a leaf
    private int nodes;
    private final int[] stack = new int[64];

    float hitNormalX, hitNormalY, hitNormalZ;

    private CollisionMesh(float[] triangles, float[] normals, int triangleCount) {
        this.triangles = triangles;
        this.normals = normals;
        this.triangleCount = triangleCount;
        this.order = new int[triangleCount];
        Integer[] sorted = new Integer[triangleCount];
        for (int i = 0; i < triangleCount; i++) sorted[i] = i;
        build(sorted, 0, triangleCount);
        for (int i = 0; i < triangleCount; i++) order[i] = sorted[i];
        minX = nodeBounds[0]; minY = nodeBounds[1]; minZ = nodeBounds[2];
        maxX = nodeBounds[3]; maxY = nodeBounds[4]; maxZ = nodeBounds[5];
    }

    /** Builds a collider from a render mesh, or returns null when it has no usable triangles. */
    static CollisionMesh fromMesh(MeshBufferData data, float scaleX, float scaleY, float scaleZ) {
        if (data == null || data.vertices == null || data.vertexCount < 3) return null;
        int stride = data.vertices.length / data.vertexCount;
        boolean indexed = data.indices != null && data.indexCount >= 3;
        int count = (indexed ? data.indexCount : data.vertexCount) / 3;
        float[] tris = new float[count * 9];
        float[] normals = new float[count * 3];
        int kept = 0;
        for (int t = 0; t < count; t++) {
            int base = kept * 9;
            for (int c = 0; c < 3; c++) {
                int index = indexed ? data.indices[t * 3 + c] : t * 3 + c;
                tris[base + c * 3] = data.vertices[index * stride] * scaleX;
                tris[base + c * 3 + 1] = data.vertices[index * stride + 1] * scaleY;
                tris[base + c * 3 + 2] = data.vertices[index * stride + 2] * scaleZ;
            }
            float ux = tris[base + 3] - tris[base], uy = tris[base + 4] - tris[base + 1], uz = tris[base + 5] - tris[base + 2];
            float vx = tris[base + 6] - tris[base], vy = tris[base + 7] - tris[base + 1], vz = tris[base + 8] - tris[base + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-10f) continue; // Zero-area triangle
            normals[kept * 3] = nx / len; normals[kept * 3 + 1] = ny / len; normals[kept * 3 + 2] = nz / len;
            kept++;
        }
        return kept == 0 ? null : new CollisionMesh(tris, normals, kept);
    }

    private int build(Integer[] sorted, int start, int end) {
        int node = nodes++;
        if (node == nodeLeft.length) {
            nodeLeft = Arrays.copyOf(nodeLeft, node * 2);
            nodeRight = Arrays.copyOf(nodeRight, node * 2);
            nodeCount = Arrays.copyOf(nodeCount, node * 2);
            nodeBounds = Arrays.copyOf(nodeBounds, node * 12);
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = start; i < end; i++) {
            int base = sorted[i] * 9;
            for (int k = 0; k < 9; k++) {
                b[k % 3] = Math.min(b[k % 3], triangles[base + k]);
                b[3 + k % 3] = Math.max(b[3 + k % 3], triangles[base + k]);
            }
        }
        System.arraycopy(b, 0, nodeBounds, node * 6, 6);
        if (end - start <= LEAF_TRIANGLES) {
            nodeLeft[node] = -1;
            nodeRight[node] = start;
            nodeCount[node] = end - start;
            return node;
        }
        int axis = 0;
        if (b[4] - b[1] > b[3 + axis] - b[axis]) axis = 1;
        if (b[5] - b[2] > b[3 + axis] - b[axis]) axis = 2;
        final int sortAxis = axis;
        Arrays.sort(sorted, start, end, (x, y) -> Float.compare(centroid(x, sortAxis), centroid(y, sortAxis)));
        int mid = (start + end) >>> 1;
        int left = build(sorted, start, mid);
        int right = build(sorted, mid, end);
        nodeLeft[node] = left;
        nodeRight[node] = right;
        return node;
    }

    private float centroid(int triangle, int axis) {
        int base = triangle * 9 + axis;
        return triangles[base] + triangles[base + 3] + triangles[base + 6];
    }

    /** Collects every triangle whose node bounds touch the box. */
    void query(float loX, float loY, float loZ, float hiX, float hiY, float hiZ, Hits out) {
        out.count = 0;
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            int b = node * 6;
            if (nodeBounds[b] > hiX || nodeBounds[b + 3] < loX || nodeBounds[b + 1] > hiY || nodeBounds[b + 4] < loY
                    || nodeBounds[b + 2] > hiZ || nodeBounds[b + 5] < loZ) continue;
            if (nodeLeft[node] >= 0) {
                stack[top++] = nodeLeft[node];
                stack[top++] = nodeRight[node];
            } else {
                for (int i = 0, first = nodeRight[node]; i < nodeCount[node]; i++) out.add(order[first + i]);
            }
        }
    }

    /**
     * Distance along a local unit ray to the nearest triangle, from either side, or -1. {@code inflate} stops that far
     * short of the surface, which is close enough for a sweep. The normal faces the ray.
     */
    float raycast(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance, float inflate) {
        float best = maxDistance + inflate;
        boolean found = false;
        float ix = 1.0f / (dx != 0 ? dx : 1e-8f), iy = 1.0f / (dy != 0 ? dy : 1e-8f), iz = 1.0f / (dz != 0 ? dz : 1e-8f);
        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            int b = node * 6;
            float t1 = (nodeBounds[b] - ox) * ix, t2 = (nodeBounds[b + 3] - ox) * ix;
            float t3 = (nodeBounds[b + 1] - oy) * iy, t4 = (nodeBounds[b + 4] - oy) * iy;
            float t5 = (nodeBounds[b + 2] - oz) * iz, t6 = (nodeBounds[b + 5] - oz) * iz;
            float tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
            float tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));
            if (tmax < Math.max(tmin, 0.0f) || tmin > best) continue;
            if (nodeLeft[node] >= 0) {
                stack[top++] = nodeLeft[node];
                stack[top++] = nodeRight[node];
                continue;
            }
            for (int i = 0, first = nodeRight[node]; i < nodeCount[node]; i++) {
                int tri = order[first + i];
                float t = rayTriangle(tri, ox, oy, oz, dx, dy, dz);
                if (t >= 0.0f && t < best) {
                    best = t;
                    found = true;
                    float nx = normals[tri * 3], ny = normals[tri * 3 + 1], nz = normals[tri * 3 + 2];
                    float facing = nx * dx + ny * dy + nz * dz;
                    float flip = facing > 0.0f ? -1.0f : 1.0f;
                    hitNormalX = nx * flip; hitNormalY = ny * flip; hitNormalZ = nz * flip;
                }
            }
        }
        if (!found) return -1.0f;
        if (inflate <= 0.0f) return best;
        float cosine = Math.max(MIN_RAY_COSINE, Math.abs(hitNormalX * dx + hitNormalY * dy + hitNormalZ * dz));
        return Math.max(0.0f, best - inflate / cosine);
    }

    /** Moller-Trumbore, from either side. */
    private float rayTriangle(int tri, float ox, float oy, float oz, float dx, float dy, float dz) {
        int b = tri * 9;
        float ax = triangles[b], ay = triangles[b + 1], az = triangles[b + 2];
        float e1x = triangles[b + 3] - ax, e1y = triangles[b + 4] - ay, e1z = triangles[b + 5] - az;
        float e2x = triangles[b + 6] - ax, e2y = triangles[b + 7] - ay, e2z = triangles[b + 8] - az;
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12f) return -1.0f;
        float inv = 1.0f / det;
        float tx = ox - ax, ty = oy - ay, tz = oz - az;
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < 0.0f || u > 1.0f) return -1.0f;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0.0f || u + v > 1.0f) return -1.0f;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }
}
