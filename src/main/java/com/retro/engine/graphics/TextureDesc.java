package com.retro.engine.graphics;

public record TextureDesc(
    int width,
    int height,
    int format,
    boolean isPaletted
) {}
