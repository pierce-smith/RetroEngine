package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;

import java.util.List;

/**
 * Modularized GizmoSystem acting as a Backwards-Compatible Delegation Bridge.
 * Forwards calls to GizmoMath (projections), GizmoInteractor (raycasting & dragging),
 * and GizmoRenderer (immediate-mode UI vector batching).
 */
public class GizmoSystem {

    public static void setSnapEnabled(boolean enable) { GizmoInteractor.setSnapEnabled(enable); }
    public static boolean isSnapEnabled() { return GizmoInteractor.isSnapEnabled(); }
    public static void toggleSnap() { GizmoInteractor.toggleSnap(); }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        return GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, EditorWorkspace manager) {
        return GizmoMath.projectWorldToNdc(wx, wy, wz, manager);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        return GizmoInteractor.pickGizmoAxis(refX, refY, selectedEntity, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        return GizmoInteractor.pickGizmoAxis(refX, refY, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, SceneWorld sceneManager, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        return GizmoInteractor.pickGizmoAxis(refX, refY, selectedEntity, sceneManager, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, EditorWorkspace manager, GizmoMode currentGizmoMode) {
        return GizmoInteractor.pickGizmoAxis(refX, refY, selectedEntity, manager, currentGizmoMode);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, SceneWorld sceneManager, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, sceneManager, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, EditorWorkspace manager) {
        return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, manager);
    }

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmos(buffer, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmos(verts, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmos(buffer, entity, sceneManager, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmos(verts, entity, sceneManager, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmosAtWorldPosition(UIGeometryBatch.Buffer buffer, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmosAtWorldPosition(buffer, px, py, pz, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmosAtWorldPosition(List<Float> verts, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        GizmoRenderer.render3DGizmosAtWorldPosition(verts, px, py, pz, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }
}
