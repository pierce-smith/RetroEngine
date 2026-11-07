package com.retro.engine.animation;

import com.retro.engine.ecs.components.AnimatorComponent;

/**
 * Edits a finished pose in place, in the mesh's model space, for solvers that run after animation. Palette entries are
 * boneWorld * offset, so left-multiplying one by a rigid transform moves that bone, and doing the same to its descendants
 * carries them along. No local transforms are rebuilt. The palette's rotation is also the bone's rotation away from its
 * bind pose, which makes it the way to carry a direction measured on the bind pose over to the current pose.
 *
 * <p>Rotations are row-major 3x3, the layout HierarchySystem uses. Nothing here allocates.</p>
 */
public final class PoseEditor {

    private static final double EPSILON = 1e-9;
    // Below this sine the knee is too straight for the limb to say which way it bends
    private static final double STRAIGHT_LIMB_SINE = 0.02;
    private static final double REACH_MARGIN = 1e-4;

    private final boolean[] moved = new boolean[AnimatorComponent.MAX_BONES];
    private final double[] root = new double[3], joint = new double[3], end = new double[3];
    private final double[] toRoot = new double[3], toEnd = new double[3], axis = new double[3], toTarget = new double[3];
    private final double[] rotation = new double[9];

    /** Rotates {@code bone} and everything below it by {@code r} about the model-space point {@code pivot}. */
    public void rotate(AnimatorComponent animator, int bone, double[] pivot, double[] r) {
        markSubtree(animator.skeleton, bone);
        for (int i = 0; i < moved.length; i++) {
            if (!moved[i]) continue;
            float[] m = animator.bonePaletteMatrices[i];
            for (int column = 0; column < 3; column++) {
                int at = column * 4;
                double x = m[at], y = m[at + 1], z = m[at + 2];
                m[at] = (float) (r[0] * x + r[1] * y + r[2] * z);
                m[at + 1] = (float) (r[3] * x + r[4] * y + r[5] * z);
                m[at + 2] = (float) (r[6] * x + r[7] * y + r[8] * z);
            }
            double x = m[12] - pivot[0], y = m[13] - pivot[1], z = m[14] - pivot[2];
            m[12] = (float) (r[0] * x + r[1] * y + r[2] * z + pivot[0]);
            m[13] = (float) (r[3] * x + r[4] * y + r[5] * z + pivot[1]);
            m[14] = (float) (r[6] * x + r[7] * y + r[8] * z + pivot[2]);
        }
    }

    /** Moves {@code bone} and everything below it by a model-space offset. */
    public void translate(AnimatorComponent animator, int bone, double dx, double dy, double dz) {
        markSubtree(animator.skeleton, bone);
        for (int i = 0; i < moved.length; i++) {
            if (!moved[i]) continue;
            float[] m = animator.bonePaletteMatrices[i];
            m[12] += (float) dx;
            m[13] += (float) dy;
            m[14] += (float) dz;
        }
    }

    private void markSubtree(Skeleton skeleton, int bone) {
        java.util.Arrays.fill(moved, false);
        moved[bone] = true;
        for (int i : skeleton.getEvaluationOrder()) {
            if (i >= moved.length) continue;
            int parent = skeleton.getEvaluationParent(i);
            if (parent >= 0 && parent < moved.length && moved[parent]) moved[i] = true;
        }
    }

    /**
     * Bends {@code upper -> lower -> tip} so the tip reaches {@code target}, or as close as the limb's length allows. The
     * limb keeps the plane it was animated in; {@code bendHint} only decides the plane for a limb that is dead straight,
     * and is the way its middle joint should point.
     */
    public void solveTwoBone(AnimatorComponent animator, int upper, int lower, int tip, double[] target, double[] bendHint) {
        float[] joints = animator.skeleton.getBindJointPositions();
        jointPosition(animator, joints, upper, root);
        jointPosition(animator, joints, lower, joint);
        jointPosition(animator, joints, tip, end);
        subtract(root, joint, toRoot);
        subtract(end, joint, toEnd);
        double upperLength = normalize(toRoot), lowerLength = normalize(toEnd);
        if (upperLength < EPSILON || lowerLength < EPSILON) return;

        subtract(target, root, toTarget);
        double reach = Math.sqrt(dot(toTarget, toTarget));
        reach = Math.max(Math.abs(upperLength - lowerLength) + REACH_MARGIN, Math.min(upperLength + lowerLength - REACH_MARGIN, reach));

        cross(toRoot, toEnd, axis);
        double sine = Math.sqrt(dot(axis, axis));
        double current = Math.atan2(sine, dot(toRoot, toEnd));
        if (sine < STRAIGHT_LIMB_SINE) cross(bendHint, toRoot, axis);
        if (normalize(axis) < EPSILON) return;

        double cosine = (upperLength * upperLength + lowerLength * lowerLength - reach * reach) / (2.0 * upperLength * lowerLength);
        double wanted = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
        axisAngle(axis, wanted - current, rotation);
        rotate(animator, lower, joint, rotation);

        jointPosition(animator, joints, tip, end);
        subtract(end, root, toEnd);
        if (normalize(toEnd) < EPSILON || normalize(toTarget) < EPSILON) return;
        rotationBetween(toEnd, toTarget, 1.0, rotation);
        rotate(animator, upper, root, rotation);
    }

    /** Where {@code bone}'s joint is in the current pose. {@code bindJoints} is {@link Skeleton#getBindJointPositions()}. */
    public static void jointPosition(AnimatorComponent animator, float[] bindJoints, int bone, double[] out) {
        float[] m = animator.bonePaletteMatrices[bone];
        double x = bindJoints[bone * 3], y = bindJoints[bone * 3 + 1], z = bindJoints[bone * 3 + 2];
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    /** Carries a direction measured on the bind pose onto {@code bone}'s current pose, as a unit vector. */
    public static void boneDirection(AnimatorComponent animator, int bone, double x, double y, double z, double[] out) {
        float[] m = animator.bonePaletteMatrices[bone];
        out[0] = m[0] * x + m[4] * y + m[8] * z;
        out[1] = m[1] * x + m[5] * y + m[9] * z;
        out[2] = m[2] * x + m[6] * y + m[10] * z;
        normalize(out);
    }

    /** {@code bone}'s rotation away from its bind pose, with any bone scale divided out. */
    public static void boneRotation(AnimatorComponent animator, int bone, double[] out) {
        float[] m = animator.bonePaletteMatrices[bone];
        for (int column = 0; column < 3; column++) {
            double x = m[column * 4], y = m[column * 4 + 1], z = m[column * 4 + 2];
            double length = Math.sqrt(x * x + y * y + z * z);
            double scale = length > EPSILON ? 1.0 / length : 0.0;
            out[column] = x * scale;
            out[3 + column] = y * scale;
            out[6 + column] = z * scale;
        }
    }

    /** {@code amount} of the shortest rotation taking unit vector {@code from} to unit vector {@code to}. */
    public static void rotationBetween(double[] from, double[] to, double amount, double[] out) {
        double ax = from[1] * to[2] - from[2] * to[1];
        double ay = from[2] * to[0] - from[0] * to[2];
        double az = from[0] * to[1] - from[1] * to[0];
        double sine = Math.sqrt(ax * ax + ay * ay + az * az);
        double cosine = dot(from, to);
        if (sine < EPSILON) {
            if (cosine > 0.0) {
                axisAngle(1.0, 0.0, 0.0, 0.0, out);
                return;
            }
            // Opposite vectors have no shortest way round, so any perpendicular axis will do
            boolean nearX = Math.abs(from[0]) > 0.9;
            ax = nearX ? -from[1] : 0.0;
            ay = nearX ? from[0] : -from[2];
            az = nearX ? 0.0 : from[1];
            sine = Math.sqrt(ax * ax + ay * ay + az * az);
        }
        axisAngle(ax / sine, ay / sine, az / sine, Math.atan2(sine, cosine) * amount, out);
    }

    /** Rotation of {@code angle} radians about a unit axis. */
    public static void axisAngle(double[] unitAxis, double angle, double[] out) {
        axisAngle(unitAxis[0], unitAxis[1], unitAxis[2], angle, out);
    }

    private static void axisAngle(double x, double y, double z, double angle, double[] out) {
        double c = Math.cos(angle), s = Math.sin(angle), t = 1.0 - c;
        out[0] = t * x * x + c;     out[1] = t * x * y - s * z; out[2] = t * x * z + s * y;
        out[3] = t * x * y + s * z; out[4] = t * y * y + c;     out[5] = t * y * z - s * x;
        out[6] = t * x * z - s * y; out[7] = t * y * z + s * x; out[8] = t * z * z + c;
    }

    /** Angle in radians between two unit vectors. */
    public static double angleBetween(double[] a, double[] b) {
        double x = a[1] * b[2] - a[2] * b[1], y = a[2] * b[0] - a[0] * b[2], z = a[0] * b[1] - a[1] * b[0];
        return Math.atan2(Math.sqrt(x * x + y * y + z * z), dot(a, b));
    }

    /** out = r * v. {@code out} may be {@code v}. */
    public static void apply(double[] r, double[] v, double[] out) {
        double x = v[0], y = v[1], z = v[2];
        out[0] = r[0] * x + r[1] * y + r[2] * z;
        out[1] = r[3] * x + r[4] * y + r[5] * z;
        out[2] = r[6] * x + r[7] * y + r[8] * z;
    }

    /** out = transpose(r) * v, the inverse of {@link #apply} for a rotation. {@code out} may be {@code v}. */
    public static void applyInverse(double[] r, double[] v, double[] out) {
        double x = v[0], y = v[1], z = v[2];
        out[0] = r[0] * x + r[3] * y + r[6] * z;
        out[1] = r[1] * x + r[4] * y + r[7] * z;
        out[2] = r[2] * x + r[5] * y + r[8] * z;
    }

    /** Scales {@code v} to unit length and returns the length it had. A zero vector stays zero. */
    public static double normalize(double[] v) {
        double length = Math.sqrt(dot(v, v));
        if (length < EPSILON) return 0.0;
        v[0] /= length;
        v[1] /= length;
        v[2] /= length;
        return length;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void subtract(double[] a, double[] b, double[] out) {
        out[0] = a[0] - b[0];
        out[1] = a[1] - b[1];
        out[2] = a[2] - b[2];
    }

    private static void cross(double[] a, double[] b, double[] out) {
        out[0] = a[1] * b[2] - a[2] * b[1];
        out[1] = a[2] * b[0] - a[0] * b[2];
        out[2] = a[0] * b[1] - a[1] * b[0];
    }
}
