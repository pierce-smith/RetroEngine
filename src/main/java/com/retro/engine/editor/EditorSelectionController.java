package com.retro.engine.editor;

import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.AssetType;
import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.EditorPanelManager.ModalState;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;
import com.retro.engine.editor.commands.TransformCommand;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.List;

/**
 * EditorSelectionController routes mouse input to Top Bar Dropdown Menus,
 * Asset Picker Modals, 3D Viewport Transform Gizmos, Inspector Fields, and
 * captures Transform edits for Undo/Redo.
 *
 * Refactored in Step 3 to support dynamic layout scaling and decoupled panel event routing.
 * Target JDK: JDK 21+ / JDK 25 / macOS
 */
public class EditorSelectionController {
    public enum PanelType { TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, NONE }
    public enum InspectorField { NONE, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, SCL_X, SCL_Y, SCL_Z }

    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;

    private EditorPanelManager panelManager;
    private ViewportCameraController cameraController;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private double lastAngle = 0.0;

    private InspectorField activeInspectorField = InspectorField.NONE;

    private SceneEntity transformDragEntity = null;
    private float startPx, startPy, startPz;
    private float startRx, startRy, startRz;
    private float startSx, startSy, startSz;

    public EditorSelectionController() {
        this(null);
    }

    public EditorSelectionController(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void setPanelManager(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void setCameraController(ViewportCameraController cameraController) {
        this.cameraController = cameraController;
    }

    private void captureTransformStart(SceneEntity entity) {
        if (entity == null) return;
        this.transformDragEntity = entity;
        TransformComponent tc = entity.getTransform();
        this.startPx = tc.posX;
        this.startPy = tc.posY;
        this.startPz = tc.posZ;
        this.startRx = tc.rotX;
        this.startRy = tc.rotY;
        this.startRz = tc.rotZ;
        this.startSx = tc.scaleX;
        this.startSy = tc.scaleY;
        this.startSz = tc.scaleZ;
    }

    private void checkAndPushTransformEnd() {
        if (transformDragEntity != null) {
            TransformComponent tc = transformDragEntity.getTransform();
            boolean changed = (tc.posX != startPx || tc.posY != startPy || tc.posZ != startPz
                    || tc.rotX != startRx || tc.rotY != startRy || tc.rotZ != startRz
                    || tc.scaleX != startSx || tc.scaleY != startSy || tc.scaleZ != startSz);

            if (changed && panelManager != null) {
                TransformCommand cmd = new TransformCommand(
                        transformDragEntity,
                        startPx, startPy, startPz,
                        startRx, startRy, startRz,
                        startSx, startSy, startSz,
                        tc.posX, tc.posY, tc.posZ,
                        tc.rotX, tc.rotY, tc.rotZ,
                        tc.scaleX, tc.scaleY, tc.scaleZ
                );
                panelManager.getCommandHistory().executeCommand(cmd);
            }
            transformDragEntity = null;
        }
    }

    public void onMouseRelease() {
        if (panelManager != null && panelManager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            System.out.println("[Gizmo] Released Axis Drag.");
            checkAndPushTransformEnd();
            panelManager.setActiveGizmoAxis(GizmoAxis.NONE);
        }
        if (activeInspectorField != InspectorField.NONE) {
            System.out.println("[Inspector] Released Field Edit.");
            checkAndPushTransformEnd();
            activeInspectorField = InspectorField.NONE;
        }
    }

    public PanelType hitTest(double mouseX, double mouseY, float windowWidth, float windowHeight) {
        if (mouseY < TOP_BAR_HEIGHT) return PanelType.TOP_BAR;
        if (mouseY >= windowHeight - BOTTOM_PANEL_HEIGHT) return PanelType.ASSET_DIRECTORY;
        if (mouseX < SIDE_PANEL_WIDTH) return PanelType.SCENE_HIERARCHY;
        if (mouseX >= windowWidth - SIDE_PANEL_WIDTH) return PanelType.INSPECTOR;
        return PanelType.VIEWPORT_3D;
    }

    public void handleMouseClick(double mouseX, double mouseY, int button) {
        handleMouseClick(mouseX, mouseY, button, 1, this.panelManager);
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, EditorPanelManager manager) {
        handleMouseClick(mouseX, mouseY, button, 1, manager);
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, int action, EditorPanelManager manager) {
        EditorPanelManager activeManager = manager != null ? manager : this.panelManager;
        if (activeManager == null) return;

        if (action == 1) { // GLFW_PRESS
            // 1. Modal Dialog Overlay Clicks
            if (activeManager.getActiveModal() == ModalState.ASSET_PICKER) {
                boolean handled = processModalClick(mouseX, mouseY, activeManager);
                if (handled) return;
            }

            // 2. Dropdown Menu Overlay Clicks
            DropdownMenu activeMenu = activeManager.getActiveDropdownMenu();
            if (activeMenu != DropdownMenu.NONE) {
                boolean handled = processDropdownOverlayClick(mouseX, mouseY, activeMenu, activeManager);
                activeManager.setActiveDropdownMenu(DropdownMenu.NONE);
                if (handled) return;
            }

            // 3. Main Editor Panel Clicks (Supports dynamic layout scaling)
            PanelType clickedPanel = hitTest(mouseX, mouseY, 1280.0f, 720.0f);

            switch (clickedPanel) {
                case TOP_BAR -> processTopBarClick(mouseX, mouseY, activeManager);
                case SCENE_HIERARCHY -> processHierarchyClick(mouseY, activeManager);
                case INSPECTOR -> processInspectorClick(mouseX, mouseY, activeManager);
                case ASSET_DIRECTORY -> processAssetClick(mouseX, mouseY, activeManager);
                case VIEWPORT_3D -> {
                    if (button == 0) { // LMB
                        boolean hitToolbar = processViewportToolbarClick(mouseX, mouseY, activeManager);
                        if (!hitToolbar) {
                            processViewportGizmoClick(mouseX, mouseY, activeManager);
                        }
                    }
                }
                default -> {}
            }
        } else if (action == 0) { // GLFW_RELEASE
            onMouseRelease();
        }
    }

    private boolean processModalClick(double mouseX, double mouseY, EditorPanelManager manager) {
        double minX = 352.0, maxX = 928.0;
        double minY = 198.0, maxY = 522.0;

        if (mouseX < minX || mouseX > maxX || mouseY < minY || mouseY > maxY) {
            manager.closeModal();
            return true;
        }

        // Close [X] Button
        if (mouseX >= 870.0 && mouseX <= 915.0 && mouseY >= 198.0 && mouseY <= 230.0) {
            manager.closeModal();
            return true;
        }

        // [NONE / UNBIND] Button
        if (mouseX >= 370.0 && mouseX <= 590.0 && mouseY >= 238.0 && mouseY <= 270.0) {
            SceneEntity selected = manager.getSelectedEntity();
            if (selected != null) {
                if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                    selected.setTextureAsset(null, "DEFAULT_CHECKER");
                    System.out.println("[AssetPicker] Unbound texture from selected entity.");
                } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                    selected.setMeshAssetPath(null);
                    System.out.println("[AssetPicker] Unbound 3D mesh from selected entity.");
                }
            }
            manager.closeModal();
            return true;
        }

        // [IMPORT DISK...] Button
        if (mouseX >= 680.0 && mouseX <= 900.0 && mouseY >= 238.0 && mouseY <= 270.0) {
            manager.closeModal();
            if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                promptImportTexture(manager);
            } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                promptImportMesh(manager);
            }
            return true;
        }

        List<AssetEntry> filtered = manager.getAssetDirectory().stream()
                .filter(a -> a.type == manager.getPickerFilterType())
                .toList();

        if (!filtered.isEmpty()) {
            double startItemY = 285.0;
            double itemHeight = 36.0;

            for (int i = 0; i < filtered.size(); i++) {
                double itemY = startItemY + (i * itemHeight);
                if (mouseY >= itemY && mouseY <= itemY + itemHeight) {
                    AssetEntry selectedAsset = filtered.get(i);
                    if (manager.getPickerFilterType() == AssetType.TEXTURE) {
                        manager.assignTextureToSelectedEntity(selectedAsset);
                    } else if (manager.getPickerFilterType() == AssetType.MESH_3D) {
                        manager.assignMeshToSelectedEntity(selectedAsset);
                    }
                    manager.closeModal();
                    return true;
                }
            }
        }

        return true;
    }

    private void promptImportTexture(EditorPanelManager manager) {
        String defaultDir = new File("Assets/Textures").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog(
                "Select Texture Asset",
                defaultDir,
                "Texture Images (*.png, *.jpg, *.tga)",
                "png", "jpg", "tga"
        );
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            File file = new File(importedPath);
            AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.TEXTURE);
            manager.onFilesDropped(List.of(importedPath));
            manager.assignTextureToSelectedEntity(newAsset);
            System.out.println("[Inspector] Imported & Bound Texture: " + file.getName());
        }
    }

    private void promptImportMesh(EditorPanelManager manager) {
        String defaultDir = new File("Assets/Models").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog(
                "Select 3D Mesh Asset",
                defaultDir,
                "3D Models (*.obj, *.fbx, *.gltf)",
                "obj", "fbx", "gltf", "glb"
        );
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            File file = new File(importedPath);
            AssetEntry newAsset = new AssetEntry(file.getName(), importedPath, AssetType.MESH_3D);
            manager.onFilesDropped(List.of(importedPath));
            manager.assignMeshToSelectedEntity(newAsset);
            System.out.println("[Inspector] Imported & Bound 3D Mesh: " + file.getName());
        }
    }

    private void promptImportAssetForDirectory(EditorPanelManager manager) {
        try {
            String defaultDir = new File("Assets").getAbsolutePath();
            String importedPath = NativeFileDialog.openFileDialog(
                    "Import Asset to Directory",
                    defaultDir,
                    "Supported Assets (*.obj, *.fbx, *.gltf, *.png, *.jpg, *.tga, *.scene)",
                    "obj", "fbx", "gltf", "glb", "png", "jpg", "tga", "scene"
            );
            if (importedPath != null && !importedPath.trim().isEmpty()) {
                manager.onFilesDropped(List.of(importedPath));
            }
        } catch (Exception e) {
            System.err.println("[AssetDirectory] File import dialog error: " + e.getMessage());
        }
    }

    private void processTopBarClick(double mouseX, double mouseY, EditorPanelManager manager) {
        if (mouseX >= 170.0 && mouseX <= 245.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.FILE) ? DropdownMenu.NONE : DropdownMenu.FILE;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 250.0 && mouseX <= 315.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.EDIT) ? DropdownMenu.NONE : DropdownMenu.EDIT;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 320.0 && mouseX <= 395.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.ENTITY) ? DropdownMenu.NONE : DropdownMenu.ENTITY;
            manager.setActiveDropdownMenu(next);
        } else if (mouseX >= 400.0 && mouseX <= 465.0) {
            DropdownMenu next = (manager.getActiveDropdownMenu() == DropdownMenu.VIEW) ? DropdownMenu.NONE : DropdownMenu.VIEW;
            manager.setActiveDropdownMenu(next);
        }
    }

    private boolean processDropdownOverlayClick(double mouseX, double mouseY, DropdownMenu menu, EditorPanelManager manager) {
        double minX = switch (menu) {
            case FILE -> 175.0;
            case EDIT -> 250.0;
            case ENTITY -> 320.0;
            case VIEW -> 400.0;
            default -> 175.0;
        };
        double maxX = minX + 210.0;

        if (mouseX >= minX && mouseX <= maxX && mouseY >= 30.0 && mouseY <= 180.0) {
            int itemIndex = (int) ((mouseY - 30.0) / 32.0);

            switch (menu) {
                case FILE -> {
                    if (itemIndex == 0) manager.newScene();
                    else if (itemIndex == 1) manager.promptOpenSceneWithNativeDialog();
                    else if (itemIndex == 2) manager.promptSaveSceneWithNativeDialog();
                }
                case EDIT -> {
                    if (itemIndex == 0) manager.undo();
                    else if (itemIndex == 1) manager.redo();
                    else if (itemIndex == 2) manager.deleteSelectedEntity();
                }
                case ENTITY -> {
                    if (itemIndex == 0) manager.spawnDefaultCube();
                    else if (itemIndex == 1) manager.spawnLightSource();
                }
                case VIEW -> {
                    if (itemIndex == 0) {
                        if (cameraController != null) {
                            cameraController.reset();
                            System.out.println("[View Menu] Reset Viewport Camera.");
                        }
                    } else if (itemIndex == 1) {
                        manager.toggle3DGrid();
                    }
                }
                default -> {}
            }
            return true;
        }
        return false;
    }

    private boolean processViewportToolbarClick(double mouseX, double mouseY, EditorPanelManager manager) {
        if (mouseY >= 72.0 && mouseY <= 115.0) {
            if (mouseX >= 307.0 && mouseX <= 390.0) {
                manager.setCurrentGizmoMode(GizmoMode.TRANSLATE);
                return true;
            } else if (mouseX >= 396.0 && mouseX <= 480.0) {
                manager.setCurrentGizmoMode(GizmoMode.ROTATE);
                return true;
            } else if (mouseX >= 486.0 && mouseX <= 570.0) {
                manager.setCurrentGizmoMode(GizmoMode.SCALE);
                return true;
            }
        }
        return false;
    }

    private void processViewportGizmoClick(double mouseX, double mouseY, EditorPanelManager manager) {
        SceneEntity selected = manager.getSelectedEntity();
        if (selected == null) return;

        TransformComponent tc = selected.getTransform();
        float px = tc.posX;
        float py = tc.posY;
        float pz = tc.posZ;

        float[] p0 = manager.projectWorldToNdc(px, py, pz);
        float cx = p0[0];
        float cy = p0[1];

        float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

        GizmoMode mode = manager.getCurrentGizmoMode();

        if (mode == GizmoMode.TRANSLATE || mode == GizmoMode.SCALE) {
            float axisWorldLen = 1.2f;
            float[] pX = manager.projectWorldToNdc(px + axisWorldLen, py, pz);
            float[] pY = manager.projectWorldToNdc(px, py + axisWorldLen, pz);
            float[] pZ = manager.projectWorldToNdc(px, py, pz + axisWorldLen);

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

            double distToX = pointToSegmentDistance(ndcX, ndcY, cx, cy, xTipX, xTipY);
            double distToY = pointToSegmentDistance(ndcX, ndcY, cx, cy, yTipX, yTipY);
            double distToZ = pointToSegmentDistance(ndcX, ndcY, cx, cy, zTipX, zTipY);

            double threshold = 0.045;

            GizmoAxis grabbed = GizmoAxis.NONE;
            if (distToX < threshold && distToX <= distToY && distToX <= distToZ) grabbed = GizmoAxis.X;
            else if (distToY < threshold && distToY <= distToX && distToY <= distToZ) grabbed = GizmoAxis.Y;
            else if (distToZ < threshold && distToZ <= distToX && distToZ <= distToY) grabbed = GizmoAxis.Z;

            if (grabbed != GizmoAxis.NONE) {
                manager.setActiveGizmoAxis(grabbed);
                captureTransformStart(selected);
                System.out.println("[Gizmo] Grabbed " + grabbed + " Axis Handle.");
            }
        } else if (mode == GizmoMode.ROTATE) {
            double rxNorm = 0.05, ryNorm = 0.14;
            double dxNormX = (ndcX - cx) / rxNorm;
            double dyNormX = (ndcY - cy) / ryNorm;
            double distEllipseX = Math.abs(Math.hypot(dxNormX, dyNormX) - 1.0);

            double gxNorm = 0.14, gyNorm = 0.05;
            double dxNormY = (ndcX - cx) / gxNorm;
            double dyNormY = (ndcY - cy) / gyNorm;
            double distEllipseY = Math.abs(Math.hypot(dxNormY, dyNormY) - 1.0);

            double distToCenter = Math.hypot(ndcX - cx, ndcY - cy);
            double distCircleZ = Math.abs(distToCenter - 0.14);

            double thresh = 0.35;
            GizmoAxis grabbed = GizmoAxis.NONE;

            if (distEllipseX < thresh && distEllipseX <= distEllipseY && distEllipseX <= distCircleZ) grabbed = GizmoAxis.X;
            else if (distEllipseY < thresh && distEllipseY <= distEllipseX && distEllipseY <= distCircleZ) grabbed = GizmoAxis.Y;
            else if (distCircleZ < 0.05) grabbed = GizmoAxis.Z;

            if (grabbed != GizmoAxis.NONE) {
                manager.setActiveGizmoAxis(grabbed);
                captureTransformStart(selected);
                lastAngle = Math.atan2(ndcY - cy, ndcX - cx);
                System.out.println("[Gizmo] Grabbed Rotate " + grabbed + " Arc.");
            }
        }
    }

    private double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double l2 = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1);
        if (l2 == 0.0) return Math.hypot(px - x1, py - y1);
        double t = Math.max(0, Math.min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2));
        double projX = x1 + t * (x2 - x1);
        double projY = y1 + t * (y2 - y1);
        return Math.hypot(px - projX, py - projY);
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        EditorPanelManager activeManager = manager != null ? manager : this.panelManager;
        if (activeManager == null) return false;

        SceneEntity selected = activeManager.getSelectedEntity();
        if (selected == null) return false;

        TransformComponent tc = selected.getTransform();

        if (activeInspectorField != InspectorField.NONE) {
            float dragSpeedPos = 0.05f;
            float dragSpeedRot = 0.8f;
            float dragSpeedScl = 0.02f;

            switch (activeInspectorField) {
                case POS_X -> tc.posX += (float) dx * dragSpeedPos;
                case POS_Y -> tc.posY -= (float) dy * dragSpeedPos;
                case POS_Z -> tc.posZ += (float) dx * dragSpeedPos;

                case ROT_X -> tc.rotX += (float) dx * dragSpeedRot;
                case ROT_Y -> tc.rotY += (float) dx * dragSpeedRot;
                case ROT_Z -> tc.rotZ += (float) dx * dragSpeedRot;

                case SCL_X -> tc.scaleX = Math.max(0.1f, tc.scaleX + (float) dx * dragSpeedScl);
                case SCL_Y -> tc.scaleY = Math.max(0.1f, tc.scaleY - (float) dy * dragSpeedScl);
                case SCL_Z -> tc.scaleZ = Math.max(0.1f, tc.scaleZ + (float) dx * dragSpeedScl);
                default -> {}
            }
            return true;
        }

        if (activeManager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            GizmoMode mode = activeManager.getCurrentGizmoMode();
            GizmoAxis axis = activeManager.getActiveGizmoAxis();

            float px = tc.posX;
            float py = tc.posY;
            float pz = tc.posZ;

            float[] p0 = activeManager.projectWorldToNdc(px, py, pz);
            float cx = p0[0];
            float cy = p0[1];

            float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

            if (mode == GizmoMode.TRANSLATE || mode == GizmoMode.SCALE) {
                float axisWorldLen = 1.2f;
                float[] pX = activeManager.projectWorldToNdc(px + axisWorldLen, py, pz);
                float[] pY = activeManager.projectWorldToNdc(px, py + axisWorldLen, pz);
                float[] pZ = activeManager.projectWorldToNdc(px, py, pz + axisWorldLen);

                float dirXX = pX[0] - cx, dirXY = pX[1] - cy;
                float dirYX = pY[0] - cx, dirYY = pY[1] - cy;
                float dirZX = pZ[0] - cx, dirZY = pZ[1] - cy;

                float lenX = (float) Math.hypot(dirXX, dirXY);
                float lenY = (float) Math.hypot(dirYX, dirYY);
                float lenZ = (float) Math.hypot(dirZX, dirZY);

                float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
                float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

                float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
                float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

                float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
                float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

                float ndcDx = (float) (dx / 1280.0 * 2.0);
                float ndcDy = (float) (-dy / 720.0 * 2.0);

                if (mode == GizmoMode.TRANSLATE) {
                    float speed = 2.5f;
                    switch (axis) {
                        case X -> tc.posX += (ndcDx * uXX + ndcDy * uXY) * speed;
                        case Y -> tc.posY += (ndcDx * uYX + ndcDy * uYY) * speed;
                        case Z -> tc.posZ += (ndcDx * uZX + ndcDy * uZY) * speed;
                        default -> {}
                    }
                } else if (mode == GizmoMode.SCALE) {
                    float speed = 2.5f;
                    switch (axis) {
                        case X -> tc.scaleX = Math.max(0.1f, tc.scaleX + (ndcDx * uXX + ndcDy * uXY) * speed);
                        case Y -> tc.scaleY = Math.max(0.1f, tc.scaleY + (ndcDx * uYX + ndcDy * uYY) * speed);
                        case Z -> tc.scaleZ = Math.max(0.1f, tc.scaleZ + (ndcDx * uZX + ndcDy * uZY) * speed);
                        default -> {}
                    }
                }
            } else if (mode == GizmoMode.ROTATE) {
                double currentAngle = Math.atan2(ndcY - cy, ndcX - cx);
                double deltaAngle = Math.toDegrees(currentAngle - lastAngle);

                if (deltaAngle > 180.0) deltaAngle -= 360.0;
                if (deltaAngle < -180.0) deltaAngle += 360.0;

                float rotSpeed = 0.8f;
                switch (axis) {
                    case X -> tc.rotX += (float) dy * rotSpeed;
                    case Y -> tc.rotY += (float) dx * rotSpeed;
                    case Z -> tc.rotZ += (float) deltaAngle * rotSpeed;
                    default -> {}
                }
                lastAngle = currentAngle;
            }
            return true;
        }
        return false;
    }

    private void processHierarchyClick(double mouseY, EditorPanelManager manager) {
        List<SceneEntity> entities = manager.getSceneEntities();
        if (entities.isEmpty()) return;

        double startY = TOP_BAR_HEIGHT + 30.0;
        double itemHeight = 24.0;

        int index = (int) ((mouseY - startY) / itemHeight);
        if (index >= 0 && index < entities.size()) {
            manager.selectEntity(entities.get(index));
            System.out.println("[Scene Hierarchy] Selected Entity: " + entities.get(index).name);
        }
    }

    private void processInspectorClick(double mouseX, double mouseY, EditorPanelManager manager) {
        SceneEntity active = manager.getSelectedEntity();
        if (active == null) return;

        // Mesh Renderer Row Clicks (Y: 185 to 225)
        if (mouseY >= 185.0 && mouseY <= 225.0) {
            if (mouseX >= 1150.0 && mouseX <= 1205.0) {
                manager.openAssetPicker(AssetType.MESH_3D);
                return;
            }
            if (mouseX >= 1210.0 && mouseX <= 1270.0) {
                promptImportMesh(manager);
                return;
            }
        }

        // Material Texture Row Clicks (Y: 250 to 295)
        if (mouseY >= 250.0 && mouseY <= 295.0) {
            if (mouseX >= 1150.0 && mouseX <= 1205.0) {
                manager.openAssetPicker(AssetType.TEXTURE);
                return;
            }
            if (mouseX >= 1210.0 && mouseX <= 1270.0) {
                promptImportTexture(manager);
                return;
            }
        }

        int axis = -1;
        if (mouseX >= 1050.0 && mouseX <= 1115.0) axis = 0;
        else if (mouseX >= 1120.0 && mouseX <= 1185.0) axis = 1;
        else if (mouseX >= 1190.0 && mouseX <= 1255.0) axis = 2;

        if (axis != -1) {
            if (mouseY >= 118.0 && mouseY <= 148.0) {
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.POS_X;
                    case 1 -> InspectorField.POS_Y;
                    default -> InspectorField.POS_Z;
                };
                captureTransformStart(active);
                System.out.println("[Inspector] Grabbed Position " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 154.0 && mouseY <= 184.0) {
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.ROT_X;
                    case 1 -> InspectorField.ROT_Y;
                    default -> InspectorField.ROT_Z;
                };
                captureTransformStart(active);
                System.out.println("[Inspector] Grabbed Rotation " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 190.0 && mouseY <= 220.0) {
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.SCL_X;
                    case 1 -> InspectorField.SCL_Y;
                    default -> InspectorField.SCL_Z;
                };
                captureTransformStart(active);
                System.out.println("[Inspector] Grabbed Scale " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            }
        }
    }

    private void processAssetClick(double mouseX, double mouseY, EditorPanelManager manager) {
        // [+ IMPORT ASSET] Header Button click (X: 1030 to 1250, Y: 520 to 555)
        if (mouseX >= 1030.0 && mouseX <= 1250.0 && mouseY >= 520.0 && mouseY <= 555.0) {
            promptImportAssetForDirectory(manager);
            return;
        }

        List<AssetEntry> assets = manager.getAssetDirectory();
        if (assets.isEmpty()) {
            promptImportAssetForDirectory(manager);
            return;
        }

        double startX = 20.0;
        double cardWidth = 230.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size()) {
            AssetEntry clickedAsset = assets.get(index);
            if (clickedAsset.type == AssetType.MESH_3D) {
                manager.spawnEntityFromAsset(clickedAsset);
            } else if (clickedAsset.type == AssetType.TEXTURE) {
                manager.assignTextureToSelectedEntity(clickedAsset);
            } else if (clickedAsset.type == AssetType.SCENE) {
                manager.loadSceneFromFile(clickedAsset.absolutePath);
            }
        }
    }
}
