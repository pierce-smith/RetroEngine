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

public class Main {

    private static String lastLoadedMeshPath = null;
    private static String lastLoadedTexturePath = null;

    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("  Retro Engine Shell - Visual Editor IDE   ");
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

        window.setOnFileDropListener(filePaths -> {
            editorManager.onFilesDropped(filePaths);
            System.out.println("[Asset Directory] Ingested " + filePaths.size() + " asset(s). Click assets to spawn or link.");
        });

        window.setOnMouseClickListener((mouseX, mouseY, button, action) -> {
            selectionController.handleMouseClick(mouseX, mouseY, button, action, editorManager);
            cameraController.onMouseButton(button, action, mouseX, mouseY);
        });

        window.setOnCursorPosListener((x, y) -> {
            boolean handledByGizmo = selectionController.onCursorPos(x, y, editorManager);
            if (!handledByGizmo) {
                boolean isShiftDown = window.isKeyDown(GLFW_KEY_LEFT_SHIFT) || window.isKeyDown(GLFW_KEY_RIGHT_SHIFT);
                boolean isAltDown = window.isKeyDown(GLFW_KEY_LEFT_ALT) || window.isKeyDown(GLFW_KEY_RIGHT_ALT);
                cameraController.onCursorPos(x, y, isShiftDown, isAltDown);
            }
        });

        window.setOnScrollListener((xoffset, yoffset) -> cameraController.onScroll(yoffset));

        if (rhi.initialize(window.getWindowInfo())) {
            System.out.println("[Engine] Running. Drag files to Ingest -> Click Asset Cards to Spawn or Link Texture.");
            System.out.println("[Gizmo] Press 1(Move) / 2(Rotate) / 3(Scale) or W/E/R to switch modes in Viewport.");

            long lastTime = System.nanoTime();

            while (!window.shouldClose()) {
                window.pollEvents();

                long currentTime = System.nanoTime();
                float deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                boolean isCtrlDown = window.isKeyDown(GLFW_KEY_LEFT_CONTROL) || window.isKeyDown(GLFW_KEY_RIGHT_CONTROL);
                boolean is1 = window.isKeyDown(GLFW_KEY_1);
                boolean is2 = window.isKeyDown(GLFW_KEY_2);
                boolean is3 = window.isKeyDown(GLFW_KEY_3);
                boolean isW = window.isKeyDown(GLFW_KEY_W);
                boolean isS = window.isKeyDown(GLFW_KEY_S);
                boolean isA = window.isKeyDown(GLFW_KEY_A);
                boolean isD = window.isKeyDown(GLFW_KEY_D);
                boolean isQ = window.isKeyDown(GLFW_KEY_Q);
                boolean isE = window.isKeyDown(GLFW_KEY_E);
                boolean isR = window.isKeyDown(GLFW_KEY_R);
                if (window.isKeyDown(GLFW_KEY_F)) cameraController.reset();

                // Quick Gizmo Mode Shortcuts: 1/W -> Move, 2/E -> Rotate, 3/R -> Scale
                if (!isCtrlDown && !cameraController.isDraggingRmb() && !cameraController.isDraggingMmb()) {
                    if (is1 || isW) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.TRANSLATE);
                    if (is2 || isE) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.ROTATE);
                    if (is3 || isR) editorManager.setCurrentGizmoMode(EditorPanelManager.GizmoMode.SCALE);
                }

                cameraController.update(deltaTime, isW, isS, isA, isD, isQ, isE);

                // Pass Camera Transform State to RHI & Editor Manager for 3D Projection
                rhi.setCameraTransform(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );
                editorManager.setCameraState(
                        cameraController.getPanX(),
                        cameraController.getPanY(),
                        cameraController.getZoom(),
                        cameraController.getPitch(),
                        cameraController.getYaw()
                );

                editorManager.update(deltaTime);

                // Stream 3D Ground Grid lines to Metal VRAM
                rhi.updateGridMesh(editorManager.get3DGridVertices(), editorManager.get3DGridVertexCount());

                // Sync 3D Mesh & Model Matrix in Viewport if selected entity mesh changed
                EditorPanelManager.SceneEntity selected = editorManager.getSelectedEntity();
                if (selected != null) {
                    rhi.setModelTransform(
                            selected.transform.posX,
                            selected.transform.posY,
                            selected.transform.posZ,
                            selected.transform.rotX,
                            selected.transform.rotY,
                            selected.transform.rotZ,
                            selected.transform.scaleX,
                            selected.transform.scaleY,
                            selected.transform.scaleZ
                    );

                    if (selected.meshAssetPath != null && !selected.meshAssetPath.equals(lastLoadedMeshPath)) {
                        ModelData model = ModelLoader.loadModel(selected.meshAssetPath);
                        if (model != null && model.vertices != null && model.vertices.length > 0) {
                            rhi.updateMesh(model.vertices, model.vertexCount);
                            selected.vertexCount = model.vertexCount;
                            lastLoadedMeshPath = selected.meshAssetPath;
                            System.out.println("[Engine] Loaded 3D Mesh in Viewport: " + selected.name);
                        }
                    }

                    if (selected.textureAssetPath != null && !selected.textureAssetPath.equals(lastLoadedTexturePath)) {
                        TextureData tex = TextureLoader.loadTexture(selected.textureAssetPath);
                        if (tex != null && tex.pixels != null) {
                            rhi.updateTexture(tex.pixels, tex.width, tex.height);
                            lastLoadedTexturePath = selected.textureAssetPath;
                            System.out.println("[Engine] Linked Texture in Metal VRAM: " + tex.name);
                        }
                    }
                }

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
