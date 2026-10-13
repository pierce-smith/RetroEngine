package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorPanelManager;

import java.util.List;

/**
 * ViewportPanel handles rendering the Viewport framing, Toolbar buttons, and 3D Transform Gizmos.
 * Target JDK: JDK 25 / macOS
 */
public class ViewportPanel {

    public static void renderFraming(List<Float> verts) {
        UIBatchBuilder.addQuad(verts, -0.55f, 0.88f, 0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -0.55f, -0.5f, 0.55f, 0.88f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "3D VIEWPORT [METAL RHI CANVAS]", -0.42f, 0.95f, 0.52f, 1.4f, 0.3f, 0.8f, 1.0f, 1.0f);
    }

    public static void renderToolbar(List<Float> verts, EditorPanelManager.GizmoMode currentGizmoMode) {
        float barX1 = -0.52f, barY1 = 0.80f, barX2 = -0.10f, barY2 = 0.86f;
        UIBatchBuilder.addQuad(verts, barX1, barY1, barX2, barY2, 0.10f, 0.10f, 0.13f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, barX1, barY1, barX2, barY2, 0.002f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Button 1: [1/W] MOVE
        float btn1X1 = -0.51f, btn1X2 = -0.38f;
        if (currentGizmoMode == EditorPanelManager.GizmoMode.TRANSLATE) {
            UIBatchBuilder.addQuad(verts, btn1X1, barY1 + 0.005f, btn1X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIBatchBuilder.addBoundedText(verts, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 2: [2/E] ROT
        float btn2X1 = -0.37f, btn2X2 = -0.25f;
        if (currentGizmoMode == EditorPanelManager.GizmoMode.ROTATE) {
            UIBatchBuilder.addQuad(verts, btn2X1, barY1 + 0.005f, btn2X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIBatchBuilder.addBoundedText(verts, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 3: [3/R] SCL
        float btn3X1 = -0.24f, btn3X2 = -0.11f;
        if (currentGizmoMode == EditorPanelManager.GizmoMode.SCALE) {
            UIBatchBuilder.addQuad(verts, btn3X1, barY1 + 0.005f, btn3X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIBatchBuilder.addBoundedText(verts, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }
    }

    public static void renderGizmos(List<Float> verts, EditorPanelManager.SceneEntity entity, EditorPanelManager.GizmoMode currentGizmoMode, EditorPanelManager.GizmoAxis activeGizmoAxis) {
        renderGizmos(verts, entity, currentGizmoMode, activeGizmoAxis, new float[] { 0.0f, 0.19f });
    }

    public static void renderGizmos(List<Float> verts, EditorPanelManager.SceneEntity entity, EditorPanelManager.GizmoMode currentGizmoMode, EditorPanelManager.GizmoAxis activeGizmoAxis, float[] anchorNdc) {
        float cx = Math.max(-0.50f, Math.min(0.50f, anchorNdc[0]));
        float cy = Math.max(-0.40f, Math.min(0.75f, anchorNdc[1]));

        // Center Origin Core Box
        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        // Colors (Active axis gets highlighted yellow/cyan)
        float rx = 1.0f, rg = 0.25f, rb = 0.25f; // RED
        float gx = 0.25f, gg = 1.0f, gb = 0.25f; // GREEN
        float bx = 0.25f, bg = 0.55f, bb = 1.0f; // BLUE

        if (activeGizmoAxis == EditorPanelManager.GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeGizmoAxis == EditorPanelManager.GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeGizmoAxis == EditorPanelManager.GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        float len = 0.14f;
        float lineThick = 0.006f;

        if (currentGizmoMode == EditorPanelManager.GizmoMode.TRANSLATE) {
            // RED X AXIS
            UIBatchBuilder.addLineSegment(verts, cx, cy, cx + len, cy, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, cx + len + 0.025f, cy, 1.0f, 0.0f, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", cx + len + 0.035f, cy + 0.010f, cx + len + 0.08f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y AXIS
            UIBatchBuilder.addLineSegment(verts, cx, cy, cx, cy + len, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, cx, cy + len + 0.025f, 0.0f, 1.0f, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", cx - 0.010f, cy + len + 0.040f, cx + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z AXIS
            float zEndX = cx - len * 0.707f;
            float zEndY = cy - len * 0.707f;
            UIBatchBuilder.addLineSegment(verts, cx, cy, zEndX, zEndY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zEndX - 0.018f, zEndY - 0.018f, -0.707f, -0.707f, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zEndX - 0.045f, zEndY - 0.025f, zEndX, 1.1f, bx, bg, bb, 1.0f);

        } else if (currentGizmoMode == EditorPanelManager.GizmoMode.SCALE) {
            // RED X SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, cx + len, cy, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, cx + len + 0.015f, cy, 0.024f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", cx + len + 0.035f, cy + 0.010f, cx + len + 0.08f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, cx, cy + len, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, cx, cy + len + 0.015f, 0.024f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", cx - 0.010f, cy + len + 0.040f, cx + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z SCALE
            float zEndX = cx - len * 0.707f;
            float zEndY = cy - len * 0.707f;
            UIBatchBuilder.addLineSegment(verts, cx, cy, zEndX, zEndY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zEndX - 0.012f, zEndY - 0.012f, 0.024f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zEndX - 0.045f, zEndY - 0.025f, zEndX, 1.1f, bx, bg, bb, 1.0f);

        } else if (currentGizmoMode == EditorPanelManager.GizmoMode.ROTATE) {
            // RED PITCH ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            // GREEN YAW ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            // BLUE ROLL ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }
}
