package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;

/**
 * Mutable 3-component float vector for gameplay scripts. Every operation writes into {@code this} (or an explicit
 * {@code out}) and returns it for chaining, so a script can keep a few Vector3 fields and do all of its per-frame math
 * without allocating. Create instances in field initializers or onStart, never inside onUpdate.
 */
@PublicApi
public final class Vector3 {

    public float x;
    public float y;
    public float z;

    public Vector3() {}

    public Vector3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3 set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vector3 set(Vector3 other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        return this;
    }

    public Vector3 zero() {
        return set(0.0f, 0.0f, 0.0f);
    }

    public Vector3 add(float dx, float dy, float dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    public Vector3 add(Vector3 other) {
        return add(other.x, other.y, other.z);
    }

    public Vector3 sub(Vector3 other) {
        return add(-other.x, -other.y, -other.z);
    }

    public Vector3 scale(float s) {
        this.x *= s;
        this.y *= s;
        this.z *= s;
        return this;
    }

    /** this += other * s */
    public Vector3 addScaled(Vector3 other, float s) {
        return add(other.x * s, other.y * s, other.z * s);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /** Scales to unit length; a (near-)zero vector is left at zero. */
    public Vector3 normalize() {
        float len = length();
        if (len < 1e-6f) return zero();
        return scale(1.0f / len);
    }

    /** Shortens the vector to {@code maxLength} when it is longer. */
    public Vector3 clampLength(float maxLength) {
        float lenSq = lengthSquared();
        if (lenSq > maxLength * maxLength && lenSq > 1e-12f) scale(maxLength / (float) Math.sqrt(lenSq));
        return this;
    }

    public float dot(Vector3 other) {
        return x * other.x + y * other.y + z * other.z;
    }

    /** out = a x b (out may alias a or b). */
    public static Vector3 cross(Vector3 a, Vector3 b, Vector3 out) {
        float cx = a.y * b.z - a.z * b.y;
        float cy = a.z * b.x - a.x * b.z;
        float cz = a.x * b.y - a.y * b.x;
        return out.set(cx, cy, cz);
    }

    /** out = a + (b - a) * t (out may alias a or b). */
    public static Vector3 lerp(Vector3 a, Vector3 b, float t, Vector3 out) {
        return out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    public float distanceTo(Vector3 other) {
        float dx = other.x - x, dy = other.y - y, dz = other.z - z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float distanceSquaredTo(Vector3 other) {
        float dx = other.x - x, dy = other.y - y, dz = other.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Allocates; for logging only. */
    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
