package com.retro.engine.content.mesh;

public class ModelData {
    public float[] vertices;
    public int vertexCount;

    public ModelData(float[] vertices, int vertexCount) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
    }

    public float[] vertices() {
        return vertices;
    }

    public int vertexCount() {
        return vertexCount;
    }
}
