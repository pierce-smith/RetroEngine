package com.retro.engine.graphics;

/**
 * Standalone runtime view state. In an exported game the scene fills the whole window instead of the editor's docked
 * Scene tile; {@link com.retro.engine.core.RuntimeLoop} reports the window size here every frame and the renderer
 * reads it back. Untouched (and {@link #isFullscreen()} false) inside the editor.
 */
public final class GameView {

    private static volatile boolean fullscreen = false;
    private static volatile float aspect = 16.0f / 9.0f;
    private static volatile int width = 1280, height = 720;

    private GameView() {}

    /** Called each runtime frame with the window size; the scene viewport then covers the whole window. */
    public static void setFullscreen(int windowWidth, int windowHeight) {
        fullscreen = true;
        if (windowWidth > 0 && windowHeight > 0) {
            aspect = (float) windowWidth / (float) windowHeight;
            width = windowWidth;
            height = windowHeight;
        }
    }

    public static boolean isFullscreen() { return fullscreen; }
    public static int width() { return width; }
    public static int height() { return height; }
    public static float aspect() { return aspect; }
}
