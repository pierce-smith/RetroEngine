package com.retro.engine.animation.clips;

/**
 * Stores keyframe samples for translation, rotation (quaternion), and scale
 * for a specific bone node over time, with high-performance NLERP/SLERP interpolation
 * using flat 1D contiguous arrays for zero-allocation hot paths.
 */
public class KeyframeTrack {

    public String boneName;
    public int boneIndex = -1;

    // Translation Keyframes (Flat 1D array: [X0, Y0, Z0, X1, Y1, Z1, ...])
    public float[] positionTimes = new float[0];
    public float[] positions = new float[0];

    // Rotation Keyframes (Flat 1D array: [QX0, QY0, QZ0, QW0, QX1, QY1, QZ1, QW1, ...])
    public float[] rotationTimes = new float[0];
    public float[] rotations = new float[0];

    // Scale Keyframes (Flat 1D array: [SX0, SY0, SZ0, SX1, SY1, SZ1, ...])
    public float[] scaleTimes = new float[0];
    public float[] scales = new float[0];

    public KeyframeTrack(String boneName) {
        this.boneName = boneName;
    }

    public void setPositions(float[] flatPositions) {
        this.positions = (flatPositions != null) ? flatPositions : new float[0];
    }

    public void setPositions(float[][] pos2D) {
        if (pos2D == null || pos2D.length == 0) {
            this.positions = new float[0];
            return;
        }
        this.positions = new float[pos2D.length * 3];
        for (int i = 0; i < pos2D.length; i++) {
            this.positions[i * 3]     = pos2D[i][0];
            this.positions[i * 3 + 1] = pos2D[i][1];
            this.positions[i * 3 + 2] = pos2D[i][2];
        }
    }

    public void setRotations(float[] flatRotations) {
        this.rotations = (flatRotations != null) ? flatRotations : new float[0];
    }

    public void setRotations(float[][] rot2D) {
        if (rot2D == null || rot2D.length == 0) {
            this.rotations = new float[0];
            return;
        }
        this.rotations = new float[rot2D.length * 4];
        for (int i = 0; i < rot2D.length; i++) {
            this.rotations[i * 4]     = rot2D[i][0];
            this.rotations[i * 4 + 1] = rot2D[i][1];
            this.rotations[i * 4 + 2] = rot2D[i][2];
            this.rotations[i * 4 + 3] = rot2D[i][3];
        }
    }

    public void setScales(float[] flatScales) {
        this.scales = (flatScales != null) ? flatScales : new float[0];
    }

    public void setScales(float[][] scale2D) {
        if (scale2D == null || scale2D.length == 0) {
            this.scales = new float[0];
            return;
        }
        this.scales = new float[scale2D.length * 3];
        for (int i = 0; i < scale2D.length; i++) {
            this.scales[i * 3]     = scale2D[i][0];
            this.scales[i * 3 + 1] = scale2D[i][1];
            this.scales[i * 3 + 2] = scale2D[i][2];
        }
    }

    /**
     * Evaluates interpolated 3D position vector [X, Y, Z] at specified time t.
     */
    public void samplePosition(float time, float[] outPos) {
        int numKeys = positionTimes.length;
        if (numKeys == 0 || positions.length < 3) {
            outPos[0] = 0.0f; outPos[1] = 0.0f; outPos[2] = 0.0f;
            return;
        }
        if (numKeys == 1 || time <= positionTimes[0]) {
            outPos[0] = positions[0]; outPos[1] = positions[1]; outPos[2] = positions[2];
            return;
        }
        if (time >= positionTimes[numKeys - 1]) {
            int lastIdx = (numKeys - 1) * 3;
            outPos[0] = positions[lastIdx]; outPos[1] = positions[lastIdx + 1]; outPos[2] = positions[lastIdx + 2];
            return;
        }

        int idx = findTimeIndex(positionTimes, time);
        int nextIdx = idx + 1;
        float t0 = positionTimes[idx];
        float t1 = positionTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        int i0 = idx * 3;
        int i1 = nextIdx * 3;
        outPos[0] = positions[i0] + factor * (positions[i1] - positions[i0]);
        outPos[1] = positions[i0 + 1] + factor * (positions[i1 + 1] - positions[i0 + 1]);
        outPos[2] = positions[i0 + 2] + factor * (positions[i1 + 2] - positions[i0 + 2]);
    }

    /**
     * Evaluates interpolated Normalized Quaternion [QX, QY, QZ, QW] at time t using SLERP.
     */
    public void sampleRotation(float time, float[] outQuat) {
        int numKeys = rotationTimes.length;
        if (numKeys == 0 || rotations.length < 4) {
            outQuat[0] = 0.0f; outQuat[1] = 0.0f; outQuat[2] = 0.0f; outQuat[3] = 1.0f;
            return;
        }
        if (numKeys == 1 || time <= rotationTimes[0]) {
            outQuat[0] = rotations[0]; outQuat[1] = rotations[1]; outQuat[2] = rotations[2]; outQuat[3] = rotations[3];
            return;
        }
        if (time >= rotationTimes[numKeys - 1]) {
            int lastIdx = (numKeys - 1) * 4;
            outQuat[0] = rotations[lastIdx]; outQuat[1] = rotations[lastIdx + 1]; outQuat[2] = rotations[lastIdx + 2]; outQuat[3] = rotations[lastIdx + 3];
            return;
        }

        int idx = findTimeIndex(rotationTimes, time);
        int nextIdx = idx + 1;
        float t0 = rotationTimes[idx];
        float t1 = rotationTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        int i0 = idx * 4;
        int i1 = nextIdx * 4;

        slerpQuatDirect(rotations, i0, rotations, i1, factor, outQuat);
    }

    /**
     * Evaluates interpolated 3D scale vector [SX, SY, SZ] at specified time t.
     */
    public void sampleScale(float time, float[] outScale) {
        int numKeys = scaleTimes.length;
        if (numKeys == 0 || scales.length < 3) {
            outScale[0] = 1.0f; outScale[1] = 1.0f; outScale[2] = 1.0f;
            return;
        }
        if (numKeys == 1 || time <= scaleTimes[0]) {
            outScale[0] = scales[0]; outScale[1] = scales[1]; outScale[2] = scales[2];
            return;
        }
        if (time >= scaleTimes[numKeys - 1]) {
            int lastIdx = (numKeys - 1) * 3;
            outScale[0] = scales[lastIdx]; outScale[1] = scales[lastIdx + 1]; outScale[2] = scales[lastIdx + 2];
            return;
        }

        int idx = findTimeIndex(scaleTimes, time);
        int nextIdx = idx + 1;
        float t0 = scaleTimes[idx];
        float t1 = scaleTimes[nextIdx];
        float factor = (time - t0) / (t1 - t0);

        int i0 = idx * 3;
        int i1 = nextIdx * 3;
        outScale[0] = scales[i0] + factor * (scales[i1] - scales[i0]);
        outScale[1] = scales[i0 + 1] + factor * (scales[i1 + 1] - scales[i0 + 1]);
        outScale[2] = scales[i0 + 2] + factor * (scales[i1 + 2] - scales[i0 + 2]);
    }

    private static int findTimeIndex(float[] times, float t) {
        for (int i = 0; i < times.length - 1; i++) {
            if (t >= times[i] && t < times[i + 1]) {
                return i;
            }
        }
        return times.length - 2;
    }

    private static void slerpQuatDirect(float[] arr1, int off1, float[] arr2, int off2, float t, float[] out) {
        float q1x = arr1[off1],     q1y = arr1[off1 + 1], q1z = arr1[off1 + 2], q1w = arr1[off1 + 3];
        float q2x = arr2[off2],     q2y = arr2[off2 + 1], q2z = arr2[off2 + 2], q2w = arr2[off2 + 3];

        float cosTheta = q1x * q2x + q1y * q2y + q1z * q2z + q1w * q2w;

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

        out[0] = scale0 * q1x + scale1 * q2x;
        out[1] = scale0 * q1y + scale1 * q2y;
        out[2] = scale0 * q1z + scale1 * q2z;
        out[3] = scale0 * q1w + scale1 * q2w;

        float len = (float) Math.sqrt(out[0]*out[0] + out[1]*out[1] + out[2]*out[2] + out[3]*out[3]);
        if (len > 0.0f) {
            out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len;
        }
    }
}
