package com.retro.engine.rhi;

public record TextureDesc(
    int width,
    int height,
    int format,
    boolean isPaletted
) {}
