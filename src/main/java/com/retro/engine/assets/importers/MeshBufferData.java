package com.retro.engine.assets.importers;

/**
 * Immutable container for interleaved vertex buffer data. Two strides are produced by the importers:
 * 22-float static:  [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS]
 * 30-float skinned: static layout followed by [BONE_IDX_0..3, BONE_WT_0..3].
 * Bone indices are stored as raw int bit patterns (Float.intBitsToFloat) so shaders can reinterpret them as uint.
 * Optional triangle-list indices reference vertices by position; null indices means non-indexed geometry.
 */
public class MeshBufferData {
    public static final int STATIC_FLOATS_PER_VERTEX = 22;
    public static final int SKINNED_FLOATS_PER_VERTEX = 30;
    public static final int BONE_INDEX_OFFSET = 22;
    public static final int BONE_WEIGHT_OFFSET = 26;
    public static final int MAX_BONE_INFLUENCES = 4;

    public final float[] vertices;
    public final int vertexCount;
    public final int[] indices;
    public final int indexCount;

    public MeshBufferData(float[] vertices, int vertexCount) {
        this(vertices, vertexCount, null, 0);
    }

    public MeshBufferData(float[] vertices, int vertexCount, int[] indices, int indexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
        this.indices = indices;
        this.indexCount = indices != null ? Math.min(indexCount, indices.length) : 0;
    }

    public float[] vertices() { return vertices; }
    public int vertexCount() { return vertexCount; }
    public int[] indices() { return indices; }
    public int indexCount() { return indexCount; }
    public boolean isIndexed() { return indices != null && indexCount > 0; }

    /** Exact float stride derived from the buffer length; avoids ambiguous modulo-based guessing. */
    public int stride() { return strideOf(vertices, vertexCount); }

    public static int strideOf(float[] vertices, int vertexCount) {
        if (vertices == null || vertexCount <= 0) return STATIC_FLOATS_PER_VERTEX;
        return vertices.length / vertexCount;
    }
}
