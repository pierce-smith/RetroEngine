package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.ui.UIBatchBuilder;
import com.retro.engine.editor.ViewportCameraController;

import java.util.List;

/**
 * GizmoInteractor encapsulating 3D World Projection, Raycasting / Axis Picking,
 * Delta Dragging with Grid & Angle Snapping, and Immediate-Mode 3D Vector Batching.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;
    private static boolean snapEnabled = false;
    private static float gridSnapSize = 0.5f;
    private static float angleSnapStep = 15.0f;

    public static void setSnapEnabled(boolean enable) { snapEnabled = enable; }
    public static boolean isSnapEnabled() { return snapEnabled; }
    public static void toggleSnap() { snapEnabled = !snapEnabled; }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
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

        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, EditorPanelManager manager) {
        if (manager == null) return new float[] { 0.0f, 0.0f };
        return projectWorldToNdc(wx, wy, wz, manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }

    // --- Pick Gizmo Axis Overloads ---

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, float wx, float wy, float wz, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;

        float[] p0 = projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
        double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

        float[] pX = projectWorldToNdc(wx + 1.0f, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pY = projectWorldToNdc(wx, wy + 1.0f, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pZ = projectWorldToNdc(wx, wy, wz + 1.0f, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        double xx = ((pX[0] + 1.0) / 2.0) * 1280.0, xy = ((1.0 - pX[1]) / 2.0) * 720.0;
        double yx = ((pY[0] + 1.0) / 2.0) * 1280.0, yy = ((1.0 - pY[1]) / 2.0) * 720.0;
        double zx = ((pZ[0] + 1.0) / 2.0) * 1280.0, zy = ((1.0 - pZ[1]) / 2.0) * 720.0;

        double distX = pointToSegmentDistance(refX, refY, cx, cy, xx, xy);
        double distY = pointToSegmentDistance(refX, refY, cx, cy, yx, yy);
        double distZ = pointToSegmentDistance(refX, refY, cx, cy, zx, zy);

        double threshold = 35.0;
        if (distX < threshold && distX <= distY && distX <= distZ) return GizmoAxis.X;
        if (distY < threshold && distY <= distX && distY <= distZ) return GizmoAxis.Y;
        if (distZ < threshold && distZ <= distX && distZ <= distY) return GizmoAxis.Z;

        return GizmoAxis.NONE;
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return GizmoAxis.NONE;
        return pickGizmoAxis(refX, refY, selectedEntity, tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, ViewportCameraController cameraController, double camPanX, double camPanY, double camZoom, double camPitch, double camYaw, GizmoMode currentGizmoMode) {
        return pickGizmoAxis(refX, refY, selectedEntity, cameraController, (float) camPanX, (float) camPanY, (float) camZoom, (float) camPitch, (float) camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, SceneManager sceneManager, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;
        if (sceneManager != null) {
            SceneManager.WorldTransform wt = sceneManager.getComputedWorldTransform(selectedEntity);
            return pickGizmoAxis(refX, refY, selectedEntity, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
        }
        return pickGizmoAxis(refX, refY, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, currentGizmoMode);
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, EditorPanelManager manager, GizmoMode currentGizmoMode) {
        if (manager == null) return GizmoAxis.NONE;
        return pickGizmoAxis(refX, refY, selectedEntity, manager.getSceneManager(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f, currentGizmoMode);
    }

    // --- Handle Gizmo Drag Overloads ---

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, float wx, float wy, float wz, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (activeGizmoAxis == GizmoAxis.NONE || selectedEntity == null) return false;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return false;

        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            float[] p0 = projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float axisLen = 1.0f;
            float[] pX = projectWorldToNdc(wx + axisLen, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pY = projectWorldToNdc(wx, wy + axisLen, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZ = projectWorldToNdc(wx, wy, wz + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

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
            float[] p0 = projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
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

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, ViewportCameraController cameraController, double camPanX, double camPanY, double camZoom, double camPitch, double camYaw) {
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, cameraController, (float) camPanX, (float) camPanY, (float) camZoom, (float) camPitch, (float) camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, SceneManager sceneManager, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        if (sceneManager != null) {
            SceneManager.WorldTransform wt = sceneManager.getComputedWorldTransform(selectedEntity);
            return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, wt.px(), wt.py(), wt.pz(), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, EditorPanelManager manager) {
        if (manager == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, manager.getSceneManager(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }

    // --- Render Gizmos Overloads ---

    public static void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        TransformComponent tc = entity.getTransform();
        if (tc == null) return;
        render3DGizmosAtWorldPosition(verts, tc.posX, tc.posY, tc.posZ, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCameraController cameraController, double camPanX, double camPanY, double camZoom, double camPitch, double camYaw) {
        render3DGizmos(verts, entity, mode, activeAxis, cameraController, (float) camPanX, (float) camPanY, (float) camZoom, (float) camPitch, (float) camYaw);
    }

    public static void render3DGizmos(List<Float> verts, SceneEntity entity, SceneManager sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (entity == null) return;
        if (sceneManager != null) {
            SceneManager.WorldTransform wt = sceneManager.getComputedWorldTransform(entity);
            render3DGizmosAtWorldPosition(verts, wt.px(), wt.py(), wt.pz(), mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        } else {
            render3DGizmos(verts, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    public static void render3DGizmosAtWorldPosition(List<Float> verts, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float[] p0 = projectWorldToNdc(px, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float cx = p0[0], cy = p0[1];

        float axisWorldLen = 1.0f;

        float[] pX = projectWorldToNdc(px + axisWorldLen, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pY = projectWorldToNdc(px, py + axisWorldLen, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pZ = projectWorldToNdc(px, py, pz + axisWorldLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

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

    private static double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) return Math.hypot(px - x1, py - y1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return Math.hypot(px - projX, py - projY);
    }
}
