package com.retroengine;

public class Main {
    static {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("org.lwjgl.vulkan.libname", "libMoltenVK.dylib");
            System.setProperty("org.lwjgl.util.NoChecks", "true");

            // FIX: Changes the title of the app process sitting directly in the macOS Dock
            System.setProperty("apple.awt.application.name", "Eating Cable");
        }
    }

    public static void main(String[] args) {
        try {
            EngineShell engine = new EngineShell();
            engine.run();
        } catch (Exception e) {
            System.err.println("CRITICAL FAULT: Engine loop unmounted due to structural error.");
            e.printStackTrace();
            System.exit(-1);
        }
    }
}
