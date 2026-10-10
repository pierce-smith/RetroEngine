package com.retro.engine.editor;

import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

/**
 * EditorSelectionController handles panel region hit-testing, input routing, 3D Viewport Transform Gizmos,
 * and Inspector Panel direct field editing.
 * Updated: Operates cleanly on ECS TransformComponents.
 */
public class EditorSelectionController {
    public enum PanelType { TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, NONE }
    public enum InspectorField { NONE, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, SCL_X, SCL_Y, SCL_Z }

    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;

    private EditorPanelManager panelManager;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private double lastAngle = 0.0;

    private InspectorField activeInspectorField = InspectorField.NONE;

    public EditorSelectionController() { this(null); }

    public EditorSelectionController(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void setPanelManager(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void onMouseRelease() {
        if (panelManager != null && panelManager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            System.out.println("[Gizmo] Released Axis Drag.");
            panelManager.setActiveGizmoAxis(GizmoAxis.NONE);
        }
        if (activeInspectorField != InspectorField.NONE) {
            System.out.println("[Inspector] Released Field Edit.");
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

        PanelType clickedPanel = hitTest(mouseX, mouseY, 1280.0f, 720.0f);

        if (action == 1) { // GLFW_PRESS
            switch (clickedPanel) {
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

            if (distToX < threshold && distToX <= distToY && distToX <= distToZ) {
                manager.setActiveGizmoAxis(GizmoAxis.X);
                System.out.println("[Gizmo] Grabbed X Axis Handle.");
            } else if (distToY < threshold && distToY <= distToX && distToY <= distToZ) {
                manager.setActiveGizmoAxis(GizmoAxis.Y);
                System.out.println("[Gizmo] Grabbed Y Axis Handle.");
            } else if (distToZ < threshold && distToZ <= distToX && distToZ <= distToY) {
                manager.setActiveGizmoAxis(GizmoAxis.Z);
                System.out.println("[Gizmo] Grabbed Z Axis Handle.");
            }
        } else if (mode == GizmoMode.ROTATE) {
            double distToCenter = Math.hypot(ndcX - cx, ndcY - cy);
            if (distToCenter >= 0.08 && distToCenter <= 0.18) {
                manager.setActiveGizmoAxis(GizmoAxis.Z);
                lastAngle = Math.atan2(ndcY - cy, ndcX - cx);
                System.out.println("[Gizmo] Grabbed Z Roll Ring.");
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

            float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

            float px = tc.posX;
            float py = tc.posY;
            float pz = tc.posZ;

            float[] p0 = activeManager.projectWorldToNdc(px, py, pz);
            float cx = p0[0];
            float cy = p0[1];

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
                    case X -> tc.rotX += (float) deltaAngle * rotSpeed;
                    case Y -> tc.rotY += (float) deltaAngle * rotSpeed;
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

        if (mouseY >= 280.0 && mouseY <= 320.0) {
            List<AssetEntry> assets = manager.getAssetDirectory();
            List<AssetEntry> textures = assets.stream().filter(a -> a.type == EditorPanelManager.AssetType.TEXTURE).toList();
            if (!textures.isEmpty()) {
                AssetEntry nextTex = textures.get(0);
                manager.assignTextureToSelectedEntity(nextTex);
            }
            return;
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
                System.out.println("[Inspector] Grabbed Position " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 154.0 && mouseY <= 184.0) {
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.ROT_X;
                    case 1 -> InspectorField.ROT_Y;
                    default -> InspectorField.ROT_Z;
                };
                System.out.println("[Inspector] Grabbed Rotation " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 190.0 && mouseY <= 220.0) {
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.SCL_X;
                    case 1 -> InspectorField.SCL_Y;
                    default -> InspectorField.SCL_Z;
                };
                System.out.println("[Inspector] Grabbed Scale " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            }
        }
    }

    private void processAssetClick(double mouseX, double mouseY, EditorPanelManager manager) {
        List<AssetEntry> assets = manager.getAssetDirectory();
        if (assets.isEmpty()) return;

        double startX = 20.0;
        double cardWidth = 230.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size()) {
            AssetEntry clickedAsset = assets.get(index);
            if (clickedAsset.type == EditorPanelManager.AssetType.MESH_3D) {
                manager.spawnEntityFromAsset(clickedAsset);
            } else if (clickedAsset.type == EditorPanelManager.AssetType.TEXTURE) {
                manager.assignTextureToSelectedEntity(clickedAsset);
            }
        }
    }
}
