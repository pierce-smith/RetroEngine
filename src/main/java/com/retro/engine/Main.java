package com.retro.engine;

import com.retro.engine.content.mesh.ModelData;
import com.retro.engine.content.mesh.ModelLoader;
import com.retro.engine.content.texture.TextureData;
import com.retro.engine.content.texture.TextureLoader;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorSelectionController;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.host.GlfwWindow;
import com.retro.engine.host.HostBackendLoader;
import com.retro.engine.rhi.IRenderBackend;

import static org.lwjgl.glfw.GLFW.*;

import java.util.List;

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

        // 2. Initialize Controllers
        EditorPanelManager editorManager = new EditorPanelManager();
        EditorSelectionController selectionController = new EditorSelectionController();
        ViewportCameraController cameraController = new ViewportCameraController();

        // 3. Load Platform Graphics Backend (Metal on macOS)
        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        // 4. Wire Input Listeners
        window.setOnMouseButtonListener((button, action, x, y) -> {
            selectionController.handleMouseClick(x, y, button, editorManager);
            cameraController.onMouseButton(button, action, x, y);
        });

        window.setOnCursorPosListener((x, y, isShift, isAlt) -> {
            cameraController.onCursorPos(x, y, isShift, isAlt);
        });

        window.setOnScrollListener((xoff, yoff) -> {
            cameraController.onScroll(yoff);
        });

        // Wire native Finder drag-and-drop listener
        window.setOnFileDropListener((List<String> paths) -> {
            editorManager.onFilesDropped(paths);
            for (String path : paths) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".obj") || lower.endsWith(".gltf") || lower.endsWith(".glb") || lower.endsWith(".fbx") || lower.endsWith(".dae")) {
                    ModelData model = ModelLoader.loadModel(path);
                    if (model != null && model.vertices != null) {
                        int vertCount = model.vertices.length / 7;
                        rhi.updateMesh(model.vertices, vertCount);

                        // Update live vertex count in Inspector
                        EditorPanelManager.SceneEntity selected = editorManager.getSelectedEntity();
                        if (selected != null) {
                            selected.vertexCount = vertCount;
                        }
                    }
                } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
                    TextureData tex = TextureLoader.loadTexture(path);
                    if (tex != null) {
                        System.out.println("[Engine] Registered texture asset: " + tex.width + "x" + tex.height);
                    }
                }
            }
        });

        // 5. Initialize RHI Loop
        if (rhi.initialize(window.getWindowInfo())) {

            System.out.println("[Engine] Running. WASD/RMB/Scroll: Nav Camera | F: Focus | Drag & Drop .fbx/.gltf/.obj from Finder.");

            long lastTime = System.nanoTime();

            while (!window.shouldClose()) {
                window.pollEvents();

                long currentTime = System.nanoTime();
                float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                // Handle Camera Key Inputs
                boolean isW = window.isKeyDown(GLFW_KEY_W) || window.isKeyDown(GLFW_KEY_UP);
                boolean isS = window.isKeyDown(GLFW_KEY_S) || window.isKeyDown(GLFW_KEY_DOWN);
                boolean isA = window.isKeyDown(GLFW_KEY_A) || window.isKeyDown(GLFW_KEY_LEFT);
                boolean isD = window.isKeyDown(GLFW_KEY_D) || window.isKeyDown(GLFW_KEY_RIGHT);
                boolean isQ = window.isKeyDown(GLFW_KEY_Q);
                boolean isE = window.isKeyDown(GLFW_KEY_E);

                if (window.isKeyDown(GLFW_KEY_F)) {
                    cameraController.reset();
                }

                cameraController.update(deltaTime, isW, isS, isA, isD, isQ, isE);

                // Send camera transform to Metal backend
                rhi.setCameraTransform(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );

                // Update Editor State
                editorManager.update(deltaTime);

                // Stream 2D UI Overlay Mesh
                float[] uiVerts = editorManager.getUiVertices();
                int uiCount = editorManager.getUiVertexCount();
                if (uiVerts != null && uiCount > 0) {
                    rhi.updateUiMesh(uiVerts, uiCount);
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
