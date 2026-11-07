package com.retro.engine.navigation;

/**
 * A* over the {@link NavMesh} polygon graph followed by funnel (string-pulling) smoothing. All scratch storage is sized
 * to the mesh once in the constructor; {@link #findPath} never allocates. Not thread-safe: one instance per mesh, used
 * from the simulation thread.
 */
public final class NavMeshQuery {

    /** Horizontal / vertical search extents used to snap query points onto the mesh. */
    public static final float SNAP_EXTENT_XZ = 3.0f;
    public static final float SNAP_EXTENT_Y = 4.0f;

    private static final byte OPEN = 1, CLOSED = 2;

    private final NavMesh mesh;

    // Per-poly node state, lazily reset by search stamp
    private final int[] stamp;
    private final byte[] state;
    private final float[] g, f, nodeX, nodeY, nodeZ;
    private final int[] parent, parentLink;
    private int searchId = 0;

    // Indexed binary min-heap on f
    private final int[] heap, heapPos;
    private int heapSize;

    // Corridor + funnel portals (index 0 = start point, last = end point)
    private final int[] corridor;
    private final float[] plx, plz, ply, prx, prz, pry;

    private final float[] snapScratch = new float[3];
    private final boolean[] gateClosed;

    /** True when the last {@link #findPath} could not reach the goal and returned a path to the closest reachable poly. */
    public boolean lastPathPartial;
    /** True when the last {@link #findPath} ran out of corner capacity before reaching its end point. */
    public boolean lastPathTruncated;

    public NavMeshQuery(NavMesh mesh) {
        this.mesh = mesh;
        int n = Math.max(1, mesh.polyCount);
        stamp = new int[n];
        state = new byte[n];
        g = new float[n]; f = new float[n];
        nodeX = new float[n]; nodeY = new float[n]; nodeZ = new float[n];
        parent = new int[n]; parentLink = new int[n];
        heap = new int[n]; heapPos = new int[n];
        corridor = new int[n];
        plx = new float[n + 1]; plz = new float[n + 1]; ply = new float[n + 1];
        prx = new float[n + 1]; prz = new float[n + 1]; pry = new float[n + 1];
        gateClosed = new boolean[mesh.gateIds.length];
    }

    public NavMesh getMesh() { return mesh; }

    public boolean isGateOpen(int gate) { return !gateClosed[gate]; }

    public void setGateOpen(int gate, boolean open) { gateClosed[gate] = !open; }

    /**
     * Finds a smoothed path from start to end and writes its corners (x, y, z triples, start excluded, end included)
     * into {@code outCorners}. Returns the corner count, or -1 when start or end cannot be placed on the mesh.
     * When more than {@code maxCorners} corners are needed the path is truncated (the caller should re-plan from the
     * last corner); {@link #lastPathPartial} is set when the goal was unreachable. Closed gates can't be entered, but a
     * path that starts inside one can still walk out of it.
     */
    public int findPath(float sx, float sy, float sz, float ex, float ey, float ez, float[] outCorners, int maxCorners) {
        lastPathPartial = false;
        lastPathTruncated = false;
        float[] s = snapScratch;
        int startPoly = mesh.findNearestPoly(sx, sy, sz, SNAP_EXTENT_XZ, SNAP_EXTENT_Y, s);
        if (startPoly < 0) return -1;
        float spx = s[0], spy = s[1], spz = s[2];
        int endPoly = mesh.findNearestPoly(ex, ey, ez, SNAP_EXTENT_XZ, SNAP_EXTENT_Y, s);
        if (endPoly < 0) return -1;
        float epx = s[0], epy = s[1], epz = s[2];

        int reached = search(startPoly, spx, spy, spz, endPoly, epx, epy, epz);
        if (reached != endPoly) {
            lastPathPartial = true;
            // Aim for the point on the closest reachable poly nearest to the requested goal
            epx = Math.max(mesh.polyMinX[reached], Math.min(mesh.polyMaxX[reached], ex));
            epz = Math.max(mesh.polyMinZ[reached], Math.min(mesh.polyMaxZ[reached], ez));
            epy = mesh.polyY[reached];
        }

        // Corridor start -> reached
        int len = 0;
        for (int p = reached; p >= 0; p = parent[p]) len++;
        int idx = len;
        for (int p = reached; p >= 0; p = parent[p]) corridor[--idx] = p;

        return stringPull(len, spx, spy, spz, epx, epy, epz, outCorners, maxCorners);
    }

    // ------------------------------------------------------------------------------------------
    // A*
    // ------------------------------------------------------------------------------------------

    /** Returns endPoly when reached, otherwise the closed poly with the smallest heuristic to the goal. */
    private int search(int startPoly, float sx, float sy, float sz, int endPoly, float ex, float ey, float ez) {
        if (++searchId == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);
            searchId = 1;
        }
        heapSize = 0;
        int startGate = mesh.polyGate[startPoly];
        touch(startPoly);
        g[startPoly] = 0.0f;
        nodeX[startPoly] = sx; nodeY[startPoly] = sy; nodeZ[startPoly] = sz;
        float h0 = dist(sx, sy, sz, ex, ey, ez);
        f[startPoly] = h0;
        parent[startPoly] = -1;
        parentLink[startPoly] = -1;
        state[startPoly] = OPEN;
        heapPush(startPoly);

        int best = startPoly;
        float bestH = h0;

        while (heapSize > 0) {
            int cur = heapPop();
            state[cur] = CLOSED;
            float h = f[cur] - g[cur];
            if (h < bestH) { bestH = h; best = cur; }
            if (cur == endPoly) return endPoly;

            for (int l = mesh.linkStart[cur], le = mesh.linkStart[cur + 1]; l < le; l++) {
                int nb = mesh.linkPoly[l];
                int gate = mesh.polyGate[nb];
                if (gate >= 0 && gate != startGate && gateClosed[gate]) continue;
                touch(nb);
                if (state[nb] == CLOSED) continue;
                // Node position: portal midpoint (goal point for the end poly)
                float mx, my, mz;
                if (nb == endPoly) {
                    mx = ex; my = ey; mz = ez;
                } else {
                    mx = (mesh.linkAx[l] + mesh.linkBx[l]) * 0.5f;
                    mz = (mesh.linkAz[l] + mesh.linkBz[l]) * 0.5f;
                    my = mesh.polyY[nb];
                }
                float ng = g[cur] + dist(nodeX[cur], nodeY[cur], nodeZ[cur], mx, my, mz);
                if (state[nb] == OPEN && ng >= g[nb]) continue;
                g[nb] = ng;
                f[nb] = ng + dist(mx, my, mz, ex, ey, ez);
                nodeX[nb] = mx; nodeY[nb] = my; nodeZ[nb] = mz;
                parent[nb] = cur;
                parentLink[nb] = l;
                if (state[nb] == OPEN) {
                    heapUp(heapPos[nb]);
                } else {
                    state[nb] = OPEN;
                    heapPush(nb);
                }
            }
        }
        return best;
    }

    private void touch(int p) {
        if (stamp[p] != searchId) {
            stamp[p] = searchId;
            state[p] = 0;
        }
    }

    private static float dist(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void heapPush(int p) {
        heap[heapSize] = p;
        heapPos[p] = heapSize;
        heapUp(heapSize++);
    }

    private int heapPop() {
        int top = heap[0];
        heapSize--;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            heapPos[heap[0]] = 0;
            heapDown(0);
        }
        return top;
    }

    private void heapUp(int i) {
        int p = heap[i];
        float key = f[p];
        while (i > 0) {
            int pi = (i - 1) >> 1;
            int q = heap[pi];
            if (f[q] <= key) break;
            heap[i] = q; heapPos[q] = i;
            i = pi;
        }
        heap[i] = p; heapPos[p] = i;
    }

    private void heapDown(int i) {
        int p = heap[i];
        float key = f[p];
        while (true) {
            int c = 2 * i + 1;
            if (c >= heapSize) break;
            if (c + 1 < heapSize && f[heap[c + 1]] < f[heap[c]]) c++;
            int q = heap[c];
            if (f[q] >= key) break;
            heap[i] = q; heapPos[q] = i;
            i = c;
        }
        heap[i] = p; heapPos[p] = i;
    }

    // ------------------------------------------------------------------------------------------
    // Funnel / string pulling
    // ------------------------------------------------------------------------------------------

    private int stringPull(int len, float sx, float sy, float sz, float ex, float ey, float ez, float[] out, int maxCorners) {
        // Build portals: 0 = start, 1..len-1 = shared edges along the corridor, len = end
        plx[0] = prx[0] = sx; plz[0] = prz[0] = sz; ply[0] = pry[0] = sy;
        for (int i = 1; i < len; i++) {
            int from = corridor[i - 1], to = corridor[i];
            int l = parentLink[to];
            float ax = mesh.linkAx[l], az = mesh.linkAz[l], bx = mesh.linkBx[l], bz = mesh.linkBz[l];
            float dx = mesh.centerX(to) - mesh.centerX(from), dz = mesh.centerZ(to) - mesh.centerZ(from);
            // side > 0 is left of the travel direction (see triArea2)
            float sa = dx * (az - mesh.centerZ(from)) - dz * (ax - mesh.centerX(from));
            float sb = dx * (bz - mesh.centerZ(from)) - dz * (bx - mesh.centerX(from));
            float y = Math.max(mesh.polyY[from], mesh.polyY[to]);
            if (sa >= sb) {
                plx[i] = ax; plz[i] = az; prx[i] = bx; prz[i] = bz;
            } else {
                plx[i] = bx; plz[i] = bz; prx[i] = ax; prz[i] = az;
            }
            ply[i] = pry[i] = y;
        }
        plx[len] = prx[len] = ex; plz[len] = prz[len] = ez; ply[len] = pry[len] = ey;
        int n = len + 1;

        int count = 0;
        float apexX = plx[0], apexZ = plz[0];
        float leftX = apexX, leftZ = apexZ, rightX = apexX, rightZ = apexZ;
        int apexIndex = 0, leftIndex = 0, rightIndex = 0;

        for (int i = 1; i < n && count < maxCorners; i++) {
            float lx = plx[i], lz = plz[i], rx = prx[i], rz = prz[i];

            // Tighten the right side
            if (triArea2(apexX, apexZ, rightX, rightZ, rx, rz) <= 0.0f) {
                if (same(apexX, apexZ, rightX, rightZ) || triArea2(apexX, apexZ, leftX, leftZ, rx, rz) > 0.0f) {
                    rightX = rx; rightZ = rz; rightIndex = i;
                } else {
                    // Right crossed left: the left vertex becomes a corner
                    count = addCorner(out, count, leftX, ply[leftIndex], leftZ);
                    apexX = leftX; apexZ = leftZ; apexIndex = leftIndex;
                    leftX = rightX = apexX; leftZ = rightZ = apexZ;
                    leftIndex = rightIndex = apexIndex;
                    i = apexIndex;
                    continue;
                }
            }
            // Tighten the left side
            if (triArea2(apexX, apexZ, leftX, leftZ, lx, lz) >= 0.0f) {
                if (same(apexX, apexZ, leftX, leftZ) || triArea2(apexX, apexZ, rightX, rightZ, lx, lz) < 0.0f) {
                    leftX = lx; leftZ = lz; leftIndex = i;
                } else {
                    count = addCorner(out, count, rightX, pry[rightIndex], rightZ);
                    apexX = rightX; apexZ = rightZ; apexIndex = rightIndex;
                    leftX = rightX = apexX; leftZ = rightZ = apexZ;
                    leftIndex = rightIndex = apexIndex;
                    i = apexIndex;
                }
            }
        }
        if (count < maxCorners) {
            count = addCorner(out, count, ex, ey, ez);
        } else {
            int p = (count - 1) * 3;
            lastPathTruncated = !same(out[p], out[p + 2], ex, ez);
        }
        return count;
    }

    /** Twice the signed area of (a, b, c) in the XZ plane; matches the left/right portal ordering above. */
    private static float triArea2(float ax, float az, float bx, float bz, float cx, float cz) {
        return (cx - ax) * (bz - az) - (bx - ax) * (cz - az);
    }

    private static boolean same(float ax, float az, float bx, float bz) {
        float dx = ax - bx, dz = az - bz;
        return dx * dx + dz * dz < 1e-8f;
    }

    private static int addCorner(float[] out, int count, float x, float y, float z) {
        if (count > 0) {
            int p = (count - 1) * 3;
            if (same(out[p], out[p + 2], x, z)) {
                out[p + 1] = y;
                return count;
            }
        }
        int o = count * 3;
        out[o] = x; out[o + 1] = y; out[o + 2] = z;
        return count + 1;
    }
}
