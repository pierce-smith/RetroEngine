package com.retro.engine.physics;

import com.retro.engine.physics.PhysicsSystem.PhysicsBody;

/**
 * Shape-against-shape contact generation. Boxes are oriented. A sphere is a capsule with a zero-length segment, so
 * there are three exact cases: round against round, round against box, and box against box. Triangle meshes are static,
 * so they only ever meet those shapes. Allocates nothing once its scratch lists have grown.
 *
 * <p>Contacts are reported up to {@code margin} before the shapes touch. The solver uses those to stop bodies arriving
 * at a surface instead of letting them sink in first.
 */
final class NarrowphaseSolver {

    private static final float EPSILON = 1e-6f;
    // How much better another axis must be before it replaces the preferred one, so contacts don't flicker between axes
    private static final float AXIS_BIAS = 1e-3f;
    private static final int MAX_CLIP_VERTS = 12;

    private final float[] axesA = new float[9], axesB = new float[9];
    private final float[] halfA = new float[3], halfB = new float[3];
    private final float[] projA = new float[3], projB = new float[3];
    private final float[] absR = new float[9];
    private final float[] clipIn = new float[MAX_CLIP_VERTS * 3], clipOut = new float[MAX_CLIP_VERTS * 3];
    private final float[] clipSep = new float[MAX_CLIP_VERTS];
    private final boolean[] clipUsed = new boolean[MAX_CLIP_VERTS];
    private final float[] closest = new float[6];
    private final float[] boxPoint = new float[7];
    private final float[] triPoint = new float[3];
    private final float[] tri = new float[9];
    private final float[] triRel = new float[9]; // The triangle relative to the box's centre
    private float bestGap, bestNx, bestNy, bestNz;
    private final CollisionMesh.Hits meshHits = new CollisionMesh.Hits();

    /** Fills {@code m} with contacts between its two bodies. Returns true when there is at least one. */
    boolean collide(ContactManifold m, float margin) {
        m.count = 0;
        PhysicsBody a = m.a, b = m.b;
        if (a.shape == PhysicsBody.SHAPE_MESH) {
            if (b.shape != PhysicsBody.SHAPE_MESH) collideMesh(b, a, margin, m, true);
            return m.count > 0;
        }
        if (b.shape == PhysicsBody.SHAPE_MESH) {
            collideMesh(a, b, margin, m, false);
            return m.count > 0;
        }
        boolean aRound = a.shape != PhysicsBody.SHAPE_BOX, bRound = b.shape != PhysicsBody.SHAPE_BOX;
        if (aRound && bRound) collideRound(a, b, margin, m);
        else if (aRound) collideRoundBox(a, b, margin, m, true);
        else if (bRound) collideRoundBox(b, a, margin, m, false);
        else collideBoxes(a, b, margin, m);
        return m.count > 0;
    }

    private void collideRound(PhysicsBody a, PhysicsBody b, float margin, ContactManifold m) {
        closestBetweenSegments(a, b, closest);
        float x1 = closest[0], y1 = closest[1], z1 = closest[2];
        float dx = closest[3] - x1, dy = closest[4] - y1, dz = closest[5] - z1;
        float reach = a.radius + b.radius;
        float distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > (reach + margin) * (reach + margin)) return;
        float dist = (float) Math.sqrt(distSq);
        float nx = 0.0f, ny = 1.0f, nz = 0.0f;
        if (dist > EPSILON) { nx = dx / dist; ny = dy / dist; nz = dz / dist; }
        float sep = dist - reach;
        float offset = a.radius + sep * 0.5f;
        m.add(x1 + nx * offset, y1 + ny * offset, z1 + nz * offset, nx, ny, nz, sep);
    }

    /** Closest points of two bodies' core segments: out[0..2] on a, out[3..5] on b. */
    private static void closestBetweenSegments(PhysicsBody a, PhysicsBody b, float[] out) {
        float ax = a.r[1] * a.segmentHalf, ay = a.r[4] * a.segmentHalf, az = a.r[7] * a.segmentHalf;
        float bx = b.r[1] * b.segmentHalf, by = b.r[4] * b.segmentHalf, bz = b.r[7] * b.segmentHalf;
        // Segments run from c - axis to c + axis, as p + s * d with s in 0..1
        float p1x = a.cx - ax, p1y = a.cy - ay, p1z = a.cz - az, d1x = 2 * ax, d1y = 2 * ay, d1z = 2 * az;
        float p2x = b.cx - bx, p2y = b.cy - by, p2z = b.cz - bz, d2x = 2 * bx, d2y = 2 * by, d2z = 2 * bz;
        float rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
        float aa = d1x * d1x + d1y * d1y + d1z * d1z;
        float ee = d2x * d2x + d2y * d2y + d2z * d2z;
        float f = d2x * rx + d2y * ry + d2z * rz;
        float s, t;
        if (aa <= EPSILON && ee <= EPSILON) {
            s = 0.0f; t = 0.0f;
        } else if (aa <= EPSILON) {
            s = 0.0f; t = clamp01(f / ee);
        } else {
            float c = d1x * rx + d1y * ry + d1z * rz;
            if (ee <= EPSILON) {
                t = 0.0f; s = clamp01(-c / aa);
            } else {
                float bb = d1x * d2x + d1y * d2y + d1z * d2z;
                float denom = aa * ee - bb * bb;
                // Parallel segments have no single closest pair, so take the middle of the first one
                s = denom > EPSILON ? clamp01((bb * f - c * ee) / denom) : 0.5f;
                t = (bb * s + f) / ee;
                if (t < 0.0f) { t = 0.0f; s = clamp01(-c / aa); }
                else if (t > 1.0f) { t = 1.0f; s = clamp01((bb - c) / aa); }
            }
        }
        out[0] = p1x + d1x * s; out[1] = p1y + d1y * s; out[2] = p1z + d1z * s;
        out[3] = p2x + d2x * t; out[4] = p2y + d2y * t; out[5] = p2z + d2z * t;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private void collideRoundBox(PhysicsBody round, PhysicsBody box, float margin, ContactManifold m, boolean roundIsA) {
        float seg = round.segmentHalf;
        if (seg <= EPSILON) {
            addRoundPoint(round.cx, round.cy, round.cz, round.radius, box, margin, m, roundIsA);
            return;
        }
        float ax = round.r[1], ay = round.r[4], az = round.r[7];
        addRoundPoint(round.cx - ax * seg, round.cy - ay * seg, round.cz - az * seg, round.radius, box, margin, m, roundIsA);
        addRoundPoint(round.cx + ax * seg, round.cy + ay * seg, round.cz + az * seg, round.radius, box, margin, m, roundIsA);
        // A box corner can poke the middle of the capsule while both ends stay clear. Alternating projections converge
        // on the closest pair because both shapes are convex
        float t = 0.0f;
        for (int i = 0; i < 4; i++) {
            closestOnBox(round.cx + ax * t, round.cy + ay * t, round.cz + az * t, box, boxPoint);
            t = (boxPoint[0] - round.cx) * ax + (boxPoint[1] - round.cy) * ay + (boxPoint[2] - round.cz) * az;
            t = Math.max(-seg, Math.min(seg, t));
        }
        if (Math.abs(t) < seg - 0.01f) {
            addRoundPoint(round.cx + ax * t, round.cy + ay * t, round.cz + az * t, round.radius, box, margin, m, roundIsA);
        }
    }

    private void addRoundPoint(float x, float y, float z, float radius, PhysicsBody box, float margin, ContactManifold m, boolean roundIsA) {
        closestOnBox(x, y, z, box, boxPoint);
        float qx = boxPoint[0], qy = boxPoint[1], qz = boxPoint[2];
        float nx = boxPoint[3], ny = boxPoint[4], nz = boxPoint[5]; // Box toward the round shape
        float sep = boxPoint[6] - radius;
        if (sep > margin) return;
        float px = qx + nx * sep * 0.5f, py = qy + ny * sep * 0.5f, pz = qz + nz * sep * 0.5f;
        if (roundIsA) m.add(px, py, pz, -nx, -ny, -nz, sep);
        else m.add(px, py, pz, nx, ny, nz, sep);
    }

    /**
     * Closest surface point of a box to (x, y, z). out[0..2] is the point, out[3..5] the unit normal facing the query
     * point, and out[6] the signed distance, negative when the query point is inside.
     */
    private static void closestOnBox(float x, float y, float z, PhysicsBody box, float[] out) {
        float[] r = box.r;
        float dx = x - box.cx, dy = y - box.cy, dz = z - box.cz;
        float lx = r[0] * dx + r[3] * dy + r[6] * dz;
        float ly = r[1] * dx + r[4] * dy + r[7] * dz;
        float lz = r[2] * dx + r[5] * dy + r[8] * dz;
        float qx = Math.max(-box.hx, Math.min(box.hx, lx));
        float qy = Math.max(-box.hy, Math.min(box.hy, ly));
        float qz = Math.max(-box.hz, Math.min(box.hz, lz));
        float ex = lx - qx, ey = ly - qy, ez = lz - qz;
        float distSq = ex * ex + ey * ey + ez * ez;
        float nx, ny, nz, dist;
        if (distSq > EPSILON * EPSILON) {
            dist = (float) Math.sqrt(distSq);
            nx = ex / dist; ny = ey / dist; nz = ez / dist;
        } else {
            // On or inside the box: leave through the nearest face
            float fx = box.hx - Math.abs(lx), fy = box.hy - Math.abs(ly), fz = box.hz - Math.abs(lz);
            nx = 0.0f; ny = 0.0f; nz = 0.0f;
            if (fy <= fx && fy <= fz) { ny = ly >= 0.0f ? 1.0f : -1.0f; qy = ny * box.hy; dist = -fy; }
            else if (fx <= fz) { nx = lx >= 0.0f ? 1.0f : -1.0f; qx = nx * box.hx; dist = -fx; }
            else { nz = lz >= 0.0f ? 1.0f : -1.0f; qz = nz * box.hz; dist = -fz; }
        }
        out[0] = box.cx + r[0] * qx + r[1] * qy + r[2] * qz;
        out[1] = box.cy + r[3] * qx + r[4] * qy + r[5] * qz;
        out[2] = box.cz + r[6] * qx + r[7] * qy + r[8] * qz;
        out[3] = r[0] * nx + r[1] * ny + r[2] * nz;
        out[4] = r[3] * nx + r[4] * ny + r[5] * nz;
        out[5] = r[6] * nx + r[7] * ny + r[8] * nz;
        out[6] = dist;
    }

    /** Copies a rotation's columns into axes[3 * i ..] so each box axis is contiguous. */
    private static void loadAxes(float[] r, float[] axes) {
        for (int i = 0; i < 3; i++) {
            axes[i * 3] = r[i]; axes[i * 3 + 1] = r[3 + i]; axes[i * 3 + 2] = r[6 + i];
        }
    }

    private void collideBoxes(PhysicsBody a, PhysicsBody b, float margin, ContactManifold m) {
        float[] ua = axesA, ub = axesB, ha = halfA, hb = halfB;
        loadAxes(a.r, ua);
        loadAxes(b.r, ub);
        ha[0] = a.hx; ha[1] = a.hy; ha[2] = a.hz;
        hb[0] = b.hx; hb[1] = b.hy; hb[2] = b.hz;
        float tx = b.cx - a.cx, ty = b.cy - a.cy, tz = b.cz - a.cz;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                absR[i * 3 + j] = Math.abs(ua[i * 3] * ub[j * 3] + ua[i * 3 + 1] * ub[j * 3 + 1] + ua[i * 3 + 2] * ub[j * 3 + 2]);
            }
        }

        // Separating axis test. The largest separation is the axis of least penetration
        float bestA = -Float.MAX_VALUE, bestB = -Float.MAX_VALUE, bestEdge = -Float.MAX_VALUE;
        int axisA = 0, axisB = 0, edgeI = 0, edgeJ = 0;
        float edgeX = 0, edgeY = 0, edgeZ = 0;
        for (int i = 0; i < 3; i++) {
            float dist = Math.abs(tx * ua[i * 3] + ty * ua[i * 3 + 1] + tz * ua[i * 3 + 2]);
            float s = dist - (ha[i] + hb[0] * absR[i * 3] + hb[1] * absR[i * 3 + 1] + hb[2] * absR[i * 3 + 2]);
            if (s > margin) return;
            if (s > bestA) { bestA = s; axisA = i; }
        }
        for (int j = 0; j < 3; j++) {
            float dist = Math.abs(tx * ub[j * 3] + ty * ub[j * 3 + 1] + tz * ub[j * 3 + 2]);
            float s = dist - (hb[j] + ha[0] * absR[j] + ha[1] * absR[3 + j] + ha[2] * absR[6 + j]);
            if (s > margin) return;
            if (s > bestB) { bestB = s; axisB = j; }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float cx = ua[i * 3 + 1] * ub[j * 3 + 2] - ua[i * 3 + 2] * ub[j * 3 + 1];
                float cy = ua[i * 3 + 2] * ub[j * 3] - ua[i * 3] * ub[j * 3 + 2];
                float cz = ua[i * 3] * ub[j * 3 + 1] - ua[i * 3 + 1] * ub[j * 3];
                float lenSq = cx * cx + cy * cy + cz * cz;
                if (lenSq < 1e-6f) continue; // Parallel edges: the face axes already cover this direction
                float inv = 1.0f / (float) Math.sqrt(lenSq);
                cx *= inv; cy *= inv; cz *= inv;
                float reach = 0.0f;
                for (int k = 0; k < 3; k++) {
                    reach += ha[k] * Math.abs(cx * ua[k * 3] + cy * ua[k * 3 + 1] + cz * ua[k * 3 + 2]);
                    reach += hb[k] * Math.abs(cx * ub[k * 3] + cy * ub[k * 3 + 1] + cz * ub[k * 3 + 2]);
                }
                float along = tx * cx + ty * cy + tz * cz;
                float s = Math.abs(along) - reach;
                if (s > margin) return;
                if (s > bestEdge) {
                    bestEdge = s; edgeI = i; edgeJ = j;
                    float sign = along >= 0.0f ? 1.0f : -1.0f;
                    edgeX = cx * sign; edgeY = cy * sign; edgeZ = cz * sign;
                }
            }
        }

        boolean useB = bestB > bestA + AXIS_BIAS;
        float bestFace = useB ? bestB : bestA;
        if (bestEdge > bestFace + AXIS_BIAS) {
            addEdgeContact(a, b, edgeI, edgeJ, edgeX, edgeY, edgeZ, bestEdge, m);
        } else if (useB) {
            clipFace(b, a, ub, hb, ua, ha, axisB, -tx, -ty, -tz, margin, m, true);
        } else {
            clipFace(a, b, ua, ha, ub, hb, axisA, tx, ty, tz, margin, m, false);
        }
    }

    private void addEdgeContact(PhysicsBody a, PhysicsBody b, int i, int j, float nx, float ny, float nz, float sep, ContactManifold m) {
        float[] ua = axesA, ub = axesB;
        // The edge of each box that reaches furthest toward the other
        float pax = a.cx, pay = a.cy, paz = a.cz, pbx = b.cx, pby = b.cy, pbz = b.cz;
        for (int k = 0; k < 3; k++) {
            if (k != i) {
                float s = (nx * ua[k * 3] + ny * ua[k * 3 + 1] + nz * ua[k * 3 + 2]) >= 0.0f ? halfA[k] : -halfA[k];
                pax += ua[k * 3] * s; pay += ua[k * 3 + 1] * s; paz += ua[k * 3 + 2] * s;
            }
            if (k != j) {
                float s = (nx * ub[k * 3] + ny * ub[k * 3 + 1] + nz * ub[k * 3 + 2]) >= 0.0f ? -halfB[k] : halfB[k];
                pbx += ub[k * 3] * s; pby += ub[k * 3 + 1] * s; pbz += ub[k * 3 + 2] * s;
            }
        }
        float d1x = ua[i * 3], d1y = ua[i * 3 + 1], d1z = ua[i * 3 + 2];
        float d2x = ub[j * 3], d2y = ub[j * 3 + 1], d2z = ub[j * 3 + 2];
        float rx = pax - pbx, ry = pay - pby, rz = paz - pbz;
        float dot = d1x * d2x + d1y * d2y + d1z * d2z;
        float c = d1x * rx + d1y * ry + d1z * rz;
        float f = d2x * rx + d2y * ry + d2z * rz;
        float denom = 1.0f - dot * dot;
        float s = denom > EPSILON ? (dot * f - c) / denom : 0.0f;
        float t = dot * s + f;
        float x = (pax + d1x * s + pbx + d2x * t) * 0.5f;
        float y = (pay + d1y * s + pby + d2y * t) * 0.5f;
        float z = (paz + d1z * s + pbz + d2z * t) * 0.5f;
        m.add(x, y, z, nx, ny, nz, sep);
    }

    /** Clips the incident box's most opposed face against the reference box's side planes. {@code t} runs ref to inc. */
    private void clipFace(PhysicsBody ref, PhysicsBody inc, float[] ur, float[] hr, float[] ui, float[] hi, int axis,
                          float tx, float ty, float tz, float margin, ContactManifold m, boolean refIsB) {
        float sign = (tx * ur[axis * 3] + ty * ur[axis * 3 + 1] + tz * ur[axis * 3 + 2]) >= 0.0f ? 1.0f : -1.0f;
        float nx = ur[axis * 3] * sign, ny = ur[axis * 3 + 1] * sign, nz = ur[axis * 3 + 2] * sign;

        int face = 0;
        float bestDot = -1.0f;
        for (int k = 0; k < 3; k++) {
            projA[k] = nx * ui[k * 3] + ny * ui[k * 3 + 1] + nz * ui[k * 3 + 2];
            float d = Math.abs(projA[k]);
            if (d > bestDot) { bestDot = d; face = k; }
        }
        float faceSign = projA[face] > 0.0f ? -hi[face] : hi[face];
        float fx = inc.cx + ui[face * 3] * faceSign, fy = inc.cy + ui[face * 3 + 1] * faceSign, fz = inc.cz + ui[face * 3 + 2] * faceSign;
        int u = (face + 1) % 3, v = (face + 2) % 3;
        float ux = ui[u * 3] * hi[u], uy = ui[u * 3 + 1] * hi[u], uz = ui[u * 3 + 2] * hi[u];
        float vx = ui[v * 3] * hi[v], vy = ui[v * 3 + 1] * hi[v], vz = ui[v * 3 + 2] * hi[v];
        float[] in = clipIn, out = clipOut;
        setVert(in, 0, fx + ux + vx, fy + uy + vy, fz + uz + vz);
        setVert(in, 1, fx - ux + vx, fy - uy + vy, fz - uz + vz);
        setVert(in, 2, fx - ux - vx, fy - uy - vy, fz - uz - vz);
        setVert(in, 3, fx + ux - vx, fy + uy - vy, fz + uz - vz);
        int count = 4;

        for (int k = 1; k <= 2 && count > 0; k++) {
            int side = (axis + k) % 3;
            float ax = ur[side * 3], ay = ur[side * 3 + 1], az = ur[side * 3 + 2];
            float centre = ref.cx * ax + ref.cy * ay + ref.cz * az;
            count = clipPlane(in, count, out, ax, ay, az, centre + hr[side]);
            float[] swap = in; in = out; out = swap;
            count = clipPlane(in, count, out, -ax, -ay, -az, -centre + hr[side]);
            swap = in; in = out; out = swap;
        }

        float planeD = (ref.cx + nx * hr[axis]) * nx + (ref.cy + ny * hr[axis]) * ny + (ref.cz + nz * hr[axis]) * nz;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            float sep = in[i * 3] * nx + in[i * 3 + 1] * ny + in[i * 3 + 2] * nz - planeD;
            if (sep > margin) continue;
            if (kept != i) {
                in[kept * 3] = in[i * 3]; in[kept * 3 + 1] = in[i * 3 + 1]; in[kept * 3 + 2] = in[i * 3 + 2];
            }
            clipSep[kept++] = sep;
        }
        reducePoints(in, kept, nx, ny, nz, m, refIsB);
    }

    private static void setVert(float[] verts, int i, float x, float y, float z) {
        verts[i * 3] = x; verts[i * 3 + 1] = y; verts[i * 3 + 2] = z;
    }

    /** Keeps the side of the plane where n . p is at most d. */
    private static int clipPlane(float[] in, int count, float[] out, float nx, float ny, float nz, float d) {
        int outCount = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float x1 = in[i * 3], y1 = in[i * 3 + 1], z1 = in[i * 3 + 2];
            float x2 = in[j * 3], y2 = in[j * 3 + 1], z2 = in[j * 3 + 2];
            float d1 = nx * x1 + ny * y1 + nz * z1 - d;
            float d2 = nx * x2 + ny * y2 + nz * z2 - d;
            if (d1 <= 0.0f && outCount < MAX_CLIP_VERTS) setVert(out, outCount++, x1, y1, z1);
            if (d1 * d2 < 0.0f && outCount < MAX_CLIP_VERTS) {
                float t = d1 / (d1 - d2);
                setVert(out, outCount++, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t);
            }
        }
        return outCount;
    }

    /** Adds at most four points: the deepest, the one furthest from it, and the two that span the most area. */
    private void reducePoints(float[] verts, int count, float nx, float ny, float nz, ContactManifold m, boolean refIsB) {
        if (count <= ContactManifold.MAX_POINTS) {
            for (int i = 0; i < count; i++) addFacePoint(verts, i, nx, ny, nz, m, refIsB);
            return;
        }
        for (int i = 0; i < count; i++) clipUsed[i] = false;
        int first = 0;
        for (int i = 1; i < count; i++) if (clipSep[i] < clipSep[first]) first = i;
        clipUsed[first] = true;

        int second = first;
        float bestDist = -1.0f;
        for (int i = 0; i < count; i++) {
            float dx = verts[i * 3] - verts[first * 3], dy = verts[i * 3 + 1] - verts[first * 3 + 1], dz = verts[i * 3 + 2] - verts[first * 3 + 2];
            float d = dx * dx + dy * dy + dz * dz;
            if (!clipUsed[i] && d > bestDist) { bestDist = d; second = i; }
        }
        clipUsed[second] = true;

        float ex = verts[second * 3] - verts[first * 3], ey = verts[second * 3 + 1] - verts[first * 3 + 1], ez = verts[second * 3 + 2] - verts[first * 3 + 2];
        int third = -1, fourth = -1;
        float most = 0.0f, least = 0.0f;
        for (int i = 0; i < count; i++) {
            if (clipUsed[i]) continue;
            float dx = verts[i * 3] - verts[first * 3], dy = verts[i * 3 + 1] - verts[first * 3 + 1], dz = verts[i * 3 + 2] - verts[first * 3 + 2];
            float area = nx * (ey * dz - ez * dy) + ny * (ez * dx - ex * dz) + nz * (ex * dy - ey * dx);
            if (area > most) { most = area; third = i; }
            if (area < least) { least = area; fourth = i; }
        }
        addFacePoint(verts, first, nx, ny, nz, m, refIsB);
        addFacePoint(verts, second, nx, ny, nz, m, refIsB);
        if (third >= 0) addFacePoint(verts, third, nx, ny, nz, m, refIsB);
        if (fourth >= 0) addFacePoint(verts, fourth, nx, ny, nz, m, refIsB);
    }

    private void addFacePoint(float[] verts, int i, float nx, float ny, float nz, ContactManifold m, boolean refIsB) {
        float sep = clipSep[i];
        // Halfway between the two surfaces
        float x = verts[i * 3] - nx * sep * 0.5f, y = verts[i * 3 + 1] - ny * sep * 0.5f, z = verts[i * 3 + 2] - nz * sep * 0.5f;
        if (refIsB) m.add(x, y, z, -nx, -ny, -nz, sep);
        else m.add(x, y, z, nx, ny, nz, sep);
    }

    /** {@code meshIsA} says which way round the manifold's normal runs. */
    private void collideMesh(PhysicsBody other, PhysicsBody meshBody, float margin, ContactManifold m, boolean meshIsA) {
        float[] r = meshBody.r;
        float ex = (other.maxX - other.minX) * 0.5f + margin, ey = (other.maxY - other.minY) * 0.5f + margin, ez = (other.maxZ - other.minZ) * 0.5f + margin;
        float wx = (other.maxX + other.minX) * 0.5f - meshBody.cx;
        float wy = (other.maxY + other.minY) * 0.5f - meshBody.cy;
        float wz = (other.maxZ + other.minZ) * 0.5f - meshBody.cz;
        float lx = r[0] * wx + r[3] * wy + r[6] * wz, ly = r[1] * wx + r[4] * wy + r[7] * wz, lz = r[2] * wx + r[5] * wy + r[8] * wz;
        float hx = Math.abs(r[0]) * ex + Math.abs(r[3]) * ey + Math.abs(r[6]) * ez;
        float hy = Math.abs(r[1]) * ex + Math.abs(r[4]) * ey + Math.abs(r[7]) * ez;
        float hz = Math.abs(r[2]) * ex + Math.abs(r[5]) * ey + Math.abs(r[8]) * ez;
        CollisionMesh mesh = meshBody.mesh;
        mesh.query(lx - hx, ly - hy, lz - hz, lx + hx, ly + hy, lz + hz, meshHits);

        for (int i = 0; i < meshHits.count; i++) {
            int t = meshHits.items[i];
            for (int v = 0; v < 3; v++) {
                float x = mesh.triangles[t * 9 + v * 3], y = mesh.triangles[t * 9 + v * 3 + 1], z = mesh.triangles[t * 9 + v * 3 + 2];
                tri[v * 3] = meshBody.cx + r[0] * x + r[1] * y + r[2] * z;
                tri[v * 3 + 1] = meshBody.cy + r[3] * x + r[4] * y + r[5] * z;
                tri[v * 3 + 2] = meshBody.cz + r[6] * x + r[7] * y + r[8] * z;
            }
            float nx = mesh.normals[t * 3], ny = mesh.normals[t * 3 + 1], nz = mesh.normals[t * 3 + 2];
            float fx = r[0] * nx + r[1] * ny + r[2] * nz, fy = r[3] * nx + r[4] * ny + r[5] * nz, fz = r[6] * nx + r[7] * ny + r[8] * nz;
            if (other.shape == PhysicsBody.SHAPE_BOX) collideBoxTriangle(other, fx, fy, fz, margin, m, meshIsA);
            else collideRoundTriangle(other, fx, fy, fz, margin, m, meshIsA);
        }
    }

    private void collideRoundTriangle(PhysicsBody round, float fx, float fy, float fz, float margin, ContactManifold m, boolean meshIsA) {
        float seg = round.segmentHalf;
        if (seg <= EPSILON) {
            addRoundTrianglePoint(round.cx, round.cy, round.cz, round.radius, fx, fy, fz, margin, m, meshIsA);
            return;
        }
        float ax = round.r[1], ay = round.r[4], az = round.r[7];
        float t = 0.0f; // Alternating projections, as for a box
        for (int i = 0; i < 4; i++) {
            closestOnTriangle(round.cx + ax * t, round.cy + ay * t, round.cz + az * t, triPoint);
            t = (triPoint[0] - round.cx) * ax + (triPoint[1] - round.cy) * ay + (triPoint[2] - round.cz) * az;
            t = Math.max(-seg, Math.min(seg, t));
        }
        addRoundTrianglePoint(round.cx + ax * t, round.cy + ay * t, round.cz + az * t, round.radius, fx, fy, fz, margin, m, meshIsA);
    }

    private void addRoundTrianglePoint(float x, float y, float z, float radius, float fx, float fy, float fz,
                                       float margin, ContactManifold m, boolean meshIsA) {
        closestOnTriangle(x, y, z, triPoint);
        float dx = x - triPoint[0], dy = y - triPoint[1], dz = z - triPoint[2];
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float sep = dist - radius;
        if (sep > margin) return;
        float nx, ny, nz;
        if (dist > EPSILON) { nx = dx / dist; ny = dy / dist; nz = dz / dist; }
        else { nx = fx; ny = fy; nz = fz; }
        // A neighbouring triangle facing the same way says the same thing, so keep only the deeper of the two
        float sign = meshIsA ? 1.0f : -1.0f; // Manifold normals run from A to B, and n runs from the mesh to the shape
        for (int i = 0; i < m.count; i++) {
            ContactManifold.Point p = m.points[i];
            if (p.nx * nx * sign + p.ny * ny * sign + p.nz * nz * sign > 0.999f) {
                if (sep < p.separation) {
                    p.x = triPoint[0]; p.y = triPoint[1]; p.z = triPoint[2];
                    p.separation = sep;
                }
                return;
            }
        }
        m.add(triPoint[0], triPoint[1], triPoint[2], nx * sign, ny * sign, nz * sign, sep);
    }

    /**
     * Box corners against the triangle's face, and triangle corners against the box. That covers a box on a floor and
     * a spike into a box. When neither finds anything, {@link #crossBoxTriangle} covers edges crossing with no corner
     * inside either shape.
     */
    private void collideBoxTriangle(PhysicsBody box, float fx, float fy, float fz, float margin, ContactManifold m, boolean meshIsA) {
        float[] r = box.r;
        int found = m.count;
        float side = (box.cx - tri[0]) * fx + (box.cy - tri[1]) * fy + (box.cz - tri[2]) * fz;
        if (side < 0.0f) { fx = -fx; fy = -fy; fz = -fz; }
        float sign = meshIsA ? 1.0f : -1.0f;
        float depthLimit = -2.0f * Math.max(box.hx, Math.max(box.hy, box.hz));
        for (int c = 0; c < 8; c++) {
            float lx = (c & 1) != 0 ? box.hx : -box.hx, ly = (c & 2) != 0 ? box.hy : -box.hy, lz = (c & 4) != 0 ? box.hz : -box.hz;
            float x = box.cx + r[0] * lx + r[1] * ly + r[2] * lz;
            float y = box.cy + r[3] * lx + r[4] * ly + r[5] * lz;
            float z = box.cz + r[6] * lx + r[7] * ly + r[8] * lz;
            float sep = (x - tri[0]) * fx + (y - tri[1]) * fy + (z - tri[2]) * fz;
            if (sep > margin || sep < depthLimit) continue;
            float px = x - fx * sep, py = y - fy * sep, pz = z - fz * sep;
            closestOnTriangle(px, py, pz, triPoint);
            float ox = triPoint[0] - px, oy = triPoint[1] - py, oz = triPoint[2] - pz;
            if (ox * ox + oy * oy + oz * oz > 1e-8f) continue; // Its shadow falls outside the triangle
            m.add(px, py, pz, fx * sign, fy * sign, fz * sign, sep);
        }
        for (int v = 0; v < 3; v++) {
            closestOnBox(tri[v * 3], tri[v * 3 + 1], tri[v * 3 + 2], box, boxPoint);
            float sep = boxPoint[6];
            if (sep > margin) continue;
            // boxPoint's normal faces the triangle corner, and contacts here run from the mesh to the box
            m.add(tri[v * 3], tri[v * 3 + 1], tri[v * 3 + 2], -boxPoint[3] * sign, -boxPoint[4] * sign, -boxPoint[5] * sign, sep);
        }
        if (m.count == found && m.count < ContactManifold.MAX_POINTS) crossBoxTriangle(box, fx, fy, fz, margin, m, sign);
    }

    /**
     * Separating axes between a box and the current triangle: the triangle's normal, the box's three axes, and the nine
     * edge pairs. If none separates them, the axis with the least overlap gives the normal, and the contacts are where
     * the triangle's edges enter the box, or where the box's edges pass through the triangle.
     */
    private void crossBoxTriangle(PhysicsBody box, float fx, float fy, float fz, float margin, ContactManifold m, float sign) {
        for (int i = 0; i < 9; i += 3) {
            triRel[i] = tri[i] - box.cx;
            triRel[i + 1] = tri[i + 1] - box.cy;
            triRel[i + 2] = tri[i + 2] - box.cz;
        }
        float[] r = box.r;
        bestGap = -Float.MAX_VALUE;
        if (!overlapsOn(box, fx, fy, fz, 0.0f, margin)) return;
        for (int k = 0; k < 3; k++) {
            if (!overlapsOn(box, r[k], r[3 + k], r[6 + k], 0.0f, margin)) return;
        }
        for (int e = 0; e < 3; e++) {
            int a = e * 3, b = (e + 1) % 3 * 3;
            float ex = triRel[b] - triRel[a], ey = triRel[b + 1] - triRel[a + 1], ez = triRel[b + 2] - triRel[a + 2];
            for (int k = 0; k < 3; k++) {
                float ux = r[k], uy = r[3 + k], uz = r[6 + k];
                // Faces come first, and an edge pair has to beat them clearly, as in box against box
                if (!overlapsOn(box, ey * uz - ez * uy, ez * ux - ex * uz, ex * uy - ey * ux, AXIS_BIAS, margin)) return;
            }
        }
        float nx = bestNx, ny = bestNy, nz = bestNz;
        float reach = boxReach(box, nx, ny, nz);
        int found = m.count;
        for (int e = 0; e < 3; e++) {
            clipTriangleEdge(box, e * 3, (e + 1) % 3 * 3, nx, ny, nz, reach, margin, m, sign);
        }
        if (m.count == found) boxEdgesThroughTriangle(box, fx, fy, fz, nx, ny, nz, reach, margin, m, sign);
    }

    /** False when the axis separates the shapes. Otherwise keeps it if it has the least overlap so far. */
    private boolean overlapsOn(PhysicsBody box, float x, float y, float z, float bias, float margin) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < EPSILON) return true; // Parallel edges: their cross product isn't an axis
        x /= len; y /= len; z /= len;
        float reach = boxReach(box, x, y, z);
        float p0 = triRel[0] * x + triRel[1] * y + triRel[2] * z;
        float p1 = triRel[3] * x + triRel[4] * y + triRel[5] * z;
        float p2 = triRel[6] * x + triRel[7] * y + triRel[8] * z;
        float above = Math.min(p0, Math.min(p1, p2)) - reach, below = -Math.max(p0, Math.max(p1, p2)) - reach;
        float gap = Math.max(above, below);
        if (gap > margin) return false;
        if (gap > bestGap + bias) {
            bestGap = gap;
            float toBox = above >= below ? -1.0f : 1.0f; // The normal runs from the mesh to the box
            bestNx = x * toBox; bestNy = y * toBox; bestNz = z * toBox;
        }
        return true;
    }

    /** How far the box reaches from its centre along a unit axis. */
    private static float boxReach(PhysicsBody box, float x, float y, float z) {
        float[] r = box.r;
        return Math.abs(r[0] * x + r[3] * y + r[6] * z) * box.hx + Math.abs(r[1] * x + r[4] * y + r[7] * z) * box.hy
                + Math.abs(r[2] * x + r[5] * y + r[8] * z) * box.hz;
    }

    /** Contacts where one triangle edge enters and leaves the box, grown by the margin. */
    private void clipTriangleEdge(PhysicsBody box, int a, int b, float nx, float ny, float nz, float reach, float margin,
                                  ContactManifold m, float sign) {
        float[] r = box.r;
        float ax = triRel[a], ay = triRel[a + 1], az = triRel[a + 2];
        float dx = triRel[b] - ax, dy = triRel[b + 1] - ay, dz = triRel[b + 2] - az;
        float enter = 0.0f, exit = 1.0f;
        for (int k = 0; k < 3; k++) {
            float half = (k == 0 ? box.hx : k == 1 ? box.hy : box.hz) + margin;
            float o = ax * r[k] + ay * r[3 + k] + az * r[6 + k];
            float d = dx * r[k] + dy * r[3 + k] + dz * r[6 + k];
            if (Math.abs(d) < EPSILON) {
                if (Math.abs(o) > half) return;
                continue;
            }
            float t1 = (-half - o) / d, t2 = (half - o) / d;
            enter = Math.max(enter, Math.min(t1, t2));
            exit = Math.min(exit, Math.max(t1, t2));
            if (enter > exit) return;
        }
        addCrossPoint(box, ax + dx * enter, ay + dy * enter, az + dz * enter, nx, ny, nz, reach, margin, m, sign);
        if (exit - enter > 1e-4f) addCrossPoint(box, ax + dx * exit, ay + dy * exit, az + dz * exit, nx, ny, nz, reach, margin, m, sign);
    }

    /** Contacts for a box edge resting on a triangle too small for any corner to reach, where no triangle edge enters the box. */
    private void boxEdgesThroughTriangle(PhysicsBody box, float fx, float fy, float fz, float nx, float ny, float nz, float reach,
                                         float margin, ContactManifold m, float sign) {
        float[] r = box.r;
        float plane = triRel[0] * fx + triRel[1] * fy + triRel[2] * fz;
        for (int c = 0; c < 8; c++) {
            float lx = (c & 1) != 0 ? box.hx : -box.hx, ly = (c & 2) != 0 ? box.hy : -box.hy, lz = (c & 4) != 0 ? box.hz : -box.hz;
            float x0 = r[0] * lx + r[1] * ly + r[2] * lz, y0 = r[3] * lx + r[4] * ly + r[5] * lz, z0 = r[6] * lx + r[7] * ly + r[8] * lz;
            float d0 = x0 * fx + y0 * fy + z0 * fz - plane;
            for (int k = 0; k < 3; k++) {
                if ((c & (1 << k)) != 0) continue; // Each edge once, from its negative end
                float span = 2.0f * (k == 0 ? box.hx : k == 1 ? box.hy : box.hz);
                float ex = r[k] * span, ey = r[3 + k] * span, ez = r[6 + k] * span;
                float d1 = d0 + ex * fx + ey * fy + ez * fz;
                if ((d0 > 0.0f) == (d1 > 0.0f)) continue;
                float t = d0 / (d0 - d1);
                float x = x0 + ex * t, y = y0 + ey * t, z = z0 + ez * t;
                closestOnTriangle(x + box.cx, y + box.cy, z + box.cz, triPoint);
                float ox = triPoint[0] - box.cx - x, oy = triPoint[1] - box.cy - y, oz = triPoint[2] - box.cz - z;
                if (ox * ox + oy * oy + oz * oz <= 1e-8f) addCrossPoint(box, x, y, z, nx, ny, nz, reach, margin, m, sign);
            }
        }
    }

    /** Adds a point given relative to the box, measured against the face of the box's hull that the normal leaves from. */
    private static void addCrossPoint(PhysicsBody box, float x, float y, float z, float nx, float ny, float nz, float reach, float margin,
                                      ContactManifold m, float sign) {
        float sep = -reach - (x * nx + y * ny + z * nz);
        if (sep <= margin) m.add(x + box.cx, y + box.cy, z + box.cz, nx * sign, ny * sign, nz * sign, sep);
    }

    /** Closest point on the current triangle to (x, y, z), by Voronoi region. */
    private void closestOnTriangle(float x, float y, float z, float[] out) {
        float ax = tri[0], ay = tri[1], az = tri[2];
        float abx = tri[3] - ax, aby = tri[4] - ay, abz = tri[5] - az;
        float acx = tri[6] - ax, acy = tri[7] - ay, acz = tri[8] - az;
        float apx = x - ax, apy = y - ay, apz = z - az;
        float d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0.0f && d2 <= 0.0f) { out[0] = ax; out[1] = ay; out[2] = az; return; }

        float bpx = x - tri[3], bpy = y - tri[4], bpz = z - tri[5];
        float d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0.0f && d4 <= d3) { out[0] = tri[3]; out[1] = tri[4]; out[2] = tri[5]; return; }

        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0f && d1 >= 0.0f && d3 <= 0.0f) {
            float v = d1 / (d1 - d3);
            out[0] = ax + abx * v; out[1] = ay + aby * v; out[2] = az + abz * v;
            return;
        }

        float cpx = x - tri[6], cpy = y - tri[7], cpz = z - tri[8];
        float d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0.0f && d5 <= d6) { out[0] = tri[6]; out[1] = tri[7]; out[2] = tri[8]; return; }

        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0f && d2 >= 0.0f && d6 <= 0.0f) {
            float w = d2 / (d2 - d6);
            out[0] = ax + acx * w; out[1] = ay + acy * w; out[2] = az + acz * w;
            return;
        }

        float va = d3 * d6 - d5 * d4;
        if (va <= 0.0f && (d4 - d3) >= 0.0f && (d5 - d6) >= 0.0f) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            out[0] = tri[3] + (tri[6] - tri[3]) * w;
            out[1] = tri[4] + (tri[7] - tri[4]) * w;
            out[2] = tri[5] + (tri[8] - tri[5]) * w;
            return;
        }

        float denom = 1.0f / (va + vb + vc);
        float v = vb * denom, w = vc * denom;
        out[0] = ax + abx * v + acx * w; out[1] = ay + aby * v + acy * w; out[2] = az + abz * v + acz * w;
    }
}
