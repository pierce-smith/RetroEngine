package com.retro.engine.physics;

import com.retro.engine.physics.PhysicsSystem.PhysicsBody;

/**
 * Ray tests against one body's real shape. {@code inflate} grows the shape, which turns a ray into a sphere sweep. A ray
 * that starts inside the shape misses it, so a probe from inside a collider ignores that collider.
 */
final class ShapeRaycast {

    private float hitT, hitNx, hitNy, hitNz; // Local-space result of the last round test

    float normalX, normalY, normalZ;

    /** Distance along the unit ray to the shape, or -1 on a miss. The world normal lands in normalX/Y/Z. */
    float cast(PhysicsBody body, float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance, float inflate) {
        float[] r = body.r;
        float rx = ox - body.cx, ry = oy - body.cy, rz = oz - body.cz;
        float lox = r[0] * rx + r[3] * ry + r[6] * rz;
        float loy = r[1] * rx + r[4] * ry + r[7] * rz;
        float loz = r[2] * rx + r[5] * ry + r[8] * rz;
        float ldx = r[0] * dx + r[3] * dy + r[6] * dz;
        float ldy = r[1] * dx + r[4] * dy + r[7] * dz;
        float ldz = r[2] * dx + r[5] * dy + r[8] * dz;

        if (body.shape == PhysicsBody.SHAPE_MESH) {
            float t = body.mesh.raycast(lox, loy, loz, ldx, ldy, ldz, maxDistance, inflate);
            if (t < 0.0f || t > maxDistance) return -1.0f;
            CollisionMesh mesh = body.mesh;
            normalX = r[0] * mesh.hitNormalX + r[1] * mesh.hitNormalY + r[2] * mesh.hitNormalZ;
            normalY = r[3] * mesh.hitNormalX + r[4] * mesh.hitNormalY + r[5] * mesh.hitNormalZ;
            normalZ = r[6] * mesh.hitNormalX + r[7] * mesh.hitNormalY + r[8] * mesh.hitNormalZ;
            return t;
        }

        boolean hit = body.shape == PhysicsBody.SHAPE_BOX
                ? castBox(body.hx + inflate, body.hy + inflate, body.hz + inflate, lox, loy, loz, ldx, ldy, ldz)
                : castRound(body.radius + inflate, body.segmentHalf, lox, loy, loz, ldx, ldy, ldz);
        if (!hit || hitT > maxDistance) return -1.0f;

        normalX = r[0] * hitNx + r[1] * hitNy + r[2] * hitNz;
        normalY = r[3] * hitNx + r[4] * hitNy + r[5] * hitNz;
        normalZ = r[6] * hitNx + r[7] * hitNy + r[8] * hitNz;
        return hitT;
    }

    private boolean castBox(float hx, float hy, float hz, float ox, float oy, float oz, float dx, float dy, float dz) {
        float tMin = -Float.MAX_VALUE, tMax = Float.MAX_VALUE;
        int axis = -1;
        float sign = 0.0f;
        for (int i = 0; i < 3; i++) {
            float o = i == 0 ? ox : (i == 1 ? oy : oz);
            float d = i == 0 ? dx : (i == 1 ? dy : dz);
            float h = i == 0 ? hx : (i == 1 ? hy : hz);
            if (Math.abs(d) < 1e-9f) {
                if (o < -h || o > h) return false;
                continue;
            }
            float t1 = (-h - o) / d, t2 = (h - o) / d;
            float s = -1.0f;
            if (t1 > t2) { float swap = t1; t1 = t2; t2 = swap; s = 1.0f; }
            if (t1 > tMin) { tMin = t1; axis = i; sign = s; }
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }
        if (axis < 0 || tMin < 0.0f) return false;
        hitT = tMin;
        hitNx = axis == 0 ? sign : 0.0f;
        hitNy = axis == 1 ? sign : 0.0f;
        hitNz = axis == 2 ? sign : 0.0f;
        return true;
    }

    /** Capsule along local Y: an open cylinder plus a sphere at each end. A sphere has a zero-length segment. */
    private boolean castRound(float radius, float seg, float ox, float oy, float oz, float dx, float dy, float dz) {
        float best = Float.MAX_VALUE;
        float sideSq = ox * ox + oz * oz - radius * radius;
        if (sideSq <= 0.0f && Math.abs(oy) <= seg) return false; // Inside the cylinder

        if (seg > 0.0f) {
            float a = dx * dx + dz * dz;
            if (a > 1e-12f) {
                float b = ox * dx + oz * dz;
                float disc = b * b - a * sideSq;
                if (disc >= 0.0f) {
                    float t = (-b - (float) Math.sqrt(disc)) / a;
                    float y = oy + dy * t;
                    if (t >= 0.0f && Math.abs(y) <= seg) {
                        best = t;
                        hitNx = (ox + dx * t) / radius; hitNy = 0.0f; hitNz = (oz + dz * t) / radius;
                    }
                }
            }
        }
        for (int end = -1; end <= 1; end += 2) {
            float cy = end * seg;
            float ey = oy - cy;
            float c = ox * ox + ey * ey + oz * oz - radius * radius;
            if (c <= 0.0f) return false; // Inside this end
            float b = ox * dx + ey * dy + oz * dz;
            float disc = b * b - c;
            if (b > 0.0f || disc < 0.0f) continue;
            float t = -b - (float) Math.sqrt(disc);
            if (t < best) {
                best = t;
                hitNx = (ox + dx * t) / radius; hitNy = (ey + dy * t) / radius; hitNz = (oz + dz * t) / radius;
            }
            if (seg == 0.0f) break;
        }
        if (best == Float.MAX_VALUE) return false;
        hitT = best;
        return true;
    }
}
