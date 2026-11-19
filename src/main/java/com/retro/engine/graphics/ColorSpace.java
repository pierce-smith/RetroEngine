package com.retro.engine.graphics;

import com.retro.engine.api.Experimental;
import com.retro.engine.api.PublicApi;

/**
 * Project-wide color space for lighting and fog, chosen in Project Settings.
 *
 * <p>Every color an author picks (texture pixels, light colors, fog, ambient, vertex tints) is a display (sRGB) value
 * in both modes: it means the color shown on screen. The modes differ only in where the lit shaders do their math:
 * <ul>
 *   <li>{@link #GAMMA}: directly on those display values, as PS1/PS2-class hardware did. Light falloff is steeper and
 *   overlapping lights saturate quickly. Every project saved before this setting existed loads as Gamma.</li>
 *   <li>{@link #LINEAR}: the lit shaders decode colors to linear light, shade and fog there, and encode the result
 *   back to display values as their last step. Falloff and light mixing behave physically, and lighting matches
 *   Blender and glTF viewers. The default for new projects.</li>
 * </ul>
 *
 * <p>The scene target holds display values in both modes, so alpha blending (decals, transparency, shadow blobs),
 * post-processing, the UI and the Scene tile composite work on display values and are identical across modes. Unlit
 * draws and particles look the same in both. Normal, specular and lightmap/AO channels are data and are never decoded.
 */
@Experimental
@PublicApi
public enum ColorSpace {

    GAMMA("GAMMA (CLASSIC)", 0.0f),
    LINEAR("LINEAR", 1.0f);

    private final String displayName;
    private final float shaderValue;

    ColorSpace(String displayName, float shaderValue) {
        this.displayName = displayName;
        this.shaderValue = shaderValue;
    }

    /** Upper-case label for the editor UI. */
    public String displayName() {
        return displayName;
    }

    /** Value the lit shaders read (Vulkan {@code LightBuffer.ambientColor.w}, Metal {@code Uniforms.ambientColor.w}): 0 = Gamma, 1 = Linear. */
    public float shaderValue() {
        return shaderValue;
    }

    /**
     * Converts a display-value color channel (0..1) to the value the lit shaders compute with: unchanged for Gamma,
     * decoded to linear light for Linear. Intensities and other scalars are never passed through this.
     */
    public float toShading(float displayValue) {
        return this == LINEAR ? srgbToLinear(displayValue) : displayValue;
    }

    /** The sRGB transfer function's inverse (IEC 61966-2-1), matching {@code srgbToLinear} in the lit shaders. */
    public static float srgbToLinear(float c) {
        if (!(c > 0.0f)) return 0.0f; // also maps NaN to 0
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    /** The sRGB transfer function (IEC 61966-2-1), matching {@code linearToSrgb} in the lit shaders. */
    public static float linearToSrgb(float c) {
        if (!(c > 0.0f)) return 0.0f;
        return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1.0f / 2.4f) - 0.055f;
    }

    /** The named value, or {@code fallback} for a name this build doesn't know. */
    public static ColorSpace fromName(String name, ColorSpace fallback) {
        for (ColorSpace value : values()) {
            if (value.name().equals(name)) return value;
        }
        return fallback;
    }
}
