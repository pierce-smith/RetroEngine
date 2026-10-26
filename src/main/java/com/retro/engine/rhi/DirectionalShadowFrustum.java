package com.retro.engine.rhi;

/**
 * Backend-neutral directional shadow map math shared by the Vulkan and Metal backends so both render the
 * identical light-space projection. Accumulates per-entity bounding spheres into a world AABB, then fits a
 * column-major orthographic light view-projection (depth range [0, 1], right-handed view) around it.
 * Instances are reused every frame and never allocate.
 */
public final class DirectionalShadowFrustum {

    public static final int SHADOW_MAP_SIZE = 2048;

    // Slope-scaled receiver bias in normalized light depth; the fitted depth range spans the scene diameter,
    // so this stays roughly a constant number of shadow texels regardless of scene size
    public static final float SHADOW_BIAS = 0.003f;

    private float minX, minY, minZ, maxX, maxY, maxZ;
    private boolean empty = true;

    public void reset() {
        empty = true;
    }

    /** Grows the scene bounds by a sphere at (x, y, z) with the given world-space radius. */
    public void include(float x, float y, float z, float radius) {
        float r = Math.abs(radius);
        if (empty) {
            minX = x - r; minY = y - r; minZ = z - r;
            maxX = x + r; maxY = y + r; maxZ = z + r;
            empty = false;
            return;
        }
        minX = Math.min(minX, x - r); minY = Math.min(minY, y - r); minZ = Math.min(minZ, z - r);
        maxX = Math.max(maxX, x + r); maxY = Math.max(maxY, y + r); maxZ = Math.max(maxZ, z + r);
    }

    /**
     * Writes the light view-projection for a directional light travelling along (dirX, dirY, dirZ) into out[16].
     * With no geometry included, a 10 m sphere at the origin is used so the matrix is always well formed.
     */
    public void build(float dirX, float dirY, float dirZ, float[] out) {
        float cx = 0.0f, cy = 0.0f, cz = 0.0f, radius = 10.0f;
        if (!empty) {
            cx = (minX + maxX) * 0.5f; cy = (minY + maxY) * 0.5f; cz = (minZ + maxZ) * 0.5f;
            float ex = maxX - minX, ey = maxY - minY, ez = maxZ - minZ;
            radius = Math.max(0.5f * (float) Math.sqrt(ex * ex + ey * ey + ez * ez), 1.0f);
        }

        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        float fx, fy, fz;
        if (len < 1e-6f) { fx = 0.0f; fy = -1.0f; fz = 0.0f; }
        else { fx = dirX / len; fy = dirY / len; fz = dirZ / len; }

        // Pick an up vector that is never parallel to the light direction
        float upX = 0.0f, upY = 1.0f, upZ = 0.0f;
        if (Math.abs(fy) > 0.99f) { upY = 0.0f; upZ = 1.0f; }

        // s = normalize(f x up), u = s x f
        float sx = fy * upZ - fz * upY, sy = fz * upX - fx * upZ, sz = fx * upY - fy * upX;
        float sLen = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sLen; sy /= sLen; sz /= sLen;
        float ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;

        // Eye sits one radius behind the bounds centre, so the whole sphere lies in view depth [0, 2r]
        float eyeX = cx - fx * radius, eyeY = cy - fy * radius, eyeZ = cz - fz * radius;
        float tx = -(sx * eyeX + sy * eyeY + sz * eyeZ);
        float ty = -(ux * eyeX + uy * eyeY + uz * eyeZ);
        float tz = (fx * eyeX + fy * eyeY + fz * eyeZ);

        // Orthographic: x,y in [-r, r] -> [-1, 1]; view z in [0, -2r] -> depth [0, 1]
        float invR = 1.0f / radius;
        float invDepth = 1.0f / (2.0f * radius);

        // Column-major P * V with P = diag(invR, invR, -invDepth) (no translation since near = 0)
        out[0] = sx * invR;  out[1] = ux * invR;  out[2] = fx * invDepth;  out[3] = 0.0f;
        out[4] = sy * invR;  out[5] = uy * invR;  out[6] = fy * invDepth;  out[7] = 0.0f;
        out[8] = sz * invR;  out[9] = uz * invR;  out[10] = fz * invDepth; out[11] = 0.0f;
        out[12] = tx * invR; out[13] = ty * invR; out[14] = -tz * invDepth; out[15] = 1.0f;
    }

    /** Largest distance of any vertex position (first three floats of each vertex) from the mesh origin. */
    public static float computeLocalBoundsRadius(float[] vertices, int floatStride, int vertexCount) {
        if (vertices == null || floatStride < 3) return 1.0f;
        float maxSq = 0.0f;
        int count = Math.min(vertexCount, vertices.length / floatStride);
        for (int v = 0; v < count; v++) {
            int i = v * floatStride;
            float x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
            maxSq = Math.max(maxSq, x * x + y * y + z * z);
        }
        return maxSq > 0.0f ? (float) Math.sqrt(maxSq) : 1.0f;
    }
}
