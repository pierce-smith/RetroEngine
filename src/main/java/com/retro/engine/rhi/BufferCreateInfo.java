package com.retro.engine.rhi;

public record BufferCreateInfo(
    long size,
    boolean isHostVisible
) {}
