package com.retro.engine.physics;

/**
 * Rotation helpers for the solver. Matrices are row-major 3x3 and match HierarchySystem.rotationMatrix, so a local
 * vector rotated here lands where the renderers put it. The local X, Y and Z axes are the matrix columns.
 */
final class PhysicsMath {

    private PhysicsMath() {}

    /** Rotation matrix for Transform Euler angles in degrees. */
    static void eulerToMatrix(float xDeg, float yDeg, float zDeg, float[] m) {
        double ax = Math.toRadians(xDeg), ay = Math.toRadians(yDeg), az = Math.toRadians(zDeg);
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
        m[0] = (float) (cy * cz);  m[1] = (float) (cx * sz + sx * sy * cz);  m[2] = (float) (sx * sz - cx * sy * cz);
        m[3] = (float) (-cy * sz); m[4] = (float) (cx * cz - sx * sy * sz);  m[5] = (float) (sx * cz + cx * sy * sz);
        m[6] = (float) sy;         m[7] = (float) (-sx * cy);                m[8] = (float) (cx * cy);
    }

    /** Euler degrees for a rotation matrix, written to out. Gimbal lock folds roll into pitch. */
    static void matrixToEuler(float[] m, float[] out) {
        double sy = Math.max(-1.0, Math.min(1.0, m[6]));
        out[1] = (float) Math.toDegrees(Math.asin(sy));
        if (Math.abs(sy) < 0.99999) {
            out[0] = (float) Math.toDegrees(Math.atan2(-m[7], m[8]));
            out[2] = (float) Math.toDegrees(Math.atan2(-m[3], m[0]));
        } else {
            out[0] = (float) Math.toDegrees(Math.atan2(sy * m[1], m[4]));
            out[2] = 0.0f;
        }
    }

    /** Unit quaternion (x, y, z, w) for a rotation matrix. */
    static void matrixToQuat(float[] m, float[] q) {
        float trace = m[0] + m[4] + m[8];
        float x, y, z, w;
        if (trace > 0.0f) {
            float s = (float) Math.sqrt(trace + 1.0f) * 2.0f;
            w = 0.25f * s; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            float s = (float) Math.sqrt(1.0f + m[0] - m[4] - m[8]) * 2.0f;
            w = (m[7] - m[5]) / s; x = 0.25f * s; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            float s = (float) Math.sqrt(1.0f + m[4] - m[0] - m[8]) * 2.0f;
            w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = 0.25f * s; z = (m[5] + m[7]) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + m[8] - m[0] - m[4]) * 2.0f;
            w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = 0.25f * s;
        }
        q[0] = x; q[1] = y; q[2] = z; q[3] = w;
    }

    /** Rotation matrix for a unit quaternion (x, y, z, w). */
    static void quatToMatrix(float[] q, float[] m) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - z * w);     m[2] = 2 * (x * z + y * w);
        m[3] = 2 * (x * y + z * w);     m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - x * w);
        m[6] = 2 * (x * z - y * w);     m[7] = 2 * (y * z + x * w);     m[8] = 1 - 2 * (x * x + y * y);
    }

    /** Advances q by angular velocity (wx, wy, wz) in rad/s over dt, and renormalizes. Uses only sqrt, so it is exact across machines. */
    static void integrateQuat(float[] q, float wx, float wy, float wz, float dt) {
        float h = 0.5f * dt;
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float nx = x + h * (wx * w + wy * z - wz * y);
        float ny = y + h * (wy * w + wz * x - wx * z);
        float nz = z + h * (wz * w + wx * y - wy * x);
        float nw = w + h * (-wx * x - wy * y - wz * z);
        float inv = 1.0f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
        q[0] = nx * inv; q[1] = ny * inv; q[2] = nz * inv; q[3] = nw * inv;
    }
}
