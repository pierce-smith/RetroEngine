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

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

public class Main {

    private static final Map<String, ModelData> meshCache = new HashMap<>();
    private static final Map<String, TextureData> textureCache = new HashMap<>();

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

        // Drag and Drop ONLY ingests files into Asset Directory
        window.setOnFileDropListener(filePaths -> {
            editorManager.onFilesDropped(filePaths);
            System.out.println("[Asset Directory] Ingested " + filePaths.size() + " asset(s). Click assets to spawn or link.");
        });

        // Mouse click listener: Forward press & release actions to both selection & camera controllers
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
            System.out.println("[Gizmo] Press 1/W(Translate) / 2/E(Rotate) / 3/R(Scale) to switch modes in Viewport.");

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

                // Clear & Submit All Active Scene Entities
                rhi.clearSceneQueue();

                for (EditorPanelManager.SceneEntity entity : editorManager.getSceneEntities()) {
                    if (entity.meshAssetPath != null) {
                        ModelData model = meshCache.computeIfAbsent(entity.meshAssetPath, ModelLoader::loadModel);

                        byte[] pixels = null;
                        int texW = 0, texH = 0;
                        if (entity.textureAssetPath != null) {
                            TextureData tex = textureCache.computeIfAbsent(entity.textureAssetPath, TextureLoader::loadTexture);
                            if (tex != null) {
                                pixels = tex.pixels;
                                texW = tex.width;
                                texH = tex.height;
                            }
                        }

                        if (model != null && model.vertices != null && model.vertices.length > 0) {
                            rhi.submitEntity(
                                    entity.meshAssetPath,
                                    model.vertices,
                                    model.vertexCount,
                                    entity.textureAssetPath,
                                    pixels, texW, texH,
                                    entity.transform.posX, entity.transform.posY, entity.transform.posZ,
                                    entity.transform.rotX, entity.transform.rotY, entity.transform.rotZ,
                                    entity.transform.scaleX, entity.transform.scaleY, entity.transform.scaleZ
                            );
                        }
                    }
                }

                rhi.updateGridMesh(editorManager.get3DGridVertices(), editorManager.get3DGridVertexCount());
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
