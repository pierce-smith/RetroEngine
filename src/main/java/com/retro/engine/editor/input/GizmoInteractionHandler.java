package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.GizmoAxis;
import com.retro.engine.editor.EditorPanelManager.GizmoMode;

/**
 * GizmoInteractionHandler handles 3D Viewport transform gizmo picking,
 * axis handle hit detection, and mouse drag updates across Translate, Rotate, and Scale modes.
 */
public class GizmoInteractionHandler {

    private double lastRotateAngle = 0.0;

    public boolean processViewportGizmoClick(double mouseX, double mouseY, EditorPanelManager manager, TransformUndoTracker undoTracker) {
        SceneEntity selected = manager.getSelectedEntity();
        if (selected == null) return false;

        TransformComponent tc = selected.getTransform();
        if (tc == null) return false;

        float px = tc.posX, py = tc.posY, pz = tc.posZ;
        float[] p0 = manager.projectWorldToNdc(px, py, pz);
        float cx = p0[0], cy = p0[1];

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

            GizmoAxis grabbed = GizmoAxis.NONE;
            if (distToX < threshold && distToX <= distToY && distToX <= distToZ) grabbed = GizmoAxis.X;
            else if (distToY < threshold && distToY <= distToX && distToY <= distToZ) grabbed = GizmoAxis.Y;
            else if (distToZ < threshold && distToZ <= distToX && distToZ <= distToY) grabbed = GizmoAxis.Z;

            if (grabbed != GizmoAxis.NONE) {
                manager.setActiveGizmoAxis(grabbed);
                undoTracker.captureStart(selected);
                System.out.println("[Gizmo] Grabbed " + grabbed + " Axis Handle.");
                return true;
            }
        } else if (mode == GizmoMode.ROTATE) {
            double rxNorm = 0.05, ryNorm = 0.14;
            double dxNormX = (ndcX - cx) / rxNorm;
            double dyNormX = (ndcY - cy) / ryNorm;
            double distEllipseX = Math.abs(Math.hypot(dxNormX, dyNormX) - 1.0);

            double gxNorm = 0.14, gyNorm = 0.05;
            double dxNormY = (ndcX - cx) / gxNorm;
            double dyNormY = (ndcY - cy) / gyNorm;
            double distEllipseY = Math.abs(Math.hypot(dxNormY, dyNormY) - 1.0);

            double distToCenter = Math.hypot(ndcX - cx, ndcY - cy);
            double distCircleZ = Math.abs(distToCenter - 0.14);

            double thresh = 0.35;
            GizmoAxis grabbed = GizmoAxis.NONE;

            if (distEllipseX < thresh && distEllipseX <= distEllipseY && distEllipseX <= distCircleZ) grabbed = GizmoAxis.X;
            else if (distEllipseY < thresh && distEllipseY <= distEllipseX && distEllipseY <= distCircleZ) grabbed = GizmoAxis.Y;
            else if (distCircleZ < 0.05) grabbed = GizmoAxis.Z;

            if (grabbed != GizmoAxis.NONE) {
                manager.setActiveGizmoAxis(grabbed);
                undoTracker.captureStart(selected);
                lastRotateAngle = Math.atan2(ndcY - cy, ndcX - cx);
                System.out.println("[Gizmo] Grabbed Rotate " + grabbed + " Arc.");
                return true;
            }
        }
        return false;
    }

    public boolean handleGizmoDrag(double mouseX, double mouseY, double deltaX, double deltaY, EditorPanelManager manager) {
        GizmoAxis axis = manager.getActiveGizmoAxis();
        if (axis == GizmoAxis.NONE) return false;

        SceneEntity selected = manager.getSelectedEntity();
        if (selected == null) return false;

        TransformComponent tc = selected.getTransform();
        if (tc == null) return false;

        GizmoMode mode = manager.getCurrentGizmoMode();

        if (mode == GizmoMode.TRANSLATE) {
            float px = tc.posX, py = tc.posY, pz = tc.posZ;
            float axisWorldLen = 1.2f;
            float[] p0 = manager.projectWorldToNdc(px, py, pz);
            float[] pX = manager.projectWorldToNdc(px + axisWorldLen, py, pz);
            float[] pY = manager.projectWorldToNdc(px, py + axisWorldLen, pz);
            float[] pZ = manager.projectWorldToNdc(px, py, pz + axisWorldLen);

            float dirXX = pX[0] - p0[0], dirXY = pX[1] - p0[1];
            float dirYX = pY[0] - p0[0], dirYY = pY[1] - p0[1];
            float dirZX = pZ[0] - p0[0], dirZY = pZ[1] - p0[1];

            float lenX = (float) Math.hypot(dirXX, dirXY);
            float lenY = (float) Math.hypot(dirYX, dirYY);
            float lenZ = (float) Math.hypot(dirZX, dirZY);

            float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
            float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

            float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
            float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

            float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
            float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

            float ndcDx = (float) (deltaX / 1280.0 * 2.0);
            float ndcDy = (float) (-deltaY / 720.0 * 2.0);

            float projAmount = 0.0f;
            if (axis == GizmoAxis.X) {
                projAmount = ndcDx * uXX + ndcDy * uXY;
                tc.posX += projAmount * 12.0f;
            } else if (axis == GizmoAxis.Y) {
                projAmount = ndcDx * uYX + ndcDy * uYY;
                tc.posY += projAmount * 12.0f;
            } else if (axis == GizmoAxis.Z) {
                projAmount = ndcDx * uZX + ndcDy * uZY;
                tc.posZ += projAmount * 12.0f;
            }
            return true;
        } else if (mode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;

            if (axis == GizmoAxis.X) tc.scaleX = Math.max(0.01f, tc.scaleX + ds);
            else if (axis == GizmoAxis.Y) tc.scaleY = Math.max(0.01f, tc.scaleY + ds);
            else if (axis == GizmoAxis.Z) tc.scaleZ = Math.max(0.01f, tc.scaleZ + ds);
            return true;
        } else if (mode == GizmoMode.ROTATE) {
            float[] p0 = manager.projectWorldToNdc(tc.posX, tc.posY, tc.posZ);
            float cx = p0[0], cy = p0[1];
            float ndcX = (float) (mouseX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - mouseY / 720.0 * 2.0);

            double currentAngle = Math.atan2(ndcY - cy, ndcX - cx);
            double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
            lastRotateAngle = currentAngle;

            if (axis == GizmoAxis.X) tc.rotX = (tc.rotX + (float) angleDelta) % 360.0f;
            else if (axis == GizmoAxis.Y) tc.rotY = (tc.rotY + (float) angleDelta) % 360.0f;
            else if (axis == GizmoAxis.Z) tc.rotZ = (tc.rotZ + (float) angleDelta) % 360.0f;
            return true;
        }
        return false;
    }

    private double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
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
