package com.retro.engine.content.mesh;

import java.util.ArrayList;
import java.util.List;

public class ModelData {
    public float[] vertices; // Stride: pos(3), normal(3), uv(2), boneIndices(4), boneWeights(4) = 16 floats
    public int[] indices;
    public boolean hasAnimation;
    public String name;

    public ModelData(String name, float[] vertices, int[] indices, boolean hasAnimation) {
        this.name = name;
        this.vertices = vertices;
        this.indices = indices;
        this.hasAnimation = hasAnimation;
    }
}