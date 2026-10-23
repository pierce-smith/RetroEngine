package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.UIGeometryBatch;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.BoxCollider;
import com.retro.engine.scene.components.CapsuleCollider;
import com.retro.engine.scene.components.SphereCollider;
import com.retro.engine.scene.components.Transform;

import java.util.List;

/**
 * Immediate-mode 3D Viewport Debug Renderer for Collider Wireframes.
 * Projects 3D Box, Sphere, and Capsule collider geometry into viewport screen space
 * using ViewportCamera and GizmoMath projection matrix routines.
 */
public class ColliderDebugRenderer {

    // Wireframe Color Schemes (Bright Cyan for Colliders, Gold/Yellow for Triggers, Blue for Selected)
    private static final float WIRE_R = 0.2f;
    private static final float WIRE_G = 1.0f;
    private static final float WIRE_B = 0.5f;
    private static final float WIRE_ALPHA = 0.85f;

    private static final float TRIGGER_R = 1.0f;
    private static final float TRIGGER_G = 0.8f;
    private static final float TRIGGER_B = 0.2f;

    private static final float SELECTED_R = 0.0f;
    private static final float SELECTED_G = 0.9f;
    private static final float SELECTED_B = 1.0f;

    public static void renderColliderWireframes(
            UIGeometryBatch.Buffer buffer,
            List<Entity> entities,
            Entity selectedEntity,
            ViewportCamera cameraController,
            float camPanX, float camPanY, float camZoom, float camPitch, float camYaw
    ) {
        if (entities == null || buffer == null) return;

        for (Entity entity : entities) {
            if (entity == null) continue;

            Transform tc = entity.getTransform();
            if (tc == null) continue;

            boolean isSelected = (entity == selectedEntity);

            BoxCollider box = entity.getComponent(BoxCollider.class);
            if (box != null) {
                renderBoxCollider(buffer, tc, box, isSelected, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }

            SphereCollider sphere = entity.getComponent(SphereCollider.class);
            if (sphere != null) {
                renderSphereCollider(buffer, tc, sphere, isSelected, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }

            CapsuleCollider capsule = entity.getComponent(CapsuleCollider.class);
            if (capsule != null) {
                renderCapsuleCollider(buffer, tc, capsule, isSelected, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }
        }
    }

    private static void renderBoxCollider(
            UIGeometryBatch.Buffer buffer,
            Transform tc,
            BoxCollider box,
            boolean isSelected,
            ViewportCamera cameraController,
            float camPanX, float camPanY, float camZoom, float camPitch, float camYaw
    ) {
        float r = isSelected ? SELECTED_R : (box.isTrigger ? TRIGGER_R : WIRE_R);
        float g = isSelected ? SELECTED_G : (box.isTrigger ? TRIGGER_G : WIRE_G);
        float b = isSelected ? SELECTED_B : (box.isTrigger ? TRIGGER_B : WIRE_B);
        float thickness = isSelected ? 0.003f : 0.002f;

        float hx = (box.sizeX * tc.scaleX) * 0.5f;
        float hy = (box.sizeY * tc.scaleY) * 0.5f;
        float hz = (box.sizeZ * tc.scaleZ) * 0.5f;

        float cx = tc.posX + box.offsetX;
        float cy = tc.posY + box.offsetY;
        float cz = tc.posZ + box.offsetZ;

        // 8 Corners of 3D Box
        float[][] corners3D = {
                { cx - hx, cy - hy, cz - hz },
                { cx + hx, cy - hy, cz - hz },
                { cx + hx, cy + hy, cz - hz },
                { cx - hx, cy + hy, cz - hz },
                { cx - hx, cy - hy, cz + hz },
                { cx + hx, cy - hy, cz + hz },
                { cx + hx, cy + hy, cz + hz },
                { cx - hx, cy + hy, cz + hz }
        };

        // Project corners to NDC
        float[][] corners2D = new float[8][2];
        for (int i = 0; i < 8; i++) {
            corners2D[i] = GizmoMath.projectWorldToNdc(
                    corners3D[i][0], corners3D[i][1], corners3D[i][2],
                    cameraController, camPanX, camPanY, camZoom, camPitch, camYaw
            );
        }

        // 12 Edges
        int[][] edges = {
                {0,1}, {1,2}, {2,3}, {3,0},
                {4,5}, {5,6}, {6,7}, {7,4},
                {0,4}, {1,5}, {2,6}, {3,7}
        };

        for (int[] edge : edges) {
            float[] p1 = corners2D[edge[0]];
            float[] p2 = corners2D[edge[1]];
            UIGeometryBatch.addLineSegment(buffer, p1[0], p1[1], p2[0], p2[1], thickness, r, g, b, WIRE_ALPHA);
        }
    }

    private static void renderSphereCollider(
            UIGeometryBatch.Buffer buffer,
            Transform tc,
            SphereCollider sphere,
            boolean isSelected,
            ViewportCamera cameraController,
            float camPanX, float camPanY, float camZoom, float camPitch, float camYaw
    ) {
        float r = isSelected ? SELECTED_R : (sphere.isTrigger ? TRIGGER_R : WIRE_R);
        float g = isSelected ? SELECTED_G : (sphere.isTrigger ? TRIGGER_G : WIRE_G);
        float b = isSelected ? SELECTED_B : (sphere.isTrigger ? TRIGGER_B : WIRE_B);
        float thickness = isSelected ? 0.003f : 0.002f;

        float maxScale = Math.max(Math.abs(tc.scaleX), Math.max(Math.abs(tc.scaleY), Math.abs(tc.scaleZ)));
        float rad = sphere.radius * maxScale;

        float cx = tc.posX + sphere.offsetX;
        float cy = tc.posY + sphere.offsetY;
        float cz = tc.posZ + sphere.offsetZ;

        int segments = 16;
        float step = (float) (2.0 * Math.PI / segments);

        // 3 Orthographic Rings (XY, XZ, YZ)
        renderRing(buffer, cx, cy, cz, rad, 0, segments, step, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, thickness, r, g, b);
        renderRing(buffer, cx, cy, cz, rad, 1, segments, step, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, thickness, r, g, b);
        renderRing(buffer, cx, cy, cz, rad, 2, segments, step, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, thickness, r, g, b);
    }

    private static void renderRing(
            UIGeometryBatch.Buffer buffer,
            float cx, float cy, float cz,
            float rad, int plane, int segments, float step,
            ViewportCamera cameraController,
            float camPanX, float camPanY, float camZoom, float camPitch, float camYaw,
            float thickness, float r, float g, float b
    ) {
        float prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i <= segments; i++) {
            float angle = i * step;
            float cos = (float) Math.cos(angle) * rad;
            float sin = (float) Math.sin(angle) * rad;

            float px = cx, py = cy, pz = cz;
            if (plane == 0) { px += cos; py += sin; }      // XY
            else if (plane == 1) { px += cos; pz += sin; } // XZ
            else { py += cos; pz += sin; }                 // YZ

            if (i > 0) {
                float[] p1 = GizmoMath.projectWorldToNdc(prevX, prevY, prevZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                float[] p2 = GizmoMath.projectWorldToNdc(px, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                UIGeometryBatch.addLineSegment(buffer, p1[0], p1[1], p2[0], p2[1], thickness, r, g, b, WIRE_ALPHA);
            }
            prevX = px; prevY = py; prevZ = pz;
        }
    }

    private static void renderCapsuleCollider(
            UIGeometryBatch.Buffer buffer,
            Transform tc,
            CapsuleCollider capsule,
            boolean isSelected,
            ViewportCamera cameraController,
            float camPanX, float camPanY, float camZoom, float camPitch, float camYaw
    ) {
        float r = isSelected ? SELECTED_R : (capsule.isTrigger ? TRIGGER_R : WIRE_R);
        float g = isSelected ? SELECTED_G : (capsule.isTrigger ? TRIGGER_G : WIRE_G);
        float b = isSelected ? SELECTED_B : (capsule.isTrigger ? TRIGGER_B : WIRE_B);
        float thickness = isSelected ? 0.003f : 0.002f;

        float rad = capsule.radius * Math.max(Math.abs(tc.scaleX), Math.abs(tc.scaleZ));
        float halfH = (capsule.height * tc.scaleY) * 0.5f;

        float cx = tc.posX + capsule.offsetX;
        float cy = tc.posY + capsule.offsetY;
        float cz = tc.posZ + capsule.offsetZ;

        int segments = 16;
        float step = (float) (2.0 * Math.PI / segments);

        // Top & Bottom horizontal rings
        renderRing(buffer, cx, cy + halfH, cz, rad, 1, segments, step, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, thickness, r, g, b);
        renderRing(buffer, cx, cy - halfH, cz, rad, 1, segments, step, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, thickness, r, g, b);

        // 4 Vertical connecting lines
        float[][] topPoints = { {cx + rad, cy + halfH, cz}, {cx - rad, cy + halfH, cz}, {cx, cy + halfH, cz + rad}, {cx, cy + halfH, cz - rad} };
        float[][] botPoints = { {cx + rad, cy - halfH, cz}, {cx - rad, cy - halfH, cz}, {cx, cy - halfH, cz + rad}, {cx, cy - halfH, cz - rad} };

        for (int i = 0; i < 4; i++) {
            float[] p1 = GizmoMath.projectWorldToNdc(topPoints[i][0], topPoints[i][1], topPoints[i][2], cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] p2 = GizmoMath.projectWorldToNdc(botPoints[i][0], botPoints[i][1], botPoints[i][2], cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            UIGeometryBatch.addLineSegment(buffer, p1[0], p1[1], p2[0], p2[1], thickness, r, g, b, WIRE_ALPHA);
        }
    }
}
