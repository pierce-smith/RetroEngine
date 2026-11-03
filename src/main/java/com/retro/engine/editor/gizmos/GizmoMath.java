package com.retro.engine.editor.gizmos;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ViewportCamera;

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
        float[] b = new float[6];
        computeWorldBounds(entity, sceneManager, b);
        return new float[] { (b[0] + b[3]) * 0.5f, b[1], (b[2] + b[5]) * 0.5f };
    }

    /**
     * World-space AABB {minX, minY, minZ, maxX, maxY, maxZ} of the entity's drawn mesh bounds; a point at its world
     * position when it has no mesh. Returns false (out untouched) for a null entity or one with no transform.
     */
    public static boolean computeWorldBounds(Entity entity, SceneWorld sceneManager, float[] out) {
        if (entity == null) return false;
        SceneWorld.WorldTransform wt;
        if (sceneManager != null) {
            wt = sceneManager.getComputedWorldTransform(entity);
        } else {
            var tc = entity.getTransform();
            if (tc == null) return false;
            wt = new SceneWorld.WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }
        MeshFilter mf = entity.getComponent(MeshFilter.class);
        float[] b = (mf != null) ? mf.getDrawnLocalBounds() : null;
        if (b == null) {
            out[0] = out[3] = wt.px(); out[1] = out[4] = wt.py(); out[2] = out[5] = wt.pz();
            return true;
        }

        float[] m = new float[9];
        computeModelBasis(wt.rx(), wt.ry(), wt.rz(), wt.sx(), wt.sy(), wt.sz(), m);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            float lx = (corner & 1) != 0 ? b[3] : b[0];
            float ly = (corner & 2) != 0 ? b[4] : b[1];
            float lz = (corner & 4) != 0 ? b[5] : b[2];
            float x = m[0] * lx + m[3] * ly + m[6] * lz;
            float y = m[1] * lx + m[4] * ly + m[7] * lz;
            float z = m[2] * lx + m[5] * ly + m[8] * lz;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        out[0] = wt.px() + minX; out[1] = wt.py() + minY; out[2] = wt.pz() + minZ;
        out[3] = wt.px() + maxX; out[4] = wt.py() + maxY; out[5] = wt.pz() + maxZ;
        return true;
    }

    /** Center of the union of the entities' world AABBs: the shared pivot for multi-selection gizmos. */
    public static float[] computeGroupCenter(Iterable<Entity> entities, SceneWorld sceneManager) {
        float[] b = new float[6];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        boolean any = false;
        for (Entity e : entities) {
            if (!computeWorldBounds(e, sceneManager, b)) continue;
            any = true;
            minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]); minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[3]); maxY = Math.max(maxY, b[4]); maxZ = Math.max(maxZ, b[5]);
        }
        if (!any) return new float[] { 0.0f, 0.0f, 0.0f };
        return new float[] { (minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f };
    }

    /** Pure rotation basis (unit scale) of Euler degrees, column-major, same convention as {@link #computeModelBasis}. */
    public static void computeRotationBasis(float rx, float ry, float rz, float[] out) {
        computeModelBasis(rx, ry, rz, 1.0f, 1.0f, 1.0f, out);
    }

    /**
     * Inverse of {@link #computeRotationBasis}: Euler degrees {rx, ry, rz} of a column-major rotation basis. In that
     * convention m(2,0) = sin(ry), m(0,0) = cos(ry)cos(rz), m(1,0) = -cos(ry)sin(rz), m(2,1) = -sin(rx)cos(ry),
     * m(2,2) = cos(rx)cos(ry). At gimbal lock (cos(ry) ~ 0) rx is taken as 0 and rz read from column 1.
     */
    public static void eulerFromRotationBasis(float[] m, float[] outDeg) {
        float sy = Math.max(-1.0f, Math.min(1.0f, m[2]));
        double ry = Math.asin(sy);
        double rx, rz;
        if (Math.abs(sy) < 0.99999f) {
            rx = Math.atan2(-m[5], m[8]);
            rz = Math.atan2(-m[1], m[0]);
        } else {
            rx = 0.0;
            rz = Math.atan2(m[3], m[4]);
        }
        outDeg[0] = (float) Math.toDegrees(rx);
        outDeg[1] = (float) Math.toDegrees(ry);
        outDeg[2] = (float) Math.toDegrees(rz);
    }

    /** out = a * b for column-major 3x3 matrices (out may not alias a or b). */
    public static void multiply3x3(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                out[col * 3 + row] = a[row] * b[col * 3] + a[3 + row] * b[col * 3 + 1] + a[6 + row] * b[col * 3 + 2];
            }
        }
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

    /** Column-major 4x4 model matrix of a world transform (same as {@link #computeModelBasis} plus translation). */
    public static void computeModelMatrix(SceneWorld.WorldTransform wt, float[] out) {
        float[] basis = new float[9];
        computeModelBasis(wt.rx(), wt.ry(), wt.rz(), wt.sx(), wt.sy(), wt.sz(), basis);
        for (int col = 0; col < 3; col++) {
            out[col * 4] = basis[col * 3];
            out[col * 4 + 1] = basis[col * 3 + 1];
            out[col * 4 + 2] = basis[col * 3 + 2];
            out[col * 4 + 3] = 0.0f;
        }
        out[12] = wt.px(); out[13] = wt.py(); out[14] = wt.pz(); out[15] = 1.0f;
    }

    /**
     * World matrix of bone {@code boneIndex} in its animator's current palette (entity model * bone model transform), or
     * the entity's model matrix alone for {@code boneIndex} -1 (the space root bones are posed in). False if unavailable.
     */
    public static boolean computeBoneWorldMatrix(Entity entity, int boneIndex, SceneWorld sceneManager, float[] out) {
        if (entity == null || sceneManager == null) return false;
        computeModelMatrix(sceneManager.getComputedWorldTransform(entity), out);
        if (boneIndex < 0) return true;
        AnimatorComponent animator = entity.getComponent(AnimatorComponent.class);
        if (animator == null || animator.skeleton == null) return false;
        float[] bone = new float[16];
        if (!animator.skeleton.computeBoneModelTransform(boneIndex, animator.bonePaletteMatrices, bone)) return false;
        float[] model = out.clone();
        AnimationSystem.multiply4x4(model, bone, out);
        return true;
    }

    /** World-space unit vector pointing from the scene back toward the viewer (the view matrix's +Z row). */
    public static float[] viewBackAxis(ViewportCamera cameraController, float camPitch, float camYaw) {
        if (cameraController != null) {
            float[] v = VIEW_SCRATCH.get();
            cameraController.computeViewMatrix(v);
            return new float[] { v[2], v[6], v[10] };
        }
        float radYaw = (float) Math.toRadians(camYaw), radPitch = (float) Math.toRadians(camPitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        return new float[] { sy * cp, -sp, cy * cp };
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
