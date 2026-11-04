package com.retro.engine.navigation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Baked navigation mesh: axis-aligned walkable rectangles (convex polygons, each at a single height) linked by shared
 * edge portals, plus a column grid for O(1) point location. Produced by {@link NavMeshBaker}, persisted as
 * {@code .navmesh} next to the scene, and queried by {@link NavMeshQuery}. Immutable after construction, so every
 * query on it is read-only and allocation-free.
 */
public final class NavMesh {

    private static final int MAGIC = 0x524E4156; // "RNAV"
    private static final int VERSION = 1;

    // Bake settings the mesh was generated with (informational, shown in the baker panel)
    public final float cellSize, cellHeight, agentHeight, agentRadius, maxSlope, stepHeight;

    // Polygons: rect [minX,maxX] x [minZ,maxZ] at height y
    public final int polyCount;
    public final float[] polyMinX, polyMinZ, polyMaxX, polyMaxZ, polyY;

    // Adjacency (CSR): links of poly p are [linkStart[p], linkStart[p+1]); portal segment A-B on the shared edge
    public final int[] linkStart, linkPoly;
    public final float[] linkAx, linkAz, linkBx, linkBz;

    // Point-location grid (CSR): polys covering column (cx, cz) are colPolys[colStart[c] .. colStart[c+1])
    public final float originX, originZ;
    public final int gridW, gridH;
    public final int[] colStart, colPolys;

    NavMesh(float cellSize, float cellHeight, float agentHeight, float agentRadius, float maxSlope, float stepHeight,
            int polyCount, float[] polyMinX, float[] polyMinZ, float[] polyMaxX, float[] polyMaxZ, float[] polyY,
            int[] linkStart, int[] linkPoly, float[] linkAx, float[] linkAz, float[] linkBx, float[] linkBz,
            float originX, float originZ, int gridW, int gridH, int[] colStart, int[] colPolys) {
        this.cellSize = cellSize; this.cellHeight = cellHeight; this.agentHeight = agentHeight;
        this.agentRadius = agentRadius; this.maxSlope = maxSlope; this.stepHeight = stepHeight;
        this.polyCount = polyCount;
        this.polyMinX = polyMinX; this.polyMinZ = polyMinZ; this.polyMaxX = polyMaxX; this.polyMaxZ = polyMaxZ; this.polyY = polyY;
        this.linkStart = linkStart; this.linkPoly = linkPoly;
        this.linkAx = linkAx; this.linkAz = linkAz; this.linkBx = linkBx; this.linkBz = linkBz;
        this.originX = originX; this.originZ = originZ; this.gridW = gridW; this.gridH = gridH;
        this.colStart = colStart; this.colPolys = colPolys;
    }

    public int getLinkCount() { return linkPoly.length; }

    public float centerX(int p) { return (polyMinX[p] + polyMaxX[p]) * 0.5f; }
    public float centerZ(int p) { return (polyMinZ[p] + polyMaxZ[p]) * 0.5f; }

    /**
     * Poly whose rect contains (x, z) and whose surface is closest to {@code y}, or -1. Only polys within
     * {@code maxDy} vertically are considered.
     */
    public int findPoly(float x, float y, float z, float maxDy) {
        int cx = (int) Math.floor((x - originX) / cellSize);
        int cz = (int) Math.floor((z - originZ) / cellSize);
        if (cx < 0 || cz < 0 || cx >= gridW || cz >= gridH) return -1;
        int c = cz * gridW + cx;
        int best = -1;
        float bestDy = maxDy;
        for (int i = colStart[c], n = colStart[c + 1]; i < n; i++) {
            int p = colPolys[i];
            float dy = Math.abs(polyY[p] - y);
            if (dy <= bestDy && x >= polyMinX[p] && x <= polyMaxX[p] && z >= polyMinZ[p] && z <= polyMaxZ[p]) {
                bestDy = dy;
                best = p;
            }
        }
        return best;
    }

    /**
     * Nearest poly to (x, y, z) within {@code extentXZ} horizontally and {@code extentY} vertically, or -1. The closest
     * point on it is written to {@code out} (x, y, z). Allocation-free.
     */
    public int findNearestPoly(float x, float y, float z, float extentXZ, float extentY, float[] out) {
        int c0x = Math.max(0, (int) Math.floor((x - extentXZ - originX) / cellSize));
        int c1x = Math.min(gridW - 1, (int) Math.floor((x + extentXZ - originX) / cellSize));
        int c0z = Math.max(0, (int) Math.floor((z - extentXZ - originZ) / cellSize));
        int c1z = Math.min(gridH - 1, (int) Math.floor((z + extentXZ - originZ) / cellSize));
        int best = -1;
        float bestD = Float.MAX_VALUE;
        float bx = x, by = y, bz = z;
        for (int cz = c0z; cz <= c1z; cz++) {
            for (int cx = c0x; cx <= c1x; cx++) {
                int c = cz * gridW + cx;
                for (int i = colStart[c], n = colStart[c + 1]; i < n; i++) {
                    int p = colPolys[i];
                    float dy = polyY[p] - y;
                    if (dy > extentY || dy < -extentY) continue;
                    float px = Math.max(polyMinX[p], Math.min(polyMaxX[p], x));
                    float pz = Math.max(polyMinZ[p], Math.min(polyMaxZ[p], z));
                    float dx = px - x, dz = pz - z;
                    float d = dx * dx + dz * dz + dy * dy;
                    if (d < bestD) {
                        bestD = d; best = p;
                        bx = px; by = polyY[p]; bz = pz;
                    }
                }
            }
        }
        if (best >= 0 && out != null) { out[0] = bx; out[1] = by; out[2] = bz; }
        return best;
    }

    // ------------------------------------------------------------------------------------------
    // Persistence (.navmesh)
    // ------------------------------------------------------------------------------------------

    /** The {@code .navmesh} file that belongs to a scene file ({@code Level.rscene -> Level.navmesh}). */
    public static String pathForScene(String scenePath) {
        if (scenePath == null) return null;
        int dot = scenePath.lastIndexOf('.');
        int slash = Math.max(scenePath.lastIndexOf('/'), scenePath.lastIndexOf(File.separatorChar));
        String base = (dot > slash) ? scenePath.substring(0, dot) : scenePath;
        return base + ".navmesh";
    }

    public void save(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeFloat(cellSize); out.writeFloat(cellHeight); out.writeFloat(agentHeight);
            out.writeFloat(agentRadius); out.writeFloat(maxSlope); out.writeFloat(stepHeight);
            out.writeInt(polyCount);
            writeFloats(out, polyMinX, polyCount); writeFloats(out, polyMinZ, polyCount);
            writeFloats(out, polyMaxX, polyCount); writeFloats(out, polyMaxZ, polyCount);
            writeFloats(out, polyY, polyCount);
            writeInts(out, linkStart, polyCount + 1);
            int links = linkPoly.length;
            out.writeInt(links);
            writeInts(out, linkPoly, links);
            writeFloats(out, linkAx, links); writeFloats(out, linkAz, links);
            writeFloats(out, linkBx, links); writeFloats(out, linkBz, links);
            out.writeFloat(originX); out.writeFloat(originZ);
            out.writeInt(gridW); out.writeInt(gridH);
            int cols = gridW * gridH;
            writeInts(out, colStart, cols + 1);
            out.writeInt(colPolys.length);
            writeInts(out, colPolys, colPolys.length);
        }
    }

    /** Loads a {@code .navmesh}; returns null when the file is missing or not a valid navmesh. */
    public static NavMesh load(File file) {
        if (file == null || !file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            int version = in.readInt();
            if (version != VERSION) return null;
            float cs = in.readFloat(), ch = in.readFloat(), ah = in.readFloat();
            float ar = in.readFloat(), ms = in.readFloat(), sh = in.readFloat();
            int pc = in.readInt();
            float[] minX = readFloats(in, pc), minZ = readFloats(in, pc), maxX = readFloats(in, pc), maxZ = readFloats(in, pc);
            float[] py = readFloats(in, pc);
            int[] ls = readInts(in, pc + 1);
            int links = in.readInt();
            int[] lp = readInts(in, links);
            float[] ax = readFloats(in, links), az = readFloats(in, links), bx = readFloats(in, links), bz = readFloats(in, links);
            float ox = in.readFloat(), oz = in.readFloat();
            int gw = in.readInt(), gh = in.readInt();
            int[] cst = readInts(in, gw * gh + 1);
            int[] cp = readInts(in, in.readInt());
            return new NavMesh(cs, ch, ah, ar, ms, sh, pc, minX, minZ, maxX, maxZ, py, ls, lp, ax, az, bx, bz, ox, oz, gw, gh, cst, cp);
        } catch (IOException | RuntimeException e) {
            System.err.println("[NavMesh] Failed to load '" + file + "': " + e);
            return null;
        }
    }

    private static void writeFloats(DataOutputStream out, float[] a, int n) throws IOException {
        for (int i = 0; i < n; i++) out.writeFloat(a[i]);
    }

    private static void writeInts(DataOutputStream out, int[] a, int n) throws IOException {
        for (int i = 0; i < n; i++) out.writeInt(a[i]);
    }

    private static float[] readFloats(DataInputStream in, int n) throws IOException {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = in.readFloat();
        return a;
    }

    private static int[] readInts(DataInputStream in, int n) throws IOException {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = in.readInt();
        return a;
    }
}
