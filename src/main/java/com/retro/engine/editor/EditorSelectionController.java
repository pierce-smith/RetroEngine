package com.retro.engine.editor;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

/**
 * EditorSelectionController handles panel region hit-testing, input routing, 3D Viewport Transform Gizmos,
 * and Inspector Panel direct field editing.
 */
public class EditorSelectionController {

    public enum PanelType {
        TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, NONE
    }

    public enum InspectorField {
        NONE, POS_X, POS_Y, POS_Z, ROT_X, ROT_Y, ROT_Z, SCL_X, SCL_Y, SCL_Z
    }

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

        float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

        float px = selected.transform.posX;
        float py = selected.transform.posY;
        float pz = selected.transform.posZ;

        float[] p0 = manager.projectWorldToNdc(px, py, pz);
        float cx = Math.max(-0.50f, Math.min(0.50f, p0[0]));
        float cy = Math.max(-0.40f, Math.min(0.75f, p0[1]));

        float axisLen = 0.8f;
        float[] pX = manager.projectWorldToNdc(px + axisLen, py, pz);
        float[] pY = manager.projectWorldToNdc(px, py + axisLen, pz);
        float[] pZ = manager.projectWorldToNdc(px, py, pz + axisLen);

        float dxX = pX[0] - p0[0], dyX = pX[1] - p0[1];
        float dxY = pY[0] - p0[0], dyY = pY[1] - p0[1];
        float dxZ = pZ[0] - p0[0], dyZ = pZ[1] - p0[1];

        float lenX = (float) Math.hypot(dxX, dyX);
        float lenY = (float) Math.hypot(dxY, dyY);
        float lenZ = (float) Math.hypot(dxZ, dyZ);

        float handleVisualLen = 0.14f;

        float dirXX = lenX > 0.0001f ? (dxX / lenX) * handleVisualLen : handleVisualLen;
        float dirXY = lenX > 0.0001f ? (dyX / lenX) * handleVisualLen : 0.0f;

        float dirYX = lenY > 0.0001f ? (dxY / lenY) * handleVisualLen : 0.0f;
        float dirYY = lenY > 0.0001f ? (dyY / lenY) * handleVisualLen : handleVisualLen;

        float dirZX = lenZ > 0.0001f ? (dxZ / lenZ) * handleVisualLen : -handleVisualLen * 0.707f;
        float dirZY = lenZ > 0.0001f ? (dyZ / lenZ) * handleVisualLen : -handleVisualLen * 0.707f;

        float xTipX = cx + dirXX, xTipY = cy + dirXY;
        float yTipX = cx + dirYX, yTipY = cy + dirYY;
        float zTipX = cx + dirZX, zTipY = cy + dirZY;

        float distX = (ndcX - xTipX) * (ndcX - xTipX) + (ndcY - xTipY) * (ndcY - xTipY);
        float distY = (ndcX - yTipX) * (ndcX - yTipX) + (ndcY - yTipY) * (ndcY - yTipY);
        float distZ = (ndcX - zTipX) * (ndcX - zTipX) + (ndcY - zTipY) * (ndcY - zTipY);

        float distSegX = distToSegment(ndcX, ndcY, cx, cy, xTipX, xTipY);
        float distSegY = distToSegment(ndcX, ndcY, cx, cy, yTipX, yTipY);
        float distSegZ = distToSegment(ndcX, ndcY, cx, cy, zTipX, zTipY);

        float threshold = 0.0015f;

        if (manager.getCurrentGizmoMode() == GizmoMode.ROTATE) {
            float distCenter = (float) Math.sqrt((ndcX - cx) * (ndcX - cx) + (ndcY - cy) * (ndcY - cy));
            if (Math.abs(distCenter - 0.14f) < 0.05f) {
                if (Math.abs(ndcX - cx) < 0.06f) {
                    manager.setActiveGizmoAxis(GizmoAxis.X);
                    System.out.println("[Gizmo] Grabbed RED X Pitch Arc.");
                } else if (Math.abs(ndcY - cy) < 0.06f) {
                    manager.setActiveGizmoAxis(GizmoAxis.Y);
                    System.out.println("[Gizmo] Grabbed GREEN Y Yaw Arc.");
                } else {
                    manager.setActiveGizmoAxis(GizmoAxis.Z);
                    System.out.println("[Gizmo] Grabbed BLUE Z Roll Outer Ring.");
                }
                lastAngle = Math.atan2(ndcY - cy, ndcX - cx);
            }
        } else {
            if (distSegX < 0.03f || distX < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.X);
                System.out.println("[Gizmo] Grabbed RED X Axis Handle.");
            } else if (distSegY < 0.03f || distY < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.Y);
                System.out.println("[Gizmo] Grabbed GREEN Y Axis Handle.");
            } else if (distSegZ < 0.03f || distZ < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.Z);
                System.out.println("[Gizmo] Grabbed BLUE Z Axis Handle.");
            }
        }
    }

    private float distToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        if (dx == 0.0f && dy == 0.0f) {
            return (float) Math.hypot(px - x1, py - y1);
        }
        float t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0f, Math.min(1.0f, t));
        float projX = x1 + t * dx;
        float projY = y1 + t * dy;
        return (float) Math.hypot(px - projX, py - projY);
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        EditorPanelManager activeManager = manager != null ? manager : this.panelManager;
        if (activeManager == null) return false;

        SceneEntity selected = activeManager.getSelectedEntity();
        if (selected == null) return false;

        // 1. Handle Active Inspector Field Drag
        if (activeInspectorField != InspectorField.NONE) {
            float dragSpeedPos = 0.05f;
            float dragSpeedRot = 0.8f;
            float dragSpeedScl = 0.02f;

            switch (activeInspectorField) {
                case POS_X -> selected.transform.posX += (float) dx * dragSpeedPos;
                case POS_Y -> selected.transform.posY -= (float) dy * dragSpeedPos;
                case POS_Z -> selected.transform.posZ += (float) dx * dragSpeedPos;

                case ROT_X -> selected.transform.rotX += (float) dx * dragSpeedRot;
                case ROT_Y -> selected.transform.rotY += (float) dx * dragSpeedRot;
                case ROT_Z -> selected.transform.rotZ += (float) dx * dragSpeedRot;

                case SCL_X -> selected.transform.scaleX = Math.max(0.1f, selected.transform.scaleX + (float) dx * dragSpeedScl);
                case SCL_Y -> selected.transform.scaleY = Math.max(0.1f, selected.transform.scaleY - (float) dy * dragSpeedScl);
                case SCL_Z -> selected.transform.scaleZ = Math.max(0.1f, selected.transform.scaleZ + (float) dx * dragSpeedScl);
                default -> {}
            }
            return true;
        }

        // 2. Handle Active Viewport Gizmo Axis Drag
        if (activeManager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            GizmoMode mode = activeManager.getCurrentGizmoMode();
            GizmoAxis axis = activeManager.getActiveGizmoAxis();

            float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

            float px = selected.transform.posX;
            float py = selected.transform.posY;
            float pz = selected.transform.posZ;

            float[] p0 = activeManager.projectWorldToNdc(px, py, pz);
            float cx = Math.max(-0.50f, Math.min(0.50f, p0[0]));
            float cy = Math.max(-0.40f, Math.min(0.75f, p0[1]));

            if (mode == GizmoMode.TRANSLATE || mode == GizmoMode.SCALE) {
                float axisLen = 0.8f;
                float[] pX = activeManager.projectWorldToNdc(px + axisLen, py, pz);
                float[] pY = activeManager.projectWorldToNdc(px, py + axisLen, pz);
                float[] pZ = activeManager.projectWorldToNdc(px, py, pz + axisLen);

                float dxX = pX[0] - p0[0], dyX = pX[1] - p0[1];
                float dxY = pY[0] - p0[0], dyY = pY[1] - p0[1];
                float dxZ = pZ[0] - p0[0], dyZ = pZ[1] - p0[1];

                float lenX = (float) Math.hypot(dxX, dyX);
                float lenY = (float) Math.hypot(dxY, dyY);
                float lenZ = (float) Math.hypot(dxZ, dyZ);

                float uXX = lenX > 0.0001f ? (dxX / lenX) : 1.0f;
                float uXY = lenX > 0.0001f ? (dyX / lenX) : 0.0f;

                float uYX = lenY > 0.0001f ? (dxY / lenY) : 0.0f;
                float uYY = lenY > 0.0001f ? (dyY / lenY) : 1.0f;

                float uZX = lenZ > 0.0001f ? (dxZ / lenZ) : -0.707f;
                float uZY = lenZ > 0.0001f ? (dyZ / lenZ) : -0.707f;

                float ndcDx = (float) (dx / 1280.0 * 2.0);
                float ndcDy = (float) (-dy / 720.0 * 2.0);

                if (mode == GizmoMode.TRANSLATE) {
                    float speed = 2.5f;
                    switch (axis) {
                        case X -> selected.transform.posX += (ndcDx * uXX + ndcDy * uXY) * speed;
                        case Y -> selected.transform.posY += (ndcDx * uYX + ndcDy * uYY) * speed;
                        case Z -> selected.transform.posZ += (ndcDx * uZX + ndcDy * uZY) * speed;
                        default -> {}
                    }
                } else if (mode == GizmoMode.SCALE) {
                    float speed = 2.5f;
                    switch (axis) {
                        case X -> selected.transform.scaleX = Math.max(0.1f, selected.transform.scaleX + (ndcDx * uXX + ndcDy * uXY) * speed);
                        case Y -> selected.transform.scaleY = Math.max(0.1f, selected.transform.scaleY + (ndcDx * uYX + ndcDy * uYY) * speed);
                        case Z -> selected.transform.scaleZ = Math.max(0.1f, selected.transform.scaleZ + (ndcDx * uZX + ndcDy * uZY) * speed);
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
                    case X -> selected.transform.rotX += (float) deltaAngle * rotSpeed;
                    case Y -> selected.transform.rotY += (float) deltaAngle * rotSpeed;
                    case Z -> selected.transform.rotZ += (float) deltaAngle * rotSpeed;
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

        // Texture Assignment Button
        if (mouseY >= 280.0 && mouseY <= 320.0) {
            List<AssetEntry> assets = manager.getAssetDirectory();
            List<AssetEntry> textures = assets.stream().filter(a -> a.type == EditorPanelManager.AssetType.TEXTURE).toList();
            if (!textures.isEmpty()) {
                AssetEntry nextTex = textures.get(0);
                manager.assignTextureToSelectedEntity(nextTex);
            }
            return;
        }

        // Inspector Field Boxes (POS: ~118-148px, ROT: ~154-184px, SCL: ~190-220px)
        int axis = -1;
        if (mouseX >= 1050.0 && mouseX <= 1115.0) axis = 0; // X
        else if (mouseX >= 1120.0 && mouseX <= 1185.0) axis = 1; // Y
        else if (mouseX >= 1190.0 && mouseX <= 1255.0) axis = 2; // Z

        if (axis != -1) {
            if (mouseY >= 118.0 && mouseY <= 148.0) { // POS
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.POS_X;
                    case 1 -> InspectorField.POS_Y;
                    default -> InspectorField.POS_Z;
                };
                System.out.println("[Inspector] Grabbed Position " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 154.0 && mouseY <= 184.0) { // ROT
                activeInspectorField = switch (axis) {
                    case 0 -> InspectorField.ROT_X;
                    case 1 -> InspectorField.ROT_Y;
                    default -> InspectorField.ROT_Z;
                };
                System.out.println("[Inspector] Grabbed Rotation " + (axis == 0 ? "X" : axis == 1 ? "Y" : "Z") + " Field.");
            } else if (mouseY >= 190.0 && mouseY <= 220.0) { // SCL
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
