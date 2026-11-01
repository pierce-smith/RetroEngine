package com.retro.engine.assets.importers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel) that coarsens the grid until the output strictly fits the
 * RenderWare 3.5 budget, preserving UV seams, normals, and tangent frames. Each voxel's vertex is placed by its quadric
 * error (Lindstrom-style clustering) so flat panels, hard edges, and open borders stay put instead of shrinking. Sources are read through VertexSource so
 * the full-resolution triangle soup is never materialized; all decimation state lives in primitive arrays.
 */
final class MeshDecimator {

    // UV bins per axis for decimation clustering. Vertices whose UVs fall in different bins are never merged, which
    // keeps texture seams and atlas boundaries intact. Finer bins catch narrower seams but split more cells inside
    // a continuous UV island.
    private static final int UV_SEAM_BINS = 16;
    // Finest decimation grid tried first; the grid is coarsened until the decimated mesh fits the vertex budget
    private static final int MAX_GRID_RES = 96;
    // Smallest face budget a non-empty sub-mesh is given (a closed box), so small parts never vanish
    private static final int MIN_GROUP_FACES = 12;
    // Decimated faces meeting at more than this angle keep separate normals (hard edge); below it they shade smooth
    private static final float CREASE_COS = (float) Math.cos(Math.toRadians(60.0));
    // Weight of the constraint planes that pin open borders in place, relative to area-weighted surface planes
    private static final double BOUNDARY_WEIGHT = 4.0;
    // Pull of each representative toward its cell centroid, relative to the quadric's mean eigenvalue. Only decides
    // directions the surface leaves free (along a flat panel or a straight edge); constrained directions ignore it.
    private static final double QEM_REGULARIZATION = 1e-3;
    // RMS plane distance, as a fraction of the cell size, at which a cell stops trusting its quadric solution and falls
    // back to the centroid. Sharp edges and corners sit near zero; unrelated sheets crossing one cell (foliage) do not.
    private static final double QEM_TRUST_FRACTION = 0.05;
    // Sub-meshes above this many faces are voxel-clustered down to it before edge collapse, bounding import time and
    // memory on huge scans while leaving the final, budget-fitting reduction to the higher quality collapse
    private static final int EDGE_COLLAPSE_INPUT_FACES = 60000;
    // Weight of the constraint planes that keep UV seams in shape during edge collapse (open borders use BOUNDARY_WEIGHT)
    private static final double SEAM_WEIGHT = 1.0;

    private MeshDecimator() {}

    interface VertexSource {
        int vertexCount();
        int triangleCount();
        int stride();
        /** Writes triangle t's vertex indices into out; returns false if the face is not a valid triangle. */
        boolean triangle(int t, int[] out);
        /** Writes vertex v's full interleaved layout into out at offset. */
        void read(int v, float[] out, int offset);
        /** Writes vertex v's position into out[0..2]. */
        void position(int v, float[] out);
    }


    /** Source made of independent sub-meshes, decimated on separate grids with water-filled budgets. */
    interface GroupedSource extends VertexSource {
        int groupCount();
        /** Sub-mesh i as a standalone source with local vertex and triangle numbering. */
        VertexSource group(int i);
    }

    /** Pre-expanded triangle soup: vertex 3t+k is corner k of triangle t. */
    static final class SoupSource implements VertexSource {
        final float[] verts;
        final int count, stride;

        SoupSource(float[] verts, int count) {
            this.verts = verts;
            this.count = count;
            this.stride = MeshBufferData.strideOf(verts, count);
        }

        public int vertexCount() { return count; }
        public int triangleCount() { return count / 3; }
        public int stride() { return stride; }

        public boolean triangle(int t, int[] out) {
            out[0] = t * 3; out[1] = t * 3 + 1; out[2] = t * 3 + 2;
            return true;
        }

        public void read(int v, float[] out, int offset) {
            System.arraycopy(verts, v * stride, out, offset, stride);
        }

        public void position(int v, float[] out) {
            System.arraycopy(verts, v * stride, out, 0, 3);
        }
    }

    /** Contiguous vertex and triangle range of a parent source, renumbered from zero. */
    static final class SubSource implements VertexSource {
        final VertexSource parent;
        final int vertexBase, vertexCount, triangleBase, triangleCount;

        SubSource(VertexSource parent, int vertexBase, int vertexCount, int triangleBase, int triangleCount) {
            this.parent = parent;
            this.vertexBase = vertexBase;
            this.vertexCount = vertexCount;
            this.triangleBase = triangleBase;
            this.triangleCount = triangleCount;
        }

        public int vertexCount() { return vertexCount; }
        public int triangleCount() { return triangleCount; }
        public int stride() { return parent.stride(); }

        public boolean triangle(int t, int[] out) {
            if (!parent.triangle(triangleBase + t, out)) return false;
            out[0] -= vertexBase; out[1] -= vertexBase; out[2] -= vertexBase;
            return true;
        }

        public void read(int v, float[] out, int offset) { parent.read(vertexBase + v, out, offset); }
        public void position(int v, float[] out) { parent.position(vertexBase + v, out); }
    }

    /** Writes the source straight into a triangle-list buffer when it fits the budget, otherwise decimates it. */
    static MeshBufferData buildOrDecimate(VertexSource src, int maxTargetVerts) {
        int[] tri = new int[3];
        int validTriangles = 0;
        for (int t = 0; t < src.triangleCount(); t++) {
            if (src.triangle(t, tri)) validTriangles++;
        }
        if (validTriangles == 0) return null;

        long cornerCount = validTriangles * 3L;
        if (cornerCount > maxTargetVerts) {
            System.out.println("[MeshDecimator] High-poly model detected (" + cornerCount + " verts). Executing RenderWare 3.5 target voxel vertex collapse...");
            if (src instanceof GroupedSource grouped && grouped.groupCount() > 1) {
                return decimateGrouped(grouped, maxTargetVerts);
            }
            return decimate(src, maxTargetVerts, true);
        }
        return expand(src, validTriangles);
    }

    /** Copies every valid triangle of the source into a triangle-list buffer without decimation. */
    private static MeshBufferData expand(VertexSource src, int validTriangles) {
        int[] tri = new int[3];
        long cornerCount = validTriangles * 3L;
        int stride = src.stride();
        float[] verts = new float[(int) cornerCount * stride];
        int dst = 0;
        for (int t = 0; t < src.triangleCount(); t++) {
            if (!src.triangle(t, tri)) continue;
            for (int k = 0; k < 3; k++) {
                src.read(tri[k], verts, dst);
                dst += stride;
            }
        }
        return new MeshBufferData(verts, (int) cornerCount);
    }

    /**
     * Decimates a multi-part model with one edge collapse over every part, so the budget goes wherever it removes the
     * least visible error: a dense leaf canopy gets more faces than a smooth pot, and nothing is starved by a fixed
     * split. Collapse never deletes a part outright, so small parts thin out instead of vanishing. Parts that would
     * push the total past EDGE_COLLAPSE_INPUT_FACES are voxel-clustered first, each on its own grid, down to their
     * proportional share; one grid over the whole model would fuse neighbouring parts together. Falls back to
     * per-part budgets if the collapse cannot produce a mesh.
     */
    private static MeshBufferData decimateGrouped(GroupedSource src, int maxTargetVerts) {
        int groups = src.groupCount();
        long totalFaces = 0;
        for (int i = 0; i < groups; i++) totalFaces += src.group(i).triangleCount();

        VertexSource input = src;
        if (totalFaces > EDGE_COLLAPSE_INPUT_FACES) {
            List<MeshBufferData> parts = new ArrayList<>();
            int totalVerts = 0;
            int[] tri = new int[3];
            for (int i = 0; i < groups; i++) {
                VertexSource sub = src.group(i);
                int faces = 0;
                for (int t = 0; t < sub.triangleCount(); t++) if (sub.triangle(t, tri)) faces++;
                if (faces == 0) continue;
                int share = (int) Math.max(Math.min(faces, MIN_GROUP_FACES * 8L), (long) faces * EDGE_COLLAPSE_INPUT_FACES / totalFaces);
                MeshBufferData part = faces > share ? cluster(sub, share * 3, false) : expand(sub, faces);
                if (part == null || part.vertexCount == 0) part = expand(sub, faces);
                parts.add(part);
                totalVerts += part.vertexCount;
            }
            if (parts.isEmpty()) return null;
            float[] verts = new float[totalVerts * src.stride()];
            int dst = 0;
            for (MeshBufferData part : parts) {
                System.arraycopy(part.vertices, 0, verts, dst, part.vertexCount * src.stride());
                dst += part.vertexCount * src.stride();
            }
            input = new SoupSource(verts, totalVerts);
        }

        MeshBufferData data = EdgeCollapse.simplify(input, maxTargetVerts / 3);
        if (data == null) return decimatePerSubMesh(src, maxTargetVerts);
        System.out.println("[MeshDecimator] Successfully decimated high-poly mesh from " + totalFaces * 3L + " to " + data.vertexCount
                + " vertices across " + groups + " sub-meshes (RW 3.5 Target: " + maxTargetVerts + ").");
        return data;
    }

    /**
     * Decimates each sub-mesh on its own grid within its own share of the budget. One shared grid hands faces out by
     * surface area, so a large backdrop swallows the budget while small parts such as wheels, tires, and hubs collapse
     * below a cell and vanish. Each sub-mesh is weighted by sqrt(min(faces, area-proportional faces)): dense
     * micro-detail and huge low-detail surfaces are both damped. Budgets are water-filled, so parts that fit keep
     * their full geometry and hand the remainder to the rest; the sum never exceeds maxTargetVerts.
     */
    private static MeshBufferData decimatePerSubMesh(GroupedSource src, int maxTargetVerts) {
        int groups = src.groupCount();
        VertexSource[] subs = new VertexSource[groups];
        int[] faces = new int[groups];
        double[] area = new double[groups];
        int[] tri = new int[3];
        float[] p0 = new float[3], p1 = new float[3], p2 = new float[3];
        long totalFaces = 0;
        double totalArea = 0.0;
        for (int i = 0; i < groups; i++) {
            VertexSource sub = subs[i] = src.group(i);
            for (int t = 0; t < sub.triangleCount(); t++) {
                if (!sub.triangle(t, tri)) continue;
                sub.position(tri[0], p0);
                sub.position(tri[1], p1);
                sub.position(tri[2], p2);
                double e1x = p1[0] - p0[0], e1y = p1[1] - p0[1], e1z = p1[2] - p0[2];
                double e2x = p2[0] - p0[0], e2y = p2[1] - p0[1], e2z = p2[2] - p0[2];
                double cx = e1y * e2z - e1z * e2y, cy = e1z * e2x - e1x * e2z, cz = e1x * e2y - e1y * e2x;
                area[i] += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
                faces[i]++;
            }
            totalFaces += faces[i];
            totalArea += area[i];
        }

        double[] weight = new double[groups];
        for (int i = 0; i < groups; i++) {
            if (faces[i] == 0) continue;
            double areaFaces = totalArea > 0.0 ? totalFaces * area[i] / totalArea : faces[i];
            weight[i] = Math.sqrt(Math.min(faces[i], areaFaces));
        }

        // Water-fill: sub-meshes whose fair share covers all their faces keep them; the rest split what remains
        int[] budget = new int[groups];
        boolean[] settled = new boolean[groups];
        long remaining = maxTargetVerts / 3;
        boolean changed = true;
        while (changed) {
            changed = false;
            double sumW = 0.0;
            for (int i = 0; i < groups; i++) if (!settled[i] && weight[i] > 0.0) sumW += weight[i];
            if (sumW <= 0.0) break;
            for (int i = 0; i < groups; i++) {
                if (settled[i] || weight[i] <= 0.0) continue;
                if (faces[i] <= remaining * weight[i] / sumW) {
                    budget[i] = faces[i];
                    settled[i] = true;
                    remaining -= faces[i];
                    changed = true;
                }
            }
        }
        double sumW = 0.0;
        for (int i = 0; i < groups; i++) if (!settled[i] && weight[i] > 0.0) sumW += weight[i];
        for (int i = 0; i < groups; i++) {
            if (!settled[i] && weight[i] > 0.0) budget[i] = (int) Math.floor(remaining * weight[i] / sumW);
        }
        reserveMinimumBudgets(budget, faces, maxTargetVerts / 3);

        List<MeshBufferData> parts = new ArrayList<>();
        int totalVerts = 0, keptGroups = 0;
        for (int i = 0; i < groups; i++) {
            if (budget[i] <= 0) continue;
            VertexSource sub = subs[i];
            MeshBufferData part = budget[i] >= faces[i] ? expand(sub, faces[i]) : decimate(sub, budget[i] * 3, false);
            if (part == null || part.vertexCount == 0) continue;
            parts.add(part);
            totalVerts += part.vertexCount;
            keptGroups++;
        }
        if (parts.isEmpty()) return null;

        int stride = src.stride();
        float[] verts = new float[totalVerts * stride];
        int dst = 0;
        for (MeshBufferData part : parts) {
            System.arraycopy(part.vertices, 0, verts, dst, part.vertexCount * stride);
            dst += part.vertexCount * stride;
        }
        System.out.println("[MeshDecimator] Successfully decimated high-poly mesh from " + totalFaces * 3L + " to " + totalVerts
                + " vertices across " + keptGroups + "/" + groups + " sub-meshes (RW 3.5 Target: " + maxTargetVerts + ").");
        return new MeshBufferData(verts, totalVerts);
    }

    /**
     * Raises every non-empty sub-mesh to at least min(faces, MIN_GROUP_FACES) so small parts such as bolts, joints, and
     * panels never round down to nothing and leave a hole where they attached. The faces are taken from the largest
     * budgets, which lose the least relative detail; skipped entirely if the budget cannot cover every minimum.
     */
    private static void reserveMinimumBudgets(int[] budget, int[] faces, int totalFaces) {
        int groups = budget.length;
        long reserve = 0, deficit = 0;
        for (int i = 0; i < groups; i++) {
            int min = Math.min(faces[i], MIN_GROUP_FACES);
            reserve += min;
            deficit += Math.max(0, min - budget[i]);
        }
        if (deficit == 0 || reserve > totalFaces) return;

        for (int i = 0; i < groups; i++) budget[i] = Math.max(budget[i], Math.min(faces[i], MIN_GROUP_FACES));
        // Trim one face at a time from whichever budget is currently largest; reserve <= totalFaces guarantees a donor
        while (deficit > 0) {
            int donor = -1;
            for (int i = 0; i < groups; i++) {
                if (budget[i] > Math.min(faces[i], MIN_GROUP_FACES) && (donor < 0 || budget[i] > budget[donor])) donor = i;
            }
            if (donor < 0) break;
            budget[donor]--;
            deficit--;
        }
    }

    /**
     * Decimates one mesh to at most maxTargetVerts triangle-list vertices. Garland-Heckbert edge collapse does the
     * work: it removes the cheapest edge by quadric error one at a time and stops exactly at the budget, so thin parts
     * (stems, rods, cables) thin out instead of vanishing, separate walls never fuse, and no budget is left unused.
     * Sub-meshes too large to collapse edge by edge are voxel-clustered down to EDGE_COLLAPSE_INPUT_FACES first, which
     * keeps import time and memory bounded; clustering alone is the fallback if the collapse cannot produce a mesh.
     */
    static MeshBufferData decimate(VertexSource src, int maxTargetVerts, boolean verbose) {
        int targetFaces = maxTargetVerts / 3;
        if (src.vertexCount() == 0 || targetFaces == 0) return null;

        VertexSource input = src;
        if (src.triangleCount() > EDGE_COLLAPSE_INPUT_FACES) {
            MeshBufferData coarse = cluster(src, EDGE_COLLAPSE_INPUT_FACES * 3, false);
            if (coarse != null && coarse.vertexCount / 3 <= targetFaces) return coarse;
            if (coarse != null) input = new SoupSource(coarse.vertices, coarse.vertexCount);
        }

        MeshBufferData data = EdgeCollapse.simplify(input, targetFaces);
        if (data == null) data = cluster(src, maxTargetVerts, verbose);
        if (data != null && verbose) {
            System.out.println("[MeshDecimator] Successfully decimated high-poly mesh from " + src.triangleCount() * 3L + " to "
                    + data.vertexCount + " vertices (RW 3.5 Target: " + maxTargetVerts + ").");
        }
        return data;
    }

    /**
     * Writes per-corner normals into an expanded triangle list: each corner takes the area-weighted sum of the faces
     * around its position (cornerKey) that lie within the crease angle of its own face. Hard-surface edges stay crisp,
     * curved surfaces stay smooth, and shading is continuous across UV seams because neighbours are gathered by
     * position rather than by attribute vertex. faceN holds unnormalized (area-weighted) face normals.
     */
    private static void writeCreaseNormals(float[] verts, int stride, int faceCount, float[] faceN, int[] cornerKey, int keyCount) {
        int[] start = new int[keyCount + 1];
        for (int i = 0; i < faceCount * 3; i++) start[cornerKey[i] + 1]++;
        for (int c = 0; c < keyCount; c++) start[c + 1] += start[c];
        int[] incident = new int[faceCount * 3];
        int[] fill = Arrays.copyOf(start, keyCount);
        for (int i = 0; i < faceCount * 3; i++) incident[fill[cornerKey[i]]++] = i / 3;

        for (int f = 0; f < faceCount; f++) {
            float fx = faceN[f * 3], fy = faceN[f * 3 + 1], fz = faceN[f * 3 + 2];
            float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            for (int k = 0; k < 3; k++) {
                int o = (f * 3 + k) * stride + 3;
                if (fl > 1e-20f) {
                    float nx = 0.0f, ny = 0.0f, nz = 0.0f;
                    int key = cornerKey[f * 3 + k];
                    for (int j = start[key]; j < start[key + 1]; j++) {
                        int g = incident[j];
                        float gx = faceN[g * 3], gy = faceN[g * 3 + 1], gz = faceN[g * 3 + 2];
                        float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
                        if (gl <= 1e-20f || (fx * gx + fy * gy + fz * gz) < CREASE_COS * fl * gl) continue;
                        nx += gx; ny += gy; nz += gz;
                    }
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    // The face itself always passes the crease test, so len > 0 unless sums cancel numerically
                    if (len > 1e-20f) {
                        verts[o] = nx / len; verts[o + 1] = ny / len; verts[o + 2] = nz / len;
                    } else {
                        verts[o] = fx / fl; verts[o + 1] = fy / fl; verts[o + 2] = fz / fl;
                    }
                }
                // Never hand a backend a zero or non-finite normal
                float l2 = verts[o] * verts[o] + verts[o + 1] * verts[o + 1] + verts[o + 2] * verts[o + 2];
                if (!(l2 > 1e-12f) || !Float.isFinite(l2)) {
                    verts[o] = 0.0f; verts[o + 1] = 1.0f; verts[o + 2] = 0.0f;
                }
            }
        }
    }

    /**
     * Garland-Heckbert quadric edge collapse on primitive arrays. Vertices are split into positions (welded by exact
     * coordinates) and wedges (a position plus one set of UVs, colors, tangents, and bone data), so texture seams keep
     * separate attributes while the surface stays connected. Open borders and UV seams add constraint planes so they
     * keep their shape, and a collapse is rejected if it would flip a face or violate the link condition, which is
     * what stops a thin tube from being crushed into a line. Coincident faces with opposite winding (fused double
     * walls, duplicated double-sided cards) are merged into one so no two triangles ever z-fight.
     */
    private static final class EdgeCollapse {
        final int stride;
        int wedgeCount, posCount, triCount, live;
        float[] attr;       // wedge attributes, stride floats each
        int[] wedgePos;     // wedge -> position id
        double[] px;        // position xyz
        int[] tri;          // 3 wedges per triangle
        boolean[] dead;
        double[] q;         // 10-float quadric per position
        int[][] adj;        // position -> incident triangles (may hold dead entries, filtered on use)
        int[] adjN;
        int[] ver;
        boolean[] removed;
        int[] mark;
        int stamp;

        // Lazy binary min-heap of candidate collapses; stale entries are skipped by position version
        double[] hCost = new double[64];
        double[] hX = new double[64 * 3];
        int[] hA = new int[64], hB = new int[64], hVa = new int[64], hVb = new int[64];
        int hSize;

        static MeshBufferData simplify(VertexSource src, int targetFaces) {
            EdgeCollapse ec = new EdgeCollapse(src);
            if (ec.live == 0) return null;
            if (ec.live > targetFaces) {
                ec.run(targetFaces, true);
                // Strict checks can stall on tiny budgets; finish without them, then trim as a last resort
                if (ec.live > targetFaces) ec.run(targetFaces, false);
                if (ec.live > targetFaces) ec.dropSmallest(targetFaces);
            }
            ec.mergeOpposingTwins();
            return ec.build();
        }

        EdgeCollapse(VertexSource src) {
            stride = src.stride();
            int n = src.vertexCount();
            attr = new float[n * stride];
            wedgePos = new int[n];
            px = new double[n * 3];
            int[] srcWedge = new int[n];
            LongIntMap wedgeMap = new LongIntMap(n), posMap = new LongIntMap(n);
            float[] s = new float[stride];
            for (int v = 0; v < n; v++) {
                src.read(v, s, 0);
                // Normals are rebuilt at the end, so they never split a wedge; everything else does
                long h = 0x9E3779B97F4A7C15L;
                for (int i = 0; i < stride; i++) {
                    if (i >= 3 && i <= 5) continue;
                    h = mix(h ^ (Float.floatToRawIntBits(i < 3 ? s[i] + 0.0f : s[i]) & 0xFFFFFFFFL) ^ ((long) i << 40));
                }
                int w = wedgeMap.getOrPut(h, wedgeCount);
                if (w == wedgeCount) {
                    System.arraycopy(s, 0, attr, w * stride, stride);
                    long pk = mix(mix(mix(Float.floatToIntBits(s[0] + 0.0f)) ^ Float.floatToIntBits(s[1] + 0.0f)) ^ Float.floatToIntBits(s[2] + 0.0f));
                    int p = posMap.getOrPut(pk, posCount);
                    if (p == posCount) {
                        px[p * 3] = s[0]; px[p * 3 + 1] = s[1]; px[p * 3 + 2] = s[2];
                        posCount++;
                    }
                    wedgePos[w] = p;
                    wedgeCount++;
                }
                srcWedge[v] = w;
            }

            int[] t3 = new int[3];
            tri = new int[src.triangleCount() * 3];
            for (int t = 0; t < src.triangleCount(); t++) {
                if (!src.triangle(t, t3)) continue;
                int w0 = srcWedge[t3[0]], w1 = srcWedge[t3[1]], w2 = srcWedge[t3[2]];
                int p0 = wedgePos[w0], p1 = wedgePos[w1], p2 = wedgePos[w2];
                if (p0 == p1 || p1 == p2 || p2 == p0) continue;
                tri[triCount * 3] = w0; tri[triCount * 3 + 1] = w1; tri[triCount * 3 + 2] = w2;
                triCount++;
            }
            dead = new boolean[triCount];
            live = triCount;

            adjN = new int[posCount];
            for (int i = 0; i < triCount * 3; i++) adjN[wedgePos[tri[i]]]++;
            adj = new int[posCount][];
            for (int p = 0; p < posCount; p++) adj[p] = new int[Math.max(4, adjN[p])];
            Arrays.fill(adjN, 0);
            for (int t = 0; t < triCount; t++) {
                for (int k = 0; k < 3; k++) {
                    int p = wedgePos[tri[t * 3 + k]];
                    adj[p][adjN[p]++] = t;
                }
            }
            ver = new int[posCount];
            removed = new boolean[posCount];
            mark = new int[posCount];

            q = new double[posCount * 10];
            double[] nrm = new double[3];
            for (int t = 0; t < triCount; t++) {
                double area = faceNormal(t, nrm);
                if (area <= 0.0) continue;
                int p0 = pos(t, 0);
                double d = -(nrm[0] * px[p0 * 3] + nrm[1] * px[p0 * 3 + 1] + nrm[2] * px[p0 * 3 + 2]);
                for (int k = 0; k < 3; k++) addPlane(pos(t, k), nrm[0], nrm[1], nrm[2], d, area);
            }
            classifyEdges();
        }

        int pos(int t, int k) { return wedgePos[tri[t * 3 + k]]; }

        /** Unit normal of triangle t into out; returns its area (0 for degenerate). */
        double faceNormal(int t, double[] out) {
            int a = pos(t, 0) * 3, b = pos(t, 1) * 3, c = pos(t, 2) * 3;
            double e1x = px[b] - px[a], e1y = px[b + 1] - px[a + 1], e1z = px[b + 2] - px[a + 2];
            double e2x = px[c] - px[a], e2y = px[c + 1] - px[a + 1], e2z = px[c + 2] - px[a + 2];
            double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(len > 1e-30)) return 0.0;
            out[0] = nx / len; out[1] = ny / len; out[2] = nz / len;
            return 0.5 * len;
        }

        void addPlane(int p, double nx, double ny, double nz, double d, double w) {
            int o = p * 10;
            q[o] += w * nx * nx; q[o + 1] += w * nx * ny; q[o + 2] += w * nx * nz;
            q[o + 3] += w * ny * ny; q[o + 4] += w * ny * nz; q[o + 5] += w * nz * nz;
            q[o + 6] += w * nx * d; q[o + 7] += w * ny * d; q[o + 8] += w * nz * d;
            q[o + 9] += w * d * d;
        }

        /**
         * Finds every edge, pins open borders (used by one face) and UV seams (faces disagree on a wedge) with
         * perpendicular constraint planes, and seeds the heap with one candidate per edge.
         */
        void classifyEdges() {
            long[] keys = new long[triCount * 3];
            for (int t = 0; t < triCount; t++) {
                for (int k = 0; k < 3; k++) {
                    int a = pos(t, k), b = pos(t, (k + 1) % 3);
                    int lo = Math.min(a, b), hi = Math.max(a, b);
                    keys[t * 3 + k] = ((long) lo << 44) | ((long) hi << 24) | (t * 3L + k);
                }
            }
            Arrays.sort(keys);
            double[] nrm = new double[3];
            for (int i = 0; i < keys.length; ) {
                long edge = keys[i] >>> 24;
                int j = i + 1;
                while (j < keys.length && (keys[j] >>> 24) == edge) j++;
                int lo = (int) (keys[i] >>> 44), hi = (int) ((keys[i] >>> 24) & 0xFFFFF);
                double weight = 0.0;
                if (j - i == 1) {
                    weight = BOUNDARY_WEIGHT;
                } else {
                    int ref = (int) (keys[i] & 0xFFFFFF);
                    int wLo = wedgeAt(ref / 3, lo), wHi = wedgeAt(ref / 3, hi);
                    for (int m = i + 1; m < j; m++) {
                        int c = (int) (keys[m] & 0xFFFFFF);
                        if (wedgeAt(c / 3, lo) != wLo || wedgeAt(c / 3, hi) != wHi) { weight = SEAM_WEIGHT; break; }
                    }
                }
                if (weight > 0.0) {
                    for (int m = i; m < j; m++) {
                        int t = (int) (keys[m] & 0xFFFFFF) / 3;
                        if (faceNormal(t, nrm) <= 0.0) continue;
                        double ex = px[hi * 3] - px[lo * 3], ey = px[hi * 3 + 1] - px[lo * 3 + 1], ez = px[hi * 3 + 2] - px[lo * 3 + 2];
                        double mx = ey * nrm[2] - ez * nrm[1], my = ez * nrm[0] - ex * nrm[2], mz = ex * nrm[1] - ey * nrm[0];
                        double ml = Math.sqrt(mx * mx + my * my + mz * mz);
                        if (!(ml > 1e-30)) continue;
                        double w = weight * ml * ml; // ml = |edge|, so the constraint scales like the face areas
                        mx /= ml; my /= ml; mz /= ml;
                        double d = -(mx * px[lo * 3] + my * px[lo * 3 + 1] + mz * px[lo * 3 + 2]);
                        addPlane(lo, mx, my, mz, d, w);
                        addPlane(hi, mx, my, mz, d, w);
                    }
                }
                pushCandidate(lo, hi);
                i = j;
            }
        }

        int wedgeAt(int t, int p) {
            for (int k = 0; k < 3; k++) if (wedgePos[tri[t * 3 + k]] == p) return tri[t * 3 + k];
            return -1;
        }

        static double error(double[] m, double x, double y, double z) {
            return x * (m[0] * x + 2.0 * (m[1] * y + m[2] * z)) + y * (m[3] * y + 2.0 * m[4] * z) + m[5] * z * z
                    + 2.0 * (m[6] * x + m[7] * y + m[8] * z) + m[9];
        }

        final double[] qs = new double[10];

        /** Scores collapsing edge (a, b) at its quadric-optimal point and pushes it onto the heap. */
        void pushCandidate(int a, int b) {
            for (int i = 0; i < 10; i++) qs[i] = q[a * 10 + i] + q[b * 10 + i];
            double ax = px[a * 3], ay = px[a * 3 + 1], az = px[a * 3 + 2];
            double bx = px[b * 3], by = px[b * 3 + 1], bz = px[b * 3 + 2];
            double mx = 0.5 * (ax + bx), my = 0.5 * (ay + by), mz = 0.5 * (az + bz);
            double ex = bx - ax, ey = by - ay, ez = bz - az;
            double len2 = ex * ex + ey * ey + ez * ez;

            // Endpoints and midpoint are always candidates; the solved optimum joins them when it stays near the edge
            double bestX = mx, bestY = my, bestZ = mz, best = error(qs, mx, my, mz);
            double e = error(qs, ax, ay, az);
            if (e < best) { best = e; bestX = ax; bestY = ay; bestZ = az; }
            e = error(qs, bx, by, bz);
            if (e < best) { best = e; bestX = bx; bestY = by; bestZ = bz; }

            double lambda = 1e-6 * (qs[0] + qs[3] + qs[5]) / 3.0;
            if (lambda > 0.0) {
                double a00 = qs[0] + lambda, a01 = qs[1], a02 = qs[2], a11 = qs[3] + lambda, a12 = qs[4], a22 = qs[5] + lambda;
                double r0 = -qs[6] + lambda * mx, r1 = -qs[7] + lambda * my, r2 = -qs[8] + lambda * mz;
                double i00 = a11 * a22 - a12 * a12, i01 = a02 * a12 - a01 * a22, i02 = a01 * a12 - a02 * a11;
                double det = a00 * i00 + a01 * i01 + a02 * i02;
                if (Math.abs(det) > 1e-300) {
                    double i11 = a00 * a22 - a02 * a02, i12 = a01 * a02 - a00 * a12, i22 = a00 * a11 - a01 * a01;
                    double sx = (i00 * r0 + i01 * r1 + i02 * r2) / det;
                    double sy = (i01 * r0 + i11 * r1 + i12 * r2) / det;
                    double sz = (i02 * r0 + i12 * r1 + i22 * r2) / det;
                    double dx = sx - mx, dy = sy - my, dz = sz - mz;
                    if (Double.isFinite(sx) && Double.isFinite(sy) && Double.isFinite(sz) && dx * dx + dy * dy + dz * dz <= len2) {
                        e = error(qs, sx, sy, sz);
                        if (e < best) { best = e; bestX = sx; bestY = sy; bestZ = sz; }
                    }
                }
            }
            heapPush(Math.max(0.0, best), a, b, bestX, bestY, bestZ);
        }

        void run(int targetFaces, boolean strict) {
            if (!strict) {
                hSize = 0;
                for (int t = 0; t < triCount; t++) {
                    if (dead[t]) continue;
                    for (int k = 0; k < 3; k++) {
                        int a = pos(t, k), b = pos(t, (k + 1) % 3);
                        if (a < b) pushCandidate(a, b);
                    }
                }
            }
            while (live > targetFaces && hSize > 0) {
                int a = hA[0], b = hB[0], va = hVa[0], vb = hVb[0];
                double x = hX[0], y = hX[1], z = hX[2];
                heapPop();
                if (removed[a] || removed[b] || ver[a] != va || ver[b] != vb) continue;
                collapse(a, b, x, y, z, strict);
            }
        }

        /** Merges position b into a at (x, y, z); returns false (and changes nothing) if the collapse is rejected. */
        boolean collapse(int a, int b, double x, double y, double z, boolean strict) {
            int shared = 0;
            for (int i = 0; i < adjN[b]; i++) {
                int t = adj[b][i];
                if (!dead[t] && wedgeAt(t, a) >= 0) shared++;
            }
            if (shared == 0) return false;

            if (strict) {
                // Link condition: a and b may only share the neighbours of the faces being removed, otherwise the
                // collapse pinches the surface (a thin tube flattening into a line, a hole closing into a fin)
                stamp++;
                for (int i = 0; i < adjN[a]; i++) {
                    int t = adj[a][i];
                    if (dead[t]) continue;
                    for (int k = 0; k < 3; k++) mark[pos(t, k)] = stamp;
                }
                int common = 0;
                stamp++;
                for (int i = 0; i < adjN[b]; i++) {
                    int t = adj[b][i];
                    if (dead[t]) continue;
                    for (int k = 0; k < 3; k++) {
                        int p = pos(t, k);
                        if (p == a || p == b) continue;
                        if (mark[p] == stamp - 1) { common++; mark[p] = stamp; }
                    }
                }
                if (common > shared) return false;
                if (flips(a, b, x, y, z) || flips(b, a, x, y, z)) return false;
            }

            // Faces holding both ends disappear; their b-side wedges map onto the a-side wedge of the same face
            int[] from = new int[shared * 3], to = new int[shared * 3];
            int maps = 0;
            for (int i = 0; i < adjN[b]; i++) {
                int t = adj[b][i];
                if (dead[t]) continue;
                int wa = wedgeAt(t, a);
                if (wa < 0) continue;
                int wb = wedgeAt(t, b);
                boolean known = false;
                for (int m = 0; m < maps; m++) if (from[m] == wb) { known = true; break; }
                if (!known && wb != wa) { from[maps] = wb; to[maps] = wa; maps++; }
                dead[t] = true;
                live--;
            }
            // Surviving faces around b move onto a: mapped wedges switch to a's, the rest simply relocate
            for (int i = 0; i < adjN[b]; i++) {
                int t = adj[b][i];
                if (dead[t]) continue;
                for (int k = 0; k < 3; k++) {
                    int w = tri[t * 3 + k];
                    if (wedgePos[w] != b) continue;
                    int mapped = w;
                    for (int m = 0; m < maps; m++) if (from[m] == w) { mapped = to[m]; break; }
                    if (mapped != w) tri[t * 3 + k] = mapped; else wedgePos[w] = a;
                }
            }

            px[a * 3] = x; px[a * 3 + 1] = y; px[a * 3 + 2] = z;
            for (int i = 0; i < 10; i++) q[a * 10 + i] += q[b * 10 + i];

            int count = 0;
            int[] merged = new int[adjN[a] + adjN[b]];
            for (int i = 0; i < adjN[a]; i++) if (!dead[adj[a][i]]) merged[count++] = adj[a][i];
            for (int i = 0; i < adjN[b]; i++) if (!dead[adj[b][i]]) merged[count++] = adj[b][i];
            adj[a] = merged;
            adjN[a] = count;
            adj[b] = null;
            adjN[b] = 0;
            removed[b] = true;
            ver[a]++;

            stamp++;
            for (int i = 0; i < count; i++) {
                int t = merged[i];
                for (int k = 0; k < 3; k++) {
                    int p = pos(t, k);
                    if (p == a || mark[p] == stamp) continue;
                    mark[p] = stamp;
                    pushCandidate(a, p);
                }
            }
            return true;
        }

        /** True if moving `moving` to (x, y, z) would flip or collapse a face around it that does not hold `other`. */
        boolean flips(int moving, int other, double x, double y, double z) {
            for (int i = 0; i < adjN[moving]; i++) {
                int t = adj[moving][i];
                if (dead[t]) continue;
                int p0 = pos(t, 0), p1 = pos(t, 1), p2 = pos(t, 2);
                if (p0 == other || p1 == other || p2 == other) continue;
                double[] o = new double[9], m = new double[9];
                int[] ps = {p0, p1, p2};
                for (int k = 0; k < 3; k++) {
                    for (int c = 0; c < 3; c++) o[k * 3 + c] = px[ps[k] * 3 + c];
                    if (ps[k] == moving) { m[k * 3] = x; m[k * 3 + 1] = y; m[k * 3 + 2] = z; }
                    else System.arraycopy(o, k * 3, m, k * 3, 3);
                }
                double[] no = cross(o), nn = cross(m);
                double lo = Math.sqrt(no[0] * no[0] + no[1] * no[1] + no[2] * no[2]);
                double ln = Math.sqrt(nn[0] * nn[0] + nn[1] * nn[1] + nn[2] * nn[2]);
                if (!(lo > 1e-30)) continue;
                if (!(ln > 1e-30)) return true;
                if (no[0] * nn[0] + no[1] * nn[1] + no[2] * nn[2] < 0.2 * lo * ln) return true;
            }
            return false;
        }

        static double[] cross(double[] v) {
            double e1x = v[3] - v[0], e1y = v[4] - v[1], e1z = v[5] - v[2];
            double e2x = v[6] - v[0], e2y = v[7] - v[1], e2z = v[8] - v[2];
            return new double[] { e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x };
        }

        /** Last resort for budgets too small to reach by collapsing: drops the smallest faces. */
        void dropSmallest(int targetFaces) {
            double[] nrm = new double[3];
            long[] order = new long[live];
            int c = 0;
            for (int t = 0; t < triCount; t++) {
                if (dead[t]) continue;
                // Area bits sort correctly as longs for non-negative doubles
                order[c++] = (Double.doubleToLongBits(faceNormal(t, nrm)) & ~0xFFFFFL) | t;
            }
            Arrays.sort(order, 0, c);
            for (int i = 0; i < c && live > targetFaces; i++) {
                dead[(int) (order[i] & 0xFFFFF)] = true;
                live--;
            }
        }

        /**
         * Two live faces on the same three positions with opposite winding are one surface seen from both sides (a
         * collapsed double wall, or a card modeled double-sided). They sit at identical depth, so which one shows is
         * decided by rounding and differs between backends. Keep one, the first encountered, since the lit shaders
         * shade both sides of a face.
         */
        void mergeOpposingTwins() {
            LongIntMap seen = new LongIntMap(live + 1);
            for (int t = 0; t < triCount; t++) {
                if (dead[t]) continue;
                int a = pos(t, 0), b = pos(t, 1), c = pos(t, 2);
                int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
                long key = mix(mix(mix(lo) ^ mid) ^ hi);
                int before = seen.size;
                seen.getOrPut(key, t);
                if (seen.size == before) { dead[t] = true; live--; }
            }
        }

        MeshBufferData build() {
            float[] verts = new float[live * 3 * stride];
            float[] faceN = new float[live * 3];
            int[] cornerKey = new int[live * 3];
            double[] nrm = new double[3];
            int f = 0;
            for (int t = 0; t < triCount; t++) {
                if (dead[t]) continue;
                double area = faceNormal(t, nrm);
                faceN[f * 3] = (float) (nrm[0] * area); faceN[f * 3 + 1] = (float) (nrm[1] * area); faceN[f * 3 + 2] = (float) (nrm[2] * area);
                if (area <= 0.0) { faceN[f * 3] = 0.0f; faceN[f * 3 + 1] = 0.0f; faceN[f * 3 + 2] = 0.0f; }
                for (int k = 0; k < 3; k++) {
                    int w = tri[t * 3 + k], p = wedgePos[w];
                    int o = (f * 3 + k) * stride;
                    System.arraycopy(attr, w * stride, verts, o, stride);
                    verts[o] = (float) px[p * 3]; verts[o + 1] = (float) px[p * 3 + 1]; verts[o + 2] = (float) px[p * 3 + 2];
                    cornerKey[f * 3 + k] = p;
                }
                f++;
            }
            writeCreaseNormals(verts, stride, f, faceN, cornerKey, posCount);
            return new MeshBufferData(verts, f * 3);
        }

        void heapPush(double cost, int a, int b, double x, double y, double z) {
            if (hSize == hCost.length) {
                int cap = hSize * 2;
                hCost = Arrays.copyOf(hCost, cap); hX = Arrays.copyOf(hX, cap * 3);
                hA = Arrays.copyOf(hA, cap); hB = Arrays.copyOf(hB, cap); hVa = Arrays.copyOf(hVa, cap); hVb = Arrays.copyOf(hVb, cap);
            }
            int i = hSize++;
            set(i, cost, a, b, ver[a], ver[b], x, y, z);
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (hCost[parent] <= hCost[i]) break;
                swap(i, parent);
                i = parent;
            }
        }

        void heapPop() {
            hSize--;
            if (hSize == 0) return;
            swap(0, hSize);
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, m = i;
                if (l < hSize && hCost[l] < hCost[m]) m = l;
                if (r < hSize && hCost[r] < hCost[m]) m = r;
                if (m == i) break;
                swap(i, m);
                i = m;
            }
        }

        void set(int i, double cost, int a, int b, int va, int vb, double x, double y, double z) {
            hCost[i] = cost; hA[i] = a; hB[i] = b; hVa[i] = va; hVb[i] = vb;
            hX[i * 3] = x; hX[i * 3 + 1] = y; hX[i * 3 + 2] = z;
        }

        void swap(int i, int j) {
            double c = hCost[i]; hCost[i] = hCost[j]; hCost[j] = c;
            int t = hA[i]; hA[i] = hA[j]; hA[j] = t;
            t = hB[i]; hB[i] = hB[j]; hB[j] = t;
            t = hVa[i]; hVa[i] = hVa[j]; hVa[j] = t;
            t = hVb[i]; hVb[i] = hVb[j]; hVb[j] = t;
            for (int k = 0; k < 3; k++) { double x = hX[i * 3 + k]; hX[i * 3 + k] = hX[j * 3 + k]; hX[j * 3 + k] = x; }
        }
    }

    /**
     * Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel Algorithm):
     * Maps vertices to cubic voxel cells, split by quantized UV bin so seams never blend, rebuilds non-degenerate
     * faces, and recomputes normals from the decimated face geometry. The grid starts fine and is coarsened until the
     * unique surviving faces fit maxTargetVerts, so the output never exceeds the budget. All per-vertex state lives in
     * primitive arrays sized to the indexed source, never to the expanded soup.
     */
    static MeshBufferData cluster(VertexSource src, int maxTargetVerts, boolean verbose) {
        final int stride = src.stride();
        final boolean skinned = stride >= MeshBufferData.SKINNED_FLOATS_PER_VERTEX;
        final int n = src.vertexCount();
        final int targetFaces = maxTargetVerts / 3;
        if (n == 0 || targetFaces == 0) return null;

        Decimator dec = new Decimator(src, n, stride, targetFaces);

        // Coarsen the grid until the unique surviving faces fit. Surface face count scales roughly with g^2.
        int g = MAX_GRID_RES;
        int lastOverG = -1;
        boolean truncate = false;
        while (true) {
            int count = dec.pass(g, false);
            if (count >= 0 && count <= targetFaces) {
                if (count > 0) {
                    // Coarsening steps overshoot; search back toward the over-budget grid for the finest one that fits
                    int lo = g, hi = lastOverG;
                    while (hi - lo > 1) {
                        int mid = (lo + hi) >>> 1;
                        int c = dec.pass(mid, false);
                        if (c > 0 && c <= targetFaces) lo = mid; else hi = mid;
                    }
                    g = lo;
                    break;
                }
                if (lastOverG < 0) break;
                // Overshot into an empty grid: binary search between the empty and the over-budget resolutions
                int lo = g, hi = lastOverG;
                boolean found = false;
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    int c = dec.pass(mid, false);
                    if (c > 0 && c <= targetFaces) { g = mid; found = true; break; }
                    if (c == 0) lo = mid; else hi = mid;
                }
                if (!found) { g = hi; truncate = true; }
                break;
            }
            lastOverG = g;
            if (g <= 2) { truncate = true; break; }
            int next = count < 0 ? g / 2 : (int) (g * Math.sqrt((double) targetFaces / count) * 0.97);
            g = Math.max(2, Math.min(g - 1, next));
        }

        int faceCount = dec.pass(g, true);
        if (faceCount <= 0) return null;
        if (truncate && verbose) {
            System.err.println("[MeshDecimator] Decimation could not fit the budget cleanly; keeping the first " + faceCount + " faces.");
        }

        MeshBufferData data = dec.build(faceCount, skinned);
        if (verbose) System.out.println("[MeshDecimator] Voxel-clustered high-poly mesh from " + src.triangleCount() * 3L + " to "
                + data.vertexCount + " vertices (grid " + g + ", RW 3.5 Target: " + maxTargetVerts + ").");
        return data;
    }

    private static final class Decimator {
        final VertexSource src;
        final int n, stride, targetFaces;
        final float[] pos;        // source positions, xyz per vertex
        final long[] uvBin;       // quantized primary UV bin per vertex
        final int[] weldId;       // vertices at bit-identical positions share an id (split UV/normal seams rejoin)
        final int[] vertCell;
        final int[] vertCluster;
        final LongIntMap clusterMap;
        final LongIntMap faceSet;
        final int[] faces;        // collected faces: c0, c1, c2, v0, v1, v2
        final float minX, minY, minZ, extentX, extentY, extentZ, maxExtent;
        int clusterCount;
        int gridX, gridY, gridZ;
        float cellSize;

        Decimator(VertexSource src, int n, int stride, int targetFaces) {
            this.src = src;
            this.n = n;
            this.stride = stride;
            this.targetFaces = targetFaces;
            this.pos = new float[n * 3];
            this.uvBin = new long[n];
            this.weldId = new int[n];
            this.vertCell = new int[n];
            this.vertCluster = new int[n];
            this.clusterMap = new LongIntMap(n);
            this.faceSet = new LongIntMap(targetFaces * 4 + 1);
            this.faces = new int[targetFaces * 6];

            LongIntMap weld = new LongIntMap(n);
            int welded = 0;
            float[] scratch = new float[stride];
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE;
            float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
            for (int v = 0; v < n; v++) {
                src.read(v, scratch, 0);
                float x = scratch[0], y = scratch[1], z = scratch[2];
                pos[v * 3] = x; pos[v * 3 + 1] = y; pos[v * 3 + 2] = z;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
                x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
                // floor() keeps tiled UVs outside [0, 1] in distinct bins; 20 bits per axis wraps only after ~65k tiles
                long ub = (long) Math.floor(scratch[10] * UV_SEAM_BINS) & 0xFFFFFL;
                long vb = (long) Math.floor(scratch[11] * UV_SEAM_BINS) & 0xFFFFFL;
                uvBin[v] = ub | (vb << 20);
                // + 0.0f folds -0.0 into 0.0 so both signs of zero weld together
                long key = mix(mix(mix(Float.floatToIntBits(x + 0.0f)) ^ Float.floatToIntBits(y + 0.0f)) ^ Float.floatToIntBits(z + 0.0f));
                int id = weld.getOrPut(key, welded);
                if (id == welded) welded++;
                weldId[v] = id;
            }
            this.minX = x0; this.minY = y0; this.minZ = z0;
            this.extentX = Math.max(1e-6f, x1 - x0);
            this.extentY = Math.max(1e-6f, y1 - y0);
            this.extentZ = Math.max(1e-6f, z1 - z0);
            this.maxExtent = Math.max(extentX, Math.max(extentY, extentZ));
        }

        /**
         * Clusters vertices on a cubic grid with g cells along the longest axis and counts unique surviving faces.
         * Returns -1 once the count passes 4x the budget (not collecting) so passes stay cheap and bounded in memory.
         * When collecting, stops at the budget and returns the collected count.
         */
        int pass(int g, boolean collect) {
            float cell = cellSize = maxExtent / g;
            gridX = Math.max(1, Math.min(g, (int) Math.ceil(extentX / cell)));
            gridY = Math.max(1, Math.min(g, (int) Math.ceil(extentY / cell)));
            gridZ = Math.max(1, Math.min(g, (int) Math.ceil(extentZ / cell)));

            // Two keys per vertex: the voxel cell decides the collapsed position and whether a face survives; the
            // cell plus the UV bin decides which vertices share attributes. Vertices on opposite sides of a UV seam
            // share a position but land in different clusters, so the surface stays watertight and UVs never blend.
            clusterMap.clear();
            clusterCount = 0;
            for (int v = 0; v < n; v++) {
                int cx = Math.min(gridX - 1, (int) ((pos[v * 3] - minX) / cell));
                int cy = Math.min(gridY - 1, (int) ((pos[v * 3 + 1] - minY) / cell));
                int cz = Math.min(gridZ - 1, (int) ((pos[v * 3 + 2] - minZ) / cell));
                int c = cx + gridX * (cy + gridY * cz);
                vertCell[v] = c;
                int id = clusterMap.getOrPut((long) c | (uvBin[v] << 24), clusterCount);
                if (id == clusterCount) clusterCount++;
                vertCluster[v] = id;
            }

            // A face survives only if its 3 corners fall in distinct cells. Faces are deduplicated by their cells, not
            // their UV clusters, and regardless of winding: every cell has one position, so two faces on the same
            // cells are coincident. A thin double wall collapsing onto itself would otherwise leave an inward and an
            // outward face at identical depth, and which one shows would differ between backends. The lit shaders
            // shade both sides of a face, so one copy is enough. 96^3 cells always pack three indices into 63 bits.
            int limit = collect ? targetFaces : targetFaces * 4;
            faceSet.clear();
            int count = 0;
            int[] tri = new int[3];
            for (int t = 0; t < src.triangleCount(); t++) {
                if (!src.triangle(t, tri)) continue;
                int v0 = tri[0], v1 = tri[1], v2 = tri[2];
                int h0 = vertCell[v0], h1 = vertCell[v1], h2 = vertCell[v2];
                if (h0 == h1 || h1 == h2 || h2 == h0) continue;

                int a = Math.min(h0, Math.min(h1, h2)), c = Math.max(h0, Math.max(h1, h2)), b = h0 + h1 + h2 - a - c;
                long key = ((long) a << 42) | ((long) b << 21) | c;
                int before = faceSet.size;
                faceSet.getOrPut(key, 0);
                if (faceSet.size == before) continue;

                if (count >= limit) return collect ? count : -1;
                if (collect) {
                    int f = count * 6;
                    faces[f] = vertCluster[v0]; faces[f + 1] = vertCluster[v1]; faces[f + 2] = vertCluster[v2];
                    faces[f + 3] = v0; faces[f + 4] = v1; faces[f + 5] = v2;
                }
                count++;
            }
            return count;
        }

        /** Builds the decimated triangle list from the faces collected by the last pass. */
        MeshBufferData build(int faceCount, boolean skinned) {
            // Compact the clusters and cells referenced by kept faces
            int[] clusterCompact = new int[clusterCount];
            Arrays.fill(clusterCompact, -1);
            int[] cellCompact = new int[gridX * gridY * gridZ];
            Arrays.fill(cellCompact, -1);
            int usedClusters = 0, usedCells = 0;
            for (int f = 0; f < faceCount; f++) {
                for (int k = 0; k < 3; k++) {
                    int c = faces[f * 6 + k];
                    int v = faces[f * 6 + 3 + k];
                    if (clusterCompact[c] < 0) clusterCompact[c] = usedClusters++;
                    if (cellCompact[vertCell[v]] < 0) cellCompact[vertCell[v]] = usedCells++;
                }
            }

            // Bone indices are int bit patterns and cannot be averaged; skinned clusters average only the
            // static attributes and inherit bone indices/weights from the cluster's first vertex.
            final int averagedFloats = skinned ? MeshBufferData.BONE_INDEX_OFFSET : stride;
            float[] cellSums = new float[usedCells * 4];
            float[] clusterVerts = new float[usedClusters * stride];
            int[] clusterCounts = new int[usedClusters];
            int[] clusterCell = new int[usedClusters];
            float[] scratch = new float[stride];
            for (int v = 0; v < n; v++) {
                int cc = cellCompact[vertCell[v]];
                if (cc >= 0) {
                    cellSums[cc * 4] += pos[v * 3];
                    cellSums[cc * 4 + 1] += pos[v * 3 + 1];
                    cellSums[cc * 4 + 2] += pos[v * 3 + 2];
                    cellSums[cc * 4 + 3] += 1.0f;
                }
                int k = clusterCompact[vertCluster[v]];
                if (k < 0) continue;
                src.read(v, scratch, 0);
                int base = k * stride;
                for (int i = 0; i < averagedFloats; i++) clusterVerts[base + i] += scratch[i];
                if (clusterCounts[k]++ == 0) {
                    clusterCell[k] = cc;
                    if (skinned) System.arraycopy(scratch, averagedFloats, clusterVerts, base + averagedFloats, stride - averagedFloats);
                }
            }

            float[] cellPos = placeCellRepresentatives(cellCompact, usedCells, cellSums);

            // Representative vertex: the shared cell position plus the cluster's own averaged UVs, colors, and tangent
            // frame. Clusters split by a UV seam share their cell's position, so seams stay watertight.
            for (int k = 0; k < usedClusters; k++) {
                int base = k * stride;
                float inv = 1.0f / Math.max(1, clusterCounts[k]);
                for (int i = 0; i < averagedFloats; i++) clusterVerts[base + i] *= inv;
                int cs = clusterCell[k] * 3;
                clusterVerts[base] = cellPos[cs];
                clusterVerts[base + 1] = cellPos[cs + 1];
                clusterVerts[base + 2] = cellPos[cs + 2];
                // Fallback normal for corners whose faces are all degenerate; overwritten below otherwise
                normalize3(clusterVerts, base + 3);
                normalize3(clusterVerts, base + 14); // tangent (tx, ty, tz)
                normalize3(clusterVerts, base + 17); // bitangent (bx, by, bz)
            }

            // Decimated face normals; the cross product's length is twice the area, which gives area weighting
            float[] faceN = new float[faceCount * 3];
            int[] faceCell = new int[faceCount * 3];
            for (int f = 0; f < faceCount; f++) {
                int p0 = clusterCompact[faces[f * 6]] * stride, p1 = clusterCompact[faces[f * 6 + 1]] * stride, p2 = clusterCompact[faces[f * 6 + 2]] * stride;
                float e1x = clusterVerts[p1] - clusterVerts[p0], e1y = clusterVerts[p1 + 1] - clusterVerts[p0 + 1], e1z = clusterVerts[p1 + 2] - clusterVerts[p0 + 2];
                float e2x = clusterVerts[p2] - clusterVerts[p0], e2y = clusterVerts[p2 + 1] - clusterVerts[p0 + 1], e2z = clusterVerts[p2 + 2] - clusterVerts[p0 + 2];
                float fnx = e1y * e2z - e1z * e2y;
                float fny = e1z * e2x - e1x * e2z;
                float fnz = e1x * e2y - e1y * e2x;

                // A face that folded over during the collapse points against the source triangle. Orient its
                // contribution to match the source so a fold doesn't cancel out its neighbours' normals.
                int s0 = faces[f * 6 + 3] * 3, s1 = faces[f * 6 + 4] * 3, s2 = faces[f * 6 + 5] * 3;
                float o1x = pos[s1] - pos[s0], o1y = pos[s1 + 1] - pos[s0 + 1], o1z = pos[s1 + 2] - pos[s0 + 2];
                float o2x = pos[s2] - pos[s0], o2y = pos[s2 + 1] - pos[s0 + 1], o2z = pos[s2 + 2] - pos[s0 + 2];
                float onx = o1y * o2z - o1z * o2y;
                float ony = o1z * o2x - o1x * o2z;
                float onz = o1x * o2y - o1y * o2x;
                if (fnx * onx + fny * ony + fnz * onz < 0.0f) {
                    fnx = -fnx; fny = -fny; fnz = -fnz;
                }
                faceN[f * 3] = fnx; faceN[f * 3 + 1] = fny; faceN[f * 3 + 2] = fnz;
                for (int k = 0; k < 3; k++) faceCell[f * 3 + k] = cellCompact[vertCell[faces[f * 6 + 3 + k]]];
            }

            float[] finalVerts = new float[faceCount * 3 * stride];
            for (int f = 0; f < faceCount; f++) {
                for (int k = 0; k < 3; k++) {
                    System.arraycopy(clusterVerts, clusterCompact[faces[f * 6 + k]] * stride, finalVerts, (f * 3 + k) * stride, stride);
                }
            }
            // Corners gather neighbours by cell rather than UV cluster, so shading is continuous across UV seams
            writeCreaseNormals(finalVerts, stride, faceCount, faceN, faceCell, usedCells);
            return new MeshBufferData(finalVerts, faceCount * 3);
        }

        /**
         * Places each kept cell's shared vertex at the point minimizing its quadric error (Garland-Heckbert planes,
         * as in Lindstrom's out-of-core vertex clustering) instead of at the centroid of its vertices. A centroid sits
         * inside the surface wherever the cell straddles an edge or corner and pulls open borders inward, which
         * shrinks every part and opens gaps where separate parts used to touch. Each source triangle adds its plane,
         * weighted by area, to its corner cells, and every open border edge adds a perpendicular plane that pins the
         * border in place. The solve is lightly regularized toward the centroid, then clamped to the bounds of the
         * cell's own vertices: where unrelated sheets such as two leaves cross one cell, their planes meet away from
         * both, and the clamp keeps the vertex among the geometry it stands for.
         */
        private float[] placeCellRepresentatives(int[] cellCompact, int usedCells, float[] cellSums) {
            double[] q = new double[usedCells * 11]; // a00 a01 a02 a11 a12 a22 b0 b1 b2 c, total plane weight
            long[] boundary = findBoundaryEdges();
            int[] tri = new int[3];
            for (int t = 0; t < src.triangleCount(); t++) {
                if (!src.triangle(t, tri)) continue;
                int c0 = cellCompact[vertCell[tri[0]]], c1 = cellCompact[vertCell[tri[1]]], c2 = cellCompact[vertCell[tri[2]]];
                if (c0 < 0 && c1 < 0 && c2 < 0) continue;
                int s0 = tri[0] * 3, s1 = tri[1] * 3, s2 = tri[2] * 3;
                double e1x = pos[s1] - pos[s0], e1y = pos[s1 + 1] - pos[s0 + 1], e1z = pos[s1 + 2] - pos[s0 + 2];
                double e2x = pos[s2] - pos[s0], e2y = pos[s2 + 1] - pos[s0 + 1], e2z = pos[s2 + 2] - pos[s0 + 2];
                double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-30) continue;
                double area = 0.5 * len;
                nx /= len; ny /= len; nz /= len;
                double d = -(nx * pos[s0] + ny * pos[s0 + 1] + nz * pos[s0 + 2]);
                if (c0 >= 0) addPlane(q, c0, nx, ny, nz, d, area);
                if (c1 >= 0 && c1 != c0) addPlane(q, c1, nx, ny, nz, d, area);
                if (c2 >= 0 && c2 != c0 && c2 != c1) addPlane(q, c2, nx, ny, nz, d, area);

                if (boundary.length == 0) continue;
                for (int k = 0; k < 3; k++) {
                    int va = tri[k], vb = tri[(k + 1) % 3];
                    if (Arrays.binarySearch(boundary, edgeKey(weldId[va], weldId[vb])) < 0) continue;
                    int ca = cellCompact[vertCell[va]], cb = cellCompact[vertCell[vb]];
                    if (ca < 0 && cb < 0) continue;
                    int a3 = va * 3, b3 = vb * 3;
                    double ex = pos[b3] - pos[a3], ey = pos[b3 + 1] - pos[a3 + 1], ez = pos[b3 + 2] - pos[a3 + 2];
                    // Plane through the edge, perpendicular to the face: (edge x faceNormal)
                    double px = ey * nz - ez * ny, py = ez * nx - ex * nz, pz = ex * ny - ey * nx;
                    double pl = Math.sqrt(px * px + py * py + pz * pz);
                    if (pl < 1e-30) continue;
                    double w = BOUNDARY_WEIGHT * pl * pl; // pl = |edge| since the face normal is unit and orthogonal
                    px /= pl; py /= pl; pz /= pl;
                    double pd = -(px * pos[a3] + py * pos[a3 + 1] + pz * pos[a3 + 2]);
                    if (ca >= 0) addPlane(q, ca, px, py, pz, pd, w);
                    if (cb >= 0 && cb != ca) addPlane(q, cb, px, py, pz, pd, w);
                }
            }

            float[] bounds = new float[usedCells * 6];
            for (int c = 0; c < usedCells; c++) {
                Arrays.fill(bounds, c * 6, c * 6 + 3, Float.MAX_VALUE);
                Arrays.fill(bounds, c * 6 + 3, c * 6 + 6, -Float.MAX_VALUE);
            }
            for (int v = 0; v < n; v++) {
                int c = cellCompact[vertCell[v]];
                if (c < 0) continue;
                for (int k = 0; k < 3; k++) {
                    bounds[c * 6 + k] = Math.min(bounds[c * 6 + k], pos[v * 3 + k]);
                    bounds[c * 6 + 3 + k] = Math.max(bounds[c * 6 + 3 + k], pos[v * 3 + k]);
                }
            }

            float[] out = new float[usedCells * 3];
            for (int c = 0; c < usedCells; c++) {
                float cnt = Math.max(1.0f, cellSums[c * 4 + 3]);
                double mx = cellSums[c * 4] / cnt, my = cellSums[c * 4 + 1] / cnt, mz = cellSums[c * 4 + 2] / cnt;
                int o = c * 11;
                double a00 = q[o], a01 = q[o + 1], a02 = q[o + 2], a11 = q[o + 3], a12 = q[o + 4], a22 = q[o + 5];
                double lambda = QEM_REGULARIZATION * (a00 + a11 + a22) / 3.0;
                double rx = mx, ry = my, rz = mz;
                if (lambda > 0.0) {
                    // Solve (A + lambda I) x = -b + lambda m; positive definite, so the 3x3 cofactor inverse is stable
                    a00 += lambda; a11 += lambda; a22 += lambda;
                    double r0 = -q[o + 6] + lambda * mx, r1 = -q[o + 7] + lambda * my, r2 = -q[o + 8] + lambda * mz;
                    double i00 = a11 * a22 - a12 * a12, i01 = a02 * a12 - a01 * a22, i02 = a01 * a12 - a02 * a11;
                    double det = a00 * i00 + a01 * i01 + a02 * i02;
                    if (Math.abs(det) > 1e-300) {
                        double i11 = a00 * a22 - a02 * a02, i12 = a01 * a02 - a00 * a12, i22 = a00 * a11 - a01 * a01;
                        double sx = (i00 * r0 + i01 * r1 + i02 * r2) / det;
                        double sy = (i01 * r0 + i11 * r1 + i12 * r2) / det;
                        double sz = (i02 * r0 + i12 * r1 + i22 * r2) / det;
                        if (Double.isFinite(sx) && Double.isFinite(sy) && Double.isFinite(sz) && q[o + 10] > 0.0) {
                            // RMS distance from the solution to the cell's planes. A real edge or corner fits its
                            // planes almost exactly; unrelated sheets crossing one cell do not, so those cells blend
                            // back toward the centroid instead of snapping to a plane intersection between sheets.
                            double e = sx * (q[o] * sx + 2.0 * (q[o + 1] * sy + q[o + 2] * sz)) + sy * (q[o + 3] * sy + 2.0 * q[o + 4] * sz)
                                    + q[o + 5] * sz * sz + 2.0 * (q[o + 6] * sx + q[o + 7] * sy + q[o + 8] * sz) + q[o + 9];
                            double rms = Math.sqrt(Math.max(0.0, e / q[o + 10]));
                            double trust = Math.max(0.0, Math.min(1.0, 1.0 - rms / (QEM_TRUST_FRACTION * cellSize)));
                            rx = mx + (sx - mx) * trust; ry = my + (sy - my) * trust; rz = mz + (sz - mz) * trust;
                        }
                    }
                }
                out[c * 3] = clamp((float) rx, bounds[c * 6], bounds[c * 6 + 3]);
                out[c * 3 + 1] = clamp((float) ry, bounds[c * 6 + 1], bounds[c * 6 + 4]);
                out[c * 3 + 2] = clamp((float) rz, bounds[c * 6 + 2], bounds[c * 6 + 5]);
            }
            return out;
        }

        /**
         * Sorted keys of edges used by exactly one source triangle (open borders), on welded vertex ids so UV and
         * normal seams, which split vertices but not the surface, never read as borders.
         */
        private long[] findBoundaryEdges() {
            int[] tri = new int[3];
            long[] edges = new long[src.triangleCount() * 3];
            int count = 0;
            for (int t = 0; t < src.triangleCount(); t++) {
                if (!src.triangle(t, tri)) continue;
                for (int k = 0; k < 3; k++) {
                    int a = weldId[tri[k]], b = weldId[tri[(k + 1) % 3]];
                    if (a != b) edges[count++] = edgeKey(a, b);
                }
            }
            Arrays.sort(edges, 0, count);
            int out = 0;
            for (int i = 0; i < count; ) {
                int j = i + 1;
                while (j < count && edges[j] == edges[i]) j++;
                if (j - i == 1) edges[out++] = edges[i];
                i = j;
            }
            return Arrays.copyOf(edges, out);
        }

        private static long edgeKey(int a, int b) {
            return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
        }

        private static void addPlane(double[] q, int c, double nx, double ny, double nz, double d, double w) {
            int o = c * 11;
            q[o] += w * nx * nx; q[o + 1] += w * nx * ny; q[o + 2] += w * nx * nz;
            q[o + 3] += w * ny * ny; q[o + 4] += w * ny * nz; q[o + 5] += w * nz * nz;
            q[o + 6] += w * nx * d; q[o + 7] += w * ny * d; q[o + 8] += w * nz * d;
            q[o + 9] += w * d * d;
            q[o + 10] += w;
        }

        private static float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /** Open-addressing long -> int map on primitive arrays; values must be non-negative. */
    private static final class LongIntMap {
        final long[] keys;
        final int[] vals;
        final int mask;
        int size;

        LongIntMap(int expected) {
            int capacity = Integer.highestOneBit(Math.max(16, expected + (expected >> 1)) - 1) << 1;
            keys = new long[capacity];
            vals = new int[capacity];
            mask = capacity - 1;
            Arrays.fill(vals, -1);
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(vals, -1);
            size = 0;
        }

        /** Returns the existing value for key, or inserts and returns value. */
        int getOrPut(long key, int value) {
            int i = (int) mix(key) & mask;
            while (vals[i] != -1) {
                if (keys[i] == key) return vals[i];
                i = (i + 1) & mask;
            }
            keys[i] = key;
            vals[i] = value;
            size++;
            return value;
        }
    }

    static void normalize3(float[] v, int offset) {
        float len = (float) Math.sqrt(v[offset] * v[offset] + v[offset + 1] * v[offset + 1] + v[offset + 2] * v[offset + 2]);
        if (len > 0.0001f) {
            v[offset] /= len; v[offset + 1] /= len; v[offset + 2] /= len;
        }
    }
}
