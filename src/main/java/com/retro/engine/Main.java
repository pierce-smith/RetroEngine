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
        System.out.println("  Retro Engine Shell - Editor & Runtime    ");
        System.out.println("===========================================");

        GlfwWindow window = new GlfwWindow(1280, 720, "Retro Engine Editor & Runtime");
        if (!window.create()) {
            System.err.println("[Fatal] Could not create GLFW window. Halting.");
            System.exit(1);
        }

        EditorPanelManager editorManager = new EditorPanelManager();

        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. Drag .obj/.gltf files into window | ESC: Quit");

            // Wire native Finder drag-and-drop listener to editor manager & model loader
            window.setOnFileDropListener(filePaths -> {
                editorManager.onFilesDropped(filePaths);
                for (String path : filePaths) {
                    if (path.toLowerCase().endsWith(".obj") ||
                        path.toLowerCase().endsWith(".gltf") ||
                        path.toLowerCase().endsWith(".glb") ||
                        path.toLowerCase().endsWith(".fbx")) {
                        ModelData model = ModelLoader.loadModel(path);
                        if (model != null && model.vertices != null && model.vertices.length > 0) {
                            rhi.updateMesh(model.vertices, model.vertices.length / 7);
                            System.out.println("[Engine] Successfully uploaded 3D mesh to Metal: " + path);
                        }
                    }
                }
            });

            long lastTime = System.nanoTime();

            while (!window.shouldClose()) {
                window.pollEvents();

                long currentTime = System.nanoTime();
                float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                // Update Editor & Scene State
                editorManager.update(deltaTime);

                // Upload 2D UI Overlay Panel geometry to Metal RHI
                rhi.updateUiMesh(editorManager.getUiVertices(), editorManager.getUiVertexCount());

                // Render Dual-Pass Frame (Pass 1: 3D Model | Pass 2: 2D Editor UI Overlay)
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
