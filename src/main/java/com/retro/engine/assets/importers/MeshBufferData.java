package com.retro.engine.assets.importers;

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
