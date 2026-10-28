package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.EditorWorkspace.WorkspaceTab;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.entity.Entity;

import java.util.List;

/**
 * ViewportPanel handles rendering the central Viewport framing, canvas tab switcher buttons, and Toolbar controls.
 */
public class ViewportPanel {

    private static String rhiCanvasTitle = "3D VIEWPORT [RHI CANVAS]";

    private static final StringBuilder cameraTitleSb = new StringBuilder(64);
    private static final StringBuilder cameraSpecsSb = new StringBuilder(64);
    // On-screen width/height of the last drawn viewfinder; the camera preview pass projects with it so the
    // fixed-size preview target is stretched into the frame without distortion
    private static volatile float cameraPreviewAspect = 16.0f / 9.0f;

    public static float getCameraPreviewAspect() {
        return cameraPreviewAspect;
    }

    public static String getRhiCanvasTitle() {
        return rhiCanvasTitle;
    }

    public static void setRhiCanvasTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            rhiCanvasTitle = title;
        }
    }

    public static void renderFraming(UIGeometryBatch.Buffer buffer, WorkspaceTab activeTab, Entity selectedEntity, long cameraPreviewTexture) {
        renderFraming(buffer, activeTab);
        renderCameraPreviewHUD(buffer, selectedEntity, cameraPreviewTexture);
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

    /**
     * Picture-in-Picture viewfinder card (bottom-right of the viewport) shown while a Camera entity is selected. Shows the
     * backend's live camera preview target when {@code cameraPreviewTexture} is non-zero, otherwise an empty crosshair frame.
     */
    public static void renderCameraPreviewHUD(UIGeometryBatch.Buffer buffer, Entity selectedEntity, long cameraPreviewTexture) {
        if (selectedEntity == null) return;
        Camera camera = selectedEntity.getComponent(Camera.class);
        if (camera == null) return;

        // Scene panel design space ends at x = 0.55; the card keeps the profiler HUD's 0.03 right margin
        float minX = 0.14f, maxX = 0.52f;
        float minY = -0.48f, maxY = -0.20f;

        UIGeometryBatch.addRoundedCard(buffer, minX, minY, maxX, maxY, EditorTheme.RADIUS * 1.5f, EditorTheme.BACKGROUND, EditorTheme.BORDER, 0.90f);

        // Header Title
        cameraTitleSb.setLength(0);
        cameraTitleSb.append("CAMERA PREVIEW: ").append(selectedEntity.name);
        UIGeometryBatch.addTruncatedText(buffer, cameraTitleSb, minX + 0.015f, maxY - 0.030f, maxX - 0.015f, 0.9f, EditorTheme.ACCENT, 1.0f);

        // Viewfinder Frame
        float vfX0 = minX + 0.02f, vfX1 = maxX - 0.02f;
        float vfY0 = minY + 0.04f, vfY1 = maxY - 0.05f;
        float borderX = UIGeometryBatch.squareWidth(buffer, EditorTheme.HAIRLINE);
        float imgX0 = vfX0 + borderX, imgX1 = vfX1 - borderX;
        float imgY0 = vfY0 + EditorTheme.HAIRLINE, imgY1 = vfY1 - EditorTheme.HAIRLINE;
        cameraPreviewAspect = (imgX1 - imgX0) / Math.max(1e-5f, UIGeometryBatch.squareWidth(buffer, imgY1 - imgY0));

        if (cameraPreviewTexture != 0L) {
            UIGeometryBatch.addCameraPreviewQuad(buffer, imgX0, imgY0, imgX1, imgY1, 1.0f);
        } else {
            UIGeometryBatch.addQuad(buffer, vfX0, vfY0, vfX1, vfY1, EditorTheme.SURFACE_SUNKEN, 1.0f);

            // Center Crosshairs
            float cx = (vfX0 + vfX1) * 0.5f, cy = (vfY0 + vfY1) * 0.5f;
            float armY = 0.025f, halfT = EditorTheme.HAIRLINE * 0.5f;
            float armX = UIGeometryBatch.squareWidth(buffer, armY);
            float halfTX = UIGeometryBatch.squareWidth(buffer, halfT);
            UIGeometryBatch.addQuad(buffer, cx - armX, cy - halfT, cx + armX, cy + halfT, EditorTheme.TEXT_MUTED, 0.5f);
            UIGeometryBatch.addQuad(buffer, cx - halfTX, cy - armY, cx + halfTX, cy + armY, EditorTheme.TEXT_MUTED, 0.5f);
        }
        UIGeometryBatch.addFrameBorder(buffer, vfX0, vfY0, vfX1, vfY1, EditorTheme.HAIRLINE, EditorTheme.BORDER_STRONG, 1.0f);

        // Footer Specs
        cameraSpecsSb.setLength(0);
        cameraSpecsSb.append("FOV: ");
        appendFloat1Decimal(cameraSpecsSb, camera.fov);
        cameraSpecsSb.append(" deg | NEAR: ");
        appendFloat2Decimal(cameraSpecsSb, camera.nearPlane);
        cameraSpecsSb.append(" | PRI: ").append(camera.isPrimary ? "YES" : "NO");
        UIGeometryBatch.addTruncatedText(buffer, cameraSpecsSb, minX + 0.015f, minY + 0.012f, maxX - 0.015f, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void appendFloat1Decimal(StringBuilder sb, float value) {
        int scaled = Math.round(value * 10.0f);
        if (scaled < 0) { sb.append('-'); scaled = -scaled; }
        sb.append(scaled / 10).append('.').append(scaled % 10);
    }

    private static void appendFloat2Decimal(StringBuilder sb, float value) {
        int scaled = Math.round(value * 100.0f);
        if (scaled < 0) { sb.append('-'); scaled = -scaled; }
        int frac = scaled % 100;
        sb.append(scaled / 100).append('.');
        if (frac < 10) sb.append('0');
        sb.append(frac);
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
