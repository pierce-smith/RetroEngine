package com.retro.engine;

import com.retro.engine.core.EngineContext;
import com.retro.engine.core.EngineLoop;
import com.retro.engine.core.FrameLimiter;
import com.retro.engine.core.Log;
import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.RuntimeLoop;
import com.retro.engine.editor.StandaloneExporter;
import com.retro.engine.ui.FrameStatsOverlay;

import org.lwjgl.system.Configuration;

/**
 * Entry point. Arguments pick the mode:
 * <ul>
 *   <li>none: the editor (EngineLoop)</li>
 *   <li>{@code --runtime} / {@code --game}: a standalone game (RuntimeLoop), as launched by exported builds</li>
 *   <li>{@code --headless}: simulation only, no window or GPU. Loads {@code --project <dir>} like an exported game.
 *       With {@code --frames N} it runs N frames, stops the scene and exits with one of the EXIT_ codes below</li>
 *   <li>{@code --vulkan}: force the Vulkan backend (MoltenVK on macOS)</li>
 *   <li>{@code --gpu-debug}: enable Vulkan validation and log what it finds (see GpuDebug)</li>
 *   <li>{@code --scene <path>}: games and headless runs start on this project-relative scene</li>
 *   <li>{@code --frame-stats}: show the frame timing overlay (F3 toggles it)</li>
 *   <li>{@code --bench <seconds>}: game mode, measure frame times and exit, after {@code --bench-warmup <seconds>}
 *       (default 60). Volume 5 has the protocol</li>
 * </ul>
 * Packaged editors and exported games ({@code -Dretro.packaged=true}) have no visible console, so stdout, stderr and
 * uncaught exceptions are also written to a log file in the OS's standard log folder:
 * {@code ~/Library/Logs/RetroEngine} (macOS), {@code %LOCALAPPDATA%\RetroEngine\logs} (Windows) or
 * {@code $XDG_STATE_HOME/retroengine} (Linux). The previous session's log is kept as {@code <name>.log.1}.
 */
public class Main {

    /** Log file base name; exported games pass their executable name so several games do not share one log. */
    public static final String LOG_NAME_PROPERTY = "retro.logName";

    /** The run finished and nothing failed. An uncaught exception exits with 1, like any Java program. */
    public static final int EXIT_OK = 0;
    /** Headless: bad arguments, or the project or its startup scene could not be loaded. */
    public static final int EXIT_STARTUP_FAILED = 2;
    /** Headless --frames: at least one script failed to compile or threw. */
    public static final int EXIT_SCRIPT_FAILED = 3;

    private static final float DEFAULT_BENCH_WARMUP_SECONDS = 60.0f;

    public static void main(String[] args) {
        // LWJGL's default 64 KB thread stack overflows inside new VkInstance(...) on Windows NVIDIA drivers: it enumerates
        // every extension (~260 bytes each) on the MemoryStack. Must be set before any thread touches MemoryStack.
        Configuration.STACK_SIZE.set(1024);
        boolean frameStatsFlag = hasFlag(args, "--frame-stats");

        // Only exported games set this. Their native launchers pass --runtime only when started with no arguments of
        // their own, so a game launched with e.g. --no-mods would otherwise open the editor
        boolean isRuntime = System.getProperty(StandaloneExporter.GAME_DATA_PROPERTY) != null;
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

        com.retro.engine.graphics.GpuDebug.configure(args);
        FrameStatsOverlay.setVisible(frameStatsFlag);
        FrameStatsOverlay.setHotkeyEnabled(frameStatsFlag || !isRuntime);

        Log.info("Main", "RetroEngine on Java " + Runtime.version() + " (" + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ")");

        int exitCode = EXIT_OK;
        EngineContext context = new EngineContext();
        try {
            if (isHeadless) {
                Log.info("Main", "Mode: headless (--headless)");
                System.setProperty("java.awt.headless", "true");
                exitCode = runHeadless(context, args);
            } else if (isRuntime) {
                Log.info("Main", "Mode: standalone game (--runtime)");
                if (context.initialize(1280, 720, "RetroEngine Standalone Runtime", true, args)) {
                    exitCode = runGame(context, args);
                }
            } else {
                Log.info("Main", "Mode: editor");
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
        if (exitCode != EXIT_OK) System.exit(exitCode);
    }

    private static int runGame(EngineContext context, String[] args) {
        com.retro.engine.ecs.SceneWorld world = context.getEditorManager().getSceneManager();
        RuntimeLoop loop = new RuntimeLoop(false);
        String bench = EngineContext.findCliValue(args, "--bench");
        if (bench != null) {
            String warmup = EngineContext.findCliValue(args, "--bench-warmup");
            try {
                loop.setBenchmark(warmup != null ? Float.parseFloat(warmup) : DEFAULT_BENCH_WARMUP_SECONDS, Float.parseFloat(bench));
            } catch (NumberFormatException e) {
                Log.error("Main", "--bench and --bench-warmup take a number of seconds.");
                return EXIT_STARTUP_FAILED;
            }
            // An empty scene would report excellent numbers for nothing
            if (world.getSceneEntities().isEmpty()) {
                Log.error("Main", "The scene is empty or failed to load, so there is nothing to measure.");
                return EXIT_STARTUP_FAILED;
            }
        }
        if (!configureFramePacing(context, loop, args)) return EXIT_STARTUP_FAILED;
        loop.run(context, world);
        return EXIT_OK;
    }

    /**
     * Applies the project's vsync and frame cap, which {@code --vsync on|off} and {@code --fps-cap N} override, so a
     * tester can check both present paths on a machine without editing the project.
     */
    private static boolean configureFramePacing(EngineContext context, RuntimeLoop loop, String[] args) {
        ProjectSettings settings = context.getEditorManager().getProjectSettings();
        boolean vsync = settings == null || settings.vsync;
        int cap = settings != null ? settings.frameRateCap : 0;

        String vsyncArg = EngineContext.findCliValue(args, "--vsync");
        if (vsyncArg != null) {
            if ("on".equalsIgnoreCase(vsyncArg)) vsync = true;
            else if ("off".equalsIgnoreCase(vsyncArg)) vsync = false;
            else {
                Log.error("Main", "--vsync takes on or off, got: " + vsyncArg);
                return false;
            }
        }
        String capArg = EngineContext.findCliValue(args, "--fps-cap");
        if (capArg != null) {
            try {
                cap = ProjectSettings.sanitizeFrameRateCap(Integer.parseInt(capArg.trim()));
            } catch (NumberFormatException e) {
                Log.error("Main", "--fps-cap takes a number of frames per second (0 for no cap), got: " + capArg);
                return false;
            }
        }

        if (context.getRhi() != null) context.getRhi().setVsync(vsync);
        int refreshHz = context.getDisplay() != null && context.getDisplay().isOpen()
                ? context.getDisplay().getMetrics().refreshRate() : 0;
        FrameLimiter limiter = FrameLimiter.create(vsync, cap, refreshHz);
        loop.setFrameLimiter(limiter);
        Log.info("Main", "Frame pacing: vsync " + (vsync ? "on" : "off") + ", "
                + (cap > 0 ? "cap " + cap + " FPS" : vsync ? "backstop above " + (refreshHz > 0 ? refreshHz : 60) + " Hz" : "no cap"));
        return true;
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) return false;
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg)) return true;
        }
        return false;
    }

    private static int runHeadless(EngineContext context, String[] args) {
        if (!context.initializeHeadless(args)) return EXIT_STARTUP_FAILED;
        com.retro.engine.ecs.SceneWorld world = context.getEditorManager().getSceneManager();
        long frames = context.getHeadlessFrames();
        // A failed scene load looks like an empty scene, and running nothing would report success
        if (frames > 0L && world.getSceneEntities().isEmpty()) {
            Log.error("Main", "The startup scene is empty or failed to load, so there is nothing to run.");
            return EXIT_STARTUP_FAILED;
        }

        RuntimeLoop loop = new RuntimeLoop(true);
        loop.setMaxFrames(frames);
        loop.run(context, world);
        if (frames == 0L) return EXIT_OK;

        int failures = com.retro.engine.scripting.ScriptEngine.getFailureCount();
        if (failures > 0) {
            Log.error("Main", "Headless run failed: " + failures + " script error(s).");
            return EXIT_SCRIPT_FAILED;
        }
        Log.info("Main", "Headless run passed: " + frames + " frames, no script errors.");
        return EXIT_OK;
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
            Log.info("Main", "Logging to " + log);
        } catch (java.io.IOException e) {
            Log.error("Main", "Could not open log file " + log + ": " + e.getMessage());
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
