package com.retro.engine.physics;

import com.retro.engine.physics.ContactManifold.Point;
import com.retro.engine.physics.ContactManifold.Row;
import com.retro.engine.physics.PhysicsSystem.PhysicsBody;

/**
 * Sequential impulse contact solver: warm-started velocity iterations with Coulomb friction, then restitution, then a
 * split-impulse pass for overlap. The overlap pass moves bodies without touching their real velocity, so pushing a body
 * out of a surface adds no energy. Only DYNAMIC bodies have mass here, so everything else behaves as immovable.
 */
final class ContactSolver {

    private static final int VELOCITY_ITERATIONS = 8;
    private static final int POSITION_ITERATIONS = 4;
    private static final float SLOP = 0.005f;              // Overlap left alone, which keeps resting contacts alive
    private static final float CORRECTION_RATE = 0.2f;     // Fraction of the overlap removed per step
    private static final float MAX_CORRECTION_SPEED = 4.0f;
    private static final float RESTITUTION_THRESHOLD = 1.0f; // Slower impacts don't bounce, so bodies can settle

    void solve(ContactManifold[] manifolds, int count, float dt) {
        for (int i = 0; i < count; i++) prepare(manifolds[i]);
        for (int i = 0; i < count; i++) warmStart(manifolds[i]);
        float invDt = 1.0f / dt;
        for (int iter = 0; iter < VELOCITY_ITERATIONS; iter++) {
            for (int i = 0; i < count; i++) solveVelocity(manifolds[i], invDt);
        }
        for (int i = 0; i < count; i++) applyRestitution(manifolds[i]);
        for (int iter = 0; iter < POSITION_ITERATIONS; iter++) {
            for (int i = 0; i < count; i++) solveOverlap(manifolds[i], invDt);
        }
    }

    private static boolean isInert(ContactManifold m) {
        return m.a.invMass == 0.0f && m.b.invMass == 0.0f;
    }

    private static void prepare(ContactManifold m) {
        if (isInert(m)) return;
        PhysicsBody a = m.a, b = m.b;
        for (int i = 0; i < m.count; i++) {
            Point p = m.points[i];
            float rax = p.x - a.cx, ray = p.y - a.cy, raz = p.z - a.cz;
            float rbx = p.x - b.cx, rby = p.y - b.cy, rbz = p.z - b.cz;
            float nx = p.nx, ny = p.ny, nz = p.nz;
            // Any two directions across the normal will do, but they must be the same given the same normal
            float tx, ty, tz;
            if (Math.abs(nx) >= 0.57735f) { tx = ny; ty = -nx; tz = 0.0f; }
            else { tx = 0.0f; ty = nz; tz = -ny; }
            float inv = 1.0f / (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            tx *= inv; ty *= inv; tz *= inv;
            setRow(p.normal, a, b, rax, ray, raz, rbx, rby, rbz, nx, ny, nz);
            setRow(p.tangent1, a, b, rax, ray, raz, rbx, rby, rbz, tx, ty, tz);
            setRow(p.tangent2, a, b, rax, ray, raz, rbx, rby, rbz, ny * tz - nz * ty, nz * tx - nx * tz, nx * ty - ny * tx);
            p.approachSpeed = relativeSpeed(p.normal, a, b);
            p.maxNormalImpulse = 0.0f;
            p.pseudoImpulse = 0.0f;
        }
    }

    private static void setRow(Row row, PhysicsBody a, PhysicsBody b, float rax, float ray, float raz,
                               float rbx, float rby, float rbz, float dx, float dy, float dz) {
        row.dx = dx; row.dy = dy; row.dz = dz;
        row.rax = ray * dz - raz * dy; row.ray = raz * dx - rax * dz; row.raz = rax * dy - ray * dx;
        row.rbx = rby * dz - rbz * dy; row.rby = rbz * dx - rbx * dz; row.rbz = rbx * dy - rby * dx;
        float[] ia = a.invInertia, ib = b.invInertia;
        row.iax = ia[0] * row.rax + ia[1] * row.ray + ia[2] * row.raz;
        row.iay = ia[3] * row.rax + ia[4] * row.ray + ia[5] * row.raz;
        row.iaz = ia[6] * row.rax + ia[7] * row.ray + ia[8] * row.raz;
        row.ibx = ib[0] * row.rbx + ib[1] * row.rby + ib[2] * row.rbz;
        row.iby = ib[3] * row.rbx + ib[4] * row.rby + ib[5] * row.rbz;
        row.ibz = ib[6] * row.rbx + ib[7] * row.rby + ib[8] * row.rbz;
        float k = a.invMass + b.invMass
                + row.rax * row.iax + row.ray * row.iay + row.raz * row.iaz
                + row.rbx * row.ibx + row.rby * row.iby + row.rbz * row.ibz;
        row.mass = k > 0.0f ? 1.0f / k : 0.0f;
    }

    private static float relativeSpeed(Row r, PhysicsBody a, PhysicsBody b) {
        return (b.svx - a.svx) * r.dx + (b.svy - a.svy) * r.dy + (b.svz - a.svz) * r.dz
                + b.avx * r.rbx + b.avy * r.rby + b.avz * r.rbz
                - a.avx * r.rax - a.avy * r.ray - a.avz * r.raz;
    }

    private static void applyImpulse(Row r, PhysicsBody a, PhysicsBody b, float impulse) {
        float la = impulse * a.invMass, lb = impulse * b.invMass;
        a.svx -= r.dx * la; a.svy -= r.dy * la; a.svz -= r.dz * la;
        b.svx += r.dx * lb; b.svy += r.dy * lb; b.svz += r.dz * lb;
        a.avx -= r.iax * impulse; a.avy -= r.iay * impulse; a.avz -= r.iaz * impulse;
        b.avx += r.ibx * impulse; b.avy += r.iby * impulse; b.avz += r.ibz * impulse;
    }

    private static void warmStart(ContactManifold m) {
        if (isInert(m)) return;
        for (int i = 0; i < m.count; i++) {
            Point p = m.points[i];
            applyImpulse(p.normal, m.a, m.b, p.normal.impulse);
            applyImpulse(p.tangent1, m.a, m.b, p.tangent1.impulse);
            applyImpulse(p.tangent2, m.a, m.b, p.tangent2.impulse);
        }
    }

    private static void solveVelocity(ContactManifold m, float invDt) {
        if (isInert(m)) return;
        PhysicsBody a = m.a, b = m.b;
        for (int i = 0; i < m.count; i++) {
            Point p = m.points[i];
            float limit = m.friction * p.normal.impulse;
            solveFriction(p.tangent1, a, b, limit);
            solveFriction(p.tangent2, a, b, limit);

            Row n = p.normal;
            // A gap may close by exactly its own width this step, so a body lands on a surface rather than in it
            float allowed = p.separation > 0.0f ? p.separation * invDt : 0.0f;
            float delta = -n.mass * (relativeSpeed(n, a, b) + allowed);
            float total = Math.max(n.impulse + delta, 0.0f);
            applyImpulse(n, a, b, total - n.impulse);
            n.impulse = total;
            if (total > p.maxNormalImpulse) p.maxNormalImpulse = total;
        }
    }

    private static void solveFriction(Row t, PhysicsBody a, PhysicsBody b, float limit) {
        float delta = -t.mass * relativeSpeed(t, a, b);
        float total = Math.max(-limit, Math.min(limit, t.impulse + delta));
        applyImpulse(t, a, b, total - t.impulse);
        t.impulse = total;
    }

    /** Runs after the velocity iterations, because a contact stopped short of the surface must still bounce. */
    private static void applyRestitution(ContactManifold m) {
        if (m.restitution <= 0.0f || isInert(m)) return;
        for (int i = 0; i < m.count; i++) {
            Point p = m.points[i];
            if (p.approachSpeed > -RESTITUTION_THRESHOLD || p.maxNormalImpulse <= 0.0f) continue;
            Row n = p.normal;
            float delta = -n.mass * (relativeSpeed(n, m.a, m.b) + m.restitution * p.approachSpeed);
            float total = Math.max(n.impulse + delta, 0.0f);
            applyImpulse(n, m.a, m.b, total - n.impulse);
            n.impulse = total;
        }
    }

    private static void solveOverlap(ContactManifold m, float invDt) {
        if (isInert(m)) return;
        PhysicsBody a = m.a, b = m.b;
        for (int i = 0; i < m.count; i++) {
            Point p = m.points[i];
            float overlap = -p.separation - SLOP;
            if (overlap <= 0.0f) continue;
            Row r = p.normal;
            float target = Math.min(overlap * CORRECTION_RATE * invDt, MAX_CORRECTION_SPEED);
            float speed = (b.pvx - a.pvx) * r.dx + (b.pvy - a.pvy) * r.dy + (b.pvz - a.pvz) * r.dz
                    + b.pwx * r.rbx + b.pwy * r.rby + b.pwz * r.rbz
                    - a.pwx * r.rax - a.pwy * r.ray - a.pwz * r.raz;
            float delta = r.mass * (target - speed);
            float total = Math.max(p.pseudoImpulse + delta, 0.0f);
            float impulse = total - p.pseudoImpulse;
            p.pseudoImpulse = total;
            float la = impulse * a.invMass, lb = impulse * b.invMass;
            a.pvx -= r.dx * la; a.pvy -= r.dy * la; a.pvz -= r.dz * la;
            b.pvx += r.dx * lb; b.pvy += r.dy * lb; b.pvz += r.dz * lb;
            a.pwx -= r.iax * impulse; a.pwy -= r.iay * impulse; a.pwz -= r.iaz * impulse;
            b.pwx += r.ibx * impulse; b.pwy += r.iby * impulse; b.pwz += r.ibz * impulse;
        }
    }
}
