package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.transform.Transform;

/**
 * Single-Responsibility Interactor Delegate for 3D Gizmo Raycasting / Axis Picking,
 * Screen-to-World Mouse Displacement Deltas, and Grid / Angle / Scale Snapping.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;
    private static boolean isRotateStart = true;
    private static boolean snapEnabled = true;     // Snapping ENABLED by default
    private static float gridSnapSize = 0.5f;     // 0.5u translation steps
    private static float angleSnapStep = 15.0f;    // 15.0 deg rotation steps
    private static float scaleSnapStep = 0.25f;    // 0.25x scale steps

    // Unquantized accumulators for smooth continuous snapping without dropping deltas
    private static float startPx, startPy, startPz;
    private static float startRx, startRy, startRz;
    private static float startSx, startSy, startSz;
    private static double accumDragX = 0.0;
    private static double accumDragY = 0.0;
    private static double accumDragZ = 0.0;

    public static void setSnapEnabled(boolean enable) { snapEnabled = enable; }
    public static boolean isSnapEnabled() { return snapEnabled; }
    public static void toggleSnap() { snapEnabled = !snapEnabled; }

    public static float getGridSnapSize() { return gridSnapSize; }
    public static void setGridSnapSize(float size) { gridSnapSize = Math.max(0.05f, size); }

    public static float getAngleSnapStep() { return angleSnapStep; }
    public static void setAngleSnapStep(float step) { angleSnapStep = Math.max(1.0f, step); }

    public static float getScaleSnapStep() { return scaleSnapStep; }
    public static void setScaleSnapStep(float step) { scaleSnapStep = Math.max(0.05f, step); }

    public static void resetAccumulatedDrag() {
        accumDragX = 0.0;
        accumDragY = 0.0;
        accumDragZ = 0.0;
        lastRotateAngle = 0.0;
        isRotateStart = true;
    }

    public static void startDrag(Transform tc) {
        if (tc != null) {
            startPx = tc.posX; startPy = tc.posY; startPz = tc.posZ;
            startRx = tc.rotX; startRy = tc.rotY; startRz = tc.rotZ;
            startSx = tc.scaleX; startSy = tc.scaleY; startSz = tc.scaleZ;
        }
        resetAccumulatedDrag();
    }

    public static void captureDragStart(Transform tc) {
        startDrag(tc);
    }

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

            if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += moveX;
                if (snapEnabled) tc.posX = startPx + Math.round((float) accumDragX / gridSnapSize) * gridSnapSize;
                else tc.posX = startPx + (float) accumDragX;
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += moveY;
                if (snapEnabled) tc.posY = startPy + Math.round((float) accumDragY / gridSnapSize) * gridSnapSize;
                else tc.posY = startPy + (float) accumDragY;
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += moveZ;
                if (snapEnabled) tc.posZ = startPz + Math.round((float) accumDragZ / gridSnapSize) * gridSnapSize;
                else tc.posZ = startPz + (float) accumDragZ;
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += ds;
                if (snapEnabled) tc.scaleX = Math.max(0.01f, startSx + Math.round((float) accumDragX / scaleSnapStep) * scaleSnapStep);
                else tc.scaleX = Math.max(0.01f, startSx + (float) accumDragX);
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += ds;
                if (snapEnabled) tc.scaleY = Math.max(0.01f, startSy + Math.round((float) accumDragY / scaleSnapStep) * scaleSnapStep);
                else tc.scaleY = Math.max(0.01f, startSy + (float) accumDragY);
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += ds;
                if (snapEnabled) tc.scaleZ = Math.max(0.01f, startSz + Math.round((float) accumDragZ / scaleSnapStep) * scaleSnapStep);
                else tc.scaleZ = Math.max(0.01f, startSz + (float) accumDragZ);
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
            double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            if (isRotateStart) {
                lastRotateAngle = currentAngle;
                isRotateStart = false;
                return true;
            }
            double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
            lastRotateAngle = currentAngle;

            if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += angleDelta;
                if (snapEnabled) tc.rotX = (startRx + Math.round((float) accumDragX / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotX = (startRx + (float) accumDragX) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += angleDelta;
                if (snapEnabled) tc.rotY = (startRy + Math.round((float) accumDragY / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotY = (startRy + (float) accumDragY) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += angleDelta;
                if (snapEnabled) tc.rotZ = (startRz + Math.round((float) accumDragZ / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotZ = (startRz + (float) accumDragZ) % 360.0f;
            }
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
