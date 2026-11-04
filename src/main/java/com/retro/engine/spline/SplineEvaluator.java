package com.retro.engine.spline;

import com.retro.engine.ecs.components.SplineComponent;
import com.retro.engine.ecs.components.Transform;

/**
 * Curve math for {@link SplineComponent}: Catmull-Rom (centripetal-free uniform form, passes through every knot) and
 * cubic Bezier (knots plus in/out handles). The parameter {@code t} spans the whole curve in [0, 1] with every span
 * taking an equal share; {@link #getPointAtDistance} uses the arc-length table baked by {@link #bake} for uniform
 * motion. All queries write into caller-provided arrays and are allocation-free; {@link #bake} allocates only when the
 * table needs to grow (edit time). Engine-thread only (shared scratch storage).
 */
public final class SplineEvaluator {

    private static final float[] scratch = new float[3];

    private SplineEvaluator() {}

    public static int spanCount(SplineComponent s, int count) {
        if (count < 2) return 0;
        return s.loop ? count : count - 1;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Public queries (world space: the owner's Transform position is added when bound)
    // -----------------------------------------------------------------------------------------------------------------

    /** Position at curve parameter {@code t} in [0, 1] (wrapped when looping, clamped otherwise). */
    public static void getPositionAt(SplineComponent s, float t, float[] out) {
        int n = s.getCount();
        evaluate(s, n, normalizeT(s, t), out, false);
        addOwnerOffset(s, out);
    }

    /** Unit tangent (direction of travel) at curve parameter {@code t}. */
    public static void getTangentAt(SplineComponent s, float t, float[] out) {
        int n = s.getCount();
        evaluate(s, n, normalizeT(s, t), out, true);
        normalize(out);
    }

    /**
     * Position at arc-length {@code distance} from the start (wrapped when looping, clamped otherwise).
     * Returns the matching curve parameter {@code t}, usable with {@link #getTangentAt}.
     */
    public static float getPointAtDistance(SplineComponent s, float distance, float[] out) {
        float t = distanceToT(s, distance);
        getPositionAt(s, t, out);
        return t;
    }

    /** Curve parameter at arc-length {@code distance} (binary search over the baked table). */
    public static float distanceToT(SplineComponent s, float distance) {
        s.ensureUpToDate();
        float len = s.totalLength;
        int n = s.lutCount;
        if (n < 2 || len <= 0.0f) return 0.0f;
        if (s.loop) {
            distance %= len;
            if (distance < 0.0f) distance += len;
        } else if (distance <= 0.0f) {
            return 0.0f;
        } else if (distance >= len) {
            return 1.0f;
        }
        float[] L = s.lutLength;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (L[mid] <= distance) lo = mid; else hi = mid;
        }
        float seg = L[hi] - L[lo];
        float f = (seg > 1e-7f) ? (distance - L[lo]) / seg : 0.0f;
        return s.lutT[lo] + (s.lutT[hi] - s.lutT[lo]) * f;
    }

    /** Arc-length from the start to curve parameter {@code t}. */
    public static float tToDistance(SplineComponent s, float t) {
        s.ensureUpToDate();
        int n = s.lutCount;
        if (n < 2) return 0.0f;
        t = normalizeT(s, t);
        float pos = t * (n - 1);
        int i = (int) pos;
        if (i >= n - 1) return s.totalLength;
        float f = pos - i;
        return s.lutLength[i] + (s.lutLength[i + 1] - s.lutLength[i]) * f;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Baking (edit time)
    // -----------------------------------------------------------------------------------------------------------------

    /** Rebuilds the arc-length table; called by {@link SplineComponent#ensureUpToDate()} when the curve changed. */
    public static void bake(SplineComponent s) {
        int n = s.getCountRaw();
        int spans = spanCount(s, n);
        int samples = Math.max(2, spans * s.resolution + 1);
        if (s.lutT.length < samples) {
            s.lutT = new float[samples];
            s.lutLength = new float[samples];
        }
        s.lutCount = samples;
        float px = 0, py = 0, pz = 0, acc = 0.0f;
        for (int i = 0; i < samples; i++) {
            float t = (float) i / (samples - 1);
            evaluate(s, n, t, scratch, false);
            if (i > 0) {
                float dx = scratch[0] - px, dy = scratch[1] - py, dz = scratch[2] - pz;
                acc += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            px = scratch[0]; py = scratch[1]; pz = scratch[2];
            s.lutT[i] = t;
            s.lutLength[i] = acc;
        }
        s.totalLength = acc;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Core math (local space)
    // -----------------------------------------------------------------------------------------------------------------

    /** Local-space position (or first derivative w.r.t. span parameter when {@code derivative}) at normalized t. */
    public static void evaluate(SplineComponent s, int n, float t, float[] out, boolean derivative) {
        float[] p = s.points;
        if (n < 2) {
            if (derivative) { out[0] = 0; out[1] = 0; out[2] = 1; }
            else if (n == 1) { out[0] = p[0]; out[1] = p[1]; out[2] = p[2]; }
            else { out[0] = 0; out[1] = 0; out[2] = 0; }
            return;
        }
        int spans = spanCount(s, n);
        float st = t * spans;
        int seg = (int) st;
        if (seg >= spans) seg = spans - 1;
        if (seg < 0) seg = 0;
        float u = st - seg;
        int i1 = seg, i2 = (seg + 1) % n;

        if (s.curveType == SplineComponent.CurveType.BEZIER) {
            float[] h = s.handles;
            for (int k = 0; k < 3; k++) {
                float p0 = p[i1 * 3 + k];
                float p1 = p0 + h[i1 * 6 + 3 + k];
                float p3 = p[i2 * 3 + k];
                float p2 = p3 + h[i2 * 6 + k];
                out[k] = derivative ? bezierDeriv(p0, p1, p2, p3, u) : bezier(p0, p1, p2, p3, u);
            }
        } else {
            for (int k = 0; k < 3; k++) {
                float a1 = p[i1 * 3 + k], a2 = p[i2 * 3 + k];
                float a0 = knot(s, n, seg - 1, k, a1, a2);
                float a3 = knot(s, n, seg + 2, k, a2, a1);
                out[k] = derivative ? catmullDeriv(a0, a1, a2, a3, u) : catmull(a0, a1, a2, a3, u);
            }
        }
    }

    /** Neighbouring knot for Catmull-Rom: wraps when looping, otherwise reflects past the ends. */
    private static float knot(SplineComponent s, int n, int i, int k, float near, float far) {
        if (s.loop) return s.points[(((i % n) + n) % n) * 3 + k];
        if (i < 0 || i >= n) return 2.0f * near - far;
        return s.points[i * 3 + k];
    }

    private static float catmull(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u, u3 = u2 * u;
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * u + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * u2
                + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * u3);
    }

    private static float catmullDeriv(float p0, float p1, float p2, float p3, float u) {
        return 0.5f * ((-p0 + p2) + 2.0f * (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * u
                + 3.0f * (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * u * u);
    }

    private static float bezier(float p0, float p1, float p2, float p3, float u) {
        float v = 1.0f - u;
        return v * v * v * p0 + 3.0f * v * v * u * p1 + 3.0f * v * u * u * p2 + u * u * u * p3;
    }

    private static float bezierDeriv(float p0, float p1, float p2, float p3, float u) {
        float v = 1.0f - u;
        return 3.0f * v * v * (p1 - p0) + 6.0f * v * u * (p2 - p1) + 3.0f * u * u * (p3 - p2);
    }

    private static float normalizeT(SplineComponent s, float t) {
        if (s.loop) {
            t -= (float) Math.floor(t);
            return t;
        }
        return t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
    }

    private static void addOwnerOffset(SplineComponent s, float[] out) {
        Transform tr = (s.owner != null) ? s.owner.getTransform() : null;
        if (tr == null) return;
        out[0] += tr.posX; out[1] += tr.posY; out[2] += tr.posZ;
    }

    private static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-7f) { v[0] = 0; v[1] = 0; v[2] = 1; return; }
        v[0] /= len; v[1] /= len; v[2] /= len;
    }
}
