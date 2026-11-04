package com.retro.engine.graphics;

/**
 * Per-frame post-processing parameters for the single fullscreen pass that composites the offscreen scene image
 * (vignette, CRT scanlines, chromatic aberration, saturation / contrast). Plain fields so the render snapshot copies
 * it without allocating; backends copy it in {@link IRenderBackend#setPostProcess} and never retain the instance.
 */
public final class PostProcessSettings {
    public boolean enabled;
    public float vignette, scanlines, scanlineSpeed, chromatic;
    public float saturation = 1.0f, contrast = 1.0f;
    /** Seconds since startup; scrolls the scanlines. */
    public float time;

    public void copyFrom(PostProcessSettings o) {
        enabled = o.enabled;
        vignette = o.vignette; scanlines = o.scanlines; scanlineSpeed = o.scanlineSpeed; chromatic = o.chromatic;
        saturation = o.saturation; contrast = o.contrast; time = o.time;
    }

    public void disable() { enabled = false; }

    /** Number of floats written by {@link #pack}: matches the PostFX uniform block in postprocess.metal / .frag. */
    public static final int PACKED_FLOATS = 8;

    /** Packs [time, vignette, scanlines, scanlineSpeed, chromatic, saturation, contrast, 0] for the shader. */
    public void pack(java.nio.ByteBuffer dst, int offset) {
        dst.putFloat(offset, time);
        dst.putFloat(offset + 4, vignette);
        dst.putFloat(offset + 8, scanlines);
        dst.putFloat(offset + 12, scanlineSpeed);
        dst.putFloat(offset + 16, chromatic);
        dst.putFloat(offset + 20, saturation);
        dst.putFloat(offset + 24, contrast);
        dst.putFloat(offset + 28, 0.0f);
    }
}
