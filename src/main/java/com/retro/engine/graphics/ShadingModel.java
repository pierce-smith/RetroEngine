package com.retro.engine.graphics;

import com.retro.engine.api.Experimental;
import com.retro.engine.api.PublicApi;

/**
 * Project-wide lighting model for the lit scene shaders, chosen in Project Settings.
 *
 * <p>{@link #shaderValue()} is what the shaders read (Vulkan {@code LightBuffer.shadingModel}, Metal
 * {@code Uniforms.fogParams.w}). Blinn-Phong is 0 so a zeroed slot keeps the per-pixel look.
 */
@Experimental
@PublicApi
public enum ShadingModel {

    /** Per-vertex lighting, interpolated across each triangle: the faceted, low-poly look of fixed-function hardware. */
    GOURAUD("GOURAUD", 1.0f),
    /** Per-pixel Blinn-Phong with normal / specular maps, lightmaps and shadow maps. */
    BLINN_PHONG("BLINN-PHONG", 0.0f);

    private final String displayName;
    private final float shaderValue;

    ShadingModel(String displayName, float shaderValue) {
        this.displayName = displayName;
        this.shaderValue = shaderValue;
    }

    /** Upper-case label for the editor UI. */
    public String displayName() {
        return displayName;
    }

    /** Value written into the lit shaders' uniform: 0 = Blinn-Phong, 1 = Gouraud. */
    public float shaderValue() {
        return shaderValue;
    }

    /** Parses a persisted {@link #name()}; null or unknown names (a newer editor's model) fall back to {@code fallback}. */
    public static ShadingModel fromName(String name, ShadingModel fallback) {
        if (name != null) {
            for (ShadingModel m : values()) {
                if (m.name().equals(name)) return m;
            }
        }
        return fallback;
    }
}
