package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.Transform;

/**
 * Single-Responsibility Interactor Delegate for 3D Gizmo Raycasting / Axis Picking,
 * Screen-to-World Mouse Displacement Deltas, and Grid / Angle Snapping.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;
    private static boolean snapEnabled = false;
    private static float gridSnapSize = 0.5f;
    private static float angleSnapStep = 15.0f;

    public static void setSnapEnabled(boolean enable) { snapEnabled = enable; }
    public static boolean isSnapEnabled() { return snapEnabled; }
    public static void toggleSnap() { snapEnabled = !snapEnabled; }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;

        float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
        double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

        float[] pX = GizmoMath.projectWorldToNdc(wx + 1.0f, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pY = GizmoMath.projectWorldToNdc(wx, wy + 1.0f, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pZ = GizmoMath.projectWorldToNdc(wx, wy, wz + 1.0f, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        double xx = ((pX[0] + 1.0) / 2.0) * 1280.0, xy = ((1.0 - pX[1]) / 2.0) * 720.0;
        double yx = ((pY[0] + 1.0) / 2.0) * 1280.0, yy = ((1.0 - pY[1]) / 2.0) * 720.0;
        double zx = ((pZ[0] + 1.0) / 2.0) * 1280.0, zy = ((1.0 - pZ[1]) / 2.0) * 720.0;

        double distX = GizmoMath.pointToSegmentDistance(refX, refY, cx, cy, xx, xy);
        double distY = GizmoMath.pointToSegmentDistance(refX, refY, cx, cy, yx, yy);
        double distZ = GizmoMath.pointToSegmentDistance(refX, refY, cx, cy, zx, zy);

        double threshold = 35.0;
        if (distX < threshold && distX <= distY && distX <= distZ) return GizmoAxis.X;
        if (distY < threshold && distY <= distX && distY <= distZ) return GizmoAxis.Y;
        if (distZ < threshold && distZ <= distX && distZ <= distY) return GizmoAxis.Z;

        return GizmoAxis.NONE;
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;
        Transform tc = selectedEntity.getTransform();
        if (tc == null) return GizmoAxis.NONE;
        return pickGizmoAxis(refX, refY, selectedEntity, tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, SceneWorld sceneManager, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;
        if (sceneManager != null) {
            SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(selectedEntity);
            return pickGizmoAxis(refX, refY, selectedEntity, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
        }
        return pickGizmoAxis(refX, refY, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, Entity selectedEntity, EditorWorkspace manager, GizmoMode currentGizmoMode) {
        if (manager == null) return GizmoAxis.NONE;
        return pickGizmoAxis(refX, refY, selectedEntity, manager.getSceneManager(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f, currentGizmoMode);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (activeGizmoAxis == GizmoAxis.NONE || selectedEntity == null) return false;
        Transform tc = selectedEntity.getTransform();
        if (tc == null) return false;

        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float axisLen = 1.0f;
            float[] pX = GizmoMath.projectWorldToNdc(wx + axisLen, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pY = GizmoMath.projectWorldToNdc(wx, wy + axisLen, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZ = GizmoMath.projectWorldToNdc(wx, wy, wz + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

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

            float moveX = (ndcDx * uXX + ndcDy * uXY) * 10.0f;
            float moveY = (ndcDx * uYX + ndcDy * uYY) * 10.0f;
            float moveZ = (ndcDx * uZX + ndcDy * uZY) * 10.0f;

            if (snapEnabled) {
                if (activeGizmoAxis == GizmoAxis.X) tc.posX = Math.round((tc.posX + moveX) / gridSnapSize) * gridSnapSize;
                else if (activeGizmoAxis == GizmoAxis.Y) tc.posY = Math.round((tc.posY + moveY) / gridSnapSize) * gridSnapSize;
                else if (activeGizmoAxis == GizmoAxis.Z) tc.posZ = Math.round((tc.posZ + moveZ) / gridSnapSize) * gridSnapSize;
            } else {
                if (activeGizmoAxis == GizmoAxis.X) tc.posX += moveX;
                else if (activeGizmoAxis == GizmoAxis.Y) tc.posY += moveY;
                else if (activeGizmoAxis == GizmoAxis.Z) tc.posZ += moveZ;
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            if (activeGizmoAxis == GizmoAxis.X) tc.scaleX = Math.max(0.01f, tc.scaleX + ds);
            else if (activeGizmoAxis == GizmoAxis.Y) tc.scaleY = Math.max(0.01f, tc.scaleY + ds);
            else if (activeGizmoAxis == GizmoAxis.Z) tc.scaleZ = Math.max(0.01f, tc.scaleZ + ds);
            return true;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
            double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
            lastRotateAngle = currentAngle;

            if (snapEnabled) {
                angleDelta = Math.round(angleDelta / angleSnapStep) * angleSnapStep;
            }

            if (activeGizmoAxis == GizmoAxis.X) tc.rotX = (tc.rotX + (float) angleDelta) % 360.0f;
            else if (activeGizmoAxis == GizmoAxis.Y) tc.rotY = (tc.rotY + (float) angleDelta) % 360.0f;
            else if (activeGizmoAxis == GizmoAxis.Z) tc.rotZ = (tc.rotZ + (float) angleDelta) % 360.0f;
            return true;
        }
        return false;
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        Transform tc = selectedEntity.getTransform();
        if (tc == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, SceneWorld sceneManager, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        if (sceneManager != null) {
            SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(selectedEntity);
            return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, EditorWorkspace manager) {
        if (manager == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, manager.getSceneManager(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }
}
