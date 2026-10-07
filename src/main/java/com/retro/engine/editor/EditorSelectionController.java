package com.retro.engine.editor;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

/**
 * EditorSelectionController handles panel region hit-testing, input routing, and 3D Viewport Transform Gizmos:
 * - Direct 3D camera projection for gizmo base rooting.
 * - Generous grab hitboxes for Translate, Scale, and Rotate handles.
 * - Angular rotation tracking around the gizmo anchor center.
 */
public class EditorSelectionController {

    public enum PanelType {
        TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, NONE
    }

    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;

    private EditorPanelManager panelManager;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private double lastAngle = 0.0;

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
        // Toolbar region: X 307px to 576px, Y 72px to 115px
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

        // Convert mouse pixel coordinates to NDC
        float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

        // Project Entity World Base Position to Viewport NDC space
        float[] anchorNdc = manager.projectWorldToNdc(selected.transform.posX, selected.transform.posY, selected.transform.posZ);
        float cx = Math.max(-0.50f, Math.min(0.50f, anchorNdc[0]));
        float cy = Math.max(-0.40f, Math.min(0.75f, anchorNdc[1]));

        float len = 0.16f;

        // Handle Endpoint Positions
        float xX = cx + len, xY = cy;
        float yX = cx, yY = cy + len;
        float zX = cx - len * 0.707f, zY = cy - len * 0.707f;

        float distX = (ndcX - xX) * (ndcX - xX) + (ndcY - xY) * (ndcY - xY);
        float distY = (ndcX - yX) * (ndcX - yX) + (ndcY - yY) * (ndcY - yY);
        float distZ = (ndcX - zX) * (ndcX - zX) + (ndcY - zY) * (ndcY - zY);

        float threshold = 0.012f; // Generous grab radius

        if (manager.getCurrentGizmoMode() == GizmoMode.ROTATE) {
            float distCenter = (float) Math.sqrt((ndcX - cx) * (ndcX - cx) + (ndcY - cy) * (ndcY - cy));
            if (Math.abs(distCenter - 0.15f) < 0.05f) {
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
            if (distX < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.X);
                System.out.println("[Gizmo] Grabbed RED X Axis Handle.");
            } else if (distY < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.Y);
                System.out.println("[Gizmo] Grabbed GREEN Y Axis Handle.");
            } else if (distZ < threshold) {
                manager.setActiveGizmoAxis(GizmoAxis.Z);
                System.out.println("[Gizmo] Grabbed BLUE Z Axis Handle.");
            }
        }
    }

    public boolean onCursorPos(double mouseX, double mouseY, EditorPanelManager manager) {
        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        EditorPanelManager activeManager = manager != null ? manager : this.panelManager;
        if (activeManager != null && activeManager.getActiveGizmoAxis() != GizmoAxis.NONE) {
            SceneEntity selected = activeManager.getSelectedEntity();
            if (selected != null) {
                GizmoMode mode = activeManager.getCurrentGizmoMode();
                GizmoAxis axis = activeManager.getActiveGizmoAxis();

                float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
                float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

                float[] anchorNdc = activeManager.projectWorldToNdc(selected.transform.posX, selected.transform.posY, selected.transform.posZ);
                float cx = Math.max(-0.50f, Math.min(0.50f, anchorNdc[0]));
                float cy = Math.max(-0.40f, Math.min(0.75f, anchorNdc[1]));

                if (mode == GizmoMode.TRANSLATE) {
                    float speed = 0.02f;
                    switch (axis) {
                        case X -> selected.transform.posX += (float) dx * speed;
                        case Y -> selected.transform.posY -= (float) dy * speed;
                        case Z -> selected.transform.posZ += (float) (dx - dy) * speed;
                        default -> {}
                    }
                } else if (mode == GizmoMode.SCALE) {
                    float speed = 0.02f;
                    switch (axis) {
                        case X -> selected.transform.scaleX = Math.max(0.1f, selected.transform.scaleX + (float) dx * speed);
                        case Y -> selected.transform.scaleY = Math.max(0.1f, selected.transform.scaleY - (float) dy * speed);
                        case Z -> selected.transform.scaleZ = Math.max(0.1f, selected.transform.scaleZ + (float) (dx - dy) * speed);
                        default -> {}
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
                return true; // Handled by Gizmo!
            }
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

        if (mouseY >= 260.0 && mouseY <= 290.0) {
            List<AssetEntry> assets = manager.getAssetDirectory();
            List<AssetEntry> textures = assets.stream().filter(a -> a.type == EditorPanelManager.AssetType.TEXTURE).toList();
            if (!textures.isEmpty()) {
                AssetEntry nextTex = textures.get(0);
                manager.assignTextureToSelectedEntity(nextTex);
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
