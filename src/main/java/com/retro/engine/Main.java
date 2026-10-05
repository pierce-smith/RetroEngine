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

public class Main {

    private static String lastLoadedTexturePath = null;

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
        EditorSelectionController selectionController = new EditorSelectionController(editorManager);
        ViewportCameraController cameraController = new ViewportCameraController();

        HostBackendLoader loader = new HostBackendLoader();
        if (!loader.loadTargetBackend()) {
            System.err.println("[Fatal] Target backend failed to load. Halting execution.");
            window.destroy();
            System.exit(1);
        }

        IRenderBackend rhi = loader.getBackend();

        // Native drag-and-drop listener
        window.setOnFileDropListener(filePaths -> {
            editorManager.onFilesDropped(filePaths);
            for (String path : filePaths) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".obj") || lower.endsWith(".gltf") || lower.endsWith(".glb") || lower.endsWith(".fbx")) {
                    ModelData model = ModelLoader.loadModel(path);
                    if (model != null && model.vertices != null && model.vertices.length > 0) {
                        rhi.updateMesh(model.vertices, model.vertexCount);
                        System.out.println("[Engine] Uploaded 3D mesh (" + model.vertexCount + " vertices) to Metal VRAM: " + path);
                    }
                } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
                    TextureData tex = TextureLoader.loadTexture(path);
                    if (tex != null && tex.pixels != null) {
                        rhi.updateTexture(tex.pixels, tex.width, tex.height);
                        lastLoadedTexturePath = path;
                        System.out.println("[Engine] Uploaded texture (" + tex.width + "x" + tex.height + ") to Metal VRAM: " + path);
                    }
                }
            }
        });

        // Mouse click listener
        window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
            cameraController.onMouseButton(button, action, mouseX, mouseY);
            if (action == 1) { // GLFW_PRESS = 1
                selectionController.handleMouseClick(mouseX, mouseY, button, action, editorManager);
            }
        });

        // Cursor pos listener for 3D Viewport orbit and panning
        window.setOnCursorPosListener((xpos, ypos, isShiftDown, isAltDown) -> {
            cameraController.onCursorPos(xpos, ypos, isShiftDown, isAltDown);
        });

        // Scroll listener for 3D Viewport zooming
        window.setOnScrollListener((xoffset, yoffset) -> {
            cameraController.onScroll(xoffset, yoffset);
        });

        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. Drag .fbx / .obj / .jpg into window | ESC: Quit");

            long lastTime = System.nanoTime();

            while (!window.shouldClose()) {
                window.pollEvents();

                long currentTime = System.nanoTime();
                float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                // Update Camera Controls
                boolean w = window.isKeyDown(87) || window.isKeyDown(265); // W / Up
                boolean s = window.isKeyDown(83) || window.isKeyDown(264); // S / Down
                boolean a = window.isKeyDown(65) || window.isKeyDown(263); // A / Left
                boolean d = window.isKeyDown(68) || window.isKeyDown(262); // D / Right
                boolean q = window.isKeyDown(81); // Q
                boolean e = window.isKeyDown(69); // E
                cameraController.update(deltaTime, w, s, a, d, q, e);

                rhi.setCameraTransform(
                    cameraController.getPanX(),
                    cameraController.getPanY(),
                    cameraController.getZoom(),
                    cameraController.getPitch(),
                    cameraController.getYaw()
                );

                editorManager.update(deltaTime);

                // Check if Inspector texture assignment changed
                EditorPanelManager.SceneEntity selected = editorManager.getSelectedEntity();
                if (selected != null && selected.textureAssetPath != null && !selected.textureAssetPath.equals(lastLoadedTexturePath)) {
                    TextureData tex = TextureLoader.loadTexture(selected.textureAssetPath);
                    if (tex != null && tex.pixels != null) {
                        rhi.updateTexture(tex.pixels, tex.width, tex.height);
                        lastLoadedTexturePath = selected.textureAssetPath;
                        System.out.println("[Engine] Dynamic Texture Link Synced: " + tex.name);
                    }
                }

                // Stream 2D UI overlay geometry to RHI each frame
                rhi.updateUiMesh(editorManager.getUiVertices(), editorManager.getUiVertexCount());

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
