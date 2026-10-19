package com.retro.engine.assets.importers;

/**
 * Immutable container for 14-float stride vertex buffer data
 * [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2].
 */
public class MeshBufferData {
    public final float[] vertices;
    public final int vertexCount;

    public MeshBufferData(float[] vertices, int vertexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
    }

    public float[] vertices() { return vertices; }
    public int vertexCount() { return vertexCount; }
}
