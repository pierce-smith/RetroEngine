package com.retro.engine;

import com.retro.engine.content.mesh.ModelData;
import com.retro.engine.content.mesh.ModelLoader;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.host.GlfwWindow;
import com.retro.engine.host.HostBackendLoader;
import com.retro.engine.rhi.IRenderBackend;

public class Main {
    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Visual Editor IDE   ");
        System.out.println("===========================================");

        // 1. Create GLFW Window
        GlfwWindow window = new GlfwWindow(1280, 720, "Retro Engine Editor & Runtime");
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            System.exit(1);
        }

        // 2. Initialize 4-Panel Editor State Manager
        EditorPanelManager editorManager = new EditorPanelManager();

        // 3. Load Platform Graphics Backend (Metal on macOS, Vulkan on Windows/Linux)
        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        // Wire native Finder drag-and-drop listener
        window.setOnFileDropListener(paths -> {
            editorManager.onFilesDropped(paths);

            for (String path : paths) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".obj") || lower.endsWith(".gltf") || lower.endsWith(".glb") || lower.endsWith(".fbx")) {
                    ModelData model = ModelLoader.loadModel(path);
                    if (model != null && model.vertices != null && model.vertices.length > 0) {
                        rhi.updateMesh(model.vertices, model.vertices.length / 7);
                        System.out.println("[Main] Uploaded model '" + model.name + "' into GPU VRAM.");
                    }
                }
            }
        });

        // 4. Initialize Render Hardware Interface
        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. Drag .obj/.gltf/.fbx files into window | F11/Alt+Enter: Fullscreen | ESC: Quit");

            long lastTime = System.nanoTime();

            while (!window.shouldClose()) {
                window.pollEvents();

                long currentTime = System.nanoTime();
                float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                // Update Editor & Scene State
                editorManager.update(deltaTime);

                // Update 2D Editor UI Overlay Mesh
                float[] uiVerts = editorManager.generateUiVertices();
                if (uiVerts != null && uiVerts.length > 0) {
                    rhi.updateUiMesh(uiVerts, uiVerts.length / 6);
                }

                // Render Frame
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