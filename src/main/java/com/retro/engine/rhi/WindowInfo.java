package com.retro.engine.rhi;

public record WindowInfo(
    long nativeWindowHandle,
    int width,
    int height,
    boolean vsync
) {
    public WindowInfo() {
        this(0L, 1280, 720, true);
    }
}
