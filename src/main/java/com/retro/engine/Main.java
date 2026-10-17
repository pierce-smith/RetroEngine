package com.retro.engine;

import com.retro.engine.core.EngineContext;
import com.retro.engine.core.EngineLoop;
import com.retro.engine.core.RuntimeLoop;

/**
 * Main Engine Entry Point.
 * Ultra-lean entry point routing execution based on CLI arguments:
 * - Default: Boots Visual Editor IDE via EngineLoop.
 * - --runtime / --game: Boots Standalone Game Runtime via RuntimeLoop.
 * - --headless: Boots Headless Dedicated Game Server via RuntimeLoop without GPU window context.
 */
public class Main {

    public static void main(String[] args) {
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
            if (context.initializeHeadless()) {
                RuntimeLoop runtimeLoop = new RuntimeLoop(true);
                runtimeLoop.run(context, context.getEditorManager().getSceneManager());
            }
        } else if (isRuntime) {
            System.out.println("[Main] Target Execution Mode: Standalone Game Executable (--runtime)");
            if (context.initialize(1280, 720, "Retro Engine Standalone Runtime", true)) {
                RuntimeLoop runtimeLoop = new RuntimeLoop(false);
                runtimeLoop.run(context, context.getEditorManager().getSceneManager());
            }
        } else {
            System.out.println("[Main] Target Execution Mode: Visual Editor IDE");
            if (context.initialize(1280, 720, "Retro Engine Editor & Runtime", false)) {
                EngineLoop loop = new EngineLoop();
                loop.run(context);
            }
        }

        context.shutdown();
    }
}
