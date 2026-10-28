package com.retro.engine.assets.importers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel) that coarsens the grid until the output strictly fits the
 * RenderWare 3.5 budget, preserving UV seams, normals, and tangent frames. Sources are read through VertexSource so
 * the full-resolution triangle soup is never materialized; all decimation state lives in primitive arrays.
 */
final class MeshDecimator {

    // UV bins per axis for decimation clustering. Vertices whose UVs fall in different bins are never merged, which
    // keeps texture seams and atlas boundaries intact. Finer bins catch narrower seams but split more cells inside
    // a continuous UV island.
    private static final int UV_SEAM_BINS = 16;
    // Finest decimation grid tried first; the grid is coarsened until the decimated mesh fits the vertex budget
    private static final int MAX_GRID_RES = 96;

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
                return decimatePerSubMesh(grouped, maxTargetVerts);
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
     * Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel Algorithm):
     * Maps vertices to cubic voxel cells, split by quantized UV bin so seams never blend, rebuilds non-degenerate
     * faces, and recomputes normals from the decimated face geometry. The grid starts fine and is coarsened until the
     * unique surviving faces fit maxTargetVerts, so the output never exceeds the budget. All per-vertex state lives in
     * primitive arrays sized to the indexed source, never to the expanded soup.
     */
    static MeshBufferData decimate(VertexSource src, int maxTargetVerts, boolean verbose) {
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
        if (verbose) System.out.println("[MeshDecimator] Successfully decimated high-poly mesh from " + src.triangleCount() * 3L + " to "
                + data.vertexCount + " vertices (grid " + g + ", RW 3.5 Target: " + maxTargetVerts + ").");
        return data;
    }

    private static final class Decimator {
        final VertexSource src;
        final int n, stride, targetFaces;
        final float[] pos;        // source positions, xyz per vertex
        final long[] uvBin;       // quantized primary UV bin per vertex
        final int[] vertCell;
        final int[] vertCluster;
        final LongIntMap clusterMap;
        final LongIntMap faceSet;
        final int[] faces;        // collected faces: c0, c1, c2, v0, v1, v2
        final float minX, minY, minZ, extentX, extentY, extentZ, maxExtent;
        int clusterCount;
        int gridX, gridY, gridZ;

        Decimator(VertexSource src, int n, int stride, int targetFaces) {
            this.src = src;
            this.n = n;
            this.stride = stride;
            this.targetFaces = targetFaces;
            this.pos = new float[n * 3];
            this.uvBin = new long[n];
            this.vertCell = new int[n];
            this.vertCluster = new int[n];
            this.clusterMap = new LongIntMap(n);
            this.faceSet = new LongIntMap(targetFaces * 4 + 1);
            this.faces = new int[targetFaces * 6];

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
            float cell = maxExtent / g;
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

            // A face survives only if its 3 corners fall in distinct cells; two UV clusters from the same cell share
            // one position and would form a zero-area triangle. Faces collapsing onto the same clusters are kept once.
            int limit = collect ? targetFaces : targetFaces * 4;
            boolean packed = clusterCount < (1 << 21);
            faceSet.clear();
            int count = 0;
            int[] tri = new int[3];
            for (int t = 0; t < src.triangleCount(); t++) {
                if (!src.triangle(t, tri)) continue;
                int v0 = tri[0], v1 = tri[1], v2 = tri[2];
                int h0 = vertCell[v0], h1 = vertCell[v1], h2 = vertCell[v2];
                if (h0 == h1 || h1 == h2 || h2 == h0) continue;

                int c0 = vertCluster[v0], c1 = vertCluster[v1], c2 = vertCluster[v2];
                // Rotate so the smallest cluster leads; keeps winding while making the key canonical
                int a = c0, b = c1, c = c2;
                if (b < a && b < c) { a = c1; b = c2; c = c0; }
                else if (c < a && c < b) { a = c2; b = c0; c = c1; }
                long key = packed
                        ? ((long) a << 42) | ((long) b << 21) | c
                        : mix(mix(a * 0x9E3779B97F4A7C15L ^ b) ^ c);
                int before = faceSet.size;
                faceSet.getOrPut(key, 0);
                if (faceSet.size == before) continue;

                if (count >= limit) return collect ? count : -1;
                if (collect) {
                    int f = count * 6;
                    faces[f] = c0; faces[f + 1] = c1; faces[f + 2] = c2;
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

            // Representative vertex: the shared cell centroid as its position plus the cluster's own averaged
            // UVs, colors, and tangent frame
            for (int k = 0; k < usedClusters; k++) {
                int base = k * stride;
                float inv = 1.0f / Math.max(1, clusterCounts[k]);
                for (int i = 0; i < averagedFloats; i++) clusterVerts[base + i] *= inv;
                int cs = clusterCell[k] * 4;
                float cnt = Math.max(1.0f, cellSums[cs + 3]);
                clusterVerts[base] = cellSums[cs] / cnt;
                clusterVerts[base + 1] = cellSums[cs + 1] / cnt;
                clusterVerts[base + 2] = cellSums[cs + 2] / cnt;
                // Normals are rebuilt from the decimated faces below; the averaged source normal is kept only as a
                // fallback for clusters whose faces are all degenerate
                normalize3(clusterVerts, base + 3);
                normalize3(clusterVerts, base + 14); // tangent (tx, ty, tz)
                normalize3(clusterVerts, base + 17); // bitangent (bx, by, bz)
            }

            float[] faceNormalSums = new float[usedClusters * 3];
            for (int f = 0; f < faceCount; f++) {
                int k0 = clusterCompact[faces[f * 6]], k1 = clusterCompact[faces[f * 6 + 1]], k2 = clusterCompact[faces[f * 6 + 2]];
                int p0 = k0 * stride, p1 = k1 * stride, p2 = k2 * stride;

                // Face normal from the new triangle's edges; its length is twice the area, which gives area weighting
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

                for (int k : new int[] {k0, k1, k2}) {
                    faceNormalSums[k * 3] += fnx;
                    faceNormalSums[k * 3 + 1] += fny;
                    faceNormalSums[k * 3 + 2] += fnz;
                }
            }

            // Replace cluster normals with the area-weighted sum of their decimated face normals. Clusters on opposite
            // sides of a UV seam accumulate separately, so seams keep their hard shading edge.
            for (int k = 0; k < usedClusters; k++) {
                float nx = faceNormalSums[k * 3], ny = faceNormalSums[k * 3 + 1], nz = faceNormalSums[k * 3 + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-12f) {
                    int base = k * stride;
                    clusterVerts[base + 3] = nx / len; clusterVerts[base + 4] = ny / len; clusterVerts[base + 5] = nz / len;
                }
            }

            float[] finalVerts = new float[faceCount * 3 * stride];
            int dst = 0;
            for (int f = 0; f < faceCount; f++) {
                for (int k = 0; k < 3; k++) {
                    System.arraycopy(clusterVerts, clusterCompact[faces[f * 6 + k]] * stride, finalVerts, dst, stride);
                    dst += stride;
                }
            }
            return new MeshBufferData(finalVerts, faceCount * 3);
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
