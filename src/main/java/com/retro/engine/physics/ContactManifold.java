package com.retro.engine.physics;

/** The contact points between one pair of bodies for one step. Instances are pooled and reused every step. */
final class ContactManifold {

    static final int MAX_POINTS = 4;

    /** One constraint direction of a contact point, with its precomputed Jacobian terms. */
    static final class Row {
        float dx, dy, dz;      // Direction, pointing from A to B for the normal row
        float rax, ray, raz;   // rA x d
        float rbx, rby, rbz;   // rB x d
        float iax, iay, iaz;   // invInertiaA * (rA x d)
        float ibx, iby, ibz;   // invInertiaB * (rB x d)
        float mass;            // 1 / effective mass
        float impulse;         // Accumulated over the step, and kept for the next step's warm start
    }

    static final class Point {
        float x, y, z;         // World position
        float nx, ny, nz;      // Unit normal from A to B
        float separation;      // Positive is a gap, negative is overlap
        float localX, localY, localZ; // Position in A's frame, used to match points across steps
        float approachSpeed;   // Normal velocity before solving, negative when closing
        float maxNormalImpulse;
        float pseudoImpulse;
        final Row normal = new Row();
        final Row tangent1 = new Row();
        final Row tangent2 = new Row();
    }

    PhysicsSystem.PhysicsBody a, b;
    long key;
    int count;
    float friction, restitution;
    final Point[] points = new Point[MAX_POINTS];

    ContactManifold() {
        for (int i = 0; i < MAX_POINTS; i++) points[i] = new Point();
    }

    void add(float x, float y, float z, float nx, float ny, float nz, float separation) {
        Point p;
        if (count < MAX_POINTS) {
            p = points[count++];
        } else {
            // Triangle meshes can offer more than fit, and the deepest points matter most
            p = points[0];
            for (int i = 1; i < MAX_POINTS; i++) {
                if (points[i].separation > p.separation) p = points[i];
            }
            if (separation >= p.separation) return;
        }
        p.x = x; p.y = y; p.z = z;
        p.nx = nx; p.ny = ny; p.nz = nz;
        p.separation = separation;
    }

    /** The most overlapped point. Only valid when count is above zero. */
    Point deepest() {
        Point best = points[0];
        for (int i = 1; i < count; i++) {
            if (points[i].separation < best.separation) best = points[i];
        }
        return best;
    }

    /** True when the bodies overlap, or when the solver had to stop them reaching each other this step. */
    boolean isTouching() {
        for (int i = 0; i < count; i++) {
            Point p = points[i];
            if (p.separation < -PhysicsSystem.TOUCH_TOLERANCE || p.maxNormalImpulse > 0.0f) return true;
        }
        return false;
    }
}
