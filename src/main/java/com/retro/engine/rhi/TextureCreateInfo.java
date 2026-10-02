package com.retro.engine.rhi;

public record TextureCreateInfo(
    int width,
    int height,
    int format,
    boolean isPaletted
) {}
