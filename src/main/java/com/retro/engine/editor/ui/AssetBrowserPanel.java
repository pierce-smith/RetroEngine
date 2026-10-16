package com.retro.engine.editor.ui;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;

import java.util.List;

/**
 * AssetBrowserPanel handles rendering the Bottom Panel (Resource Cards & Nested Folders).
 */
public class AssetBrowserPanel {

    public static void render(UIGeometryBatch.Buffer buffer, List<AssetMeta> assetDirectory) {
        render(buffer, assetDirectory, "");
    }
    public static void render(List<Float> verts, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, assetDirectory);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render(UIGeometryBatch.Buffer buffer, List<AssetMeta> assetDirectory, String currentFolderPath) {
        // Bottom Panel Background Quads & Frame
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, -0.5f, 0.06f, 0.06f, 0.08f, 1.0f);
        UIGeometryBatch.addQuad(buffer, -1.0f, -0.5f, 1.0f, -0.42f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -1.0f, -1.0f, 1.0f, -0.5f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Header Breadcrumbs / Title
        String pathDisplay = (currentFolderPath == null || currentFolderPath.isEmpty()) ? "Assets" : "Assets / " + currentFolderPath;
        UIGeometryBatch.addBoundedText(buffer, "ASSET DIRECTORY: " + pathDisplay.toUpperCase(), -0.96f, -0.45f, 0.28f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);

        // Header [+ NEW FOLDER] Button
        UIGeometryBatch.addQuad(buffer, 0.30f, -0.49f, 0.62f, -0.43f, 0.20f, 0.25f, 0.38f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, 0.30f, -0.49f, 0.62f, -0.43f, 0.002f, 0.4f, 0.6f, 0.9f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "[+ NEW FOLDER]", 0.32f, -0.45f, 0.60f, 1.0f, 0.8f, 0.9f, 1.0f, 1.0f);

        // Header [+ IMPORT ASSET] Button
        UIGeometryBatch.addQuad(buffer, 0.65f, -0.49f, 0.98f, -0.43f, 0.15f, 0.45f, 0.30f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, 0.65f, -0.49f, 0.98f, -0.43f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "[+ IMPORT ASSET]", 0.67f, -0.45f, 0.96f, 1.1f, 0.4f, 1.0f, 0.6f, 1.0f);

        String activePath = (currentFolderPath != null) ? currentFolderPath : "";

        // Filter assets matching active directory level
        List<AssetMeta> visibleAssets = (assetDirectory != null) ? assetDirectory.stream()
                .filter(a -> a.parentFolderPath != null && a.parentFolderPath.equalsIgnoreCase(activePath))
                .toList() : List.of();

        boolean hasUpLevel = !activePath.isEmpty();

        if (visibleAssets.isEmpty() && !hasUpLevel) {
            UIGeometryBatch.addBoundedText(buffer, "NO ASSETS IN FOLDER - DRAG & DROP FILES OR CLICK [+ IMPORT ASSET]", -0.80f, -0.70f, 0.90f, 1.2f, 0.4f, 0.4f, 0.4f, 1.0f);
        } else {
            float cardX = -0.95f;

            // 1. Render [ .. ] (Up Level) Card if inside subfolder
            if (hasUpLevel) {
                UIGeometryBatch.addQuad(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.14f, 0.14f, 0.18f, 1.0f);
                UIGeometryBatch.addFrameBorder(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.4f, 0.4f, 0.5f, 1.0f);

                UIGeometryBatch.addQuad(buffer, cardX + 0.02f, -0.66f, cardX + 0.18f, -0.58f, 0.3f, 0.3f, 0.4f, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, "FOLDER", cardX + 0.03f, -0.60f, cardX + 0.17f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                UIGeometryBatch.addBoundedText(buffer, ".. [UP LEVEL]", cardX + 0.02f, -0.75f, cardX + 0.30f, 1.1f, 0.8f, 0.8f, 0.9f, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, "[CLICK: RETURN]", cardX + 0.02f, -0.85f, cardX + 0.30f, 0.9f, 0.6f, 0.6f, 0.7f, 1.0f);
                cardX += 0.36f;
            }

            // 2. Render Children
            for (AssetMeta asset : visibleAssets) {
                if (cardX + 0.35f > 0.95f) break;

                boolean isFolder = (asset.type == AssetType.FOLDER);

                if (isFolder) {
                    // Folder Card Style (Warm Amber / Dark Gold)
                    UIGeometryBatch.addQuad(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.18f, 0.14f, 0.08f, 1.0f);
                    UIGeometryBatch.addFrameBorder(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.8f, 0.6f, 0.2f, 1.0f);

                    UIGeometryBatch.addQuad(buffer, cardX + 0.02f, -0.66f, cardX + 0.18f, -0.58f, 0.65f, 0.45f, 0.10f, 1.0f);
                    UIGeometryBatch.addBoundedText(buffer, ".DIR", cardX + 0.03f, -0.60f, cardX + 0.17f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addBoundedText(buffer, "DIR: " + asset.name, cardX + 0.02f, -0.75f, cardX + 0.30f, 1.1f, 1.0f, 0.9f, 0.5f, 1.0f);
                    UIGeometryBatch.addBoundedText(buffer, "[CLICK: OPEN]", cardX + 0.02f, -0.85f, cardX + 0.30f, 0.9f, 1.0f, 0.8f, 0.3f, 1.0f);
                } else {
                    // Standard Asset Card Style
                    UIGeometryBatch.addQuad(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.10f, 0.10f, 0.13f, 1.0f);
                    UIGeometryBatch.addFrameBorder(buffer, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.2f, 0.2f, 0.25f, 1.0f);

                    UIGeometryBatch.addQuad(buffer, cardX + 0.02f, -0.66f, cardX + 0.18f, -0.58f, 0.0f, 0.45f, 0.65f, 1.0f);
                    UIGeometryBatch.addBoundedText(buffer, "." + asset.type.name(), cardX + 0.03f, -0.60f, cardX + 0.17f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                    UIGeometryBatch.addBoundedText(buffer, asset.name, cardX + 0.02f, -0.75f, cardX + 0.30f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                    UIGeometryBatch.addBoundedText(buffer, asset.type == AssetType.MESH_3D ? "[CLICK: SPAWN]" : "[CLICK: LINK]", cardX + 0.02f, -0.85f, cardX + 0.30f, 0.9f, 0.2f, 0.8f, 1.0f, 1.0f);
                }

                cardX += 0.36f;
            }
        }
    }
    public static void render(List<Float> verts, List<AssetMeta> assetDirectory, String currentFolderPath) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, assetDirectory, currentFolderPath);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}