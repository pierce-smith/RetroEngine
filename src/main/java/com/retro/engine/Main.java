package com.retro.engine;

import com.retro.engine.core.EngineContext;
import com.retro.engine.core.EngineLoop;
import com.retro.engine.core.RuntimeLoop;
import org.lwjgl.system.Configuration;

/**
 * Main Engine Entry Point.
 * Ultra-lean entry point routing execution based on CLI arguments:
 * - Default: Boots Visual Editor IDE via EngineLoop.
 * - --runtime / --game: Boots Standalone Game Runtime via RuntimeLoop.
 * - --headless: Boots Headless Dedicated Game Server via RuntimeLoop without GPU window context.
 * - --vulkan: Forces Vulkan backend selection across platforms (MoltenVK on macOS).
 */
public class Main {

    public static void main(String[] args) {
        // LWJGL's default 64 KB thread stack overflows inside new VkInstance(...) on Windows NVIDIA drivers: it enumerates
        // every extension (~260 bytes each) on the MemoryStack. Must be set before any thread touches MemoryStack.
        Configuration.STACK_SIZE.set(1024);
        if (Boolean.getBoolean("retro.packaged")) redirectOutputToLogFile();

        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Visual Editor IDE   ");
        System.out.println("  JDK 21+ Virtual Thread Asset Streaming   ");
        System.out.println("===========================================");

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

        EngineContext context = new EngineContext();

        if (isHeadless) {
            System.out.println("[Main] Target Execution Mode: Dedicated Headless Server (--headless)");
            if (context.initializeHeadless(args)) {
                RuntimeLoop runtimeLoop = new RuntimeLoop(true);
                runtimeLoop.run(context, context.getEditorManager().getSceneManager());
            }
        } else if (isRuntime) {
            System.out.println("[Main] Target Execution Mode: Standalone Game Executable (--runtime)");
            if (context.initialize(1280, 720, "Retro Engine Standalone Runtime", true, args)) {
                RuntimeLoop runtimeLoop = new RuntimeLoop(false);
                runtimeLoop.run(context, context.getEditorManager().getSceneManager());
            }
        } else {
            System.out.println("[Main] Target Execution Mode: Visual Editor IDE");
            if (context.initialize(1280, 720, "Retro Engine Editor & Runtime", false, args)) {
                EngineLoop loop = new EngineLoop();
                loop.run(context);
            }
        }

        context.shutdown();
    }

    /**
     * Packaged apps have no console on macOS (Finder) or Windows (no --win-console), so a startup failure would vanish.
     * Tees stdout/stderr and uncaught exceptions into a per-user log file.
     */
    private static void redirectOutputToLogFile() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String home = System.getProperty("user.home");
        java.nio.file.Path dir;
        if (os.contains("mac")) {
            dir = java.nio.file.Path.of(home, "Library", "Logs", "RetroEngine");
        } else if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            dir = java.nio.file.Path.of(local != null ? local : home, "RetroEngine", "logs");
        } else {
            String state = System.getenv("XDG_STATE_HOME");
            dir = state != null ? java.nio.file.Path.of(state, "retroengine") : java.nio.file.Path.of(home, ".local", "state", "retroengine");
        }
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path log = dir.resolve("editor.log");
            java.io.OutputStream file = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(log));
            System.setOut(new java.io.PrintStream(new Tee(System.out, file), true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new Tee(System.err, file), true, java.nio.charset.StandardCharsets.UTF_8));
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("[FATAL] Uncaught exception on thread " + t.getName());
                e.printStackTrace();
            });
            System.out.println("[Main] Logging to " + log);
        } catch (java.io.IOException e) {
            System.err.println("[Main] Could not open log file in " + dir + ": " + e.getMessage());
        }
    }

    private static final class Tee extends java.io.OutputStream {
        private final java.io.OutputStream a, b;
        Tee(java.io.OutputStream a, java.io.OutputStream b) { this.a = a; this.b = b; }
        // Both Tees share the file stream, so lock on it rather than on each Tee
        @Override public void write(int c) throws java.io.IOException { synchronized (b) { a.write(c); b.write(c); } }
        @Override public void write(byte[] buf, int off, int len) throws java.io.IOException { synchronized (b) { a.write(buf, off, len); b.write(buf, off, len); } }
        @Override public void flush() throws java.io.IOException { synchronized (b) { a.flush(); b.flush(); } }
    }
}
