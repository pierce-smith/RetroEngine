package com.retro.engine.editor.render;

/**
 * High-performance 6-Plane View Frustum Culler using Gribb-Hartmann matrix extraction
 * for AABB and Bounding Sphere visibility queries against the View-Projection matrix.
 */
public class FrustumCuller {

    public static class Plane {
        public float a, b, c, d;

        public void normalize() {
            float len = (float) Math.sqrt(a * a + b * b + c * c);
            if (len > 0.00001f) {
                a /= len;
                b /= len;
                c /= len;
                d /= len;
            }
        }

        public float distanceToPoint(float x, float y, float z) {
            return a * x + b * y + c * z + d;
        }
    }

    private final Plane[] planes = new Plane[6];

    public FrustumCuller() {
        for (int i = 0; i < 6; i++) {
            planes[i] = new Plane();
        }
    }

    public void updateFrustumPlanes(float[] viewProj) {
        if (viewProj == null || viewProj.length < 16) return;

        float m00 = viewProj[0],  m10 = viewProj[1],  m20 = viewProj[2],  m30 = viewProj[3];
        float m01 = viewProj[4],  m11 = viewProj[5],  m21 = viewProj[6],  m31 = viewProj[7];
        float m02 = viewProj[8],  m12 = viewProj[9],  m22 = viewProj[10], m32 = viewProj[11];
        float m03 = viewProj[12], m13 = viewProj[13], m23 = viewProj[14], m33 = viewProj[15];

        // Left Plane: Row 3 + Row 0
        planes[0].a = m30 + m00;
        planes[0].b = m31 + m01;
        planes[0].c = m32 + m02;
        planes[0].d = m33 + m03;
        planes[0].normalize();

        // Right Plane: Row 3 - Row 0
        planes[1].a = m30 - m00;
        planes[1].b = m31 - m01;
        planes[1].c = m32 - m02;
        planes[1].d = m33 - m03;
        planes[1].normalize();

        // Bottom Plane: Row 3 + Row 1
        planes[2].a = m30 + m10;
        planes[2].b = m31 + m11;
        planes[2].c = m32 + m12;
        planes[2].d = m33 + m13;
        planes[2].normalize();

        // Top Plane: Row 3 - Row 1
        planes[3].a = m30 - m10;
        planes[3].b = m31 - m11;
        planes[3].c = m32 - m12;
        planes[3].d = m33 - m13;
        planes[3].normalize();

        // Near Plane: Row 3 + Row 2
        planes[4].a = m30 + m20;
        planes[4].b = m31 + m21;
        planes[4].c = m32 + m22;
        planes[4].d = m33 + m23;
        planes[4].normalize();

        // Far Plane: Row 3 - Row 2
        planes[5].a = m30 - m20;
        planes[5].b = m31 - m21;
        planes[5].c = m32 - m22;
        planes[5].d = m33 - m23;
        planes[5].normalize();
    }

    /** Planes for an explicit column-major world-to-view matrix with the backends' perspective projection. */
    public void updateFromView(float[] view, float fovDeg, float aspect, float zNear, float zFar) {
        updateFrustumPlanes(multiplyMatrices(computePerspectiveProjectionMatrix(fovDeg, aspect, zNear, zFar), view));
    }

    public void updateFromCamera(float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, float fovDeg, float aspect, float zNear, float zFar) {
        float[] view = computeViewMatrix(camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] proj = computePerspectiveProjectionMatrix(fovDeg, aspect, zNear, zFar);
        float[] vp = multiplyMatrices(proj, view);
        updateFrustumPlanes(vp);
    }

    public boolean isSphereVisible(float cx, float cy, float cz, float radius) {
        for (int i = 0; i < 6; i++) {
            if (planes[i].distanceToPoint(cx, cy, cz) < -radius) {
                return false;
            }
        }
        return true;
    }

    public boolean isAABBVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            Plane p = planes[i];
            float px = (p.a > 0) ? maxX : minX;
            float py = (p.b > 0) ? maxY : minY;
            float pz = (p.c > 0) ? maxZ : minZ;

            if (p.distanceToPoint(px, py, pz) < 0.0f) {
                return false;
            }
        }
        return true;
    }

    private static float[] computeViewMatrix(float panX, float panY, float zoom, float pitch, float yaw) {
        float radYaw = (float) Math.toRadians(yaw);
        float radPitch = (float) Math.toRadians(pitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        float[] view = new float[16];
        view[0] = cy;        view[1] = sy * sp;   view[2] = sy * cp;   view[3] = 0.0f;
        view[4] = 0.0f;      view[5] = cp;        view[6] = -sp;       view[7] = 0.0f;
        view[8] = -sy;       view[9] = cy * sp;   view[10] = cy * cp;  view[11] = 0.0f;
        view[12] = panX;     view[13] = panY;     view[14] = zoom;     view[15] = 1.0f;

        return view;
    }

    private static float[] computePerspectiveProjectionMatrix(float fovDeg, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovDeg) / 2.0));
        float[] proj = new float[16];
        proj[0] = f / aspect;
        proj[5] = f;
        proj[10] = zFar / (zNear - zFar);
        proj[11] = -1.0f;
        proj[14] = (zNear * zFar) / (zNear - zFar);
        return proj;
    }

    private static float[] multiplyMatrices(float[] a, float[] b) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
        return result;
    }
}
