package com.retro.engine.navigation;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Editor-time NavMesh generation from the scene's static colliders (Box / Sphere / Capsule colliders on entities with no
 * RigidBody or a STATIC one; triggers, characters, vehicles and agents are skipped).
 *
 * <ol>
 *   <li>Rasterize: every grid column casts a vertical line through each collider (oriented boxes, spheres; capsules as
 *       boxes with non-walkable tops) and records the solid intervals plus the slope of each top face.</li>
 *   <li>Filter: a solid top is walkable when its slope is within {@code maxSlope} and the gap to the next solid above
 *       fits {@code agentHeight}. Neighbouring walkable cells connect when their heights differ by at most
 *       {@code stepHeight}.</li>
 *   <li>Erode: cells closer than {@code agentRadius} to an unwalkable edge or drop-off are removed.</li>
 *   <li>Polygonize: cells are greedily merged into flat rectangles, and shared edges become portals.</li>
 * </ol>
 * Allocates freely: it runs once from the editor, never during a frame tick.
 */
public final class NavMeshBaker {

    public static final class Settings {
        public float cellSize = 0.2f;
        public float cellHeight = 0.1f;
        public float agentHeight = 2.0f;
        public float agentRadius = 0.4f;
        public float maxSlope = 45.0f;
        public float stepHeight = 0.4f;
    }

    /** Result of a bake: the mesh (null when nothing was walkable) plus a human-readable summary. */
    public record Result(NavMesh mesh, String message) {}

    private static final int MAX_GRID = 2048;
    private static final int MAX_RECT_CELLS = 32;
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    private NavMeshBaker() {}

    /** Collider volume in world space: oriented box (half extents along R's columns) or sphere. */
    private static final class Shape {
        boolean sphere, walkableTop = true;
        float cx, cy, cz, hx, hy, hz, r;
        final float[] m = new float[9]; // Row-major world rotation
        float minX, maxX, minZ, maxZ, minY, maxY;
    }

    public static Result bake(List<Entity> entities, SceneWorld world, Settings s) {
        List<Shape> shapes = gatherShapes(entities, world);
        if (shapes.isEmpty()) return new Result(null, "No static colliders found (Box/Sphere/Capsule without a dynamic RigidBody).");

        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Shape sh : shapes) {
            minX = Math.min(minX, sh.minX); maxX = Math.max(maxX, sh.maxX);
            minZ = Math.min(minZ, sh.minZ); maxZ = Math.max(maxZ, sh.maxZ);
        }
        float cs = Math.max(0.02f, s.cellSize);
        int w = (int) Math.ceil((maxX - minX) / cs), h = (int) Math.ceil((maxZ - minZ) / cs);
        if (w > MAX_GRID || h > MAX_GRID) {
            cs = Math.max((maxX - minX), (maxZ - minZ)) / MAX_GRID;
            w = Math.min(MAX_GRID, (int) Math.ceil((maxX - minX) / cs));
            h = Math.min(MAX_GRID, (int) Math.ceil((maxZ - minZ) / cs));
            System.err.println("[NavMeshBaker] Scene too large for the requested cell size; using " + cs);
        }
        w = Math.max(1, w); h = Math.max(1, h);
        final float ox = minX, oz = minZ;
        final int cols = w * h;
        float slopeCos = (float) Math.cos(Math.toRadians(Math.max(0.0f, Math.min(89.9f, s.maxSlope))));

        // --- 1/2. Rasterize columns and keep walkable span tops ---
        int[] nodeStart = new int[cols + 1];
        FloatList nodeY = new FloatList();
        IntList nodeCol = new IntList();
        float[] iv = new float[4]; // y0, y1, topNormalY, walkable flag
        List<float[]> colSpans = new ArrayList<>();
        for (int cz = 0; cz < h; cz++) {
            float z = oz + (cz + 0.5f) * cs;
            for (int cx = 0; cx < w; cx++) {
                float x = ox + (cx + 0.5f) * cs;
                int c = cz * w + cx;
                nodeStart[c] = nodeY.size;
                colSpans.clear();
                for (Shape sh : shapes) {
                    if (x < sh.minX || x > sh.maxX || z < sh.minZ || z > sh.maxZ) continue;
                    if (verticalInterval(sh, x, z, iv)) colSpans.add(new float[]{iv[0], iv[1], iv[2], iv[3]});
                }
                if (colSpans.isEmpty()) continue;
                colSpans.sort((a, b) -> Float.compare(a[0], b[0]));
                // Merge overlapping intervals; the top surface belongs to whichever interval reaches highest
                int k = 0;
                while (k < colSpans.size()) {
                    float[] cur = colSpans.get(k);
                    float y1 = cur[1], ny = cur[2], walk = cur[3];
                    // Highest walkable top among the merged intervals (for the low-obstacle rule below)
                    float walkTop = (walk > 0.5f && ny >= slopeCos) ? y1 : -Float.MAX_VALUE;
                    int j = k + 1;
                    while (j < colSpans.size() && colSpans.get(j)[0] <= y1 + s.cellHeight * 0.5f) {
                        float[] o = colSpans.get(j);
                        if (o[3] > 0.5f && o[2] >= slopeCos) walkTop = Math.max(walkTop, o[1]);
                        if (o[1] > y1) { y1 = o[1]; ny = o[2]; walk = o[3]; }
                        j++;
                    }
                    float ceiling = (j < colSpans.size()) ? colSpans.get(j)[0] : Float.MAX_VALUE;
                    boolean walkable = walk > 0.5f && ny >= slopeCos;
                    // Low obstacle: a steep sliver poking at most stepHeight above walkable ground can be stepped onto
                    if (!walkable && y1 - walkTop <= s.stepHeight) walkable = true;
                    if (walkable && ceiling - y1 >= s.agentHeight) {
                        nodeY.add(y1);
                        nodeCol.add(c);
                    }
                    k = j;
                }
            }
        }
        nodeStart[cols] = nodeY.size;
        int nodeCount = nodeY.size;
        if (nodeCount == 0) return new Result(null, "No walkable surfaces found (check slope / height settings).");

        // --- Connectivity (4-neighbour), within step height, closest layer wins ---
        int[] nbr = new int[nodeCount * 4];
        for (int n = 0; n < nodeCount; n++) {
            int c = nodeCol.data[n], cx = c % w, cz = c / w;
            for (int d = 0; d < 4; d++) {
                int ncx = cx + DX[d], ncz = cz + DZ[d];
                int best = -1;
                if (ncx >= 0 && ncz >= 0 && ncx < w && ncz < h) {
                    int nc = ncz * w + ncx;
                    float bestDy = s.stepHeight;
                    for (int m = nodeStart[nc]; m < nodeStart[nc + 1]; m++) {
                        float dy = Math.abs(nodeY.data[m] - nodeY.data[n]);
                        if (dy <= bestDy) { bestDy = dy; best = m; }
                    }
                }
                nbr[n * 4 + d] = best;
            }
        }

        // --- 3. Erode by agent radius (BFS distance in cells from border cells) ---
        int[] dist = new int[nodeCount];
        Arrays.fill(dist, Integer.MAX_VALUE);
        int[] queue = new int[nodeCount];
        int qh = 0, qt = 0;
        for (int n = 0; n < nodeCount; n++) {
            boolean border = false;
            for (int d = 0; d < 4 && !border; d++) {
                int m = nbr[n * 4 + d];
                // A missing diagonal also counts, so convex corners erode on both axes
                if (m < 0 || nbr[m * 4 + ((d + 1) & 3)] < 0) border = true;
            }
            if (border) { dist[n] = 0; queue[qt++] = n; }
        }
        while (qh < qt) {
            int n = queue[qh++];
            for (int d = 0; d < 4; d++) {
                int m = nbr[n * 4 + d];
                if (m >= 0 && dist[m] > dist[n] + 1) { dist[m] = dist[n] + 1; queue[qt++] = m; }
            }
        }
        boolean[] valid = new boolean[nodeCount];
        int validCount = 0;
        for (int n = 0; n < nodeCount; n++) {
            valid[n] = (dist[n] + 0.5f) * cs >= s.agentRadius;
            if (valid[n]) validCount++;
        }
        if (validCount == 0) return new Result(null, "Every walkable cell was eroded by the agent radius.");
        for (int n = 0; n < nodeCount; n++) {
            for (int d = 0; d < 4; d++) {
                int m = nbr[n * 4 + d];
                if (m >= 0 && (!valid[m] || !valid[n])) nbr[n * 4 + d] = -1;
            }
        }

        // --- 4. Greedy rectangle merge ---
        float mergeTol = Math.max(s.cellHeight, 0.02f);
        int[] polyOf = new int[nodeCount];
        Arrays.fill(polyOf, -1);
        FloatList pMinX = new FloatList(), pMinZ = new FloatList(), pMaxX = new FloatList(), pMaxZ = new FloatList(), pY = new FloatList();
        int[] rowScratch = new int[MAX_RECT_CELLS], nextRow = new int[MAX_RECT_CELLS];
        for (int n = 0; n < nodeCount; n++) {
            if (!valid[n] || polyOf[n] >= 0) continue;
            int poly = pY.size;
            float y0 = nodeY.data[n];
            // Width along +x
            int rw = 1;
            rowScratch[0] = n;
            while (rw < MAX_RECT_CELLS) {
                int m = nbr[rowScratch[rw - 1] * 4];
                if (m < 0 || polyOf[m] >= 0 || Math.abs(nodeY.data[m] - y0) > mergeTol) break;
                rowScratch[rw++] = m;
            }
            for (int i = 0; i < rw; i++) polyOf[rowScratch[i]] = poly;
            double ySum = 0;
            for (int i = 0; i < rw; i++) ySum += nodeY.data[rowScratch[i]];
            int rh = 1;
            // Height along +z: the whole next row must exist, be free, flat and mutually connected
            while (rh < MAX_RECT_CELLS) {
                boolean ok = true;
                for (int i = 0; i < rw && ok; i++) {
                    int m = nbr[rowScratch[i] * 4 + 1];
                    if (m < 0 || polyOf[m] >= 0 || Math.abs(nodeY.data[m] - y0) > mergeTol) ok = false;
                    else if (i > 0 && nbr[nextRow[i - 1] * 4] != m) ok = false;
                    else nextRow[i] = m;
                }
                if (!ok) break;
                for (int i = 0; i < rw; i++) {
                    rowScratch[i] = nextRow[i];
                    polyOf[nextRow[i]] = poly;
                    ySum += nodeY.data[nextRow[i]];
                }
                rh++;
            }
            int c = nodeCol.data[n], cx = c % w, cz = c / w;
            pMinX.add(ox + cx * cs); pMaxX.add(ox + (cx + rw) * cs);
            pMinZ.add(oz + cz * cs); pMaxZ.add(oz + (cz + rh) * cs);
            pY.add((float) (ySum / (rw * rh)));
        }
        int polyCount = pY.size;

        // --- Portals: union of shared cell edges per (poly, neighbour) pair ---
        Map<Long, float[]> portals = new HashMap<>();
        for (int n = 0; n < nodeCount; n++) {
            int p = polyOf[n];
            if (p < 0) continue;
            int c = nodeCol.data[n], cx = c % w, cz = c / w;
            for (int d = 0; d < 4; d++) {
                int m = nbr[n * 4 + d];
                if (m < 0 || polyOf[m] < 0 || polyOf[m] == p) continue;
                int q = polyOf[m];
                float ax, az, bx, bz;
                if (DX[d] != 0) {
                    ax = bx = ox + (cx + (DX[d] > 0 ? 1 : 0)) * cs;
                    az = oz + cz * cs; bz = az + cs;
                } else {
                    az = bz = oz + (cz + (DZ[d] > 0 ? 1 : 0)) * cs;
                    ax = ox + cx * cs; bx = ax + cs;
                }
                long key = (long) p * polyCount + q;
                float[] seg = portals.get(key);
                if (seg == null) portals.put(key, new float[]{ax, az, bx, bz});
                else {
                    seg[0] = Math.min(seg[0], ax); seg[1] = Math.min(seg[1], az);
                    seg[2] = Math.max(seg[2], bx); seg[3] = Math.max(seg[3], bz);
                }
            }
        }
        int[] linkStart = new int[polyCount + 1];
        for (Long key : portals.keySet()) linkStart[(int) (key / polyCount) + 1]++;
        for (int i = 0; i < polyCount; i++) linkStart[i + 1] += linkStart[i];
        int links = portals.size();
        int[] linkPoly = new int[links];
        float[] lax = new float[links], laz = new float[links], lbx = new float[links], lbz = new float[links];
        int[] fill = Arrays.copyOf(linkStart, polyCount);
        for (Map.Entry<Long, float[]> e : portals.entrySet()) {
            int p = (int) (e.getKey() / polyCount), q = (int) (e.getKey() % polyCount);
            int i = fill[p]++;
            float[] seg = e.getValue();
            linkPoly[i] = q; lax[i] = seg[0]; laz[i] = seg[1]; lbx[i] = seg[2]; lbz[i] = seg[3];
        }

        // --- Column -> polys lookup ---
        int[] colStart = new int[cols + 1];
        IntList colPolys = new IntList();
        for (int c = 0; c < cols; c++) {
            colStart[c] = colPolys.size;
            for (int n = nodeStart[c]; n < nodeStart[c + 1]; n++) {
                int p = polyOf[n];
                if (p < 0) continue;
                boolean dup = false;
                for (int i = colStart[c]; i < colPolys.size; i++) if (colPolys.data[i] == p) { dup = true; break; }
                if (!dup) colPolys.add(p);
            }
        }
        colStart[cols] = colPolys.size;

        NavMesh mesh = new NavMesh(cs, s.cellHeight, s.agentHeight, s.agentRadius, s.maxSlope, s.stepHeight,
                polyCount, pMinX.trim(), pMinZ.trim(), pMaxX.trim(), pMaxZ.trim(), pY.trim(),
                linkStart, linkPoly, lax, laz, lbx, lbz, ox, oz, w, h, colStart, colPolys.trim());
        return new Result(mesh, "Baked " + polyCount + " polygons, " + links + " links from " + shapes.size()
                + " colliders (" + w + "x" + h + " grid).");
    }

    // ------------------------------------------------------------------------------------------
    // Geometry gathering
    // ------------------------------------------------------------------------------------------

    private static List<Shape> gatherShapes(List<Entity> entities, SceneWorld world) {
        List<Shape> shapes = new ArrayList<>();
        if (entities == null) return shapes;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (e == null) continue;
            RigidBodyComponent rb = e.getComponent(RigidBodyComponent.class);
            if (rb != null && rb.motionType != RigidBodyComponent.MotionType.STATIC) continue;
            if (e.getComponent(CharacterControllerComponent.class) != null || e.getComponent(VehicleControllerComponent.class) != null
                    || e.getComponent(NavMeshAgent.class) != null) continue;
            float px, py, pz, rx, ry, rz, sx, sy, sz;
            SceneWorld.WorldTransform wt = (world != null) ? world.getComputedWorldTransform(e) : null;
            if (wt != null) {
                px = wt.px(); py = wt.py(); pz = wt.pz(); rx = wt.rx(); ry = wt.ry(); rz = wt.rz();
                sx = wt.sx(); sy = wt.sy(); sz = wt.sz();
            } else {
                Transform t = e.getTransform();
                if (t == null) continue;
                px = t.posX; py = t.posY; pz = t.posZ; rx = t.rotX; ry = t.rotY; rz = t.rotZ;
                sx = t.scaleX; sy = t.scaleY; sz = t.scaleZ;
            }
            BoxCollider box = e.getComponent(BoxCollider.class);
            if (box != null && !box.isTrigger) {
                shapes.add(makeBox(px, py, pz, rx, ry, rz, box.offsetX * sx, box.offsetY * sy, box.offsetZ * sz,
                        Math.abs(box.sizeX * sx) * 0.5f, Math.abs(box.sizeY * sy) * 0.5f, Math.abs(box.sizeZ * sz) * 0.5f, true));
            }
            SphereCollider sphere = e.getComponent(SphereCollider.class);
            if (sphere != null && !sphere.isTrigger) {
                Shape sh = new Shape();
                sh.sphere = true;
                sh.r = Math.abs(sphere.radius * sx);
                sh.cx = px + sphere.offsetX * sx; sh.cy = py + sphere.offsetY * sy; sh.cz = pz + sphere.offsetZ * sz;
                sh.minX = sh.cx - sh.r; sh.maxX = sh.cx + sh.r; sh.minZ = sh.cz - sh.r; sh.maxZ = sh.cz + sh.r;
                sh.minY = sh.cy - sh.r; sh.maxY = sh.cy + sh.r;
                shapes.add(sh);
            }
            CapsuleCollider cap = e.getComponent(CapsuleCollider.class);
            if (cap != null && !cap.isTrigger) {
                shapes.add(makeBox(px, py, pz, rx, ry, rz, cap.offsetX * sx, cap.offsetY * sy, cap.offsetZ * sz,
                        Math.abs(cap.radius * sx), Math.abs(cap.height * sy) * 0.5f, Math.abs(cap.radius * sz), false));
            }
        }
        return shapes;
    }

    /**
     * World rotation matching the renderer's model matrix (MetalUniformStream): R = Rz(-z) * Ry(-y) * Rx(-x), applied
     * to column vectors.
     */
    private static Shape makeBox(float px, float py, float pz, float rxDeg, float ryDeg, float rzDeg,
                                 float offX, float offY, float offZ, float hx, float hy, float hz, boolean walkableTop) {
        Shape sh = new Shape();
        sh.walkableTop = walkableTop;
        double ax = Math.toRadians(rxDeg), ay = Math.toRadians(ryDeg), az = Math.toRadians(rzDeg);
        float cx = (float) Math.cos(ax), sx = (float) Math.sin(ax);
        float cy = (float) Math.cos(ay), sy = (float) Math.sin(ay);
        float cz = (float) Math.cos(az), sz = (float) Math.sin(az);
        float[] X = {1, 0, 0, 0, cx, sx, 0, -sx, cx};
        float[] Y = {cy, 0, -sy, 0, 1, 0, sy, 0, cy};
        float[] Z = {cz, sz, 0, -sz, cz, 0, 0, 0, 1};
        float[] YX = mul3(Y, X);
        float[] m = mul3(Z, YX);
        System.arraycopy(m, 0, sh.m, 0, 9);
        sh.hx = hx; sh.hy = hy; sh.hz = hz;
        // Collider offset is in local (rotated) space
        sh.cx = px + m[0] * offX + m[1] * offY + m[2] * offZ;
        sh.cy = py + m[3] * offX + m[4] * offY + m[5] * offZ;
        sh.cz = pz + m[6] * offX + m[7] * offY + m[8] * offZ;
        float ex = Math.abs(m[0]) * hx + Math.abs(m[1]) * hy + Math.abs(m[2]) * hz;
        float ey = Math.abs(m[3]) * hx + Math.abs(m[4]) * hy + Math.abs(m[5]) * hz;
        float ez = Math.abs(m[6]) * hx + Math.abs(m[7]) * hy + Math.abs(m[8]) * hz;
        sh.minX = sh.cx - ex; sh.maxX = sh.cx + ex;
        sh.minY = sh.cy - ey; sh.maxY = sh.cy + ey;
        sh.minZ = sh.cz - ez; sh.maxZ = sh.cz + ez;
        return sh;
    }

    private static float[] mul3(float[] a, float[] b) {
        float[] o = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return o;
    }

    /** Intersects the vertical line through (x, z) with a shape: out = {yMin, yMax, topNormalY, walkable}. */
    private static boolean verticalInterval(Shape sh, float x, float z, float[] out) {
        if (sh.sphere) {
            float dx = x - sh.cx, dz = z - sh.cz;
            float d2 = sh.r * sh.r - dx * dx - dz * dz;
            if (d2 < 0) return false;
            float hh = (float) Math.sqrt(d2);
            out[0] = sh.cy - hh; out[1] = sh.cy + hh; out[2] = hh / sh.r; out[3] = 1;
            return true;
        }
        float[] m = sh.m;
        // Local line: o + t * d, with o = R^T (x - c.x, -c.y, z - c.z) and d = R^T (0, 1, 0)
        float wx = x - sh.cx, wy = -sh.cy, wz = z - sh.cz;
        float tEnter = -Float.MAX_VALUE, tExit = Float.MAX_VALUE, exitNy = 1.0f;
        float[] half = {sh.hx, sh.hy, sh.hz};
        for (int k = 0; k < 3; k++) {
            float o = m[k] * wx + m[3 + k] * wy + m[6 + k] * wz;
            float d = m[3 + k];
            if (Math.abs(d) < 1e-6f) {
                if (o < -half[k] || o > half[k]) return false;
                continue;
            }
            float t1 = (-half[k] - o) / d, t2 = (half[k] - o) / d;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tEnter) tEnter = t1;
            if (t2 < tExit) { tExit = t2; exitNy = Math.abs(d); }
        }
        if (tEnter > tExit) return false;
        out[0] = tEnter; out[1] = tExit; out[2] = exitNy; out[3] = sh.walkableTop ? 1 : 0;
        return true;
    }

    private static final class FloatList {
        float[] data = new float[256];
        int size;
        void add(float v) { if (size == data.length) data = Arrays.copyOf(data, size * 2); data[size++] = v; }
        float[] trim() { return Arrays.copyOf(data, size); }
    }

    private static final class IntList {
        int[] data = new int[256];
        int size;
        void add(int v) { if (size == data.length) data = Arrays.copyOf(data, size * 2); data[size++] = v; }
        int[] trim() { return Arrays.copyOf(data, size); }
    }
}
