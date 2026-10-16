package com.retro.engine;

import com.retro.engine.core.EngineContext;
import com.retro.engine.core.EngineLoop;

/**
 * Main Engine Entry Point.
 * Ultra-lean entry point delegating bootstrapping to EngineContext and high-frequency execution to EngineLoop.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Visual Editor IDE   ");
        System.out.println("  JDK 21+ Virtual Thread Asset Streaming   ");
        System.out.println("===========================================");

        EngineContext context = new EngineContext();
        if (context.initialize(1280, 720, "Retro Engine Editor & Runtime")) {
            EngineLoop loop = new EngineLoop();
            loop.run(context);
        }
        context.shutdown();
    }
}
