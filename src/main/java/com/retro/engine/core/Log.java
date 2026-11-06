package com.retro.engine.core;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Levelled, tagged engine logging. Lines go through System.out and System.err, so the packaged log file and the editor
 * console keep receiving them: DEBUG and INFO on stdout, WARN and ERROR on stderr.
 *
 * <pre>
 *   [Tag] message            INFO
 *   [Tag] DEBUG: message
 *   [Tag] WARN: message
 *   [Tag] ERROR: message
 * </pre>
 *
 * The threshold starts at INFO, or at {@code -Dretro.log=debug|info|warn|error}. Check {@link #isEnabled} before building
 * a message on a hot path, because string concatenation allocates even when the line is then dropped.
 */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level threshold = parseLevel(System.getProperty("retro.log"), Level.INFO);

    private Log() {}

    /** The level named by {@code name} in any case, or {@code fallback} when it is null or unknown. */
    static Level parseLevel(String name, Level fallback) {
        if (name == null) return fallback;
        try {
            return Level.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static void setLevel(Level level) {
        threshold = level != null ? level : Level.INFO;
    }

    public static Level getLevel() {
        return threshold;
    }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void debug(String tag, String message) {
        write(Level.DEBUG, tag, message);
    }

    public static void info(String tag, String message) {
        write(Level.INFO, tag, message);
    }

    public static void warn(String tag, String message) {
        write(Level.WARN, tag, message);
    }

    public static void error(String tag, String message) {
        write(Level.ERROR, tag, message);
    }

    /** Logs the line, then the stack trace, as one uninterrupted block. */
    public static void error(String tag, String message, Throwable cause) {
        if (!isEnabled(Level.ERROR)) return;
        PrintStream err = System.err;
        synchronized (err) {
            write(Level.ERROR, tag, message);
            if (cause != null) cause.printStackTrace(err);
        }
    }

    private static void write(Level level, String tag, String message) {
        if (!isEnabled(level)) return;
        String prefix = level == Level.INFO ? "" : level + ": ";
        PrintStream out = level.ordinal() >= Level.WARN.ordinal() ? System.err : System.out;
        out.println("[" + tag + "] " + prefix + message);
    }
}
