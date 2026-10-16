package com.retro.engine.editor.ui;

import com.retro.engine.Main;

import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.assets.AssetMeta;

import java.util.List;

/**
 * InspectorPanel handles rendering the Right Panel (Dynamic Component Readout & Inspector Mutation).
 * Supports Light, Camera & AudioSource live property controls.
 */
public class InspectorPanel {

    public static void render(List<Float> verts, Entity selectedEntity) {
        render(verts, selectedEntity, null);
    }

    public static void render(List<Float> verts, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        // Main Inspector Background Quad & Frame Border
        UIGeometryBatch.addQuad(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addQuad(verts, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(verts, "INSPECTOR [ECS COMPONENTS]", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            UIGeometryBatch.addBoundedText(verts, "ENTITY: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.3f, 1.0f, 0.8f, 0.2f, 1.0f);

            float curY = 0.72f;

            for (Component comp : selectedEntity.getAllComponents()) {
                if (curY < -0.45f) break;

                // Header line
                UIGeometryBatch.addBoundedText(verts, "--- " + comp.getComponentName().toUpperCase() + " ---", 0.58f, curY, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
                curY -= 0.06f;

                if (comp instanceof Transform tc) {
                    UIGeometryBatch.addBoundedText(verts, String.format("POS: X %.1f Y %.1f Z %.1f", tc.posX, tc.posY, tc.posZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.05f;
                    UIGeometryBatch.addBoundedText(verts, String.format("ROT: X %.1f Y %.1f Z %.1f", tc.rotX, tc.rotY, tc.rotZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.05f;
                    UIGeometryBatch.addBoundedText(verts, String.format("SCL: X %.1f Y %.1f Z %.1f", tc.scaleX, tc.scaleY, tc.scaleZ), 0.58f, curY, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    curY -= 0.07f;

                } else if (comp instanceof MeshFilter mc) {
                    String meshName = mc.meshAssetPath != null ? new java.io.File(mc.meshAssetPath).getName() : "NONE";

                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "MESH: " + meshName, 0.59f, curY - 0.01f, 0.78f, 0.95f, 0.4f, 0.8f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);

                    curY -= 0.08f;

                } else if (comp instanceof Material mat) {
                    UIGeometryBatch.addBoundedText(verts, "SHADER: " + mat.shaderName, 0.58f, curY, 0.98f, 1.0f, 0.4f, 0.8f, 1.0f, 1.0f);
                    curY -= 0.05f;

                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "TEX: " + mat.textureName, 0.59f, curY - 0.01f, 0.78f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);

                    curY -= 0.09f;

                } else if (comp instanceof Light lc) {
                    // Light Type & Cycle Button
                    UIGeometryBatch.addBoundedText(verts, "TYPE: " + lc.type.name(), 0.58f, curY, 0.82f, 1.0f, 1.0f, 0.9f, 0.3f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.98f, curY + 0.02f, 0.25f, 0.25f, 0.35f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.84f, curY - 0.03f, 0.98f, curY + 0.02f, 0.002f, 0.5f, 0.5f, 0.7f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[CYCLE]", 0.85f, curY - 0.01f, 0.97f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.06f;

                    // Intensity Field & Adjust Buttons
                    UIGeometryBatch.addBoundedText(verts, String.format("INTENSITY: %.1f", lc.intensity), 0.58f, curY, 0.82f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                    curY -= 0.06f;

                    // Color Preview Swatch & RGB Channel Toggle Buttons
                    UIGeometryBatch.addBoundedText(verts, "COLOR (RGB):", 0.58f, curY, 0.75f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.76f, curY - 0.03f, 0.82f, curY + 0.02f, lc.r, lc.g, lc.b, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.76f, curY - 0.03f, 0.82f, curY + 0.02f, 0.002f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.88f, curY + 0.02f, 0.5f, 0.1f, 0.1f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "R", 0.85f, curY - 0.01f, 0.87f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.89f, curY - 0.03f, 0.93f, curY + 0.02f, 0.1f, 0.5f, 0.1f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "G", 0.90f, curY - 0.01f, 0.92f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.94f, curY - 0.03f, 0.98f, curY + 0.02f, 0.1f, 0.1f, 0.5f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "B", 0.95f, curY - 0.01f, 0.97f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.08f;

                } else if (comp instanceof AudioSource ac) {
                    String clipName = (ac.audioAssetPath != null && !ac.audioAssetPath.isEmpty())
                            ? new java.io.File(ac.audioAssetPath).getName() : "NONE";

                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "CLIP: " + clipName, 0.59f, curY - 0.01f, 0.78f, 0.95f, 0.4f, 0.8f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.08f;

                    // Volume Control
                    UIGeometryBatch.addBoundedText(verts, String.format("VOL: %.0f%%", ac.volume * 100.0f), 0.58f, curY, 0.82f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                    curY -= 0.06f;

                    // Pitch Control
                    UIGeometryBatch.addBoundedText(verts, String.format("PITCH: %.1fx", ac.pitch), 0.58f, curY, 0.82f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                    curY -= 0.06f;

                    // Playback Transport Controls (PLAY, PAUSE, STOP)
                    String playLabel = (ac.state == AudioSource.PlaybackState.PLAYING) ? "[PLAYING]" : "[PLAY]";
                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.03f, 0.70f, curY + 0.02f, 0.15f, 0.45f, 0.20f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, playLabel, 0.59f, curY - 0.01f, 0.69f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.71f, curY - 0.03f, 0.83f, curY + 0.02f, 0.45f, 0.35f, 0.15f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[PAUSE]", 0.72f, curY - 0.01f, 0.82f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.98f, curY + 0.02f, 0.45f, 0.15f, 0.15f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[STOP]", 0.85f, curY - 0.01f, 0.97f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.06f;

                    // Loop Toggle Button
                    String loopText = ac.loop ? "[LOOP: ON]" : "[LOOP: OFF]";
                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.20f, 0.25f, 0.35f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.002f, 0.4f, 0.5f, 0.8f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, loopText, 0.59f, curY - 0.01f, 0.84f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.08f;

                } else if (comp instanceof Camera cc) {
                    UIGeometryBatch.addBoundedText(verts, String.format("FOV: %.1f°", cc.fov), 0.58f, curY, 0.82f, 1.0f, 0.3f, 0.8f, 1.0f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                    UIGeometryBatch.addQuad(verts, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                    curY -= 0.06f;

                    UIGeometryBatch.addBoundedText(verts, String.format("NEAR: %.1f | FAR: %.0f", cc.nearPlane, cc.farPlane), 0.58f, curY, 0.98f, 1.0f, 0.8f, 0.8f, 0.8f, 1.0f);
                    curY -= 0.05f;

                    String primaryLabel = cc.isPrimary ? "[PRIMARY: YES]" : "[PRIMARY: NO]";
                    UIGeometryBatch.addQuad(verts, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.15f, 0.35f, 0.45f, 1.0f);
                    UIGeometryBatch.addFrameBorder(verts, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.002f, 0.3f, 0.7f, 0.9f, 1.0f);
                    UIGeometryBatch.addBoundedText(verts, primaryLabel, 0.59f, curY - 0.01f, 0.84f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                    curY -= 0.08f;

                } else {
                    UIGeometryBatch.addBoundedText(verts, "DATA: " + comp.toString(), 0.58f, curY, 0.98f, 1.0f, 0.7f, 0.7f, 0.7f, 1.0f);
                    curY -= 0.07f;
                }
            }
        } else {
            UIGeometryBatch.addBoundedText(verts, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(verts, "CLICK AN ENTITY IN SCENE HIERARCHY", 0.58f, 0.72f, 0.98f, 0.95f, 0.4f, 0.4f, 0.4f, 1.0f);
        }
    }
}
