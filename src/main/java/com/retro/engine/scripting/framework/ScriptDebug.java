package com.retro.engine.scripting.framework;

import com.retro.engine.physics.RaycastHit;

/**
 * Immediate-mode debug drawing for gameplay scripts: lines, rays, spheres, boxes and markers in world space, shown
 * as an overlay in the editor's Scene viewport while the game plays (the standalone runtime ignores them).
 *
 * <p>Call the draw methods every frame you want something visible (typically from onUpdate). Without a duration a
 * shape is shown for exactly one frame; with {@code durationSeconds > 0} it stays up for that long of game time. Lines
 * are stored in fixed preallocated arrays ({@link #MAX_LINES}), so drawing never allocates; once full, further lines
 * that frame are dropped.</p>
 *
 * <p>Colours are 0xRRGGBB ints; see the constants below.</p>
 */
public final class ScriptDebug {

    public static final int RED = 0xFF4040;
    public static final int GREEN = 0x40FF60;
    public static final int BLUE = 0x4080FF;
    public static final int YELLOW = 0xFFE040;
    public static final int CYAN = 0x40E0FF;
    public static final int MAGENTA = 0xFF40FF;
    public static final int ORANGE = 0xFF9020;
    public static final int WHITE = 0xFFFFFF;
    public static final int GRAY = 0x909090;

    public static final int MAX_LINES = 8192;
    private static final int SPHERE_SEGMENTS = 16;

    // Line i occupies coords[i*6 .. i*6+5]: x1 y1 z1 x2 y2 z2
    private static final float[] coords = new float[MAX_LINES * 6];
    private static final int[] colors = new int[MAX_LINES];
    private static final float[] lifetimes = new float[MAX_LINES];
    private static int lineCount = 0;
    private static boolean enabled = true;
    private static boolean overflowWarned = false;

    private ScriptDebug() {}

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    public static void drawLine(float x1, float y1, float z1, float x2, float y2, float z2, int rgb) {
        drawLine(x1, y1, z1, x2, y2, z2, rgb, 0.0f);
    }

    public static void drawLine(float x1, float y1, float z1, float x2, float y2, float z2, int rgb, float durationSeconds) {
        if (!enabled) return;
        if (lineCount >= MAX_LINES) {
            if (!overflowWarned) {
                overflowWarned = true;
                System.err.println("[ScriptDebug] More than " + MAX_LINES + " debug lines in flight; extra lines are dropped.");
            }
            return;
        }
        int o = lineCount * 6;
        coords[o] = x1; coords[o + 1] = y1; coords[o + 2] = z1;
        coords[o + 3] = x2; coords[o + 4] = y2; coords[o + 5] = z2;
        colors[lineCount] = rgb;
        lifetimes[lineCount] = Math.max(0.0f, durationSeconds);
        lineCount++;
    }

    public static void drawLine(Vector3 a, Vector3 b, int rgb) {
        drawLine(a.x, a.y, a.z, b.x, b.y, b.z, rgb, 0.0f);
    }

    /** Ray from the origin along (dx, dy, dz) scaled to {@code length}; length <= 0 draws the direction as given. */
    public static void drawRay(float ox, float oy, float oz, float dx, float dy, float dz, float length, int rgb) {
        drawRay(ox, oy, oz, dx, dy, dz, length, rgb, 0.0f);
    }

    public static void drawRay(float ox, float oy, float oz, float dx, float dy, float dz, float length, int rgb, float durationSeconds) {
        if (length > 0.0f) {
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6f) return;
            float s = length / len;
            dx *= s; dy *= s; dz *= s;
        }
        drawLine(ox, oy, oz, ox + dx, oy + dy, oz + dz, rgb, durationSeconds);
    }

    /**
     * Visualizes a raycast result: the ray in green up to the hit point plus the surface normal, or the full ray in
     * red when nothing was hit.
     */
    public static void drawRaycast(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance, RaycastHit hit) {
        if (hit != null && hit.hit) {
            drawLine(ox, oy, oz, hit.pointX, hit.pointY, hit.pointZ, GREEN);
            drawRay(hit.pointX, hit.pointY, hit.pointZ, hit.normalX, hit.normalY, hit.normalZ, 0.5f, YELLOW);
            drawCross(hit.pointX, hit.pointY, hit.pointZ, 0.15f, YELLOW);
        } else {
            drawRay(ox, oy, oz, dx, dy, dz, maxDistance, RED);
        }
    }

    /** Wire sphere: three great circles (XY, XZ, YZ). */
    public static void drawSphere(float cx, float cy, float cz, float radius, int rgb) {
        drawSphere(cx, cy, cz, radius, rgb, 0.0f);
    }

    public static void drawSphere(float cx, float cy, float cz, float radius, int rgb, float durationSeconds) {
        drawCircle(cx, cy, cz, radius, 0, rgb, durationSeconds);
        drawCircle(cx, cy, cz, radius, 1, rgb, durationSeconds);
        drawCircle(cx, cy, cz, radius, 2, rgb, durationSeconds);
    }

    /** Horizontal (XZ plane) circle, e.g. a detection radius on the ground. */
    public static void drawCircleXZ(float cx, float cy, float cz, float radius, int rgb) {
        drawCircle(cx, cy, cz, radius, 1, rgb, 0.0f);
    }

    /** Axis-aligned wire box centred on (cx, cy, cz) with full edge lengths (like BoxCollider size). */
    public static void drawBox(float cx, float cy, float cz, float sizeX, float sizeY, float sizeZ, int rgb) {
        drawBox(cx, cy, cz, sizeX, sizeY, sizeZ, rgb, 0.0f);
    }

    public static void drawBox(float cx, float cy, float cz, float sizeX, float sizeY, float sizeZ, int rgb, float durationSeconds) {
        float hx = sizeX * 0.5f, hy = sizeY * 0.5f, hz = sizeZ * 0.5f;
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        // Bottom ring
        drawLine(x0, y0, z0, x1, y0, z0, rgb, durationSeconds);
        drawLine(x1, y0, z0, x1, y0, z1, rgb, durationSeconds);
        drawLine(x1, y0, z1, x0, y0, z1, rgb, durationSeconds);
        drawLine(x0, y0, z1, x0, y0, z0, rgb, durationSeconds);
        // Top ring
        drawLine(x0, y1, z0, x1, y1, z0, rgb, durationSeconds);
        drawLine(x1, y1, z0, x1, y1, z1, rgb, durationSeconds);
        drawLine(x1, y1, z1, x0, y1, z1, rgb, durationSeconds);
        drawLine(x0, y1, z1, x0, y1, z0, rgb, durationSeconds);
        // Pillars
        drawLine(x0, y0, z0, x0, y1, z0, rgb, durationSeconds);
        drawLine(x1, y0, z0, x1, y1, z0, rgb, durationSeconds);
        drawLine(x1, y0, z1, x1, y1, z1, rgb, durationSeconds);
        drawLine(x0, y0, z1, x0, y1, z1, rgb, durationSeconds);
    }

    /** Small 3-axis cross marking a point. */
    public static void drawCross(float x, float y, float z, float size, int rgb) {
        float h = size * 0.5f;
        drawLine(x - h, y, z, x + h, y, z, rgb, 0.0f);
        drawLine(x, y - h, z, x, y + h, z, rgb, 0.0f);
        drawLine(x, y, z - h, x, y, z + h, rgb, 0.0f);
    }

    public static void clear() {
        lineCount = 0;
        overflowWarned = false;
    }

    // =========================================================================
    // Engine / renderer access
    // =========================================================================

    public static int getLineCount() {
        return lineCount;
    }

    /** Copies line {@code index}'s endpoints into {@code out[0..5]} (x1 y1 z1 x2 y2 z2) and returns its colour. */
    public static int getLine(int index, float[] out) {
        int o = index * 6;
        out[0] = coords[o]; out[1] = coords[o + 1]; out[2] = coords[o + 2];
        out[3] = coords[o + 3]; out[4] = coords[o + 4]; out[5] = coords[o + 5];
        return colors[index];
    }

    /**
     * Engine hook, called once per simulated frame before scripts run: drops lines whose time is up (one-frame lines
     * have already been rendered once) and ages the rest. Compacts in place; no allocation.
     */
    public static void beginFrame(float deltaTime) {
        int write = 0;
        for (int read = 0; read < lineCount; read++) {
            if (lifetimes[read] <= 0.0f) continue;
            lifetimes[read] -= deltaTime;
            if (write != read) {
                System.arraycopy(coords, read * 6, coords, write * 6, 6);
                colors[write] = colors[read];
                lifetimes[write] = lifetimes[read];
            }
            write++;
        }
        lineCount = write;
        if (lineCount < MAX_LINES) overflowWarned = false;
    }

    private static void drawCircle(float cx, float cy, float cz, float radius, int plane, int rgb, float durationSeconds) {
        float step = (float) (2.0 * Math.PI / SPHERE_SEGMENTS);
        float prevA = radius, prevB = 0.0f;
        for (int i = 1; i <= SPHERE_SEGMENTS; i++) {
            float angle = i * step;
            float a = (float) Math.cos(angle) * radius;
            float b = (float) Math.sin(angle) * radius;
            if (plane == 0) drawLine(cx + prevA, cy + prevB, cz, cx + a, cy + b, cz, rgb, durationSeconds);
            else if (plane == 1) drawLine(cx + prevA, cy, cz + prevB, cx + a, cy, cz + b, rgb, durationSeconds);
            else drawLine(cx, cy + prevA, cz + prevB, cx, cy + a, cz + b, rgb, durationSeconds);
            prevA = a;
            prevB = b;
        }
    }
}
