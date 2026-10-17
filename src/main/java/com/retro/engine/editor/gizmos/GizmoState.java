package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.OverlaySystem;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.Transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing 3D transform gizmo modes, active selection axes, transform drag start vectors,
 * camera orbit/pan state tracking, and 3D viewport grid generation.
 */
public class GizmoState {

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

    public void captureDragStart(Transform tc) {
        if (tc == null) return;
        this.dragStartPx = tc.posX; this.dragStartPy = tc.posY; this.dragStartPz = tc.posZ;
        this.dragStartRx = tc.rotX; this.dragStartRy = tc.rotY; this.dragStartRz = tc.rotZ;
        this.dragStartSx = tc.scaleX; this.dragStartSy = tc.scaleY; this.dragStartSz = tc.scaleZ;
        GizmoInteractor.startDrag(tc);
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
        int gridRadius = 10;
        float gridSpacing = 1.0f;
        float mainAxisAlpha = 0.85f;
        float gridAlpha = 0.25f;

        for (int i = -gridRadius; i <= gridRadius; i++) {
            float pos = i * gridSpacing;
            if (i == 0) {
                // X Axis (Red)
                addGridLine(verts, -gridRadius * gridSpacing, 0.0f, pos, gridRadius * gridSpacing, 0.0f, pos, 0.9f, 0.2f, 0.2f, mainAxisAlpha);
                // Z Axis (Blue)
                addGridLine(verts, pos, 0.0f, -gridRadius * gridSpacing, pos, 0.0f, gridRadius * gridSpacing, 0.2f, 0.4f, 0.9f, mainAxisAlpha);
            } else {
                addGridLine(verts, -gridRadius * gridSpacing, 0.0f, pos, gridRadius * gridSpacing, 0.0f, pos, 0.4f, 0.4f, 0.45f, gridAlpha);
                addGridLine(verts, pos, 0.0f, -gridRadius * gridSpacing, pos, 0.0f, gridRadius * gridSpacing, 0.4f, 0.4f, 0.45f, gridAlpha);
            }
        }
        // Vertical Y Axis line (Green)
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

    public void processViewportGizmoClick(double refX, double refY, EditorWorkspace manager) {
        if (manager == null) return;
        Entity selected = manager.getSelectedEntity();
        ViewportCamera cameraController = manager.getCameraController();

        if (selected != null) {
            SceneWorld.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(selected);
            this.activeGizmoAxis = GizmoSystem.pickGizmoAxis(refX, refY, selected, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
            if (this.activeGizmoAxis != GizmoAxis.NONE) {
                Transform tc = selected.getTransform();
                if (tc != null) {
                    captureDragStart(tc);
                }
                return;
            }
        }

        Entity bestEntity = null;
        double minDistance = 60.0;

        for (Entity entity : manager.getSceneEntities()) {
            Transform tc = entity.getTransform();
            if (tc == null) continue;

            SceneWorld.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(entity);
            float[] p0 = GizmoSystem.projectWorldToNdc(wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
            double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

            double dist = Math.hypot(refX - cx, refY - cy);
            if (dist < minDistance) {
                minDistance = dist;
                bestEntity = entity;
            }
        }

        if (bestEntity != null) {
            manager.selectEntity(bestEntity);
        } else {
            manager.selectEntity(null);
        }
    }

    public boolean onCursorPos(double rawX, double rawY, EditorWorkspace manager, OverlaySystem overlayManager) {
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
            SceneWorld.WorldTransform wt = manager.getSceneManager().getComputedWorldTransform(manager.getSelectedEntity());
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, manager.getSelectedEntity(), wt.px(), wt.py(), wt.pz(), manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
    }
}
