package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.UIElement;

/**
 * Zero-allocation tweening for position, rotation, scale, light colour, UI colour and free-standing float values.
 *
 * <p>Tweens occupy preallocated pool slots (the pool doubles only when more tweens run at once than its capacity).
 * Each start call returns an int handle for {@link #cancel(int)}, {@link #setDelay(int, float)},
 * {@link #setOnComplete(int, ScriptTask)} and {@link #getValue(int)}; stale handles are ignored. Starting a tween on
 * a target/channel that already has one replaces the old tween, so tweens never fight over the same property.</p>
 *
 * <p>The start value is read from the target when the tween actually begins (after its delay). Rotation tweens take
 * the shortest way around on each axis. {@code SimpleEntityScript} owns one tweener and ticks it before onUpdate.</p>
 */
@PublicApi
public final class ScriptTweener {

    // ---------------------------------------------------------------- Easing curves
    public static final int LINEAR = 0;
    public static final int EASE_IN_QUAD = 1;
    public static final int EASE_OUT_QUAD = 2;
    public static final int EASE_IN_OUT_QUAD = 3;
    public static final int EASE_IN_CUBIC = 4;
    public static final int EASE_OUT_CUBIC = 5;
    public static final int EASE_IN_OUT_CUBIC = 6;
    public static final int EASE_IN_SINE = 7;
    public static final int EASE_OUT_SINE = 8;
    public static final int EASE_IN_OUT_SINE = 9;
    public static final int EASE_OUT_BACK = 10;
    public static final int EASE_OUT_ELASTIC = 11;
    public static final int EASE_OUT_BOUNCE = 12;
    public static final int SMOOTHSTEP = 13;

    public static final int INVALID_HANDLE = 0;

    // ---------------------------------------------------------------- Tween channels
    private static final int KIND_POSITION = 1;
    private static final int KIND_ROTATION = 2;
    private static final int KIND_SCALE = 3;
    private static final int KIND_LIGHT_COLOR = 4;
    private static final int KIND_UI_COLOR = 5;
    private static final int KIND_VALUE = 6;

    private static final int SLOT_BITS = 16;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int MAX_SLOTS = 1 << SLOT_BITS;
    private static final int MAX_GENERATION = 0x7FFF;

    private int[] kind;
    private Object[] target;
    private float[] from;   // 4 floats per slot
    private float[] to;     // 4 floats per slot
    private float[] value;  // 4 floats per slot: the latest eased value
    private float[] duration;
    private float[] elapsed;
    private float[] delay;
    private int[] ease;
    private boolean[] started;
    private boolean[] active;
    private boolean[] pingPong;
    private int[] generation;
    private ScriptTask[] onComplete;

    private int highWater = 0;
    private int activeCount = 0;

    public ScriptTweener() {
        this(16);
    }

    public ScriptTweener(int initialCapacity) {
        allocate(Math.max(1, Math.min(initialCapacity, MAX_SLOTS)));
    }

    // =========================================================================
    // Starting tweens
    // =========================================================================

    /** Moves {@code t} (local position) to (x, y, z) over {@code seconds}. */
    public int tweenPosition(Transform t, float x, float y, float z, float seconds, int easing) {
        return start(KIND_POSITION, t, x, y, z, 0.0f, seconds, easing);
    }

    /** Rotates {@code t} (local Euler degrees) to (x, y, z) over {@code seconds}, shortest way around per axis. */
    public int tweenRotation(Transform t, float x, float y, float z, float seconds, int easing) {
        return start(KIND_ROTATION, t, x, y, z, 0.0f, seconds, easing);
    }

    public int tweenScale(Transform t, float x, float y, float z, float seconds, int easing) {
        return start(KIND_SCALE, t, x, y, z, 0.0f, seconds, easing);
    }

    /** Fades a light's colour (0..1 per channel) and intensity. */
    public int tweenLightColor(Light light, float r, float g, float b, float intensity, float seconds, int easing) {
        return start(KIND_LIGHT_COLOR, light, r, g, b, intensity, seconds, easing);
    }

    /** Fades a UI element's colour and alpha (0..1). */
    public int tweenUIColor(UIElement element, float r, float g, float b, float alpha, float seconds, int easing) {
        return start(KIND_UI_COLOR, element, r, g, b, alpha, seconds, easing);
    }

    /** Animates a free-standing number from {@code fromValue} to {@code toValue}; read it with {@link #getValue(int)}. */
    public int tweenValue(float fromValue, float toValue, float seconds, int easing) {
        int handle = start(KIND_VALUE, null, toValue, 0.0f, 0.0f, 0.0f, seconds, easing);
        int slot = slotOf(handle);
        if (slot >= 0) {
            from[slot * 4] = fromValue;
            value[slot * 4] = fromValue;
            started[slot] = true; // The start value is explicit; nothing to sample from a target
        }
        return handle;
    }

    // =========================================================================
    // Tween control
    // =========================================================================

    /** Waits {@code seconds} before the tween begins (its start value is sampled then). Returns the handle. */
    public int setDelay(int handle, float seconds) {
        int slot = slotOf(handle);
        if (slot >= 0 && elapsed[slot] == 0.0f) delay[slot] = Math.max(0.0f, seconds); // Only before it has begun
        return handle;
    }

    /** Runs {@code task} once when the tween completes (not when cancelled or replaced). Returns the handle. */
    public int setOnComplete(int handle, ScriptTask task) {
        int slot = slotOf(handle);
        if (slot >= 0) onComplete[slot] = task;
        return handle;
    }

    /** Plays the tween forwards and backwards forever (onComplete never fires). Returns the handle. */
    public int setPingPong(int handle, boolean enabled) {
        int slot = slotOf(handle);
        if (slot >= 0) pingPong[slot] = enabled;
        return handle;
    }

    public boolean cancel(int handle) {
        int slot = slotOf(handle);
        if (slot < 0) return false;
        release(slot);
        return true;
    }

    /** Cancels every tween that drives {@code targetObject} (a Transform, Light or UIElement). */
    public void cancelAllOn(Object targetObject) {
        if (targetObject == null) return;
        for (int i = 0; i < highWater; i++) {
            if (active[i] && target[i] == targetObject) release(i);
        }
    }

    public void cancelAll() {
        for (int i = 0; i < highWater; i++) {
            if (active[i]) release(i);
        }
    }

    public boolean isActive(int handle) {
        return slotOf(handle) >= 0;
    }

    /** Latest value of a {@link #tweenValue} tween (or channel 0 of any tween); 0 for a stale handle. */
    public float getValue(int handle) {
        return getValue(handle, 0);
    }

    /** Latest eased value of one channel (0..3) of a tween; 0 for a stale handle. */
    public float getValue(int handle, int channel) {
        int slot = slotOf(handle);
        if (slot < 0 || channel < 0 || channel > 3) return 0.0f;
        return value[slot * 4 + channel];
    }

    /** Normalized progress 0..1 of a tween, or -1 for a stale handle. */
    public float getProgress(int handle) {
        int slot = slotOf(handle);
        if (slot < 0) return -1.0f;
        return duration[slot] <= 0.0f ? 1.0f : Math.min(1.0f, elapsed[slot] / duration[slot]);
    }

    public int getActiveCount() {
        return activeCount;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    /** Advances every tween; completion callbacks run after the final value has been applied. */
    public void update(float deltaTime) {
        if (activeCount == 0) return;
        int n = highWater; // Tweens started from callbacks this tick begin next tick
        for (int i = 0; i < n; i++) {
            if (!active[i]) continue;

            float dt = deltaTime;
            if (delay[i] > 0.0f) {
                delay[i] -= dt;
                if (delay[i] > 0.0f) continue;
                dt = -delay[i]; // Only the part of this frame that lies past the delay
                delay[i] = 0.0f;
            }
            if (!started[i]) {
                if (!sampleStart(i)) { release(i); continue; }
                started[i] = true;
            }

            elapsed[i] += dt;
            float d = duration[i];
            float raw = d <= 0.0f ? 1.0f : elapsed[i] / d;
            boolean finished = false;
            if (pingPong[i]) {
                float cycle = raw % 2.0f;
                raw = cycle <= 1.0f ? cycle : 2.0f - cycle;
            } else if (raw >= 1.0f) {
                raw = 1.0f;
                finished = true;
            }

            if (!apply(i, ease(ease[i], raw))) { release(i); continue; }

            if (finished) {
                ScriptTask done = onComplete[i];
                release(i);
                if (done != null) done.run();
            }
        }
    }

    // =========================================================================
    // Easing math (static, usable on its own)
    // =========================================================================

    /** Eases {@code t} (clamped to 0..1) with one of the easing constants above. */
    public static float ease(int easing, float t) {
        if (t <= 0.0f) return 0.0f;
        if (t >= 1.0f) return 1.0f;
        switch (easing) {
            case EASE_IN_QUAD: return t * t;
            case EASE_OUT_QUAD: return 1.0f - (1.0f - t) * (1.0f - t);
            case EASE_IN_OUT_QUAD: return t < 0.5f ? 2.0f * t * t : 1.0f - 2.0f * (1.0f - t) * (1.0f - t);
            case EASE_IN_CUBIC: return t * t * t;
            case EASE_OUT_CUBIC: { float u = 1.0f - t; return 1.0f - u * u * u; }
            case EASE_IN_OUT_CUBIC: {
                if (t < 0.5f) return 4.0f * t * t * t;
                float u = -2.0f * t + 2.0f;
                return 1.0f - u * u * u * 0.5f;
            }
            case EASE_IN_SINE: return 1.0f - (float) Math.cos(t * Math.PI * 0.5);
            case EASE_OUT_SINE: return (float) Math.sin(t * Math.PI * 0.5);
            case EASE_IN_OUT_SINE: return (float) (-(Math.cos(Math.PI * t) - 1.0) * 0.5);
            case EASE_OUT_BACK: {
                float c1 = 1.70158f, c3 = c1 + 1.0f, u = t - 1.0f;
                return 1.0f + c3 * u * u * u + c1 * u * u;
            }
            case EASE_OUT_ELASTIC: {
                float c4 = (float) (2.0 * Math.PI / 3.0);
                return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
            }
            case EASE_OUT_BOUNCE: return bounceOut(t);
            case SMOOTHSTEP: return t * t * (3.0f - 2.0f * t);
            default: return t;
        }
    }

    private static float bounceOut(float t) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (t < 1.0f / d1) return n1 * t * t;
        if (t < 2.0f / d1) { t -= 1.5f / d1; return n1 * t * t + 0.75f; }
        if (t < 2.5f / d1) { t -= 2.25f / d1; return n1 * t * t + 0.9375f; }
        t -= 2.625f / d1;
        return n1 * t * t + 0.984375f;
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Interpolates angles in degrees along the shortest arc. */
    public static float lerpAngle(float a, float b, float t) {
        return a + deltaAngle(a, b) * t;
    }

    /** Signed shortest difference b - a in degrees, in [-180, 180). */
    public static float deltaAngle(float a, float b) {
        float d = (b - a) % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    /** Frame-rate independent exponential approach: move {@code current} toward {@code target} at {@code sharpness}. */
    public static float damp(float current, float target, float sharpness, float deltaTime) {
        return lerp(current, target, 1.0f - (float) Math.exp(-sharpness * deltaTime));
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private int start(int k, Object tgt, float a, float b, float c, float d, float seconds, int easing) {
        if (k != KIND_VALUE && tgt == null) return INVALID_HANDLE;
        if (tgt != null) {
            for (int i = 0; i < highWater; i++) {
                if (active[i] && kind[i] == k && target[i] == tgt) release(i);
            }
        }
        int slot = findFreeSlot();
        if (slot < 0) {
            System.err.println("[ScriptTweener] Tween limit (" + MAX_SLOTS + ") reached; tween not started.");
            return INVALID_HANDLE;
        }
        kind[slot] = k;
        target[slot] = tgt;
        int o = slot * 4;
        to[o] = a; to[o + 1] = b; to[o + 2] = c; to[o + 3] = d;
        from[o] = 0.0f; from[o + 1] = 0.0f; from[o + 2] = 0.0f; from[o + 3] = 0.0f;
        value[o] = 0.0f; value[o + 1] = 0.0f; value[o + 2] = 0.0f; value[o + 3] = 0.0f;
        duration[slot] = Math.max(0.0f, seconds);
        elapsed[slot] = 0.0f;
        delay[slot] = 0.0f;
        ease[slot] = easing;
        started[slot] = false;
        pingPong[slot] = false;
        onComplete[slot] = null;
        active[slot] = true;
        activeCount++;
        return (generation[slot] << SLOT_BITS) | slot;
    }

    /** Reads the target's current value as the tween's start; false when the target is unusable. */
    private boolean sampleStart(int i) {
        int o = i * 4;
        switch (kind[i]) {
            case KIND_POSITION: {
                Transform t = (Transform) target[i];
                from[o] = t.posX; from[o + 1] = t.posY; from[o + 2] = t.posZ;
                return true;
            }
            case KIND_ROTATION: {
                Transform t = (Transform) target[i];
                from[o] = t.rotX; from[o + 1] = t.rotY; from[o + 2] = t.rotZ;
                return true;
            }
            case KIND_SCALE: {
                Transform t = (Transform) target[i];
                from[o] = t.scaleX; from[o + 1] = t.scaleY; from[o + 2] = t.scaleZ;
                return true;
            }
            case KIND_LIGHT_COLOR: {
                Light l = (Light) target[i];
                from[o] = l.r; from[o + 1] = l.g; from[o + 2] = l.b; from[o + 3] = l.intensity;
                return true;
            }
            case KIND_UI_COLOR: {
                UIElement u = (UIElement) target[i];
                from[o] = u.colorR; from[o + 1] = u.colorG; from[o + 2] = u.colorB; from[o + 3] = u.alpha;
                return true;
            }
            case KIND_VALUE:
                return true;
            default:
                return false;
        }
    }

    private boolean apply(int i, float e) {
        int o = i * 4;
        float v0, v1, v2, v3;
        if (kind[i] == KIND_ROTATION) {
            v0 = lerpAngle(from[o], to[o], e);
            v1 = lerpAngle(from[o + 1], to[o + 1], e);
            v2 = lerpAngle(from[o + 2], to[o + 2], e);
            v3 = 0.0f;
        } else {
            v0 = lerp(from[o], to[o], e);
            v1 = lerp(from[o + 1], to[o + 1], e);
            v2 = lerp(from[o + 2], to[o + 2], e);
            v3 = lerp(from[o + 3], to[o + 3], e);
        }
        value[o] = v0; value[o + 1] = v1; value[o + 2] = v2; value[o + 3] = v3;

        switch (kind[i]) {
            case KIND_POSITION: ((Transform) target[i]).setPosition(v0, v1, v2); return true;
            case KIND_ROTATION: ((Transform) target[i]).setRotation(v0, v1, v2); return true;
            case KIND_SCALE: ((Transform) target[i]).setScale(v0, v1, v2); return true;
            case KIND_LIGHT_COLOR: {
                Light l = (Light) target[i];
                l.r = v0; l.g = v1; l.b = v2; l.intensity = v3;
                return true;
            }
            case KIND_UI_COLOR: {
                UIElement u = (UIElement) target[i];
                u.colorR = v0; u.colorG = v1; u.colorB = v2; u.alpha = v3;
                return true;
            }
            case KIND_VALUE: return true;
            default: return false;
        }
    }

    private int findFreeSlot() {
        for (int i = 0; i < highWater; i++) {
            if (!active[i]) return i;
        }
        if (highWater < kind.length) return highWater++;
        if (kind.length >= MAX_SLOTS) return -1;
        grow();
        return highWater++;
    }

    private void release(int slot) {
        if (!active[slot]) return;
        active[slot] = false;
        target[slot] = null;
        onComplete[slot] = null;
        generation[slot] = (generation[slot] >= MAX_GENERATION) ? 1 : generation[slot] + 1;
        activeCount--;
    }

    private int slotOf(int handle) {
        if (handle == INVALID_HANDLE) return -1;
        int slot = handle & SLOT_MASK;
        int gen = handle >>> SLOT_BITS;
        if (slot >= highWater || !active[slot] || generation[slot] != gen) return -1;
        return slot;
    }

    private void allocate(int cap) {
        kind = new int[cap];
        target = new Object[cap];
        from = new float[cap * 4];
        to = new float[cap * 4];
        value = new float[cap * 4];
        duration = new float[cap];
        elapsed = new float[cap];
        delay = new float[cap];
        ease = new int[cap];
        started = new boolean[cap];
        active = new boolean[cap];
        pingPong = new boolean[cap];
        generation = new int[cap];
        onComplete = new ScriptTask[cap];
        for (int i = 0; i < cap; i++) generation[i] = 1;
    }

    private void grow() {
        int oldCap = kind.length;
        int[] k = kind; Object[] tg = target; float[] f = from, t = to, v = value, du = duration, el = elapsed, de = delay;
        int[] ea = ease; boolean[] st = started, ac = active, pp = pingPong; int[] ge = generation; ScriptTask[] oc = onComplete;
        allocate(Math.min(oldCap * 2, MAX_SLOTS));
        System.arraycopy(k, 0, kind, 0, oldCap);
        System.arraycopy(tg, 0, target, 0, oldCap);
        System.arraycopy(f, 0, from, 0, oldCap * 4);
        System.arraycopy(t, 0, to, 0, oldCap * 4);
        System.arraycopy(v, 0, value, 0, oldCap * 4);
        System.arraycopy(du, 0, duration, 0, oldCap);
        System.arraycopy(el, 0, elapsed, 0, oldCap);
        System.arraycopy(de, 0, delay, 0, oldCap);
        System.arraycopy(ea, 0, ease, 0, oldCap);
        System.arraycopy(st, 0, started, 0, oldCap);
        System.arraycopy(ac, 0, active, 0, oldCap);
        System.arraycopy(pp, 0, pingPong, 0, oldCap);
        System.arraycopy(ge, 0, generation, 0, oldCap);
        System.arraycopy(oc, 0, onComplete, 0, oldCap);
    }
}
