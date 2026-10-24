package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld;
import com.retro.engine.scene.components.Transform;

import java.util.List;

/**
 * Single-Responsibility Rendering Delegate for Immediate-Mode 3D Vector Gizmo Geometry Batching.
 */
public class GizmoRenderer {

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        Transform tc = entity.getTransform();
        if (tc == null) return;
        render3DGizmosAtWorldPosition(buffer, tc.posX, tc.posY, tc.posZ, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmos(tempBuf, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (entity == null) return;
        if (sceneManager != null) {
            SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(entity);
            render3DGizmosAtWorldPosition(buffer, wt.px(), wt.py(), wt.pz(), mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        } else {
            render3DGizmos(buffer, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmos(tempBuf, entity, sceneManager, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render3DGizmosAtWorldPosition(UIGeometryBatch.Buffer buffer, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float[] p0 = GizmoMath.projectWorldToNdc(px, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float cx = p0[0], cy = p0[1];

        if (cx < -0.55f || cx > 0.55f || cy < -0.35f || cy > 0.75f) {
            return;
        }

        float axisWorldLen = 1.0f;

        float[] pX = GizmoMath.projectWorldToNdc(px + axisWorldLen, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pY = GizmoMath.projectWorldToNdc(px, py + axisWorldLen, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pZ = GizmoMath.projectWorldToNdc(px, py, pz + axisWorldLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

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

        UIGeometryBatch.addSquareEnd(buffer, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        float rx = 1.0f, rg = 0.25f, rb = 0.25f;
        float gx = 0.25f, gg = 1.0f, gb = 0.25f;
        float bx = 0.25f, bg = 0.55f, bb = 1.0f;

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        if (mode == GizmoMode.TRANSLATE) {
            UIGeometryBatch.addLineSegment(buffer, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, xTipX + uXX * 0.02f, xTipY + uXY * 0.02f, uXX, uXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIGeometryBatch.addLineSegment(buffer, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, yTipX + uYX * 0.02f, yTipY + uYY * 0.02f, uYX, uYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIGeometryBatch.addLineSegment(buffer, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, zTipX + uZX * 0.02f, zTipY + uZY * 0.02f, uZX, uZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            UIGeometryBatch.addLineSegment(buffer, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            UIGeometryBatch.addLineSegment(buffer, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            UIGeometryBatch.addLineSegment(buffer, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }

    public static void render3DGizmosAtWorldPosition(List<Float> verts, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmosAtWorldPosition(tempBuf, px, py, pz, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}
