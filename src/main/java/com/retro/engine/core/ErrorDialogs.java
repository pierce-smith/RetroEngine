package com.retro.engine.core;

import com.retro.engine.api.Internal;

import java.nio.file.Path;

/**
 * What a player sees when something goes wrong: a native dialog in plain words, pointing at the log file, instead of
 * an empty scene or a window that just vanishes. Off until a windowed run turns it on ({@link #setEnabled}), so
 * headless runs and tests never block on a dialog.
 */
@Internal
public final class ErrorDialogs {

    private static volatile boolean enabled;
    private static volatile Path logFile;
    private static final java.util.concurrent.atomic.AtomicBoolean levelErrorShowing = new java.util.concurrent.atomic.AtomicBoolean();

    private ErrorDialogs() {}

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled; }

    /** Where this run's log goes; exported games log to a file (see Main). Null when only the console has it. */
    public static void setLogFile(Path path) { logFile = path; }
    public static Path getLogFile() { return logFile; }

    /** The game could not load its data or startup scene. Blocks until the player closes it. */
    public static void startupFailed(String gameName, String detail) {
        show("Could not start", name(gameName) + " could not start.\n\n" + detail + logHint());
    }

    /** The game crashed. {@code extra} (e.g. where the editor backed up the scene) may be null. Blocks. */
    public static void crashed(String gameName, Throwable cause, String extra) {
        String what = cause == null ? "an unknown error" : cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        show("Crash", name(gameName) + " ran into a problem it could not recover from and has to close.\n\n"
                + "Error: " + what + (extra != null ? "\n\n" + extra : "") + logHint());
    }

    /**
     * A level (or other scene data) failed to load while playing; the game keeps running on the scene it had. Shown on
     * its own thread so the game keeps drawing, and one at a time so a broken level loop can't stack dialogs.
     */
    public static void levelLoadFailed(String gameName, String detail) {
        if (!enabled || !levelErrorShowing.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                NativeFileDialog.showError("Level error", name(gameName) + " could not load part of the game, so it stayed where it was.\n\n"
                        + detail + logHint());
            } catch (Throwable e) {
                Log.error("ErrorDialogs", "Could not show the level error dialog", e);
            } finally {
                levelErrorShowing.set(false);
            }
        }, "RetroEngine-LevelError");
        t.setDaemon(true);
        t.start();
    }

    private static void show(String title, String message) {
        if (!enabled) return;
        try {
            NativeFileDialog.showError(title, message);
        } catch (Throwable e) {
            Log.error("ErrorDialogs", "Could not show the " + title + " dialog", e);
        }
    }

    static String name(String gameName) {
        return gameName == null || gameName.isBlank() ? "The game" : gameName;
    }

    private static String logHint() {
        Path log = logFile;
        return log != null ? "\n\nThe log has the details: " + log : "";
    }
}
