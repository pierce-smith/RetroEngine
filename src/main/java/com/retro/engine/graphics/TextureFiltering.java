package com.retro.engine.graphics;

/**
 * Project-wide filtering for scene material textures, chosen in Project Settings.
 *
 * <p>Nearest keeps every texel a hard square, the unfiltered look of PS1-class hardware. Bilinear blends neighbouring
 * texels, the soft look of PS2-class hardware. UI and screen-space reads have their own samplers and never change.
 */
public enum TextureFiltering {

    NEAREST,
    BILINEAR;

    /** The named value, or {@code fallback} for a name this build doesn't know. */
    public static TextureFiltering fromName(String name, TextureFiltering fallback) {
        for (TextureFiltering value : values()) {
            if (value.name().equals(name)) return value;
        }
        return fallback;
    }
}
