package com.retro.engine.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.assets.MeshBufferData;
import com.retro.engine.ecs.HierarchySystem;
import com.retro.engine.ecs.SceneWorld.WorldTransform;

import org.junit.jupiter.api.Test;

class DecalBuilderTest {

    private static final int STRIDE = MeshBufferData.STATIC_FLOATS_PER_VERTEX;
    private static final int SKINNED = MeshBufferData.SKINNED_FLOATS_PER_VERTEX;
    private static final WorldTransform IDENTITY = new WorldTransform(0, 0, 0, 0, 0, 0, 1, 1, 1);

    private final DecalBuilder builder = new DecalBuilder();

    private static void vertex(float[] out, int index, int stride, float x, float y, float z, float nx, float ny, float nz) {
        int at = index * stride;
        out[at] = x; out[at + 1] = y; out[at + 2] = z;
        out[at + 3] = nx; out[at + 4] = ny; out[at + 5] = nz;
    }

    /** A 10x10 floor at y = 0 centered on the origin, as two triangles facing up. */
    private static MeshBufferData floor() {
        float[] v = new float[6 * STRIDE];
        float[][] corners = {{-5, -5}, {5, -5}, {5, 5}, {-5, -5}, {5, 5}, {-5, 5}};
        for (int i = 0; i < 6; i++) vertex(v, i, STRIDE, corners[i][0], 0, corners[i][1], 0, 1, 0);
        return new MeshBufferData(v, 6);
    }

    private static DecalSpec spec(float size) {
        DecalSpec spec = new DecalSpec(null, size);
        spec.setPosition(0, 0, 0, 0, 1, 0);
        return spec;
    }

    private static float area(MeshBufferData mesh, int stride) {
        double total = 0;
        float[] v = mesh.vertices;
        for (int t = 0; t + 2 < mesh.vertexCount; t += 3) {
            int a = t * stride, b = (t + 1) * stride, c = (t + 2) * stride;
            double e1x = v[b] - v[a], e1y = v[b + 1] - v[a + 1], e1z = v[b + 2] - v[a + 2];
            double e2x = v[c] - v[a], e2y = v[c + 1] - v[a + 1], e2z = v[c + 2] - v[a + 2];
            double x = e1y * e2z - e1z * e2y, y = e1z * e2x - e1x * e2z, z = e1x * e2y - e1y * e2x;
            total += Math.sqrt(x * x + y * y + z * z) * 0.5;
        }
        return (float) total;
    }

    @Test
    void clipsAFloorToExactlyTheDecalFootprint() {
        builder.setFrame(spec(2.0f));
        MeshBufferData piece = builder.build(floor(), null, IDENTITY);
        assertNotNull(piece);
        assertEquals(4.0f, area(piece, STRIDE), 1e-4f);
        for (int i = 0; i < piece.vertexCount; i++) {
            int at = i * STRIDE;
            assertTrue(Math.abs(piece.vertices[at]) <= 1.0001f && Math.abs(piece.vertices[at + 2]) <= 1.0001f);
            assertEquals(0.0f, piece.vertices[at + 1], 1e-6f);
            assertTrue(piece.vertices[at + 10] >= -1e-5f && piece.vertices[at + 10] <= 1.00001f);
            assertTrue(piece.vertices[at + 11] >= -1e-5f && piece.vertices[at + 11] <= 1.00001f);
        }
    }

    @Test
    void uvsSpanTheBoxAndSurfaceAttributesInterpolate() {
        float[] v = floor().vertices;
        for (int i = 0; i < 6; i++) {
            v[i * STRIDE + 12] = (v[i * STRIDE] + 5.0f) / 10.0f; // lightmap u runs across the floor
        }
        DecalSpec spec = spec(2.0f);
        spec.posX = 2.0f;
        builder.setFrame(spec);
        MeshBufferData piece = builder.build(new MeshBufferData(v, 6), null, IDENTITY);
        for (int i = 0; i < piece.vertexCount; i++) {
            int at = i * STRIDE;
            float x = piece.vertices[at];
            assertEquals((x + 5.0f) / 10.0f, piece.vertices[at + 12], 1e-5f, "lightmap UV follows the surface");
            assertEquals(Math.abs((x - 2.0f) / 2.0f + 0.5f - piece.vertices[at + 10]) < 1e-5f
                    || Math.abs((2.0f - x) / 2.0f + 0.5f - piece.vertices[at + 10]) < 1e-5f, true, "decal U runs across the box");
        }
    }

    @Test
    void tintIsBakedAndFacingSurfacesKeepFullAlpha() {
        DecalSpec spec = spec(1.0f).setColor(0.5f, 0.0f, 0.25f);
        builder.setFrame(spec);
        MeshBufferData piece = builder.build(floor(), null, IDENTITY);
        assertEquals(0.5f, piece.vertices[6], 1e-6f);
        assertTrue(piece.vertices[7] > 0.0f, "a zero channel stays above the shaders' black-means-untinted test");
        assertEquals(0.25f, piece.vertices[8], 1e-6f);
        assertEquals(1.0f, piece.vertices[9], 1e-5f);
    }

    @Test
    void missesWhenTheBoxDoesNotReachTheSurface() {
        DecalSpec spec = spec(1.0f);
        spec.posY = 5.0f;
        builder.setFrame(spec);
        assertNull(builder.build(floor(), null, IDENTITY));
    }

    @Test
    void surfacesFacingAwayGetNothingSoDecalsDontPrintThroughWalls() {
        DecalSpec spec = spec(1.0f);
        spec.normalY = -1.0f;
        builder.setFrame(spec);
        assertNull(builder.build(floor(), null, IDENTITY));
    }

    @Test
    void steepSurfacesPastTheMaxAngleGetNothing() {
        float[] v = new float[3 * STRIDE];
        vertex(v, 0, STRIDE, 0, -1, -1, 1, 0, 0);
        vertex(v, 1, STRIDE, 0, 1, -1, 1, 0, 0);
        vertex(v, 2, STRIDE, 0, 0, 1, 1, 0, 0);
        builder.setFrame(spec(1.0f)); // pointing up, at a wall
        assertNull(builder.build(new MeshBufferData(v, 3), null, IDENTITY));
    }

    @Test
    void reversedWindingStillCountsAsFacing() {
        float[] v = floor().vertices;
        float[] flipped = new float[v.length];
        for (int i = 0; i < 6; i++) System.arraycopy(v, (5 - i) * STRIDE, flipped, i * STRIDE, STRIDE);
        builder.setFrame(spec(1.0f));
        assertNotNull(builder.build(new MeshBufferData(flipped, 6), null, IDENTITY));
    }

    @Test
    void indexedMeshesClipLikeUnindexedOnes() {
        float[] v = new float[4 * STRIDE];
        vertex(v, 0, STRIDE, -5, 0, -5, 0, 1, 0);
        vertex(v, 1, STRIDE, 5, 0, -5, 0, 1, 0);
        vertex(v, 2, STRIDE, 5, 0, 5, 0, 1, 0);
        vertex(v, 3, STRIDE, -5, 0, 5, 0, 1, 0);
        int[] indices = {0, 1, 2, 0, 2, 3};
        builder.setFrame(spec(2.0f));
        assertEquals(4.0f, area(builder.build(new MeshBufferData(v, 4, indices, 6), null, IDENTITY), STRIDE), 1e-4f);
    }

    @Test
    void geometryIsWrittenInTargetSpaceSoItFollowsTheTarget() {
        // Floor rotated onto a wall, scaled and moved: the decal lands at world (0, 3, -4)
        WorldTransform wall = new WorldTransform(0, 3, -4, 90, 0, 0, 2, 1, 3);
        double[] r = new double[9];
        HierarchySystem.rotationMatrix(90, 0, 0, r);
        DecalSpec spec = new DecalSpec(null, 1.0f);
        spec.setPosition(0, 3, -4, (float) r[1], (float) r[4], (float) r[7]);
        builder.setFrame(spec);
        MeshBufferData piece = builder.build(floor(), null, wall);
        assertNotNull(piece);
        assertEquals(1.0f, area(piece, STRIDE) * 2.0f * 3.0f, 1e-3f, "local area times the wall's scale is the footprint");
        float[] center = new float[3];
        builder.lastBounds(center);
        assertEquals(0.0f, center[0], 1e-4f);
        assertEquals(0.0f, center[2], 1e-4f);
    }

    @Test
    void tangentMapsBackToTheImageRightEdgeThroughTheModelMatrix() {
        WorldTransform scaled = new WorldTransform(0, 0, 0, 0, 90, 0, 2, 1, 2);
        double[] r = new double[9];
        HierarchySystem.rotationMatrix(0, 90, 0, r);
        DecalSpec spec = spec(1.0f);
        spec.tangentX = 1.0f;
        builder.setFrame(spec);
        MeshBufferData piece = builder.build(floor(), null, scaled);
        float tx = piece.vertices[14] * 2.0f, ty = piece.vertices[15], tz = piece.vertices[16] * 2.0f;
        assertEquals(1.0, r[0] * tx + r[1] * ty + r[2] * tz, 1e-5);
        assertEquals(0.0, r[6] * tx + r[7] * ty + r[8] * tz, 1e-5);
    }

    /** One upward-facing triangle per bone, both resting at y = 0 at different x, weighted fully to bone 1. */
    private static MeshBufferData skinnedTriangle() {
        float[] v = new float[3 * SKINNED];
        vertex(v, 0, SKINNED, -1, 0, -1, 0, 1, 0);
        vertex(v, 1, SKINNED, 1, 0, -1, 0, 1, 0);
        vertex(v, 2, SKINNED, 0, 0, 1, 0, 1, 0);
        for (int i = 0; i < 3; i++) {
            v[i * SKINNED + 22] = Float.intBitsToFloat(1);
            v[i * SKINNED + 26] = 1.0f;
        }
        return new MeshBufferData(v, 3);
    }

    @Test
    void skinnedMeshesAreTestedInTheCurrentPoseButWrittenInBindPose() {
        float[][] palette = new float[2][16];
        for (float[] m : palette) { m[0] = m[5] = m[10] = m[15] = 1.0f; }
        palette[1][12] = 10.0f; // the limb has swung 10 units along +x

        DecalSpec spec = spec(1.0f);
        builder.setFrame(spec);
        assertNull(builder.build(skinnedTriangle(), palette, IDENTITY), "the bind-pose spot is empty now");

        spec.posX = 10.0f;
        builder.setFrame(spec);
        MeshBufferData piece = builder.build(skinnedTriangle(), palette, IDENTITY);
        assertNotNull(piece);
        assertEquals(3, piece.vertexCount, "whole triangles, so skinning can't pull a clipped vertex off the limb");
        assertEquals(SKINNED, piece.stride());
        assertEquals(-1.0f, piece.vertices[0], 1e-6f, "bind pose position");
        assertEquals(1, Float.floatToRawIntBits(piece.vertices[22]), "bone index keeps its bit pattern");
        assertEquals(1.0f, piece.vertices[26], 1e-6f);
        assertTrue(piece.vertices[10] < 0.0f, "UVs run past the box; the shader discards outside 0..1");
    }

    @Test
    void rejectsDegenerateSpecs() {
        DecalSpec spec = spec(1.0f);
        spec.normalX = spec.normalY = spec.normalZ = 0.0f;
        assertEquals(false, builder.setFrame(spec));
        DecalSpec flat = spec(1.0f);
        flat.width = 0.0f;
        assertEquals(false, builder.setFrame(flat));
    }
}
