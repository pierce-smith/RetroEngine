package com.retro.engine.editor.ui;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;

import java.util.List;

/**
 * ViewportPanel handles rendering the Viewport framing and Toolbar buttons.
 * Target JDK: JDK 25 / macOS
 */
public class ViewportPanel {

    public static void renderFraming(UIGeometryBatch.Buffer buffer) {
        UIGeometryBatch.addQuad(buffer, -0.55f, 0.88f, 0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -0.55f, -0.5f, 0.55f, 0.88f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "3D VIEWPORT [METAL RHI CANVAS]", -0.42f, 0.95f, 0.52f, 1.4f, 0.3f, 0.8f, 1.0f, 1.0f);
    }

    public static void renderFraming(List<Float> verts) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderFraming(tempBuf);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void renderToolbar(UIGeometryBatch.Buffer buffer, GizmoMode currentGizmoMode) {
        float barX1 = -0.52f, barY1 = 0.80f, barX2 = -0.10f, barY2 = 0.86f;
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