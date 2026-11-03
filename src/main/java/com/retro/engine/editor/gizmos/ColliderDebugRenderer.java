package com.retro.engine.editor.gizmos;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.List;

/**
 * Immediate-mode 3D Viewport Debug Renderer for Collider & Vehicle Wireframes.
 * Projects 3D Box, Sphere, Capsule, Character Controller, and 4-Wheel Vehicle
 * suspension geometry into viewport screen space using ViewportCamera projections.
 */
public class ColliderDebugRenderer {

    public static void renderColliderWireframes(UIGeometryBatch.Buffer buffer,
                                               List<Entity> sceneEntities,
                                               Entity selectedEntity,
                                               ViewportCamera cameraController,
                                               float camPanX, float camPanY, float camZoom,
                                               float camPitch, float camYaw) {
        if (sceneEntities == null || buffer == null) return;

        for (Entity entity : sceneEntities) {
            if (entity == null) continue;

            Transform tc = entity.getTransform();
            if (tc == null) continue;

            boolean isSelected = (selectedEntity != null && selectedEntity.id.equals(entity.id));

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

            CharacterControllerComponent cc = entity.getComponent(CharacterControllerComponent.class);
            if (cc != null) {
                renderCharacterController(buffer, tc, cc, isSelected, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }

            VehicleControllerComponent vc = entity.getComponent(VehicleControllerComponent.class);
            if (vc != null) {
                renderVehicleController(buffer, tc, vc, isSelected, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }
        }
    }

    private static void renderBoxCollider(UIGeometryBatch.Buffer buffer, Transform tc, BoxCollider box, boolean isSelected,
                                         ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float r = box.isTrigger ? 0.95f : (isSelected ? 0.35f : 0.20f);
        float g = box.isTrigger ? 0.85f : (isSelected ? 0.75f : 0.90f);
        float b = box.isTrigger ? 0.20f : (isSelected ? 1.00f : 0.50f);
        float a = isSelected ? 0.95f : 0.75f;

        float hx = box.sizeX * tc.scaleX * 0.5f;
        float hy = box.sizeY * tc.scaleY * 0.5f;
        float hz = box.sizeZ * tc.scaleZ * 0.5f;

        float cx = tc.posX + box.offsetX * tc.scaleX;
        float cy = tc.posY + box.offsetY * tc.scaleY;
        float cz = tc.posZ + box.offsetZ * tc.scaleZ;

        float[][] corners = {
            { cx - hx, cy - hy, cz - hz }, { cx + hx, cy - hy, cz - hz },
            { cx + hx, cy + hy, cz - hz }, { cx - hx, cy + hy, cz - hz },
            { cx - hx, cy - hy, cz + hz }, { cx + hx, cy - hy, cz + hz },
            { cx + hx, cy + hy, cz + hz }, { cx - hx, cy + hy, cz + hz }
        };

        int[][] edges = {
            {0,1}, {1,2}, {2,3}, {3,0},
            {4,5}, {5,6}, {6,7}, {7,4},
            {0,4}, {1,5}, {2,6}, {3,7}
        };

        for (int[] edge : edges) {
            draw3DLine(buffer, corners[edge[0]][0], corners[edge[0]][1], corners[edge[0]][2],
                                corners[edge[1]][0], corners[edge[1]][1], corners[edge[1]][2],
                                0.0025f, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    private static void renderSphereCollider(UIGeometryBatch.Buffer buffer, Transform tc, SphereCollider sphere, boolean isSelected,
                                            ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float r = sphere.isTrigger ? 0.95f : (isSelected ? 0.35f : 0.20f);
        float g = sphere.isTrigger ? 0.85f : (isSelected ? 0.75f : 0.90f);
        float b = sphere.isTrigger ? 0.20f : (isSelected ? 1.00f : 0.50f);
        float a = isSelected ? 0.95f : 0.75f;

        float maxScale = Math.max(tc.scaleX, Math.max(tc.scaleY, tc.scaleZ));
        float rad = sphere.radius * maxScale;

        float cx = tc.posX + sphere.offsetX * tc.scaleX;
        float cy = tc.posY + sphere.offsetY * tc.scaleY;
        float cz = tc.posZ + sphere.offsetZ * tc.scaleZ;

        draw3DCircle(buffer, cx, cy, cz, rad, 0, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        draw3DCircle(buffer, cx, cy, cz, rad, 1, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        draw3DCircle(buffer, cx, cy, cz, rad, 2, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    private static void renderCapsuleCollider(UIGeometryBatch.Buffer buffer, Transform tc, CapsuleCollider capsule, boolean isSelected,
                                             ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float r = capsule.isTrigger ? 0.95f : (isSelected ? 0.35f : 0.20f);
        float g = capsule.isTrigger ? 0.85f : (isSelected ? 0.75f : 0.90f);
        float b = capsule.isTrigger ? 0.20f : (isSelected ? 1.00f : 0.50f);
        float a = isSelected ? 0.95f : 0.75f;

        float rad = capsule.radius * Math.max(tc.scaleX, tc.scaleZ);
        float halfH = (capsule.height * tc.scaleY) * 0.5f;

        float cx = tc.posX + capsule.offsetX * tc.scaleX;
        float cy = tc.posY + capsule.offsetY * tc.scaleY;
        float cz = tc.posZ + capsule.offsetZ * tc.scaleZ;

        draw3DCircle(buffer, cx, cy + halfH, cz, rad, 1, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        draw3DCircle(buffer, cx, cy - halfH, cz, rad, 1, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        float[][] sides = { { rad, 0 }, { -rad, 0 }, { 0, rad }, { 0, -rad } };
        for (float[] side : sides) {
            draw3DLine(buffer, cx + side[0], cy - halfH, cz + side[1],
                                cx + side[0], cy + halfH, cz + side[1],
                                0.0025f, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    private static void renderCharacterController(UIGeometryBatch.Buffer buffer, Transform tc, CharacterControllerComponent cc, boolean isSelected,
                                                   ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float r = cc.isGrounded ? 0.2f : 0.95f;
        float g = cc.isGrounded ? 0.9f : 0.35f;
        float b = cc.isGrounded ? 0.4f : 0.20f;
        float a = 0.9f;

        // Ground Probe Line
        float startY = tc.posY;
        float endY = tc.posY - (cc.stepOffset + 0.1f);
        draw3DLine(buffer, tc.posX, startY, tc.posZ, tc.posX, endY, tc.posZ, 0.003f, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        // Ground Feet Ring
        draw3DCircle(buffer, tc.posX, endY, tc.posZ, 0.35f, 1, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    private static void renderVehicleController(UIGeometryBatch.Buffer buffer, Transform tc, VehicleControllerComponent vc, boolean isSelected,
                                                ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float radY = (float) Math.toRadians(tc.rotY);
        float forwardX = (float) Math.sin(radY);
        float forwardZ = (float) Math.cos(radY);
        float rightX = (float) Math.cos(radY);
        float rightZ = (float) -Math.sin(radY);

        float halfTrack = vc.wheelTrackWidth * 0.5f;
        float halfBase = vc.wheelBase * 0.5f;

        float[][] wheelOffsets = {
            { rightX * halfTrack + forwardX * halfBase, rightZ * halfTrack + forwardZ * halfBase },
            { -rightX * halfTrack + forwardX * halfBase, -rightZ * halfTrack + forwardZ * halfBase },
            { rightX * halfTrack - forwardX * halfBase, rightZ * halfTrack - forwardZ * halfBase },
            { -rightX * halfTrack - forwardX * halfBase, -rightZ * halfTrack - forwardZ * halfBase }
        };

        for (int i = 0; i < 4; i++) {
            boolean grounded = vc.wheelGrounded != null && i < vc.wheelGrounded.length && vc.wheelGrounded[i];
            float r = grounded ? 0.2f : 0.95f;
            float g = grounded ? 0.9f : 0.25f;
            float b = grounded ? 0.3f : 0.25f;
            float a = 0.95f;

            float wx = tc.posX + wheelOffsets[i][0];
            float wy = tc.posY;
            float wz = tc.posZ + wheelOffsets[i][1];

            float rayLen = vc.suspensionRestLength + vc.wheelRadius;
            float groundY = wy - rayLen;

            // Draw Suspension Ray
            draw3DLine(buffer, wx, wy, wz, wx, groundY, wz, 0.003f, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

            // Draw Wheel Circle
            draw3DCircle(buffer, wx, groundY + vc.wheelRadius, wz, vc.wheelRadius, 0, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    private static void draw3DCircle(UIGeometryBatch.Buffer buffer, float cx, float cy, float cz, float radius, int plane,
                                      float r, float g, float b, float a,
                                      ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        int segments = 16;
        float step = (float) (2.0 * Math.PI / segments);

        float prevX = 0, prevY = 0, prevZ = 0;
        for (int i = 0; i <= segments; i++) {
            float angle = i * step;
            float cos = (float) Math.cos(angle) * radius;
            float sin = (float) Math.sin(angle) * radius;

            float px = cx, py = cy, pz = cz;
            if (plane == 0) { px += cos; py += sin; }       // XY Plane
            else if (plane == 1) { px += cos; pz += sin; }  // XZ Plane
            else if (plane == 2) { py += cos; pz += sin; }  // YZ Plane

            if (i > 0) {
                draw3DLine(buffer, prevX, prevY, prevZ, px, py, pz, 0.002f, r, g, b, a, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            }
            prevX = px; prevY = py; prevZ = pz;
        }
    }

    private static void draw3DLine(UIGeometryBatch.Buffer buffer, float x1, float y1, float z1,
                                   float x2, float y2, float z2, float thickness,
                                   float r, float g, float b, float a,
                                   ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float[] p1 = GizmoMath.projectWorldToNdc(x1, y1, z1, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] p2 = GizmoMath.projectWorldToNdc(x2, y2, z2, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        if (p1[0] >= -0.55f && p1[0] <= 0.55f && p1[1] >= -0.50f && p1[1] <= 0.88f &&
            p2[0] >= -0.55f && p2[0] <= 0.55f && p2[1] >= -0.50f && p2[1] <= 0.88f) {
            UIGeometryBatch.addLineSegment(buffer, p1[0], p1[1], p2[0], p2[1], thickness, r, g, b, a);
        }
    }
}
