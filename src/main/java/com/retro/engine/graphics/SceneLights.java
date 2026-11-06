package com.retro.engine.graphics;

/**
 * Per-frame scene lighting the lit shaders evaluate together in a single forward pass: one Directional light, up to
 * {@link #MAX_POINT_LIGHTS} Point lights and up to {@link #MAX_SPOT_LIGHTS} Spot lights, plus the project-wide
 * {@link RenderEnvironment} (ambient, fog, shadow blobs). Every {@link Light} is preallocated, so snapshots and
 * backends fill and copy it without per-frame allocation.
 */
public final class SceneLights {

    public static final int DIRECTIONAL = 0;
    public static final int POINT = 1;
    public static final int SPOT = 2;

    /** Array sizes of the shader light blocks; must match pointLights[] / spotLights[] in the lit shaders. */
    public static final int MAX_POINT_LIGHTS = 32;
    public static final int MAX_SPOT_LIGHTS = 8;

    public static final float DEFAULT_SUN_DIR_X = -0.5f, DEFAULT_SUN_DIR_Y = -1.0f, DEFAULT_SUN_DIR_Z = -0.5f;

    public static final class Light {
        public float posX, posY, posZ;
        public float dirX, dirY, dirZ;
        public float r, g, b;
        public float intensity;

        public void set(float posX, float posY, float posZ,
                        float dirX, float dirY, float dirZ,
                        float r, float g, float b, float intensity) {
            this.posX = posX; this.posY = posY; this.posZ = posZ;
            this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
            this.r = r; this.g = g; this.b = b;
            this.intensity = intensity;
        }

        void copyFrom(Light other) {
            set(other.posX, other.posY, other.posZ, other.dirX, other.dirY, other.dirZ,
                    other.r, other.g, other.b, other.intensity);
        }
    }

    private final Light directional = new Light();
    private boolean hasDirectional;
    private final Light[] points = newLights(MAX_POINT_LIGHTS);
    private int pointCount;
    private final Light[] spots = newLights(MAX_SPOT_LIGHTS);
    private int spotCount;
    // Not cleared by reset(): it is project state, not something collected from scene entities
    private final RenderEnvironment environment = new RenderEnvironment();

    private static Light[] newLights(int count) {
        Light[] lights = new Light[count];
        for (int i = 0; i < count; i++) lights[i] = new Light();
        return lights;
    }

    public boolean hasDirectional() { return hasDirectional; }
    /** The directional light; its values are only meaningful while {@link #hasDirectional()}. */
    public Light directional() { return directional; }
    public int pointCount() { return pointCount; }
    public Light point(int index) { return points[index]; }
    public int spotCount() { return spotCount; }
    public Light spot(int index) { return spots[index]; }
    /** Ambient, fog and shadow blob settings for the frame; untouched by {@link #reset()} and {@link #setDefaultSun()}. */
    public RenderEnvironment environment() { return environment; }

    /**
     * Claims the next free light of {@code type} ({@link #DIRECTIONAL}, {@link #POINT} or {@link #SPOT}) for the caller
     * to {@link Light#set fill}, or returns null when that type is already at capacity.
     */
    public Light add(int type) {
        switch (type) {
            case DIRECTIONAL:
                if (hasDirectional) return null;
                hasDirectional = true;
                return directional;
            case POINT:
                if (pointCount < MAX_POINT_LIGHTS) return points[pointCount++];
                warnOverCapacity("point", MAX_POINT_LIGHTS);
                return null;
            case SPOT:
                if (spotCount < MAX_SPOT_LIGHTS) return spots[spotCount++];
                warnOverCapacity("spot", MAX_SPOT_LIGHTS);
                return null;
            default:
                throw new IllegalArgumentException("Unknown light type " + type);
        }
    }

    private static boolean warnedOverCapacity = false;

    /** Once per process: the same lights go over the limit every frame. */
    private static void warnOverCapacity(String type, int limit) {
        if (warnedOverCapacity) return;
        warnedOverCapacity = true;
        System.err.println("[SceneLights] More than " + limit + " " + type + " lights are active. The extra lights are not drawn.");
    }

    public boolean isEmpty() {
        return !hasDirectional && pointCount == 0 && spotCount == 0;
    }

    public boolean isFull() {
        return hasDirectional && pointCount == MAX_POINT_LIGHTS && spotCount == MAX_SPOT_LIGHTS;
    }

    public void reset() {
        hasDirectional = false;
        pointCount = 0;
        spotCount = 0;
    }

    public void copyFrom(SceneLights other) {
        hasDirectional = other.hasDirectional;
        directional.copyFrom(other.directional);
        pointCount = other.pointCount;
        for (int i = 0; i < pointCount; i++) points[i].copyFrom(other.points[i]);
        spotCount = other.spotCount;
        for (int i = 0; i < spotCount; i++) spots[i].copyFrom(other.spots[i]);
        environment.copyFrom(other.environment);
    }

    /** Engine default sun, used by backends when the scene has no Light entity. */
    public void setDefaultSun() {
        reset();
        add(DIRECTIONAL).set(0.0f, 5.0f, 0.0f, DEFAULT_SUN_DIR_X, DEFAULT_SUN_DIR_Y, DEFAULT_SUN_DIR_Z, 1.0f, 0.95f, 0.85f, 1.2f);
    }
}
