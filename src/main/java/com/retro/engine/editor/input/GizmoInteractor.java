package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;
import com.retro.engine.editor.ui.UIBatchBuilder;
import com.retro.engine.editor.ViewportCameraController;

import java.util.List;

/**
 * GizmoInteractor encapsulating 100% baseline 3D World Projection,
 * Viewport Framing Offsets (0.55x / 0.69y), Raycasting / Axis Picking,
 * Delta Dragging, and Immediate-Mode 3D Vector Batching.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;

    /**
     * Exact 1:1 camera projection math matching MetalBackend.java createCameraViewMatrix()
     * and baseline EditorPanelManager.java projectWorldToNdc().
     */
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

        // Offset into 3D Viewport Frame in UI Canvas Space
        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public static GizmoAxis pickGizmoAxis(double refX, double refY, SceneEntity selectedEntity, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, GizmoMode currentGizmoMode) {
        if (selectedEntity == null) return GizmoAxis.NONE;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return GizmoAxis.NONE;

        float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        double cx = ((p0[0] + 1.0) / 2.0) * 1280.0;
        double cy = ((1.0 - p0[1]) / 2.0) * 720.0;

        float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - refY / 720.0 * 2.0);

        if (currentGizmoMode == GizmoMode.TRANSLATE || currentGizmoMode == GizmoMode.SCALE) {
            float axisLen = 1.0f;
            float[] pX = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pY = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZ = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

            double xTipX = ((pX[0] + 1.0) / 2.0) * 1280.0, xTipY = ((1.0 - pX[1]) / 2.0) * 720.0;
            double yTipX = ((pY[0] + 1.0) / 2.0) * 1280.0, yTipY = ((1.0 - pY[1]) / 2.0) * 720.0;
            double zTipX = ((pZ[0] + 1.0) / 2.0) * 1280.0, zTipY = ((1.0 - pZ[1]) / 2.0) * 720.0;

            double distToX = pointToSegmentDistance(refX, refY, cx, cy, xTipX, xTipY);
            double distToY = pointToSegmentDistance(refX, refY, cx, cy, yTipX, yTipY);
            double distToZ = pointToSegmentDistance(refX, refY, cx, cy, zTipX, zTipY);

            double thresh = 35.0;
            if (distToX < thresh && distToX <= distToY && distToX <= distToZ) return GizmoAxis.X;
            if (distToY < thresh && distToY <= distToX && distToY <= distToZ) return GizmoAxis.Y;
            if (distToZ < thresh && distToZ <= distToX && distToZ <= distToY) return GizmoAxis.Z;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            double currentMouseAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            double distToCenter = Math.hypot(refX - cx, refY - cy);

            float axisLen = 1.0f;
            double hitThreshold = 18.0;

            GizmoAxis grabbed = GizmoAxis.NONE;
            double bestDist = hitThreshold;

            double maxInnerScreenDist = 0.0;

            float[] pXTip = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pYTip = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZTip = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pXTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pXTip[1]) / 2.0 * 720.0) - cy));
            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pYTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pYTip[1]) / 2.0 * 720.0) - cy));
            maxInnerScreenDist = Math.max(maxInnerScreenDist, Math.hypot((((pZTip[0] + 1.0) / 2.0) * 1280.0) - cx, ((1.0 - pZTip[1]) / 2.0 * 720.0) - cy));

            double dynamicZRadius = maxInnerScreenDist * 1.22;
            double distToZRing = Math.abs(distToCenter - dynamicZRadius);

            if (distToZRing < bestDist) {
                grabbed = GizmoAxis.Z;
                bestDist = distToZRing;
            }

            int numSamples = 64;
            for (int i = 0; i < numSamples; i++) {
                double angle = (i * 2.0 * Math.PI) / numSamples;
                float cosA = (float) Math.cos(angle) * axisLen;
                float sinA = (float) Math.sin(angle) * axisLen;

                float[] pXSample = projectWorldToNdc(tc.posX, tc.posY + cosA, tc.posZ + sinA, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                double sXx = ((pXSample[0] + 1.0) / 2.0) * 1280.0;
                double sXy = ((1.0 - pXSample[1]) / 2.0) * 720.0;
                double distToXSample = Math.hypot(refX - sXx, refY - sXy);

                if (distToXSample < bestDist) {
                    grabbed = GizmoAxis.X;
                    bestDist = distToXSample;
                }

                float[] pYSample = projectWorldToNdc(tc.posX + cosA, tc.posY, tc.posZ + sinA, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                double sYx = ((pYSample[0] + 1.0) / 2.0) * 1280.0;
                double sYy = ((1.0 - pYSample[1]) / 2.0) * 720.0;
                double distToYSample = Math.hypot(refX - sYx, refY - sYy);

                if (distToYSample < bestDist) {
                    grabbed = GizmoAxis.Y;
                    bestDist = distToYSample;
                }
            }

            if (grabbed != GizmoAxis.NONE) {
                lastRotateAngle = currentMouseAngle;
                return grabbed;
            }
        }
        return GizmoAxis.NONE;
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, SceneEntity selectedEntity, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (activeGizmoAxis == GizmoAxis.NONE || selectedEntity == null) return false;
        TransformComponent tc = selectedEntity.getTransform();
        if (tc == null) return false;

        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float axisLen = 1.0f;
            float[] pX = projectWorldToNdc(tc.posX + axisLen, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pY = projectWorldToNdc(tc.posX, tc.posY + axisLen, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZ = projectWorldToNdc(tc.posX, tc.posY, tc.posZ + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

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

            if (activeGizmoAxis == GizmoAxis.X) tc.posX += (ndcDx * uXX + ndcDy * uXY) * 10.0f;
            else if (activeGizmoAxis == GizmoAxis.Y) tc.posY += (ndcDx * uYX + ndcDy * uYY) * 10.0f;
            else if (activeGizmoAxis == GizmoAxis.Z) tc.posZ += (ndcDx * uZX + ndcDy * uZY) * 10.0f;
            return true;
        } else if (currentGizmoMode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            if (activeGizmoAxis == GizmoAxis.X) tc.scaleX = Math.max(0.01f, tc.scaleX + ds);
            else if (activeGizmoAxis == GizmoAxis.Y) tc.scaleY = Math.max(0.01f, tc.scaleY + ds);
            else if (activeGizmoAxis == GizmoAxis.Z) tc.scaleZ = Math.max(0.01f, tc.scaleZ + ds);
            return true;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            float[] p0 = projectWorldToNdc(tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
            double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
            lastRotateAngle = currentAngle;

            if (activeGizmoAxis == GizmoAxis.X) tc.rotX = (tc.rotX + (float) angleDelta) % 360.0f;
            else if (activeGizmoAxis == GizmoAxis.Y) tc.rotY = (tc.rotY + (float) angleDelta) % 360.0f;
            else if (activeGizmoAxis == GizmoAxis.Z) tc.rotZ = (tc.rotZ + (float) angleDelta) % 360.0f;
            return true;
        }
        return false;
    }

    public static void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCameraController cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        TransformComponent tc = entity.getTransform();
        if (tc == null) return;

        float px = tc.posX, py = tc.posY, pz = tc.posZ;

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
