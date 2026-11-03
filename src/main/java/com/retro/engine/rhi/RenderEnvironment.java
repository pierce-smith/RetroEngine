package com.retro.engine.rhi;

/**
 * Per-project global render environment the lit shaders read alongside {@link SceneLights}: ambient light, linear
 * distance fog and the ground shadow blob toggle. Owned by {@link SceneLights#environment()} so it crosses the RHI
 * boundary with the lights it shades, and filled from {@code ProjectSettings} each frame with plain field copies
 * (no allocation).
 *
 * <p>Defaults reproduce the engine's previous hard-coded look, so projects saved before these settings existed render
 * unchanged.
 */
public final class RenderEnvironment {

    public static final boolean DEFAULT_SHADOW_BLOBS = true;
    public static final boolean DEFAULT_FOG_ENABLED = true;
    public static final float DEFAULT_FOG_START = 20.0f;
    public static final float DEFAULT_FOG_END = 150.0f;
    public static final float DEFAULT_FOG_DENSITY = 1.0f;
    public static final float DEFAULT_FOG_R = 0.12f, DEFAULT_FOG_G = 0.13f, DEFAULT_FOG_B = 0.16f;
    public static final float DEFAULT_AMBIENT_R = 0.35f, DEFAULT_AMBIENT_G = 0.35f, DEFAULT_AMBIENT_B = 0.38f;
    public static final float DEFAULT_AMBIENT_INTENSITY = 1.0f;

    /** Downward-raycast ground shadow blobs under every draw (a fallback for scenes without shadow-mapped lighting). */
    public boolean shadowBlobsEnabled = DEFAULT_SHADOW_BLOBS;

    public boolean fogEnabled = DEFAULT_FOG_ENABLED;
    /** View distance (world units) where fog begins and where it reaches {@link #fogDensity}. */
    public float fogStart = DEFAULT_FOG_START, fogEnd = DEFAULT_FOG_END;
    /** Maximum fog opacity (0..1) reached at {@link #fogEnd}. */
    public float fogDensity = DEFAULT_FOG_DENSITY;
    public float fogR = DEFAULT_FOG_R, fogG = DEFAULT_FOG_G, fogB = DEFAULT_FOG_B;

    public float ambientR = DEFAULT_AMBIENT_R, ambientG = DEFAULT_AMBIENT_G, ambientB = DEFAULT_AMBIENT_B;
    public float ambientIntensity = DEFAULT_AMBIENT_INTENSITY;

    /** Fog opacity the shaders should apply at {@link #fogEnd}: 0 when fog is disabled. */
    public float effectiveFogDensity() {
        return fogEnabled ? fogDensity : 0.0f;
    }

    /** {@link #fogEnd}, kept strictly beyond {@link #fogStart} so the shader ramp never divides by zero. */
    public float safeFogEnd() {
        return Math.max(fogEnd, fogStart + 0.01f);
    }

    public void setDefaults() {
        shadowBlobsEnabled = DEFAULT_SHADOW_BLOBS;
        fogEnabled = DEFAULT_FOG_ENABLED;
        fogStart = DEFAULT_FOG_START;
        fogEnd = DEFAULT_FOG_END;
        fogDensity = DEFAULT_FOG_DENSITY;
        fogR = DEFAULT_FOG_R; fogG = DEFAULT_FOG_G; fogB = DEFAULT_FOG_B;
        ambientR = DEFAULT_AMBIENT_R; ambientG = DEFAULT_AMBIENT_G; ambientB = DEFAULT_AMBIENT_B;
        ambientIntensity = DEFAULT_AMBIENT_INTENSITY;
    }

    public void copyFrom(RenderEnvironment other) {
        shadowBlobsEnabled = other.shadowBlobsEnabled;
        fogEnabled = other.fogEnabled;
        fogStart = other.fogStart;
        fogEnd = other.fogEnd;
        fogDensity = other.fogDensity;
        fogR = other.fogR; fogG = other.fogG; fogB = other.fogB;
        ambientR = other.ambientR; ambientG = other.ambientG; ambientB = other.ambientB;
        ambientIntensity = other.ambientIntensity;
    }
}
