package com.retro.engine.graphics;

/**
 * One frame's camera-facing particle quads, built by {@link SceneRenderPipeline} and handed to
 * {@link IRenderBackend#submitParticles}. Vertices are world space, {@link #FLOATS_PER_VERTEX} floats each:
 * {@code x, y, z, r, g, b, a, u, v}, two triangles (6 vertices) per particle.
 *
 * <p>Colors are premultiplied and drawn with {@code src = ONE, dst = ONE_MINUS_SRC_ALPHA}: alpha-blended particles
 * carry {@code (rgb * a, a)}, additive ones {@code (rgb * a, 0)}, so a single pipeline renders both modes. Shaders
 * multiply the vertex color by the batch texture (or a soft round falloff when it has none).</p>
 *
 * <p>Batches are contiguous vertex ranges sharing one texture, already ordered back to front. Storage only grows.</p>
 */
public final class ParticleDrawList {

    public static final int FLOATS_PER_VERTEX = 9;
    public static final int VERTICES_PER_PARTICLE = 6;
    public static final int MAX_BATCHES = 32;

    public float[] vertices = new float[1024 * VERTICES_PER_PARTICLE * FLOATS_PER_VERTEX];
    public int vertexCount;

    public int batchCount;
    public final int[] batchFirstVertex = new int[MAX_BATCHES];
    public final int[] batchVertexCount = new int[MAX_BATCHES];
    /** Texture handle from {@link IRenderBackend#registerTexture}; 0L = procedural soft particle. */
    public final long[] batchTexture = new long[MAX_BATCHES];

    public void clear() {
        vertexCount = 0;
        batchCount = 0;
    }

    /** Ensures room for {@code particles} more quads; grows (rarely) by doubling. */
    public void ensureCapacity(int particles) {
        int needed = (vertexCount + particles * VERTICES_PER_PARTICLE) * FLOATS_PER_VERTEX;
        if (needed > vertices.length) {
            vertices = java.util.Arrays.copyOf(vertices, Math.max(needed, vertices.length * 2));
        }
    }
}
