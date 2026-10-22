package com.retro.engine.animation;

/**
 * Stores keyframe samples for translation, rotation (quaternion), and scale
 * for a specific bone node over time, with high-performance NLERP/SLERP interpolation.
 */
public class KeyframeTrack {

    public String boneName;
    public int boneIndex = -1;

    // Translation Keyframes
    public float[] positionTimes = new float[0];
    public float[][] positions = new float[0][3]; // [N] (X, Y, Z)

    // Rotation Keyframes
    public float[] rotationTimes = new float[0];
    public float[][] rotations = new float[0][4]; // [N] (QX, QY, QZ, QW)

    // Scale Keyframes
    public float[] scaleTimes = new float[0];
    public float[][] scales = new float[0][3]; // [N] (SX, SY, SZ)

    public KeyframeTrack(String boneName) {
        this.boneName = boneName;
    }

    /**
     * Evaluates interpolated 3D position vector [X, Y, Z] at specified time t.
     */
    public void samplePosition(float time, float[] outPos) {
        if (positions.length == 0) {
            outPos[0] = 0.0f; outPos[1] = 0.0f; outPos[2] = 0.0f;
            return;
        }
        if (positions.length == 1 || time <= positionTimes[0]) {
            System.arraycopy(positions[0], 0, outPos, 0, 3);
            return;
        }
        if (time >= positionTimes[positionTimes.length - 1]) {
            System.arraycopy(positions[positions.length - 1], 0, outPos, 0, 3);
            return;
        }

        int idx = findTimeIndex(positionTimes, time);
        int nextIdx = idx + 1;
        float t0 = positionTimes[idx];
        float t1 = positionTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        float[] p0 = positions[idx];
        float[] p1 = positions[nextIdx];
        outPos[0] = p0[0] + factor * (p1[0] - p0[0]);
        outPos[1] = p0[1] + factor * (p1[1] - p0[1]);
        outPos[2] = p0[2] + factor * (p1[2] - p0[2]);
    }

    /**
     * Evaluates interpolated Normalized Quaternion [QX, QY, QZ, QW] at time t using SLERP.
     */
    public void sampleRotation(float time, float[] outQuat) {
        if (rotations.length == 0) {
            outQuat[0] = 0.0f; outQuat[1] = 0.0f; outQuat[2] = 0.0f; outQuat[3] = 1.0f;
            return;
        }
        if (rotations.length == 1 || time <= rotationTimes[0]) {
            System.arraycopy(rotations[0], 0, outQuat, 0, 4);
            return;
        }
        if (time >= rotationTimes[rotationTimes.length - 1]) {
            System.arraycopy(rotations[rotations.length - 1], 0, outQuat, 0, 4);
            return;
        }

        int idx = findTimeIndex(rotationTimes, time);
        int nextIdx = idx + 1;
        float t0 = rotationTimes[idx];
        float t1 = rotationTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        float[] q0 = rotations[idx];
        float[] q1 = rotations[nextIdx];

        slerpQuat(q0, q1, factor, outQuat);
    }

    /**
     * Evaluates interpolated 3D scale vector [SX, SY, SZ] at specified time t.
     */
    public void sampleScale(float time, float[] outScale) {
        if (scales.length == 0) {
            outScale[0] = 1.0f; outScale[1] = 1.0f; outScale[2] = 1.0f;
            return;
        }
        if (scales.length == 1 || time <= scaleTimes[0]) {
            System.arraycopy(scales[0], 0, outScale, 0, 3);
            return;
        }
        if (time >= scaleTimes[scaleTimes.length - 1]) {
            System.arraycopy(scales[scales.length - 1], 0, outScale, 0, 3);
            return;
        }

        int idx = findTimeIndex(scaleTimes, time);
        int nextIdx = idx + 1;
        float t0 = scaleTimes[idx];
        float t1 = scaleTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        float[] s0 = scales[idx];
        float[] s1 = scales[nextIdx];
        outScale[0] = s0[0] + factor * (s1[0] - s0[0]);
        outScale[1] = s0[1] + factor * (s1[1] - s0[1]);
        outScale[2] = s0[2] + factor * (s1[2] - s0[2]);
    }

    private static int findTimeIndex(float[] times, float t) {
        for (int i = 0; i < times.length - 1; i++) {
            if (t >= times[i] && t < times[i + 1]) {
                return i;
            }
        }
        return times.length - 2;
    }

    private static void slerpQuat(float[] q1, float[] q2, float t, float[] out) {
        float cosTheta = q1[0]*q2[0] + q1[1]*q2[1] + q1[2]*q2[2] + q1[3]*q2[3];
        float q2x = q2[0], q2y = q2[1], q2z = q2[2], q2w = q2[3];

        if (cosTheta < 0.0f) {
            cosTheta = -cosTheta;
            q2x = -q2x; q2y = -q2y; q2z = -q2z; q2w = -q2w;
        }

        float scale0, scale1;
        if (1.0f - cosTheta > 0.001f) {
            float theta = (float) Math.acos(cosTheta);
            float sinTheta = (float) Math.sin(theta);
            scale0 = (float) Math.sin((1.0f - t) * theta) / sinTheta;
            scale1 = (float) Math.sin(t * theta) / sinTheta;
        } else {
            scale0 = 1.0f - t;
            scale1 = t;
        }

        out[0] = scale0 * q1[0] + scale1 * q2x;
        out[1] = scale0 * q1[1] + scale1 * q2y;
        out[2] = scale0 * q1[2] + scale1 * q2z;
        out[3] = scale0 * q1[3] + scale1 * q2w;

        float len = (float) Math.sqrt(out[0]*out[0] + out[1]*out[1] + out[2]*out[2] + out[3]*out[3]);
        if (len > 0.0f) {
            out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len;
        }
    }
}
