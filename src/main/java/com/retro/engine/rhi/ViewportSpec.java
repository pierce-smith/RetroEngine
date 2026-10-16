package com.retro.engine.rhi;

public record ViewportSpec(
    long nativeWindowHandle,
    int width,
    int height,
    boolean vsync
) {
    public ViewportSpec() {
        this(0L, 1280, 720, true);
    }
}
