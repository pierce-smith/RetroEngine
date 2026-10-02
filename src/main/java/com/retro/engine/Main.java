package com.retro.engine;

import com.retro.engine.host.GlfwWindow;
import com.retro.engine.host.HostBackendLoader;
import com.retro.engine.rhi.IRenderBackend;

public class Main {
    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Window Boot Test   ");
        System.out.println("===========================================");

        GlfwWindow window = new GlfwWindow(1280, 720, "Retro Engine Shell");
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            System.exit(1);
        }

        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. F11/Alt+Enter: Fullscreen | ESC: Quit");

            while (!window.shouldClose()) {
                window.pollEvents();

                rhi.beginFrame();
                rhi.endFrame();
                rhi.present();
            }

            rhi.shutdown();
        }

        window.destroy();
        System.out.println("[Engine] Shutdown complete.");
    }
}
