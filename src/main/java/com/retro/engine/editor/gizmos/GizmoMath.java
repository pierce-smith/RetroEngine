package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;

/**
 * Pure Mathematical Delegate for 3D World-to-NDC Projection,
 * Camera View-Space Transformations, and Point-to-Segment Distance Metrics.
 */
public class GizmoMath {

    public static float[] projectWorldToNdc(float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
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

        assert (clipW != 0.0f) : "[FATAL] Division by zero during camera view-space transformation";
        assert (!Float.isNaN(ndcX) && !Float.isInfinite(ndcX) && !Float.isNaN(ndcY) && !Float.isInfinite(ndcY)) : "[FATAL] Invalid NDC coordinate produced during gizmo unprojection";

        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, EditorWorkspace manager) {
        if (manager == null) return new float[] { 0.0f, 0.0f };
        return projectWorldToNdc(wx, wy, wz, manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }

    public static double pointToSegmentDistance(double px, double py, double x1, double y1, double x2, double y2) {
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
