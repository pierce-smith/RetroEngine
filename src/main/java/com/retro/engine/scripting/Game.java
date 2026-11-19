package com.retro.engine.scripting;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;

import java.util.List;

/**
 * Whole-game controls for scripts: the window title, quitting, and display settings (fullscreen, resolution, vsync,
 * frame rate cap). Typical use is an options menu or a "Quit" button.
 *
 * <p>Changes are requests: they take effect between frames, right after every script's update has run, so a script
 * never sees the window change halfway through a frame. Getters already report a requested value before it is applied.
 *
 * <p>In the editor's Play mode, {@link #quit()} stops Play mode. The window title and display settings belong to the
 * editor there, so those requests are remembered (getters report them) but only an exported game applies them.
 * Display choices are not saved between launches; store them with {@link com.retro.engine.scripting.framework.ScriptSaveData}
 * and apply them again on start.
 */
@PublicApi
public final class Game {

    /** One fullscreen video mode the primary monitor supports. */
    public record DisplayMode(int width, int height, int refreshRate) {}

    /** What the running loop provides. Exported games, headless runs and the editor each bind their own. */
    @Internal
    public interface Host {
        boolean isEditor();
        String getTitle();
        void setTitle(String title);
        void quit();
        boolean isFullscreen();
        void setFullscreen(boolean fullscreen);
        int getWindowWidth();
        int getWindowHeight();
        void setResolution(int width, int height);
        List<DisplayMode> getDisplayModes();
        boolean isVsync();
        void setVsync(boolean vsync);
        int getFrameRateCap();
        void setFrameRateCap(int fps);
    }

    private static volatile Host host;

    // Requests recorded by scripts and applied by applyPending(); null / NO_REQUEST means "nothing requested"
    private static final int NO_REQUEST = Integer.MIN_VALUE;
    private static volatile String pendingTitle;
    private static volatile Boolean pendingFullscreen;
    private static volatile int pendingWidth = NO_REQUEST, pendingHeight = NO_REQUEST;
    private static volatile Boolean pendingVsync;
    private static volatile int pendingFrameRateCap = NO_REQUEST;
    private static volatile boolean pendingQuit;

    private Game() {}

    // ------------------------------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------------------------------

    /** True while the game runs inside the editor's Play mode, false in an exported game or headless run. */
    public static boolean isEditor() {
        Host h = host;
        return h != null && h.isEditor();
    }

    /** The window title; an exported game starts with its project name. */
    public static String getTitle() {
        String t = pendingTitle;
        if (t != null) return t;
        Host h = host;
        return h != null ? h.getTitle() : "";
    }

    /** Renames the game window. Null is treated as empty. */
    public static void setTitle(String title) {
        pendingTitle = (title != null) ? title : "";
    }

    /**
     * Closes the game after the current frame: the scene stops (every script's onDestroy runs) and the process exits.
     * In the editor this stops Play mode instead.
     */
    public static void quit() {
        pendingQuit = true;
    }

    // ------------------------------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------------------------------

    public static boolean isFullscreen() {
        Boolean f = pendingFullscreen;
        if (f != null) return f;
        Host h = host;
        return h != null && h.isFullscreen();
    }

    /** Fullscreen on the primary monitor, or back to a window. Players can also press F11 or Alt+Enter. */
    public static void setFullscreen(boolean fullscreen) {
        pendingFullscreen = fullscreen;
    }

    /** Width of the game window in screen points (on a Retina/HiDPI display the image has more pixels than this). */
    public static int getWindowWidth() {
        int w = pendingWidth;
        if (w != NO_REQUEST) return w;
        Host h = host;
        return h != null ? h.getWindowWidth() : 0;
    }

    /** Height of the game window in screen points. */
    public static int getWindowHeight() {
        int hgt = pendingHeight;
        if (hgt != NO_REQUEST) return hgt;
        Host h = host;
        return h != null ? h.getWindowHeight() : 0;
    }

    /**
     * Windowed: resizes the window. Fullscreen: switches to the supported {@link #getDisplayModes() display mode}
     * closest to the size, which is kept for later fullscreen entries. Sizes below 1 are ignored.
     */
    public static void setResolution(int width, int height) {
        if (width < 1 || height < 1) return;
        pendingWidth = width;
        pendingHeight = height;
    }

    /** The primary monitor's fullscreen display modes, largest first. Empty in headless runs. */
    public static List<DisplayMode> getDisplayModes() {
        Host h = host;
        return h != null ? h.getDisplayModes() : List.of();
    }

    /** Whether frames wait for the display's refresh. Starts at the project's Runtime setting. */
    public static boolean isVsync() {
        Boolean v = pendingVsync;
        if (v != null) return v;
        Host h = host;
        return h == null || h.isVsync();
    }

    /** Turns vsync on or off. Graphics drivers can override this; the frame rate cap still applies either way. */
    public static void setVsync(boolean vsync) {
        pendingVsync = vsync;
    }

    /** The highest frame rate the game runs at, or 0 for no cap beyond vsync. Starts at the project's Runtime setting. */
    public static int getFrameRateCap() {
        int cap = pendingFrameRateCap;
        if (cap != NO_REQUEST) return cap;
        Host h = host;
        return h != null ? h.getFrameRateCap() : 0;
    }

    /** Caps the frame rate. 0 (or below) removes the cap; other values are clamped to 30..1000. */
    public static void setFrameRateCap(int fps) {
        pendingFrameRateCap = sanitizeFrameRateCap(fps);
    }

    /** Same rule as the project setting: 0 means none, anything else is clamped to 30..1000. */
    static int sanitizeFrameRateCap(int fps) {
        return com.retro.engine.core.ProjectSettings.sanitizeFrameRateCap(fps);
    }

    // ------------------------------------------------------------------------------------------
    // Loop side
    // ------------------------------------------------------------------------------------------

    /** Binds the running loop's host and drops requests left over from a previous run. */
    @Internal
    public static void bind(Host newHost) {
        host = newHost;
        clearPending();
    }

    /** Applies every request recorded since the last call. Loops call this once per frame, after the scene update. */
    @Internal
    public static void applyPending() {
        Host h = host;
        if (h == null) {
            clearPending();
            return;
        }
        String title = pendingTitle;
        if (title != null) { pendingTitle = null; h.setTitle(title); }

        Boolean fullscreen = pendingFullscreen;
        if (fullscreen != null) { pendingFullscreen = null; h.setFullscreen(fullscreen); }

        int w = pendingWidth, hgt = pendingHeight;
        if (w != NO_REQUEST && hgt != NO_REQUEST) {
            pendingWidth = NO_REQUEST;
            pendingHeight = NO_REQUEST;
            h.setResolution(w, hgt);
        }

        Boolean vsync = pendingVsync;
        int cap = pendingFrameRateCap;
        if (vsync != null) { pendingVsync = null; h.setVsync(vsync); }
        if (cap != NO_REQUEST) { pendingFrameRateCap = NO_REQUEST; h.setFrameRateCap(cap); }

        if (pendingQuit) {
            pendingQuit = false;
            h.quit();
        }
    }

    private static void clearPending() {
        pendingTitle = null;
        pendingFullscreen = null;
        pendingWidth = NO_REQUEST;
        pendingHeight = NO_REQUEST;
        pendingVsync = null;
        pendingFrameRateCap = NO_REQUEST;
        pendingQuit = false;
    }
}
