package com.retro.engine.editor;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.*;

import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.*;

/**
 * Aggregated EditorPanelManager & Unified Immediate Input Router.
 * Restores 100% baseline 3D world projection for gizmos, 3D grid origin axes,
 * Inspector [PICK] vs [IMPORT] native file dialogs, and Asset Directory imports.
 */
public class EditorPanelManager {
    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER }
    public enum InspectorField { NONE, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, SCL_X, SCL_Y, SCL_Z }

    private final SceneManager sceneManager;
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private final CommandHistory commandHistory = new CommandHistory();

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;
    private DropdownMenu activeDropdownMenu = DropdownMenu.NONE;
    private ModalState activeModal = ModalState.NONE;
    private AssetType pickerFilterType = AssetType.TEXTURE;
    private InspectorField activeInspectorField = InspectorField.NONE;

    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;
    private float[] cachedUiVertices = null;

    private ViewportCameraController cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private double lastRotateAngle = 0.0;

    private float camPanX = 0.0f, camPanY = 0.25f, camZoom = -5.0f, camPitch = 0.0f, camYaw = 0.0f;

    public EditorPanelManager() { this(new SceneManager()); }

    public EditorPanelManager(SceneManager sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneManager();
    }

    public void setWindowHandle(long windowHandle) {
        this.windowHandle = windowHandle;
    }

    public void setWindowDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.windowWidth = width;
            this.windowHeight = height;
        }
    }

    private void updateWindowDimensionsFromGlfw() {
        if (windowHandle != 0L) {
            int[] w = new int[1];
            int[] h = new int[1];
            GLFW.glfwGetWindowSize(windowHandle, w, h);
            if (w[0] > 0 && h[0] > 0) {
                this.windowWidth = w[0];
                this.windowHeight = h[0];
            }
        }
    }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }

    public void setCameraController(ViewportCameraController cameraController) { this.cameraController = cameraController; }
    public ViewportCameraController getCameraController() { return cameraController; }
    public SceneManager getSceneManager() { return sceneManager; }

    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }
    public void update(float deltaTime) { sceneManager.update(deltaTime); }

    public List<SceneEntity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public SceneEntity getSelectedEntity() { return sceneManager.getSelectedEntity(); }
    public void selectEntity(SceneEntity entity) { sceneManager.selectEntity(entity); }
    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }
    public void newScene() { sceneManager.newScene(); }
    public void promptOpenSceneWithNativeDialog() { sceneManager.promptOpenSceneWithNativeDialog(); }
    public void promptSaveSceneWithNativeDialog() { sceneManager.promptSaveSceneWithNativeDialog(); }
    public void saveActiveScene() { sceneManager.saveActiveScene(); }
    public void saveActiveScene(String filePath) { sceneManager.saveActiveScene(filePath); }
    public void loadSceneFromFile(String filePath) { sceneManager.loadSceneFromFile(filePath); }
    public void deleteSelectedEntity() { sceneManager.deleteSelectedEntity(); }
    public void spawnDefaultCube() { sceneManager.spawnDefaultCube(); }
    public void spawnLightSource() { sceneManager.spawnLightSource(); }
    public void spawnEntityFromAsset(AssetEntry meshAsset) { sceneManager.spawnEntityFromAsset(meshAsset); }
    public void assignTextureToSelectedEntity(AssetEntry textureAsset) { sceneManager.assignTextureToSelectedEntity(textureAsset); }
    public void assignMeshToSelectedEntity(AssetEntry meshAsset) { sceneManager.assignMeshToSelectedEntity(meshAsset); }

    public CommandHistory getCommandHistory() { return commandHistory; }
    public void undo() { commandHistory.undo(); }
    public void redo() { commandHistory.redo(); }

    public GizmoMode getCurrentGizmoMode() { return currentGizmoMode; }
    public void setCurrentGizmoMode(GizmoMode mode) { this.currentGizmoMode = mode; }
    public GizmoAxis getActiveGizmoAxis() { return activeGizmoAxis; }
    public void setActiveGizmoAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }
    public DropdownMenu getActiveDropdownMenu() { return activeDropdownMenu; }
    public void setActiveDropdownMenu(DropdownMenu menu) { this.activeDropdownMenu = menu; }
    public ModalState getActiveModal() { return activeModal; }
    public AssetType getPickerFilterType() { return pickerFilterType; }
    public void openAssetPicker(AssetType filterType) { this.pickerFilterType = filterType; this.activeModal = ModalState.ASSET_PICKER; }
    public void closeModal() { this.activeModal = ModalState.NONE; }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() { this.show3DGrid = !this.show3DGrid; cached3DGridVertices = null; }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    // --- AGGREGATED UNIFIED INPUT HANDLING ---
    public void handleMouseClick(double rawX, double rawY, int button, int action) {
        if (button != 0) return; // Left Click
        updateWindowDimensionsFromGlfw();

        // 1280x720 Reference Coordinates
        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;

        // Direct GPU NDC Coordinates [-1.0, 1.0]
        float ndcX = (float) ((rawX / (double) windowWidth) * 2.0 - 1.0);
        float ndcY = (float) (1.0 - (rawY / (double) windowHeight) * 2.0);

        if (action == 1) { // PRESS
            this.lastMouseX = refX;
            this.lastMouseY = refY;

            // 1. Modal Overlay Clicks
            if (activeModal == ModalState.ASSET_PICKER) {
                processModalClick(refX, refY);
                return;
            }

            // 2. Dropdown Menu Overlay Clicks
            if (activeDropdownMenu != DropdownMenu.NONE) {
                if (processDropdownOverlayClick(ndcX, ndcY)) {
                    activeDropdownMenu = DropdownMenu.NONE;
                    return;
                }
            }

            // 3. Region Routing
            if (refY < 30.0) {
                processTopBarClick(ndcX);
            } else if (refY > 520.0) {
                processAssetDirectoryClick(refX, refY);
            } else if (refX < 280.0) {
                processHierarchyClick(refY);
            } else if (refX > 1000.0) {
                processInspectorClick(refX, refY);
            } else { // Viewport 3D
                if (!processViewportToolbarClick(ndcX, ndcY)) {
                    processViewportGizmoClick(refX, refY);
                }
            }
        } else if (action == 0) { // RELEASE
            if (activeGizmoAxis != GizmoAxis.NONE) {
                activeGizmoAxis = GizmoAxis.NONE;
            }
            if (activeInspectorField != InspectorField.NONE) {
                activeInspectorField = InspectorField.NONE;
            }
        }
    }

    public boolean onCursorPos(double rawX, double rawY) {
        updateWindowDimensionsFromGlfw();

        double refX = (rawX / (double) windowWidth) * 1280.0;
        double refY = (rawY / (double) windowHeight) * 720.0;
        double deltaX = refX - lastMouseX;
        double deltaY = refY - lastMouseY;
        this.lastMouseX = refX;
        this.lastMouseY = refY;

        // 1. Gizmo Dragging
        if (activeGizmoAxis != GizmoAxis.NONE && getSelectedEntity() != null) {
            TransformComponent tc = getSelectedEntity().getTransform();
            if (tc != null) {
                if (currentGizmoMode == GizmoMode.TRANSLATE) {
                    float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ);
                    float axisLen = 1.0f;
                    float[] pX = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ);
                    float[] pY = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ);
                    float[] pZ = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen);

                    float uXX = pX[0] - p0[0], uXY = pX[1] - p0[1];
                    float uYX = pY[0] - p0[0], uYY = pY[1] - p0[1];
                    float uZX = pZ[0] - p0[0], uZY = pZ[1] - p0[1];

                    float lenX = (float) Math.hypot(uXX, uXY);
                    float lenY = (float) Math.hypot(uYX, uYY);
                    float lenZ = (float) Math.hypot(uZX, uZY);

                    if (lenX > 0.0001f) { uXX /= lenX; uXY /= lenX; }
                    if (lenY > 0.0001f) { uYX /= lenY; uYY /= lenY; }
                    if (lenZ > 0.0001f) { uZX /= lenZ; uZY /= lenZ; }

                    float ndcDx = (float) (deltaX / 1280.0 * 2.0);
                    float ndcDy = (float) (-deltaY / 720.0 * 2.0);

                    if (activeGizmoAxis == GizmoAxis.X) tc.posX += (ndcDx * uXX + ndcDy * uXY) * 10.0f;
                    else if (activeGizmoAxis == GizmoAxis.Y) tc.posY += (ndcDx * uYX + ndcDy * uYY) * 10.0f;
                    else if (activeGizmoAxis == GizmoAxis.Z) tc.posZ += (ndcDx * uZX + ndcDy * uZY) * 10.0f;
                } else if (currentGizmoMode == GizmoMode.SCALE) {
                    float ds = (float) (deltaX - deltaY) * 0.01f;
                    if (activeGizmoAxis == GizmoAxis.X) tc.scaleX = Math.max(0.01f, tc.scaleX + ds);
                    else if (activeGizmoAxis == GizmoAxis.Y) tc.scaleY = Math.max(0.01f, tc.scaleY + ds);
                    else if (activeGizmoAxis == GizmoAxis.Z) tc.scaleZ = Math.max(0.01f, tc.scaleZ + ds);
                } else if (currentGizmoMode == GizmoMode.ROTATE) {
                    float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ);
                    float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
                    float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
                    double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
                    double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
                    lastRotateAngle = currentAngle;

                    if (activeGizmoAxis == GizmoAxis.X) tc.rotX = (tc.rotX + (float) angleDelta) % 360.0f;
                    else if (activeGizmoAxis == GizmoAxis.Y) tc.rotY = (tc.rotY + (float) angleDelta) % 360.0f;
                    else if (activeGizmoAxis == GizmoAxis.Z) tc.rotZ = (tc.rotZ + (float) angleDelta) % 360.0f;
                }
                return true;
            }
        }

        // 2. Inspector Scrubbing
        if (activeInspectorField != InspectorField.NONE && getSelectedEntity() != null) {
            TransformComponent tc = getSelectedEntity().getTransform();
            if (tc != null) {
                float scrub = (float) deltaX * 0.05f;
                switch (activeInspectorField) {
                    case POS_X -> tc.posX += scrub;
                    case POS_Y -> tc.posY += scrub;
                    case POS_Z -> tc.posZ += scrub;
                    case ROT_X -> tc.rotX = (tc.rotX + scrub * 2.0f) % 360.0f;
                    case ROT_Y -> tc.rotY = (tc.rotY + scrub * 2.0f) % 360.0f;
                    case ROT_Z -> tc.rotZ = (tc.rotZ + scrub * 2.0f) % 360.0f;
                    case SCL_X -> tc.scaleX = Math.max(0.01f, tc.scaleX + scrub);
                    case SCL_Y -> tc.scaleY = Math.max(0.01f, tc.scaleY + scrub);
                    case SCL_Z -> tc.scaleZ = Math.max(0.01f, tc.scaleZ + scrub);
                    default -> {}
                }
                return true;
            }
        }

        return false;
    }

    private void processTopBarClick(float ndcX) {
        if (ndcX >= -0.72f && ndcX <= -0.62f) activeDropdownMenu = (activeDropdownMenu == DropdownMenu.FILE) ? DropdownMenu.NONE : DropdownMenu.FILE;
        else if (ndcX >= -0.61f && ndcX <= -0.51f) activeDropdownMenu = (activeDropdownMenu == DropdownMenu.EDIT) ? DropdownMenu.NONE : DropdownMenu.EDIT;
        else if (ndcX >= -0.50f && ndcX <= -0.38f) activeDropdownMenu = (activeDropdownMenu == DropdownMenu.ENTITY) ? DropdownMenu.NONE : DropdownMenu.ENTITY;
        else if (ndcX >= -0.37f && ndcX <= -0.27f) activeDropdownMenu = (activeDropdownMenu == DropdownMenu.VIEW) ? DropdownMenu.NONE : DropdownMenu.VIEW;
        else if (ndcX >= -0.14f && ndcX <= -0.02f) play();
        else if (ndcX >= -0.01f && ndcX <= 0.11f) pause();
        else if (ndcX >= 0.12f && ndcX <= 0.23f) stop();
    }

    private boolean processDropdownOverlayClick(float ndcX, float ndcY) {
        float minX = switch (activeDropdownMenu) {
            case FILE -> -0.72f; case EDIT -> -0.61f; case ENTITY -> -0.50f; case VIEW -> -0.37f; default -> -0.72f;
        };
        float maxX = minX + 0.32f;
        float minY = 0.58f, maxY = 0.92f;

        if (ndcX >= minX && ndcX <= maxX && ndcY >= minY && ndcY <= maxY) {
            int itemIndex = (int) ((maxY - ndcY) / 0.09f);
            if (activeDropdownMenu == DropdownMenu.FILE) {
                if (itemIndex == 0) newScene();
                else if (itemIndex == 1) promptOpenSceneWithNativeDialog();
                else if (itemIndex == 2) promptSaveSceneWithNativeDialog();
            } else if (activeDropdownMenu == DropdownMenu.EDIT) {
                if (itemIndex == 0) undo();
                else if (itemIndex == 1) redo();
                else if (itemIndex == 2) deleteSelectedEntity();
            } else if (activeDropdownMenu == DropdownMenu.ENTITY) {
                if (itemIndex == 0) spawnDefaultCube();
                else if (itemIndex == 1) spawnLightSource();
            } else if (activeDropdownMenu == DropdownMenu.VIEW) {
                if (itemIndex == 1) toggle3DGrid();
            }
            return true;
        }
        return false;
    }

    private void processHierarchyClick(double refY) {
        List<SceneEntity> entities = getSceneEntities();
        double startY = 75.0;
        double rowHeight = 28.0;
        int index = (int) ((refY - startY) / rowHeight);
        if (index >= 0 && index < entities.size()) {
            selectEntity(entities.get(index));
        } else {
            selectEntity(null);
        }
    }

    private boolean processViewportToolbarClick(float ndcX, float ndcY) {
        if (ndcY >= 0.80f && ndcY <= 0.86f) {
            if (ndcX >= -0.51f && ndcX <= -0.38f) { setCurrentGizmoMode(GizmoMode.TRANSLATE); return true; }
            if (ndcX >= -0.37f && ndcX <= -0.25f) { setCurrentGizmoMode(GizmoMode.ROTATE); return true; }
            if (ndcX >= -0.24f && ndcX <= -0.11f) { setCurrentGizmoMode(GizmoMode.SCALE); return true; }
        }
        return false;
    }

    private void processViewportGizmoClick(double refX, double refY) {
        SceneEntity selected = getSelectedEntity();
        if (selected == null) return;
        TransformComponent tc = selected.getTransform();
        if (tc == null) return;

        float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ);
        double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
        double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

        float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - refY / 720.0 * 2.0);

        if (currentGizmoMode == GizmoMode.TRANSLATE || currentGizmoMode == GizmoMode.SCALE) {
            float axisLen = 1.0f;
            float[] pX = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ);
            float[] pY = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ);
            float[] pZ = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen);

            double xTipX = ((pX[0] + 1.0) / 2.0) * 1280.0, xTipY = ((1.0 - pX[1]) / 2.0) * 720.0;
            double yTipX = ((pY[0] + 1.0) / 2.0) * 1280.0, yTipY = ((1.0 - pY[1]) / 2.0) * 720.0;
            double zTipX = ((pZ[0] + 1.0) / 2.0) * 1280.0, zTipY = ((1.0 - pZ[1]) / 2.0) * 720.0;

            double distToX = pointToSegmentDistance(refX, refY, cx, cy, xTipX, xTipY);
            double distToY = pointToSegmentDistance(refX, refY, cx, cy, yTipX, yTipY);
            double distToZ = pointToSegmentDistance(refX, refY, cx, cy, zTipX, zTipY);

            double thresh = 35.0;
            GizmoAxis grabbed = GizmoAxis.NONE;
            if (distToX < thresh && distToX <= distToY && distToX <= distToZ) grabbed = GizmoAxis.X;
            else if (distToY < thresh && distToY <= distToX && distToY <= distToZ) grabbed = GizmoAxis.Y;
            else if (distToZ < thresh && distToZ <= distToX && distToZ <= distToY) grabbed = GizmoAxis.Z;

            if (grabbed != GizmoAxis.NONE) {
                setActiveGizmoAxis(grabbed);
            }
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
            cy = ((1.0 - p0[1]) / 2.0) * 720.0;

            double currentMouseAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            double distToCenter = Math.hypot(refX - cx, refY - cy);

            float axisLen = 1.0f;
            double hitThreshold = 18.0;

            GizmoAxis grabbed = GizmoAxis.NONE;
            double bestDist = hitThreshold;

            // 1. FIRST PASS: Find the dynamic screen scale of the gizmo bounds
            double maxInnerScreenDist = 0.0;

            float[] pXTip = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ);
            float[] pYTip = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ);
            float[] pZTip = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen);

            // FIXED: Explicitly added [0] and [1] subscripts to the math lines below
            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pXTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pXTip[1]) / 2.0 * 720.0) - cy));
            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pYTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pYTip[1]) / 2.0 * 720.0) - cy));
            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pZTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pZTip[1]) / 2.0 * 720.0) - cy));

            double dynamicZRadius = maxInnerScreenDist * 1.22;
            double distToZRing = Math.abs(distToCenter - dynamicZRadius);

            // 2. UNIFIED SELECTION EVALUATION: Check Z priority cleanly
            if (distToZRing < bestDist) {
                grabbed = GizmoAxis.Z;
                bestDist = distToZRing;
            }

            // 3. HIGH-DENSITY 3D PERSPECTIVE TRACE FOR X & Y (Red & Green Loops)
            int numSamples = 64;
            for (int i = 0; i < numSamples; i++) {
                double angle = (i * 2.0 * Math.PI) / numSamples;
                float cosA = (float) Math.cos(angle) * axisLen;
                float sinA = (float) Math.sin(angle) * axisLen;

                // --- X Axis Ring (Rotates in the Y-Z Plane) ---
                float[] pXSample = projectWorldToNdc(tc.posX, tc.posY + cosA, tc.posZ + sinA);
                double sXx = ((pXSample[0] + 1.0) / 2.0) * 1280.0;
                double sXy = ((1.0 - pXSample[1]) / 2.0) * 720.0;
                double distToXSample = Math.hypot(refX - sXx, refY - sXy);

                if (distToXSample < bestDist) {
                    grabbed = GizmoAxis.X;
                    bestDist = distToXSample;
                }

                // --- Y Axis Ring (Rotates in the X-Z Plane) ---
                float[] pYSample = projectWorldToNdc(tc.posX + cosA, tc.posY, tc.posZ + sinA);
                double sYx = ((pYSample[0] + 1.0) / 2.0) * 1280.0;
                double sYy = ((1.0 - pYSample[1]) / 2.0) * 720.0;
                double distToYSample = Math.hypot(refX - sYx, refY - sYy);

                if (distToYSample < bestDist) {
                    grabbed = GizmoAxis.Y;
                    bestDist = distToYSample;
                }
            }

            // 4. SECURE THE GRAB
            if (grabbed != GizmoAxis.NONE) {
                setActiveGizmoAxis(grabbed);
                lastRotateAngle = currentMouseAngle;
            }
        }

    }

    private double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
        if (l2 == 0.0) return Math.hypot(px - x1, py - y1);
        double t = Math.max(0.0, Math.min(1.0, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2));
        double projX = x1 + t * (x2 - x1);
        double projY = y1 + t * (y2 - y1);
        return Math.hypot(px - projX, py - projY);
    }

    private void processInspectorClick(double refX, double refY) {
        SceneEntity selected = getSelectedEntity();
        if (selected == null) return;

        // 1. Mesh Renderer Component Row (Y: 190.0 to 225.0)
        if (refY >= 190.0 && refY <= 225.0) {
            if (refX >= 1150.0 && refX <= 1205.0) { // [PICK] Button -> Opens In-Engine Modal
                openAssetPicker(AssetType.MESH_3D);
                return;
            }
            if (refX >= 1210.0 && refX <= 1270.0) { // [IMPORT] Button -> Opens Native OS File Dialog
                promptImportMesh();
                return;
            }
        }

        // 2. Material Texture Component Row (Y: 255.0 to 295.0)
        if (refY >= 255.0 && refY <= 295.0) {
            if (refX >= 1150.0 && refX <= 1205.0) { // [PICK] Button -> Opens In-Engine Modal
                openAssetPicker(AssetType.TEXTURE);
                return;
            }
            if (refX >= 1210.0 && refX <= 1270.0) { // [IMPORT] Button -> Opens Native OS File Dialog
                promptImportTexture();
                return;
            }
        }

        // 3. Transform Scrubbing Fields (Pos / Rot / Scale)
        if (refX >= 1020.0 && refX <= 1260.0) {
            double relX = refX - 1020.0;
            int col = (int) (relX / 80.0);
            if (col >= 0 && col <= 2) {
                if (refY >= 115.0 && refY <= 132.0) {
                    activeInspectorField = (col == 0) ? InspectorField.POS_X : (col == 1) ? InspectorField.POS_Y : InspectorField.POS_Z;
                } else if (refY >= 133.0 && refY <= 150.0) {
                    activeInspectorField = (col == 0) ? InspectorField.ROT_X : (col == 1) ? InspectorField.ROT_Y : InspectorField.ROT_Z;
                } else if (refY >= 151.0 && refY <= 168.0) {
                    activeInspectorField = (col == 0) ? InspectorField.SCL_X : (col == 1) ? InspectorField.SCL_Y : InspectorField.SCL_Z;
                }
            }
        }
    }

    private void processAssetDirectoryClick(double refX, double refY) {
        // 1. [+ IMPORT ASSET] Button on bottom panel right side
        if (refX >= 1030.0 && refX <= 1250.0 && refY >= 520.0 && refY <= 555.0) {
            promptImportAssetForDirectory();
            return;
        }

        // 2. Asset Cards Click / Empty State Click
        List<AssetEntry> assets = getAssetDirectory();
        if (assets.isEmpty()) {
            if (refY >= 560.0 && refY <= 690.0) {
                promptImportAssetForDirectory();
            }
            return;
        }

        double startX = 20.0;
        double cardWidth = 230.0;
        int index = (int) ((refX - startX) / cardWidth);
        if (index >= 0 && index < assets.size() && refY >= 560.0 && refY <= 690.0) {
            AssetEntry clicked = assets.get(index);
            if (clicked.type == AssetType.MESH_3D) spawnEntityFromAsset(clicked);
            else if (clicked.type == AssetType.TEXTURE) assignTextureToSelectedEntity(clicked);
            else if (clicked.type == AssetType.SCENE) loadSceneFromFile(clicked.absolutePath);
        }
    }

    private void processModalClick(double refX, double refY) {
        // Modal Window Bounds (Ref Space)
        if (refX < 352.0 || refX > 928.0 || refY < 198.0 || refY > 522.0) { closeModal(); return; }
        // [X] Close Button
        if (refX >= 870.0 && refX <= 915.0 && refY >= 198.0 && refY <= 230.0) { closeModal(); return; }

        // [NONE / UNBIND] Button
        if (refX >= 367.0 && refX <= 600.0 && refY >= 233.0 && refY <= 270.0) {
            SceneEntity selected = getSelectedEntity();
            if (selected != null) {
                if (pickerFilterType == AssetType.TEXTURE) selected.setTextureAsset(null, "DEFAULT_CHECKER");
                else if (pickerFilterType == AssetType.MESH_3D) selected.setMeshAssetPath(null);
            }
            closeModal();
            return;
        }

        // [IMPORT DISK...] Button inside Modal Overlay
        if (refX >= 680.0 && refX <= 915.0 && refY >= 233.0 && refY <= 270.0) {
            closeModal();
            if (pickerFilterType == AssetType.TEXTURE) promptImportTexture();
            else if (pickerFilterType == AssetType.MESH_3D) promptImportMesh();
            return;
        }

        // Item List Selection
        List<AssetEntry> filtered = assetDirectory.stream().filter(a -> a.type == pickerFilterType).toList();
        double startItemY = 285.0;
        double itemHeight = 36.0;
        for (int i = 0; i < filtered.size(); i++) {
            double itemY = startItemY + (i * itemHeight);
            if (refY >= itemY && refY <= itemY + itemHeight) {
                AssetEntry selectedAsset = filtered.get(i);
                if (pickerFilterType == AssetType.TEXTURE) assignTextureToSelectedEntity(selectedAsset);
                else if (pickerFilterType == AssetType.MESH_3D) assignMeshToSelectedEntity(selectedAsset);
                closeModal();
                return;
            }
        }
    }

    public void promptImportTexture() {
        try {
            String defaultDir = new File("Assets/Textures").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Select Texture Asset", defaultDir, "Texture Images (*.png, *.jpg, *.tga)", "png", "jpg", "tga"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                File file = new File(importedPath);
                AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.TEXTURE);
                onFilesDropped(List.of(importedPath));
                assignTextureToSelectedEntity(newAsset);
                System.out.println("[Inspector] Imported & Bound Texture: " + file.getName());
            }
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening texture dialog: " + e.getMessage());
        }
    }

    public void promptImportMesh() {
        try {
            String defaultDir = new File("Assets/Models").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Select 3D Mesh Asset", defaultDir, "3D Models (*.obj, *.fbx, *.gltf)", "obj", "fbx", "gltf", "glb"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                File file = new File(importedPath);
                AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.MESH_3D);
                onFilesDropped(List.of(importedPath));
                assignMeshToSelectedEntity(newAsset);
                System.out.println("[Inspector] Imported & Bound 3D Mesh: " + file.getName());
            }
        } catch (Exception e) {
            System.err.println("[NativeFileDialog] Error opening mesh dialog: " + e.getMessage());
        }
    }

    public void promptImportAssetForDirectory() {
        try {
            String defaultDir = new File("Assets").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                "Import Asset to Directory", defaultDir, "Supported Assets (*.obj, *.fbx, *.gltf, *.png, *.jpg, *.tga, *.scene)", "obj", "fbx", "gltf", "glb", "png", "jpg", "tga", "scene"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                onFilesDropped(List.of(importedPath));
                System.out.println("[AssetDirectory] Imported Asset: " + importedPath);
            }
        } catch (Exception e) {
            System.err.println("[AssetDirectory] Error opening file dialog: " + e.getMessage());
        }
    }

    // --- 3D WORLD PROJECTION & GRID GENERATION ---
    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        float aspect = 712.0f / 488.0f;
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float activeYaw = (cameraController != null) ? cameraController.getYaw() : camYaw;
        float activePitch = (cameraController != null) ? cameraController.getPitch() : camPitch;
        float activePanX = (cameraController != null) ? cameraController.getPanX() : camPanX;
        float activePanY = (cameraController != null) ? cameraController.getPanY() : camPanY;
        float activeZoom = (cameraController != null) ? cameraController.getZoom() : camZoom;

        float radYaw = (float) Math.toRadians(activeYaw);
        float radPitch = (float) Math.toRadians(activePitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        float vx = cy * wx - sy * wz + activePanX;
        float vy = sy * sp * wx + cp * wy + cy * sp * wz + activePanY;
        float vz = sy * cp * wx - sp * wy + cy * cp * wz + activeZoom;

        float clipW = -vz;
        if (clipW <= 0.001f) clipW = 0.001f;

        float ndcX = (p00 * vx) / clipW;
        float ndcY = (p11 * vy) / clipW;

        // Offset into 3D Viewport Frame in UI Canvas Space
        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public float[] get3DGridVertices() {
        if (!show3DGrid) return new float[0];
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) cached3DGridVertices = generate3DGridVertices();
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (!show3DGrid) return 0;
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    private float[] generate3DGridVertices() {
        List<Float> verts = new ArrayList<>();
        int gridSize = 10;
        for (int i = -gridSize; i <= gridSize; i++) {
            float alpha = (i == 0) ? 0.9f : 0.35f;

            // 1. Lines running along the Z-axis (varying Z, constant X) -> Blue Z-Axis Style
            float rZ = (i == 0) ? 0.2f : 0.4f, gZ = (i == 0) ? 0.2f : 0.4f, bZ = (i == 0) ? 1.0f : 0.5f;
            addGridLine(verts, i, 0, -gridSize, i, 0, gridSize, rZ, gZ, bZ, alpha);

            // 2. Lines running along the X-axis (varying X, constant Z) -> Red X-Axis Style
            float rX = (i == 0) ? 1.0f : 0.4f, gX = (i == 0) ? 0.2f : 0.4f, bX = (i == 0) ? 0.2f : 0.5f;
            addGridLine(verts, -gridSize, 0, i, gridSize, 0, i, rX, gX, bX, alpha);
        }
        // Vertical Green Y-Axis Line (Remains unchanged)
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.2f, 1.0f, 0.2f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();
            AssetType type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "tga" -> AssetType.TEXTURE;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
            assetDirectory.add(new AssetEntry(name, path, type));
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null) getUiVertices();
        return cachedUiVertices.length / 6;
    }

    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();
        SceneHierarchyPanel.render(verts, getSceneEntities());
        InspectorPanel.render(verts, getSelectedEntity(), assetDirectory);
        AssetDirectoryPanel.render(verts, assetDirectory);
        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, currentGizmoMode);
        TopMenuBar.render(verts, activeDropdownMenu, getActiveScenePath(), getPlayState());

        SceneEntity activeEntity = getSelectedEntity();
        if (activeEntity != null) {
            render3DGizmos(verts, activeEntity, currentGizmoMode, activeGizmoAxis);
        }

        if (activeDropdownMenu != DropdownMenu.NONE) {
            TopMenuBar.renderDropdownMenuOverlay(verts, activeDropdownMenu, commandHistory, show3DGrid);
        }
        if (activeModal == ModalState.ASSET_PICKER) {
            ModalOverlayManager.renderAssetPickerModal(verts, pickerFilterType, assetDirectory);
        }
        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis) {
        TransformComponent tc = entity.getTransform();
        if (tc == null) return;

        float px = tc.posX, py = tc.posY, pz = tc.posZ;

        float[] p0 = projectWorldToNdc(px, py, pz);
        float cx = p0[0], cy = p0[1];

        float axisWorldLen = 1.0f;

        float[] pX = projectWorldToNdc(px + axisWorldLen, py, pz);
        float[] pY = projectWorldToNdc(px, py + axisWorldLen, pz);
        float[] pZ = projectWorldToNdc(px, py, pz + axisWorldLen);

        float dirXX = pX[0] - cx, dirXY = pX[1] - cy;
        float dirYX = pY[0] - cx, dirYY = pY[1] - cy;
        float dirZX = pZ[0] - cx, dirZY = pZ[1] - cy;

        float lenX = (float) Math.hypot(dirXX, dirXY);
        float lenY = (float) Math.hypot(dirYX, dirYY);
        float lenZ = (float) Math.hypot(dirZX, dirZY);

        float screenLen = 0.16f;

        float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
        float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

        float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
        float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

        float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
        float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

        float xTipX = cx + uXX * screenLen, xTipY = cy + uXY * screenLen;
        float yTipX = cx + uYX * screenLen, yTipY = cy + uYY * screenLen;
        float zTipX = cx + uZX * screenLen, zTipY = cy + uZY * screenLen;

        float lineThick = 0.006f;

        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        float rx = 1.0f, rg = 0.25f, rb = 0.25f;
        float gx = 0.25f, gg = 1.0f, gb = 0.25f;
        float bx = 0.25f, bg = 0.55f, bb = 1.0f;

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        if (mode == GizmoMode.TRANSLATE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, xTipX + uXX * 0.02f, xTipY + uXY * 0.02f, uXX, uXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, yTipX + uYX * 0.02f, yTipY + uYY * 0.02f, uYX, uYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zTipX + uZX * 0.02f, zTipY + uZY * 0.02f, uZX, uZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }
}
