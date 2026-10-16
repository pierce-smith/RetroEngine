package com.retro.engine.editor.delegates;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.ViewportCameraController;
import com.retro.engine.editor.input.GizmoInteractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing 3D transform gizmo modes, active selection axes, transform drag start vectors,
 * camera orbit/pan state tracking, and 3D viewport grid generation.
 */
public class GizmoStateController {

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;

    private float dragStartPx, dragStartPy, dragStartPz;
    private float dragStartRx, dragStartRy, dragStartRz;
    private float dragStartSx, dragStartSy, dragStartSz;

    private float camPanX = 0.0f, camPanY = 0.25f, camZoom = -5.0f, camPitch = 0.0f, camYaw = 0.0f;
    private long lastOrbitTime = 0L;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;

    public GizmoMode getGizmoMode() { return currentGizmoMode; }
    public void setGizmoMode(GizmoMode mode) { this.currentGizmoMode = mode; }

    public GizmoAxis getActiveAxis() { return activeGizmoAxis; }
    public void setActiveAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }

    public float getDragStartPx() { return dragStartPx; }
    public float getDragStartPy() { return dragStartPy; }
    public float getDragStartPz() { return dragStartPz; }
    public float getDragStartRx() { return dragStartRx; }
    public float getDragStartRy() { return dragStartRy; }
    public float getDragStartRz() { return dragStartRz; }
    public float getDragStartSx() { return dragStartSx; }
    public float getDragStartSy() { return dragStartSy; }
    public float getDragStartSz() { return dragStartSz; }

    public float getCamPanX() { return camPanX; }
    public float getCamPanY() { return camPanY; }
    public float getCamZoom() { return camZoom; }
    public float getCamPitch() { return camPitch; }
    public float getCamYaw() { return camYaw; }
    public long getLastOrbitTime() { return lastOrbitTime; }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        if (Math.abs(this.camPitch - pitch) > 0.05f || Math.abs(this.camYaw - yaw) > 0.05f || Math.abs(this.camPanX - panX) > 0.01f || Math.abs(this.camPanY - panY) > 0.01f) {
            this.lastOrbitTime = System.currentTimeMillis();
        }
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    public void captureDragStart(TransformComponent tc) {
        if (tc == null) return;
        this.dragStartPx = tc.posX; this.dragStartPy = tc.posY; this.dragStartPz = tc.posZ;
        this.dragStartRx = tc.rotX; this.dragStartRy = tc.rotY; this.dragStartRz = tc.rotZ;
        this.dragStartSx = tc.scaleX; this.dragStartSy = tc.scaleY; this.dragStartSz = tc.scaleZ;
    }

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() { this.show3DGrid = !this.show3DGrid; cached3DGridVertices = null; }

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
            float rZ = (i == 0) ? 0.2f : 0.4f, gZ = (i == 0) ? 0.2f : 0.4f, bZ = (i == 0) ? 1.0f : 0.5f;
            addGridLine(verts, i, 0, -gridSize, i, 0, gridSize, rZ, gZ, bZ, alpha);

            float rX = (i == 0) ? 1.0f : 0.4f, gX = (i == 0) ? 0.2f : 0.4f, bX = (i == 0) ? 0.2f : 0.5f;
            addGridLine(verts, -gridSize, 0, i, gridSize, 0, i, rX, gX, bX, alpha);
        }
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.2f, 1.0f, 0.2f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public boolean processViewportToolbarClick(float ndcX, float ndcY) {
        if (ndcY >= 0.80f && ndcY <= 0.86f) {
            if (ndcX >= -0.51f && ndcX <= -0.38f) { setGizmoMode(GizmoMode.TRANSLATE); return true; }
            if (ndcX >= -0.37f && ndcX <= -0.25f) { setGizmoMode(GizmoMode.ROTATE); return true; }
            if (ndcX >= -0.24f && ndcX <= -0.11f) { setGizmoMode(GizmoMode.SCALE); return true; }
        }
        return false;
    }

    public void processViewportGizmoClick(double refX, double refY, EditorPanelManager manager) {
        if (manager == null) return;
        SceneEntity selected = manager.getSelectedEntity();
        ViewportCameraController cameraController = manager.getCameraController();

        if (selected != null) {
            SceneManager.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(selected);
            this.activeGizmoAxis = GizmoInteractor.pickGizmoAxis(refX, refY, selected, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
            if (this.activeGizmoAxis != GizmoAxis.NONE) {
                TransformComponent tc = selected.getTransform();
                if (tc != null) {
                    captureDragStart(tc);
                }
                return;
            }
        }

        SceneEntity bestEntity = null;
        double minDistance = 60.0;

        for (SceneEntity entity : manager.getSceneEntities()) {
            TransformComponent tc = entity.getTransform();
            if (tc == null) continue;

            SceneManager.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(entity);
            float[] p0 = GizmoInteractor.projectWorldToNdc(wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
            double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

            double dist = Math.hypot(refX - cx, refY - cy);
            if (dist < minDistance) {
                minDistance = dist;
                bestEntity = entity;
            }
        }

        // Always update selection: selects bestEntity if clicked near an object, or deselects (null) if blank space clicked
        manager.selectEntity(bestEntity);
    }

    public boolean onCursorPos(double rawX, double rawY, EditorPanelManager manager, EditorOverlayManager overlayManager) {
        if (manager == null) return false;

        double refX = (rawX / (double) manager.getWindowWidth()) * 1280.0;
        double refY = (rawY / (double) manager.getWindowHeight()) * 720.0;

        double deltaX = refX - lastMouseX;
        double deltaY = refY - lastMouseY;
        this.lastMouseX = refX;
        this.lastMouseY = refY;

        if (overlayManager != null && overlayManager.isRightMouseDown()) {
            overlayManager.addRightClickDragDistance(Math.hypot(deltaX, deltaY));
            this.lastOrbitTime = System.currentTimeMillis();
        }

        if (activeGizmoAxis != GizmoAxis.NONE && manager.getSelectedEntity() != null) {
            SceneManager.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(manager.getSelectedEntity());
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, manager.getSelectedEntity(), wt.px(), wt.py(), wt.pz(), manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
    }
}
