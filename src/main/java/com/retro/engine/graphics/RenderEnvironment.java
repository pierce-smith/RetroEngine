package com.retro.engine.graphics;

/**
 * Per-project global render environment the lit shaders read alongside {@link SceneLights}: the shading model, ambient
 * light, distance fog (which also sets the draw distance) and the ground shadow blob toggle. Owned by {@link SceneLights#environment()} so it crosses the RHI
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
    public static final ShadingModel DEFAULT_SHADING_MODEL = ShadingModel.BLINN_PHONG;
    public static final TextureFiltering DEFAULT_TEXTURE_FILTERING = TextureFiltering.NEAREST;

    /** Per-vertex (Gouraud) or per-pixel (Blinn-Phong) lighting for every lit draw. Never null. */
    public ShadingModel shadingModel = DEFAULT_SHADING_MODEL;

    /** Filtering for scene material textures. Never null. */
    public TextureFiltering textureFiltering = DEFAULT_TEXTURE_FILTERING;

    /** Downward-raycast ground shadow blobs under every draw (a fallback for scenes without shadow-mapped lighting). */
    public boolean shadowBlobsEnabled = DEFAULT_SHADOW_BLOBS;

    /**
     * Fog doubles as the draw distance (RenderWare style): it is fully opaque at {@link #fogEnd}, the scene clears to
     * the fog color, and entities entirely beyond fogEnd are culled, so nothing visibly pops in or out.
     */
    public boolean fogEnabled = DEFAULT_FOG_ENABLED;
    /** View depth (world units) where fog begins, and where it becomes fully opaque and entities are culled. */
    public float fogStart = DEFAULT_FOG_START, fogEnd = DEFAULT_FOG_END;
    /** Thickness (0..1): 0 ramps linearly from start to end, 1 is a near-solid wall well before the end. */
    public float fogDensity = DEFAULT_FOG_DENSITY;
    public float fogR = DEFAULT_FOG_R, fogG = DEFAULT_FOG_G, fogB = DEFAULT_FOG_B;

    public float ambientR = DEFAULT_AMBIENT_R, ambientG = DEFAULT_AMBIENT_G, ambientB = DEFAULT_AMBIENT_B;
    public float ambientIntensity = DEFAULT_AMBIENT_INTENSITY;

    /** {@link ShadingModel#shaderValue()} of the current model; a null model falls back to the default. */
    public float shadingModelShaderValue() {
        return (shadingModel != null ? shadingModel : DEFAULT_SHADING_MODEL).shaderValue();
    }

    /** Fog opacity at {@link #fogEnd} for the shaders: 1 when fog is on, 0 (fog off) otherwise. */
    public float fogOpacity() {
        return fogEnabled ? 1.0f : 0.0f;
    }

    /**
     * Exponent of the shaders' ease-out ramp {@code 1 - (1 - t)^k} between fog start and end: 1 (linear) at density 0
     * up to 5 at density 1, where fog is ~97% opaque halfway to the end. NaN density falls back to linear.
     */
    public float fogCurveExponent() {
        float d = (fogDensity >= 0.0f) ? Math.min(fogDensity, 1.0f) : 0.0f;
        return 1.0f + 4.0f * d;
    }

    /** True when an object whose nearest point is {@code viewDepth} units ahead of the camera is hidden by fog. */
    public boolean isFogCulled(float viewDepth) {
        return fogEnabled && viewDepth > safeFogEnd();
    }

    /** {@link #fogStart}, with a non-finite value (a corrupt settings file) treated as 0. */
    public float safeFogStart() {
        return Float.isFinite(fogStart) ? fogStart : 0.0f;
    }

    /** {@link #fogEnd}, kept finite and strictly beyond the fog start so the shader ramp never divides by zero or NaN. */
    public float safeFogEnd() {
        float start = safeFogStart();
        return Float.isFinite(fogEnd) ? Math.max(fogEnd, start + 0.01f) : start + 0.01f;
    }

    public void setDefaults() {
        shadingModel = DEFAULT_SHADING_MODEL;
        textureFiltering = DEFAULT_TEXTURE_FILTERING;
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
        shadingModel = other.shadingModel;
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
