package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state for the Retro Engine Editor:
 * 1. Scene Manager (Hierarchy) - Left Panel (0..260, 30..520)
 * 2. 3D Viewport (Render Target) - Center Panel (260..1020, 30..520)
 * 3. Inspector (Entity Properties & Transform) - Right Panel (1020..1280, 30..520)
 * 4. Asset Directory (Project Resource Files) - Bottom Panel (0..1280, 520..720)
 */
public class EditorPanelManager {

    public enum AssetType { MESH_3D, TEXTURE, SHADER, UNKNOWN }

    public static class AssetEntry {
        public final String name;
        public final String absolutePath;
        public final AssetType type;

        public AssetEntry(String name, String absolutePath, AssetType type) {
            this.name = name;
            this.absolutePath = absolutePath;
            this.type = type;
        }

        @Override
        public String toString() {
            return "[" + type + "] " + name + " (" + absolutePath + ")";
        }
    }

    public static class Transform {
        public float posX = 0.0f, posY = 0.0f, posZ = 0.0f;
        public float rotX = 0.0f, rotY = 0.0f, rotZ = 0.0f;
        public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;

        @Override
        public String toString() {
            return String.format("Pos(%.2f, %.2f, %.2f) Rot(%.2f, %.2f, %.2f) Scale(%.2f, %.2f, %.2f)",
                    posX, posY, posZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ);
        }
    }

    public static class SceneEntity {
        public final String id;
        public String name;
        public final Transform transform = new Transform();
        public String meshAssetPath;
        public boolean selected = false;

        public SceneEntity(String id, String name, String meshAssetPath) {
            this.id = id;
            this.name = name;
            this.meshAssetPath = meshAssetPath;
        }
    }

    // Panel States
    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private SceneEntity selectedEntity = null;

    private int viewportWidth = 1280;
    private int viewportHeight = 720;
    private int targetInternalWidth = 640;   // Retro low-res buffer
    private int targetInternalHeight = 480;

    public EditorPanelManager() {
        // Initialize default scene setup
        SceneEntity camera = new SceneEntity("env_cam_01", "Main Camera", null);
        camera.transform.posZ = 5.0f;
        sceneEntities.add(camera);

        SceneEntity light = new SceneEntity("env_light_01", "Directional Light", null);
        light.transform.posY = 10.0f;
        sceneEntities.add(light);
    }

    public void update(float deltaTime) {
        // Frame tick processing for editor state updates
    }

    /**
     * Entry point for GLFW Native Drag-and-Drop.
     * Receives dropped file paths from Mac Finder / Desktop.
     */
    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = resolveAssetType(ext);
            AssetEntry asset = new AssetEntry(name, path, type);
            assetDirectory.add(asset);
            System.out.println("[Asset Directory] Registered new asset: " + asset);

            // If a 3D model is dropped, automatically instantiate it into the Scene Hierarchy
            if (type == AssetType.MESH_3D) {
                String entityId = "mesh_entity_" + (sceneEntities.size() + 1);
                String entityName = removeExtension(name);
                SceneEntity newEntity = new SceneEntity(entityId, entityName, path);

                sceneEntities.add(newEntity);
                selectEntity(newEntity);

                System.out.println("[Scene Manager] Spawning new 3D Entity into Scene: " + entityName + " (" + entityId + ")");
            }
        }
    }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
        if (entity != null) {
            System.out.println("[Inspector] Selected Entity: " + entity.name + " -> " + entity.transform);
        }
    }

    public List<SceneEntity> getSceneEntities() {
        return Collections.unmodifiableList(sceneEntities);
    }

    public List<AssetEntry> getAssetDirectory() {
        return Collections.unmodifiableList(assetDirectory);
    }

    public SceneEntity getSelectedEntity() {
        return selectedEntity;
    }

    public void setViewportSize(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    public void setRetroInternalResolution(int width, int height) {
        this.targetInternalWidth = width;
        this.targetInternalHeight = height;
        System.out.println("[Viewport] Set internal retro render buffer: " + width + "x" + height);
    }

    public int getViewportWidth() { return viewportWidth; }
    public int getViewportHeight() { return viewportHeight; }
    public int getTargetInternalWidth() { return targetInternalWidth; }
    public int getTargetInternalHeight() { return targetInternalHeight; }

    /**
     * Generates 2D UI vertex quads for rendering the 4 visual editor panels.
     * Stride per vertex: x, y, r, g, b, a (6 floats) in NDC (-1.0 to +1.0).
     */
    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();

        // 1. Top Bar (0, 0, 1280, 30) - Dark Bar
        addRect(verts, 0, 0, 1280, 30, 0.10f, 0.10f, 0.12f, 0.95f);
        addText(verts, "RETRO ENGINE SHELL - VISUAL EDITOR IDE", 10, 8, 2, 0.90f, 0.90f, 0.95f, 1.0f);
        addText(verts, "DRAG & DROP .OBJ/.GLTF INTO VIEWPORT", 850, 8, 2, 0.50f, 0.85f, 0.50f, 1.0f);

        // 2. Left Panel: Scene Manager / Hierarchy (0, 30, 260, 490)
        addRect(verts, 0, 30, 260, 490, 0.14f, 0.14f, 0.16f, 0.90f);
        addRect(verts, 0, 30, 260, 26, 0.18f, 0.18f, 0.22f, 0.95f);
        addText(verts, "SCENE HIERARCHY", 10, 36, 2, 0.85f, 0.85f, 0.90f, 1.0f);

        int yOffset = 65;
        for (SceneEntity entity : sceneEntities) {
            if (entity.selected) {
                addRect(verts, 5, yOffset - 3, 250, 22, 0.22f, 0.35f, 0.55f, 0.95f);
                addText(verts, "> " + entity.name, 12, yOffset, 2, 1.0f, 1.0f, 1.0f, 1.0f);
            } else {
                addText(verts, "  " + entity.name, 12, yOffset, 2, 0.70f, 0.70f, 0.75f, 1.0f);
            }
            yOffset += 24;
        }

        // 3. Center Panel: 3D Viewport Border & Header (260, 30, 760, 490)
        addRectOutline(verts, 260, 30, 760, 490, 2, 0.25f, 0.25f, 0.30f, 0.95f);
        addRect(verts, 260, 30, 760, 26, 0.16f, 0.16f, 0.18f, 0.85f);
        addText(verts, "3D VIEWPORT (METAL RHI)", 270, 36, 2, 0.40f, 0.75f, 1.0f, 1.0f);

        // 4. Right Panel: Inspector (1020, 30, 260, 490)
        addRect(verts, 1020, 30, 260, 490, 0.14f, 0.14f, 0.16f, 0.90f);
        addRect(verts, 1020, 30, 260, 26, 0.18f, 0.18f, 0.22f, 0.95f);
        addText(verts, "INSPECTOR", 1030, 36, 2, 0.85f, 0.85f, 0.90f, 1.0f);

        if (selectedEntity != null) {
            addText(verts, "ENTITY: " + selectedEntity.name, 1030, 68, 2, 1.0f, 0.85f, 0.40f, 1.0f);
            addText(verts, "ID: " + selectedEntity.id, 1030, 90, 2, 0.60f, 0.60f, 0.65f, 1.0f);

            addText(verts, "TRANSFORM", 1030, 120, 2, 0.80f, 0.80f, 0.85f, 1.0f);
            addText(verts, String.format("POS: %.1f, %.1f, %.1f", selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ), 1030, 142, 2, 0.75f, 0.75f, 0.80f, 1.0f);
            addText(verts, String.format("ROT: %.1f, %.1f, %.1f", selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ), 1030, 164, 2, 0.75f, 0.75f, 0.80f, 1.0f);
            addText(verts, String.format("SCL: %.1f, %.1f, %.1f", selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ), 1030, 186, 2, 0.75f, 0.75f, 0.80f, 1.0f);

            if (selectedEntity.meshAssetPath != null) {
                addText(verts, "MESH SOURCE:", 1030, 220, 2, 0.80f, 0.80f, 0.85f, 1.0f);
                File f = new File(selectedEntity.meshAssetPath);
                addText(verts, f.getName(), 1030, 242, 2, 0.50f, 0.85f, 0.50f, 1.0f);
            }
        } else {
            addText(verts, "NO ENTITY SELECTED", 1030, 68, 2, 0.50f, 0.50f, 0.55f, 1.0f);
        }

        // 5. Bottom Panel: Asset Directory (0, 520, 1280, 200)
        addRect(verts, 0, 520, 1280, 200, 0.12f, 0.12f, 0.14f, 0.95f);
        addRect(verts, 0, 520, 1280, 24, 0.16f, 0.16f, 0.18f, 0.95f);
        addText(verts, "ASSET DIRECTORY (PROJECT FILES)", 10, 525, 2, 0.85f, 0.85f, 0.90f, 1.0f);

        int xOffset = 15;
        for (AssetEntry asset : assetDirectory) {
            addRect(verts, xOffset, 552, 140, 140, 0.18f, 0.18f, 0.22f, 0.90f);
            addRectOutline(verts, xOffset, 552, 140, 140, 1, 0.30f, 0.30f, 0.35f, 0.95f);

            String badge = asset.type.toString();
            addText(verts, badge, xOffset + 8, 560, 2, 0.40f, 0.75f, 1.0f, 1.0f);

            String displayName = asset.name.length() > 12 ? asset.name.substring(0, 10) + ".." : asset.name;
            addText(verts, displayName, xOffset + 8, 665, 2, 0.85f, 0.85f, 0.90f, 1.0f);

            xOffset += 150;
            if (xOffset > 1200) break;
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) {
            array[i] = verts.get(i);
        }
        return array;
    }

    private void addRect(List<Float> v, float x, float y, float w, float h, float r, float g, float b, float a) {
        float x1 = (x / (float) viewportWidth) * 2.0f - 1.0f;
        float y1 = 1.0f - (y / (float) viewportHeight) * 2.0f;
        float x2 = ((x + w) / (float) viewportWidth) * 2.0f - 1.0f;
        float y2 = 1.0f - ((y + h) / (float) viewportHeight) * 2.0f;

        // Tri 1
        addVert(v, x1, y1, r, g, b, a);
        addVert(v, x2, y1, r, g, b, a);
        addVert(v, x2, y2, r, g, b, a);

        // Tri 2
        addVert(v, x1, y1, r, g, b, a);
        addVert(v, x2, y2, r, g, b, a);
        addVert(v, x1, y2, r, g, b, a);
    }

    private void addRectOutline(List<Float> v, float x, float y, float w, float h, float thickness, float r, float g, float b, float a) {
        addRect(v, x, y, w, thickness, r, g, b, a); // Top
        addRect(v, x, y + h - thickness, w, thickness, r, g, b, a); // Bottom
        addRect(v, x, y, thickness, h, r, g, b, a); // Left
        addRect(v, x + w - thickness, y, thickness, h, r, g, b, a); // Right
    }

    private void addText(List<Float> v, String text, float startX, float startY, float scale, float r, float g, float b, float a) {
        float curX = startX;
        for (char c : text.toUpperCase().toCharArray()) {
            drawChar(v, c, curX, startY, scale, r, g, b, a);
            curX += 6 * scale;
        }
    }

    private void drawChar(List<Float> v, char c, float x, float y, float scale, float r, float g, float b, float a) {
        int index = (c >= 'A' && c <= 'Z') ? (c - 'A') :
                (c >= '0' && c <= '9') ? (26 + c - '0') : 36;
        if (c == ':') index = 37;
        else if (c == '-') index = 38;
        else if (c == '.') index = 39;
        else if (c == '>') index = 40;
        else if (c == '(') index = 41;
        else if (c == ')') index = 42;
        else if (c == '&') index = 43;

        if (index == 36 && c != ' ') index = 36; // Unknown or space

        int[] bitmap = FONT_5X7.length > index ? FONT_5X7[index] : FONT_5X7[36];

        for (int row = 0; row < 7; row++) {
            int rowVal = bitmap[row];
            for (int col = 0; col < 5; col++) {
                if ((rowVal & (1 << (4 - col))) != 0) {
                    addRect(v, x + col * scale, y + row * scale, scale, scale, r, g, b, a);
                }
            }
        }
    }

    private void addVert(List<Float> v, float x, float y, float r, float g, float b, float a) {
        v.add(x); v.add(y);
        v.add(r); v.add(g); v.add(b); v.add(a);
    }

    private AssetType resolveAssetType(String extension) {
        return switch (extension) {
            case "obj", "gltf", "glb", "fbx" -> AssetType.MESH_3D;
            case "png", "jpg", "jpeg", "tga", "bmp" -> AssetType.TEXTURE;
            case "metal", "glsl", "hlsl", "spv" -> AssetType.SHADER;
            default -> AssetType.UNKNOWN;
        };
    }

    private String getFileExtension(String name) {
        int lastDot = name.lastIndexOf('.');
        return (lastDot == -1) ? "" : name.substring(lastDot + 1);
    }

    private String removeExtension(String name) {
        int lastDot = name.lastIndexOf('.');
        return (lastDot == -1) ? name : name.substring(0, lastDot);
    }

    // 5x7 ASCII Font Bitmap Table
    private static final int[][] FONT_5X7 = {
            {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, // A
            {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}, // B
            {0x0E,0x11,0x10,0x10,0x10,0x11,0x0E}, // C
            {0x1C,0x12,0x11,0x11,0x11,0x12,0x1C}, // D
            {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}, // E
            {0x1F,0x10,0x10,0x1E,0x10,0x10,0x10}, // F
            {0x0E,0x11,0x10,0x13,0x11,0x11,0x0F}, // G
            {0x11,0x11,0x11,0x1F,0x11,0x11,0x11}, // H
            {0x0E,0x04,0x04,0x04,0x04,0x04,0x0E}, // I
            {0x1F,0x02,0x02,0x02,0x02,0x12,0x0C}, // J
            {0x11,0x12,0x14,0x18,0x14,0x12,0x11}, // K
            {0x10,0x10,0x10,0x10,0x10,0x10,0x1F}, // L
            {0x11,0x1B,0x15,0x11,0x11,0x11,0x11}, // M
            {0x11,0x11,0x19,0x15,0x13,0x11,0x11}, // N
            {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E}, // O
            {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}, // P
            {0x0E,0x11,0x11,0x11,0x15,0x12,0x0D}, // Q
            {0x1E,0x11,0x11,0x1E,0x14,0x12,0x11}, // R
            {0x0E,0x11,0x10,0x0E,0x01,0x11,0x0E}, // S
            {0x1F,0x04,0x04,0x04,0x04,0x04,0x04}, // T
            {0x11,0x11,0x11,0x11,0x11,0x11,0x0E}, // U
            {0x11,0x11,0x11,0x11,0x11,0x0A,0x04}, // V
            {0x11,0x11,0x11,0x15,0x15,0x1B,0x11}, // W
            {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11}, // X
            {0x11,0x11,0x0A,0x04,0x04,0x04,0x04}, // Y
            {0x1F,0x01,0x02,0x04,0x08,0x10,0x1F}, // Z
            {0x0E,0x11,0x13,0x15,0x19,0x11,0x0E}, // 0
            {0x04,0x0C,0x04,0x04,0x04,0x04,0x0E}, // 1
            {0x0E,0x11,0x01,0x06,0x08,0x10,0x1F}, // 2
            {0x1F,0x02,0x04,0x02,0x01,0x11,0x0E}, // 3
            {0x02,0x06,0x0A,0x12,0x1F,0x02,0x02}, // 4
            {0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E}, // 5
            {0x06,0x08,0x10,0x1E,0x11,0x11,0x0E}, // 6
            {0x1F,0x01,0x02,0x04,0x08,0x08,0x08}, // 7
            {0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E}, // 8
            {0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C}, // 9
            {0x00,0x00,0x00,0x00,0x00,0x00,0x00}, // Space (36)
            {0x00,0x0C,0x0C,0x00,0x0C,0x0C,0x00}, // : (37)
            {0x00,0x00,0x00,0x1F,0x00,0x00,0x00}, // - (38)
            {0x00,0x00,0x00,0x00,0x00,0x0C,0x0C}, // . (39)
            {0x10,0x08,0x04,0x02,0x04,0x08,0x10}, // > (40)
            {0x02,0x04,0x08,0x08,0x08,0x04,0x02}, // ( (41)
            {0x08,0x04,0x02,0x02,0x02,0x04,0x08}, // ) (42)
            {0x0C,0x12,0x14,0x08,0x15,0x12,0x0D}  // & (43)
    };
}