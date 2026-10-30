package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;

/**
 * Pure Mathematical Delegate for 3D World-to-NDC Projection,
 * Camera View-Space Transformations, and Point-to-Segment Distance Metrics.
 */
public class GizmoMath {

    private static final ThreadLocal<float[]> VIEW_SCRATCH = ThreadLocal.withInitial(() -> new float[16]);

    public static float[] projectWorldToNdc(float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float aspect = (cameraController != null) ? cameraController.getSceneAspect() : 712.0f / 488.0f;
        float fov = (float) Math.toRadians(cameraController != null ? cameraController.getFov() : 50.0f);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float vx, vy, vz;
        if (cameraController != null) {
            // Same view the scene renders with (a Play camera entity's inverse transform, or the editor orbit)
            float[] v = VIEW_SCRATCH.get();
            cameraController.computeViewMatrix(v);
            vx = v[0] * wx + v[4] * wy + v[8] * wz + v[12];
            vy = v[1] * wx + v[5] * wy + v[9] * wz + v[13];
            vz = v[2] * wx + v[6] * wy + v[10] * wz + v[14];
        } else {
            float radYaw = (float) Math.toRadians(camYaw);
            float radPitch = (float) Math.toRadians(camPitch);
            float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
            float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
            vx = cy * wx - sy * wz + camPanX;
            vy = sy * sp * wx + cp * wy + cy * sp * wz + camPanY;
            vz = sy * cp * wx - sp * wy + cy * cp * wz + camZoom;
        }

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

    /** View-space depth of a world point along the camera's forward axis (same view as projectWorldToNdc); <= 0 is behind it. */
    public static float viewDepth(float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (cameraController != null) {
            float[] v = VIEW_SCRATCH.get();
            cameraController.computeViewMatrix(v);
            return -(v[2] * wx + v[6] * wy + v[10] * wz + v[14]);
        }
        float radYaw = (float) Math.toRadians(camYaw), radPitch = (float) Math.toRadians(camPitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        return -(sy * cp * wx - sp * wy + cy * cp * wz + camZoom);
    }

    public static float[] projectWorldToNdc(float wx, float wy, float wz, EditorWorkspace manager) {
        if (manager == null) return new float[] { 0.0f, 0.0f };
        return projectWorldToNdc(wx, wy, wz, manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }

    /**
     * World position the transform gizmo is planted at: the bottom-center of the entity's drawn mesh bounds (so models
     * whose geometry sits away from their pivot still get the gizmo on their base), else the entity's world pivot.
     * The bounds go through the same model matrix the backends render with.
     */
    public static float[] computeGizmoAnchor(Entity entity, SceneWorld sceneManager) {
        if (entity == null) return new float[] { 0.0f, 0.0f, 0.0f };
        SceneWorld.WorldTransform wt;
        if (sceneManager != null) {
            wt = sceneManager.getComputedWorldTransform(entity);
        } else {
            var tc = entity.getTransform();
            if (tc == null) return new float[] { 0.0f, 0.0f, 0.0f };
            wt = new SceneWorld.WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }
        MeshFilter mf = entity.getComponent(MeshFilter.class);
        float[] b = (mf != null) ? mf.getDrawnLocalBounds() : null;
        if (b == null) return new float[] { wt.px(), wt.py(), wt.pz() };

        float[] m = new float[9];
        computeModelBasis(wt.rx(), wt.ry(), wt.rz(), wt.sx(), wt.sy(), wt.sz(), m);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            float lx = (corner & 1) != 0 ? b[3] : b[0];
            float ly = (corner & 2) != 0 ? b[4] : b[1];
            float lz = (corner & 4) != 0 ? b[5] : b[2];
            float x = m[0] * lx + m[3] * ly + m[6] * lz;
            float y = m[1] * lx + m[4] * ly + m[7] * lz;
            float z = m[2] * lx + m[5] * ly + m[8] * lz;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return new float[] { wt.px() + (minX + maxX) * 0.5f, wt.py() + minY, wt.pz() + (minZ + maxZ) * 0.5f };
    }

    /**
     * Column-major 3x3 rotation-scale basis R * S (out[0..2] = image of local X, out[3..5] = local Y, out[6..8] = local Z),
     * the same model matrix MetalUniformStream.createModelMatrix renders with.
     */
    static void computeModelBasis(float rx, float ry, float rz, float sx, float sy, float sz, float[] out) {
        float radX = (float) Math.toRadians(rx), radY = (float) Math.toRadians(ry), radZ = (float) Math.toRadians(rz);
        float cx = (float) Math.cos(radX), sxs = (float) Math.sin(radX);
        float cy = (float) Math.cos(radY), sys = (float) Math.sin(radY);
        float cz = (float) Math.cos(radZ), szs = (float) Math.sin(radZ);

        out[0] = cy * cz * sx;                   out[1] = -cy * szs * sx;                  out[2] = sys * sx;
        out[3] = (cx * szs + sxs * sys * cz) * sy; out[4] = (cx * cz - sxs * sys * szs) * sy; out[5] = -sxs * cy * sy;
        out[6] = (sxs * szs - cx * sys * cz) * sz; out[7] = (sxs * cz + cx * sys * szs) * sz; out[8] = cx * cy * sz;
    }

    /**
     * World-space displacement that moves a point at (wx, wy, wz) by (dUiX, dUiY) in Scene design-space NDC, staying in
     * the camera's view plane (inverse of {@link #projectWorldToNdc} at that point's depth).
     */
    public static float[] screenPlaneDelta(float wx, float wy, float wz, float dUiX, float dUiY, ViewportCamera cameraController,
                                           float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float aspect = (cameraController != null) ? cameraController.getSceneAspect() : 712.0f / 488.0f;
        float fov = (float) Math.toRadians(cameraController != null ? cameraController.getFov() : 50.0f);
        float f = (float) (1.0 / Math.tan(fov / 2.0));
        float p00 = f / aspect, p11 = f;

        // Rows of the view rotation: camera right, up and (negated) forward expressed in world space
        float rX, rY, rZ, uX, uY, uZ, depth;
        if (cameraController != null) {
            float[] v = VIEW_SCRATCH.get();
            cameraController.computeViewMatrix(v);
            rX = v[0]; rY = v[4]; rZ = v[8];
            uX = v[1]; uY = v[5]; uZ = v[9];
            depth = -(v[2] * wx + v[6] * wy + v[10] * wz + v[14]);
        } else {
            float radYaw = (float) Math.toRadians(camYaw), radPitch = (float) Math.toRadians(camPitch);
            float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
            float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
            rX = cy; rY = 0.0f; rZ = -sy;
            uX = sy * sp; uY = cp; uZ = cy * sp;
            depth = -(sy * cp * wx - sp * wy + cy * cp * wz + camZoom);
        }
        depth = Math.max(depth, 0.001f);

        float viewDx = (dUiX / 0.55f) * depth / p00;
        float viewDy = (dUiY / 0.69f) * depth / p11;
        return new float[] { rX * viewDx + uX * viewDy, rY * viewDx + uY * viewDy, rZ * viewDx + uZ * viewDy };
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
