package com.retro.engine.graphics;

/**
 * Depth bias for ground shadow blobs, shared by both backends so they match.
 *
 * <p>Blobs are flat quads lying on the ground. A fixed world-space lift alone can't keep them in front of the floor:
 * depth precision drops with distance, so past a few tens of metres the lift falls below one depth step and the blob
 * and floor trade pixels every frame. That showed up as a flashing moiré over the whole scene, worse at high frame
 * rates. A slope-scaled bias moves the blob toward the camera by an amount that tracks the depth slope at each pixel.
 * Negative values pull toward the camera, because both backends use 0 = near and a LESS_EQUAL test.
 */
public final class ShadowBlobDepth {
    /** Constant term, in units of the depth buffer's minimum resolvable step. */
    public static final float CONSTANT_BIAS = -16.0f;
    /** Multiplies the primitive's depth slope; carries grazing angles, where the constant term alone falls short. */
    public static final float SLOPE_BIAS = -2.0f;

    private ShadowBlobDepth() {}
}
