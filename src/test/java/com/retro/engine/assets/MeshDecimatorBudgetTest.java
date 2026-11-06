package com.retro.engine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MeshDecimatorBudgetTest {

    /** Flat indexed grid: neighbouring triangles share vertices, like a mesh after Assimp's vertex join. */
    private static class Grid implements MeshDecimator.VertexSource {
        final int cells;

        Grid(int cells) { this.cells = cells; }

        public int vertexCount() { return (cells + 1) * (cells + 1); }
        public int triangleCount() { return cells * cells * 2; }
        public int stride() { return MeshBufferData.STATIC_FLOATS_PER_VERTEX; }

        public boolean triangle(int t, int[] out) {
            int quad = t / 2, x = quad % cells, y = quad / cells, row = cells + 1;
            int a = y * row + x, b = a + 1, c = a + row, d = c + 1;
            out[0] = a;
            out[1] = (t % 2 == 0) ? b : d;
            out[2] = (t % 2 == 0) ? d : c;
            return true;
        }

        public void read(int v, float[] out, int offset) {
            position(v, out, offset);
            out[offset + 4] = 1.0f;
        }

        public void position(int v, float[] out) { position(v, out, 0); }

        private void position(int v, float[] out, int offset) {
            out[offset] = v % (cells + 1);
            out[offset + 1] = 0.0f;
            out[offset + 2] = v / (cells + 1);
        }
    }

    @Test
    void sharedVerticesCountOnceAgainstTheBudget() {
        Grid grid = new Grid(10);   // 121 vertices, 600 corners
        MeshBufferData mesh = MeshDecimator.buildOrDecimate(grid, 121);
        assertEquals(grid.triangleCount() * 3, mesh.vertexCount);
    }

    @Test
    void meshOverTheBudgetIsStillDecimated() {
        Grid grid = new Grid(10);
        MeshBufferData mesh = MeshDecimator.buildOrDecimate(grid, 120);
        assertTrue(mesh.vertexCount <= 120, "decimated to " + mesh.vertexCount);
    }

    @Test
    void verticesNoTriangleReferencesDoNotCount() {
        Grid grid = new Grid(10) {
            @Override public int vertexCount() { return super.vertexCount() + 1000; }
        };
        MeshBufferData mesh = MeshDecimator.buildOrDecimate(grid, 121);
        assertEquals(grid.triangleCount() * 3, mesh.vertexCount);
    }
}
