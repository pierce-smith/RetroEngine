package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.EditorWorkspace.WorkspaceTab;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;

import java.util.List;

/**
 * ViewportPanel handles rendering the central Viewport framing, canvas tab switcher buttons, and Toolbar controls.
 */
public class ViewportPanel {

    private static String rhiCanvasTitle = "3D VIEWPORT [RHI CANVAS]";

    public static String getRhiCanvasTitle() {
        return rhiCanvasTitle;
    }

    public static void setRhiCanvasTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            rhiCanvasTitle = title;
        }
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer, WorkspaceTab activeTab) {
        // Viewport Header Bar (-0.55f to 0.55f, 0.86f to 0.92f)
        UIGeometryBatch.addQuad(buffer, -0.55f, 0.86f, 0.55f, 0.92f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -0.55f, -0.50f, 0.55f, 0.92f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);

        boolean is3D = (activeTab == WorkspaceTab.SCENE_3D);

        // Tab 1: [3D SCENE] (-0.53f to -0.28f)
        float t1R = is3D ? 0.0f : 0.12f;
        float t1G = is3D ? 0.45f : 0.14f;
        float t1B = is3D ? 0.70f : 0.18f;
        UIGeometryBatch.addQuad(buffer, -0.53f, 0.865f, -0.28f, 0.915f, t1R, t1G, t1B, 1.0f);
        if (is3D) {
            UIGeometryBatch.addFrameBorder(buffer, -0.53f, 0.865f, -0.28f, 0.915f, 0.0015f, 0.0f, 0.8f, 1.0f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "3D SCENE", -0.515f, 0.882f, -0.29f, 1.0f, is3D ? 1.0f : 0.6f, is3D ? 1.0f : 0.6f, is3D ? 1.0f : 0.6f, 1.0f);

        // Tab 2: [ANIMATOR GRAPH] (-0.26f to -0.01f)
        float t2R = !is3D ? 0.0f : 0.12f;
        float t2G = !is3D ? 0.45f : 0.14f;
        float t2B = !is3D ? 0.70f : 0.18f;
        UIGeometryBatch.addQuad(buffer, -0.26f, 0.865f, -0.01f, 0.915f, t2R, t2G, t2B, 1.0f);
        if (!is3D) {
            UIGeometryBatch.addFrameBorder(buffer, -0.26f, 0.865f, -0.01f, 0.915f, 0.0015f, 0.0f, 0.8f, 1.0f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "ANIMATOR GRAPH", -0.245f, 0.882f, -0.02f, 1.0f, !is3D ? 1.0f : 0.6f, !is3D ? 1.0f : 0.6f, !is3D ? 1.0f : 0.6f, 1.0f);

        // Canvas Title Header (0.02f to 0.53f)
        String title = is3D ? rhiCanvasTitle : "ANIMATOR STATE MACHINE WORKSPACE";
        UIGeometryBatch.addBoundedText(buffer, title, 0.02f, 0.882f, 0.53f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer) {
        renderFraming(buffer, WorkspaceTab.SCENE_3D);
    }

    public static void renderFraming(List<Float> verts) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderFraming(tempBuf, WorkspaceTab.SCENE_3D);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void renderToolbar(UIGeometryBatch.Buffer buffer, GizmoMode currentGizmoMode) {
        float barX1 = -0.52f, barY1 = 0.79f, barX2 = -0.10f, barY2 = 0.85f;
        UIGeometryBatch.addQuad(buffer, barX1, barY1, barX2, barY2, 0.10f, 0.10f, 0.13f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, barX1, barY1, barX2, barY2, 0.002f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Button 1: [1/W] MOVE
        float btn1X1 = -0.51f, btn1X2 = -0.38f;
        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            UIGeometryBatch.addQuad(buffer, btn1X1, barY1 + 0.005f, btn1X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 2: [2/E] ROT
        float btn2X1 = -0.37f, btn2X2 = -0.25f;
        if (currentGizmoMode == GizmoMode.ROTATE) {
            UIGeometryBatch.addQuad(buffer, btn2X1, barY1 + 0.005f, btn2X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 3: [3/R] SCL
        float btn3X1 = -0.24f, btn3X2 = -0.11f;
        if (currentGizmoMode == GizmoMode.SCALE) {
            UIGeometryBatch.addQuad(buffer, btn3X1, barY1 + 0.005f, btn3X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            UIGeometryBatch.addBoundedText(buffer, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }
    }

    public static void renderToolbar(List<Float> verts, GizmoMode currentGizmoMode) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderToolbar(tempBuf, currentGizmoMode);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}
