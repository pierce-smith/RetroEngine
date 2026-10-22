package com.retro.engine.assets.importers;

/**
 * Immutable container for 28-float stride vertex buffer data supporting skeletal skinning weights and indices:
 * [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS,
 *  BONE_IDX_0, BONE_IDX_1, BONE_IDX_2, BONE_IDX_3, BONE_WT_0, BONE_WT_1, BONE_WT_2, BONE_WT_3].
 */
public class MeshBufferData {
    public static final int FLOATS_PER_VERTEX = 28;

    public final float[] vertices;
    public final int vertexCount;

    public MeshBufferData(float[] vertices, int vertexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
    }

    public float[] vertices() { return vertices; }
    public int vertexCount() { return vertexCount; }
}
