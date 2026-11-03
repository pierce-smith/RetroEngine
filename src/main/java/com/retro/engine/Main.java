package com.retro.engine;

import com.retro.engine.core.EngineContext;
import com.retro.engine.core.EngineLoop;
import com.retro.engine.core.RuntimeLoop;

import org.lwjgl.system.Configuration;

/**
 * Entry point. Arguments pick the mode:
 * <ul>
 *   <li>none: the editor (EngineLoop)</li>
 *   <li>{@code --runtime} / {@code --game}: a standalone game (RuntimeLoop), as launched by exported builds</li>
 *   <li>{@code --headless}: a dedicated server, simulation only, no window or GPU</li>
 *   <li>{@code --vulkan}: force the Vulkan backend (MoltenVK on macOS)</li>
 * </ul>
 * Packaged editors and exported games ({@code -Dretro.packaged=true}) have no visible console, so stdout, stderr and
 * uncaught exceptions are also written to a log file in the OS's standard log folder:
 * {@code ~/Library/Logs/RetroEngine} (macOS), {@code %LOCALAPPDATA%\RetroEngine\logs} (Windows) or
 * {@code $XDG_STATE_HOME/retroengine} (Linux). The previous session's log is kept as {@code <name>.log.1}.
 */
public class Main {

    /** Log file base name; exported games pass their executable name so several games do not share one log. */
    public static final String LOG_NAME_PROPERTY = "retro.logName";

    public static void main(String[] args) {
        // LWJGL's default 64 KB thread stack overflows inside new VkInstance(...) on Windows NVIDIA drivers: it enumerates
        // every extension (~260 bytes each) on the MemoryStack. Must be set before any thread touches MemoryStack.
        Configuration.STACK_SIZE.set(1024);

        boolean isRuntime = false;
        boolean isHeadless = false;
        if (args != null) {
            for (String arg : args) {
                if ("--runtime".equalsIgnoreCase(arg) || "--game".equalsIgnoreCase(arg)) {
                    isRuntime = true;
                } else if ("--headless".equalsIgnoreCase(arg)) {
                    isHeadless = true;
                    isRuntime = true;
                }
            }
        }

        if (Boolean.getBoolean("retro.packaged")) {
            redirectOutputToLogFile(System.getProperty(LOG_NAME_PROPERTY, isRuntime ? "game" : "editor"));
        }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[FATAL] Uncaught exception on thread " + t.getName());
            e.printStackTrace();
        });

        System.out.println("[Main] RetroEngine on Java " + Runtime.version() + " (" + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ")");

        EngineContext context = new EngineContext();
        try {
            if (isHeadless) {
                System.out.println("[Main] Mode: dedicated headless server (--headless)");
                if (context.initializeHeadless(args)) {
                    new RuntimeLoop(true).run(context, context.getEditorManager().getSceneManager());
                }
            } else if (isRuntime) {
                System.out.println("[Main] Mode: standalone game (--runtime)");
                if (context.initialize(1280, 720, "RetroEngine Standalone Runtime", true, args)) {
                    new RuntimeLoop(false).run(context, context.getEditorManager().getSceneManager());
                }
            } else {
                System.out.println("[Main] Mode: editor");
                if (context.initialize(1280, 720, "RetroEngine Editor & Runtime", false, args)) {
                    new EngineLoop().run(context);
                }
            }
        } catch (Throwable fatal) {
            // Logged here so the stack trace reaches the log file before shutdown runs
            System.err.println("[FATAL] Engine loop terminated by an uncaught exception.");
            fatal.printStackTrace();
            throw fatal;
        } finally {
            context.shutdown();
        }
    }

    private static void redirectOutputToLogFile(String logName) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home");
        java.nio.file.Path dir;
        if (os.contains("mac")) {
            dir = java.nio.file.Path.of(home, "Library", "Logs", "RetroEngine");
        } else if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            dir = java.nio.file.Path.of(local != null && !local.isBlank() ? local : home, "RetroEngine", "logs");
        } else {
            String state = System.getenv("XDG_STATE_HOME");
            dir = state != null && !state.isBlank() ? java.nio.file.Path.of(state, "retroengine") : java.nio.file.Path.of(home, ".local", "state", "retroengine");
        }
        String safeName = logName.replaceAll("[^A-Za-z0-9 _.-]", "_");
        java.nio.file.Path log = dir.resolve(safeName + ".log");
        try {
            java.nio.file.Files.createDirectories(dir);
            // Keep the previous session (usually the one that crashed) instead of truncating it on relaunch
            if (java.nio.file.Files.exists(log)) {
                java.nio.file.Files.move(log, dir.resolve(safeName + ".log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.io.OutputStream file = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(log));
            System.setOut(new java.io.PrintStream(new Tee(System.out, file), true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new Tee(System.err, file), true, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("[Main] Logging to " + log);
        } catch (java.io.IOException e) {
            System.err.println("[Main] Could not open log file " + log + ": " + e.getMessage());
        }
    }

    /**
     * Writes to the log file first, then the console. A console that is gone (javaw, Finder launches) must not stop
     * the file write, so console failures are swallowed.
     */
    private static final class Tee extends java.io.OutputStream {
        private final java.io.OutputStream console, file;
        Tee(java.io.OutputStream console, java.io.OutputStream file) { this.console = console; this.file = file; }
        // Both Tees share the file stream, so lock on it rather than on each Tee
        @Override public void write(int c) throws java.io.IOException {
            synchronized (file) {
                file.write(c);
                try { console.write(c); } catch (java.io.IOException ignored) {}
            }
        }
        @Override public void write(byte[] buf, int off, int len) throws java.io.IOException {
            synchronized (file) {
                file.write(buf, off, len);
                try { console.write(buf, off, len); } catch (java.io.IOException ignored) {}
            }
        }
        @Override public void flush() throws java.io.IOException {
            synchronized (file) {
                file.flush();
                try { console.flush(); } catch (java.io.IOException ignored) {}
            }
        }
    }
}
