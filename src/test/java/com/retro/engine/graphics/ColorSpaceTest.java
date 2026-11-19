package com.retro.engine.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorSpaceTest {

    @Test
    void gammaLeavesColorsAlone() {
        for (float c = 0.0f; c <= 1.0f; c += 0.05f) assertEquals(c, ColorSpace.GAMMA.toShading(c));
    }

    @Test
    void linearDecodesWithTheSrgbCurve() {
        assertEquals(0.0f, ColorSpace.LINEAR.toShading(0.0f));
        assertEquals(1.0f, ColorSpace.LINEAR.toShading(1.0f), 1e-6f);
        // Mid grey on screen is about a fifth of the light
        assertEquals(0.2140f, ColorSpace.LINEAR.toShading(0.5f), 1e-4f);
        // The straight segment near black
        assertEquals(0.02f / 12.92f, ColorSpace.LINEAR.toShading(0.02f), 1e-7f);
    }

    @Test
    void encodeUndoesDecode() {
        for (float c = 0.0f; c <= 1.0f; c += 0.01f) {
            assertEquals(c, ColorSpace.linearToSrgb(ColorSpace.srgbToLinear(c)), 1e-5f);
        }
    }

    @Test
    void badInputIsBlackNotNaN() {
        assertEquals(0.0f, ColorSpace.srgbToLinear(Float.NaN));
        assertEquals(0.0f, ColorSpace.srgbToLinear(-1.0f));
        assertEquals(0.0f, ColorSpace.linearToSrgb(Float.NaN));
    }

    @Test
    void shadersReadZeroForGammaAndOneForLinear() {
        RenderEnvironment env = new RenderEnvironment();
        assertEquals(ColorSpace.GAMMA, env.colorSpace, "the environment default is the original look");
        assertEquals(0.0f, env.colorSpaceShaderValue());
        env.colorSpace = ColorSpace.LINEAR;
        assertEquals(1.0f, env.colorSpaceShaderValue());
        env.colorSpace = null;
        assertEquals(0.0f, env.colorSpaceShaderValue());
        assertEquals(0.5f, env.toShading(0.5f));
    }

    @Test
    void unknownNamesFallBack() {
        assertEquals(ColorSpace.LINEAR, ColorSpace.fromName("LINEAR", ColorSpace.GAMMA));
        assertEquals(ColorSpace.GAMMA, ColorSpace.fromName("HDR", ColorSpace.GAMMA));
        assertEquals(ColorSpace.GAMMA, ColorSpace.fromName(null, ColorSpace.GAMMA));
    }
}
