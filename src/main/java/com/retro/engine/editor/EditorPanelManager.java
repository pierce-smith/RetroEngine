package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state and 2D UI vertex generation
 * for the Retro Engine Editor:
 * 1. Top Menu Bar (Header & Status)
 * 2. Scene Manager (Hierarchy) - Left Panel
 * 3. 3D Viewport (Render Target Frame) - Center Panel
 * 4. Inspector (Entity Properties & Transform Readouts) - Right Panel
 * 5. Asset Directory (Project Resource Files) - Bottom Panel
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

    private int windowWidth = 1280;
    private int windowHeight = 720;

    // Generated 2D UI Vertex Buffer for Metal RHI (Stride: 7 floats -> x, y, z, r, g, b, a)
    private float[] uiVertices = new float[0];
    private int uiVertexCount = 0;

    public EditorPanelManager() {
        // Default scene setup
        SceneEntity camera = new SceneEntity("env_cam_01", "Main Camera", null);
        camera.transform.posZ = 5.0f;
        sceneEntities.add(camera);

        SceneEntity light = new SceneEntity("env_light_01", "Directional Light", null);
        light.transform.posY = 10.0f;
        sceneEntities.add(light);

        // Pre-populate asset directory with core project files
        assetDirectory.add(new AssetEntry("PottedPlant.obj", "/assets/models/PottedPlant.obj", AssetType.MESH_3D));
        assetDirectory.add(new AssetEntry("DefaultShader.metal", "/assets/shaders/DefaultShader.metal", AssetType.SHADER));
        assetDirectory.add(new AssetEntry("GridTexture.png", "/assets/textures/GridTexture.png", AssetType.TEXTURE));

        rebuildUiMesh();
    }

    public void update(float deltaTime) {
        // Periodically rebuild 2D UI vertex geometry to reflect selection & transform updates
        rebuildUiMesh();
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = resolveAssetType(ext);
            AssetEntry asset = new AssetEntry(name, path, type);
            assetDirectory.add(asset);
            System.out.println("[Asset Directory] Registered new asset: " + asset);

            if (type == AssetType.MESH_3D) {
                String entityId = "mesh_entity_" + (sceneEntities.size() + 1);
                String entityName = removeExtension(name);
                SceneEntity newEntity = new SceneEntity(entityId, entityName, path);

                sceneEntities.add(newEntity);
                selectEntity(newEntity);

                System.out.println("[Scene Manager] Spawning new 3D Entity into Scene: " + entityName + " (" + entityId + ")");
            }
        }
        rebuildUiMesh();
    }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
        rebuildUiMesh();
    }

    public float[] getUiVertices() { return uiVertices; }
    public int getUiVertexCount() { return uiVertexCount; }

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }

    // =========================================================================
    // 2D UI Mesh Generation (Solid Panels, Borders, Headers & Retro Font)
    // =========================================================================

    private void rebuildUiMesh() {
        List<Float> verts = new ArrayList<>();

        float w = (float) windowWidth;
        float h = (float) windowHeight;

        // 1. Top Menu Bar (y: 0 -> 30)
        addRect(verts, 0, 0, w, 30, 0.10f, 0.11f, 0.13f, 1.0f);
        addRect(verts, 0, 28, w, 2, 0.28f, 0.32f, 0.40f, 1.0f); // Bottom border
        drawText(verts, "RETRO ENGINE EDIT v1.0 | RHI: APPLE METAL", 12, 8, 1.8f, 0.9f, 0.9f, 0.95f, 1.0f);

        // 2. Left Panel: Scene Hierarchy (x: 0 -> 270, y: 30 -> 480)
        addRect(verts, 0, 30, 270, 450, 0.15f, 0.17f, 0.20f, 1.0f); // Panel Body
        addRect(verts, 0, 30, 270, 26, 0.22f, 0.25f, 0.32f, 1.0f);  // Header
        addRect(verts, 268, 30, 2, 450, 0.30f, 0.34f, 0.42f, 1.0f); // Right Border
        drawText(verts, "SCENE HIERARCHY", 12, 36, 1.6f, 0.7f, 0.85f, 1.0f, 1.0f);

        // Render Scene Entities
        float itemY = 65;
        for (SceneEntity entity : sceneEntities) {
            if (entity.selected) {
                addRect(verts, 8, itemY - 2, 252, 22, 0.25f, 0.40f, 0.65f, 1.0f); // Highlight selection
                drawText(verts, "> " + entity.name, 14, itemY + 2, 1.5f, 1.0f, 1.0f, 1.0f, 1.0f);
            } else {
                drawText(verts, "  " + entity.name, 14, itemY + 2, 1.5f, 0.75f, 0.78f, 0.82f, 1.0f);
            }
            itemY += 26;
        }

        // 3. Center Panel: 3D Viewport Frame (x: 270 -> 1000, y: 30 -> 480)
        // Viewport Header Bar
        addRect(verts, 270, 30, 730, 26, 0.20f, 0.22f, 0.28f, 1.0f);
        drawText(verts, "3D VIEWPORT [ACTIVE RENDER TARGET]", 282, 36, 1.6f, 0.65f, 0.90f, 0.70f, 1.0f);

        // Viewport Outer Frame / Borders surrounding the center 3D viewport
        addRect(verts, 270, 56, 730, 2, 0.30f, 0.34f, 0.42f, 1.0f);   // Top inner border
        addRect(verts, 270, 478, 730, 2, 0.30f, 0.34f, 0.42f, 1.0f);  // Bottom inner border

        // 4. Right Panel: Inspector (x: 1000 -> 1280, y: 30 -> 480)
        addRect(verts, 1000, 30, 280, 450, 0.15f, 0.17f, 0.20f, 1.0f); // Panel Body
        addRect(verts, 1000, 30, 280, 26, 0.22f, 0.25f, 0.32f, 1.0f);  // Header
        addRect(verts, 1000, 30, 2, 450, 0.30f, 0.34f, 0.42f, 1.0f);   // Left Border
        drawText(verts, "INSPECTOR", 1012, 36, 1.6f, 0.7f, 0.85f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            drawText(verts, "NAME: " + selectedEntity.name, 1012, 70, 1.5f, 0.9f, 0.9f, 0.9f, 1.0f);
            drawText(verts, "ID: " + selectedEntity.id, 1012, 92, 1.4f, 0.6f, 0.65f, 0.7f, 1.0f);

            // Transform Property Boxes
            drawText(verts, "TRANSFORM", 1012, 125, 1.5f, 0.95f, 0.80f, 0.40f, 1.0f);

            // Position
            addRect(verts, 1012, 148, 256, 24, 0.10f, 0.12f, 0.15f, 1.0f);
            drawText(verts, String.format("POS  X:%.1f Y:%.1f Z:%.1f",
                    selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ),
                    1018, 154, 1.4f, 0.85f, 0.88f, 0.92f, 1.0f);

            // Rotation
            addRect(verts, 1012, 180, 256, 24, 0.10f, 0.12f, 0.15f, 1.0f);
            drawText(verts, String.format("ROT  X:%.1f Y:%.1f Z:%.1f",
                    selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ),
                    1018, 186, 1.4f, 0.85f, 0.88f, 0.92f, 1.0f);

            // Scale
            addRect(verts, 1012, 212, 256, 24, 0.10f, 0.12f, 0.15f, 1.0f);
            drawText(verts, String.format("SCALE X:%.1f Y:%.1f Z:%.1f",
                    selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ),
                    1018, 218, 1.4f, 0.85f, 0.88f, 0.92f, 1.0f);
        } else {
            drawText(verts, "NO ENTITY SELECTED", 1012, 80, 1.4f, 0.5f, 0.55f, 0.6f, 1.0f);
        }

        // 5. Bottom Panel: Asset Directory (x: 0 -> 1280, y: 480 -> 720)
        addRect(verts, 0, 480, w, 240, 0.12f, 0.14f, 0.17f, 1.0f); // Panel Body
        addRect(verts, 0, 480, w, 26, 0.18f, 0.21f, 0.26f, 1.0f);  // Header
        addRect(verts, 0, 480, w, 2, 0.30f, 0.34f, 0.42f, 1.0f);   // Top Border
        drawText(verts, "ASSET DIRECTORY [PROJECT FILES]", 12, 486, 1.6f, 0.7f, 0.85f, 1.0f, 1.0f);

        float assetX = 16;
        float assetY = 518;
        for (AssetEntry asset : assetDirectory) {
            addRect(verts, assetX, assetY, 190, 42, 0.18f, 0.22f, 0.28f, 1.0f); // Asset card
            addRect(verts, assetX, assetY, 4, 42, 0.35f, 0.60f, 0.90f, 1.0f);   // Type indicator badge
            drawText(verts, asset.name, assetX + 10, assetY + 8, 1.3f, 0.9f, 0.92f, 0.95f, 1.0f);
            drawText(verts, "[" + asset.type + "]", assetX + 10, assetY + 24, 1.1f, 0.55f, 0.60f, 0.70f, 1.0f);

            assetX += 202;
            if (assetX + 190 > w - 16) {
                assetX = 16;
                assetY += 48;
            }
        }

        // Convert list to primitive array
        this.uiVertices = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) {
            this.uiVertices[i] = verts.get(i);
        }
        this.uiVertexCount = this.uiVertices.length / 7;
    }

    // Helper: Converts 2D Pixel Coordinates to Normalized Device Coordinates (-1.0 to +1.0)
    private void addRect(List<Float> verts, float px, float py, float pw, float ph, float r, float g, float b, float a) {
        float x1 = (px / (float) windowWidth) * 2.0f - 1.0f;
        float y1 = 1.0f - (py / (float) windowHeight) * 2.0f;
        float x2 = ((px + pw) / (float) windowWidth) * 2.0f - 1.0f;
        float y2 = 1.0f - ((py + ph) / (float) windowHeight) * 2.0f;

        // Triangle 1
        addVertex(verts, x1, y1, r, g, b, a);
        addVertex(verts, x2, y1, r, g, b, a);
        addVertex(verts, x1, y2, r, g, b, a);

        // Triangle 2
        addVertex(verts, x2, y1, r, g, b, a);
        addVertex(verts, x2, y2, r, g, b, a);
        addVertex(verts, x1, y2, r, g, b, a);
    }

    private void addVertex(List<Float> verts, float x, float y, float r, float g, float b, float a) {
        verts.add(x);
        verts.add(y);
        verts.add(0.0f); // z = 0 for 2D UI
        verts.add(r);
        verts.add(g);
        verts.add(b);
        verts.add(a);
    }

    // Helper: Retro Vector Text Drawer
    private void drawText(List<Float> verts, String text, float x, float y, float scale, float r, float g, float b, float a) {
        if (text == null) return;
        text = text.toUpperCase();
        float curX = x;
        float charW = 5.0f * scale;
        float charH = 7.0f * scale;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            drawGlyph(verts, c, curX, y, scale, r, g, b, a);
            curX += charW + (2.0f * scale);
        }
    }

    private void drawGlyph(List<Float> verts, char c, float x, float y, float scale, float r, float g, float b, float a) {
        // Draw character representation using compact pixel quads
        byte[] glyphData = getGlyphBitmap(c);
        if (glyphData == null) return;

        float pSize = scale;
        for (int row = 0; row < 7; row++) {
            byte bRow = glyphData[row];
            for (int col = 0; col < 5; col++) {
                if ((bRow & (1 << (4 - col))) != 0) {
                    addRect(verts, x + col * pSize, y + row * pSize, pSize, pSize, r, g, b, a);
                }
            }
        }
    }

    private byte[] getGlyphBitmap(char c) {
        return switch (c) {
            case 'A' -> new byte[]{0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11};
            case 'B' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E};
            case 'C' -> new byte[]{0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E};
            case 'D' -> new byte[]{0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C};
            case 'E' -> new byte[]{0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F};
            case 'F' -> new byte[]{0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10};
            case 'G' -> new byte[]{0x0E, 0x11, 0x10, 0x13, 0x11, 0x11, 0x0F};
            case 'H' -> new byte[]{0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11};
            case 'I' -> new byte[]{0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E};
            case 'J' -> new byte[]{0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C};
            case 'K' -> new byte[]{0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11};
            case 'L' -> new byte[]{0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F};
            case 'M' -> new byte[]{0x11, 0x1B, 0x15, 0x11, 0x11, 0x11, 0x11};
            case 'N' -> new byte[]{0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11};
            case 'O' -> new byte[]{0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E};
            case 'P' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10};
            case 'Q' -> new byte[]{0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D};
            case 'R' -> new byte[]{0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11};
            case 'S' -> new byte[]{0x0E, 0x11, 0x10, 0x0E, 0x01, 0x11, 0x0E};
            case 'T' -> new byte[]{0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04};
            case 'U' -> new byte[]{0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E};
            case 'V' -> new byte[]{0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04};
            case 'W' -> new byte[]{0x11, 0x11, 0x11, 0x11, 0x15, 0x15, 0x0A};
            case 'X' -> new byte[]{0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11};
            case 'Y' -> new byte[]{0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04};
            case 'Z' -> new byte[]{0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F};
            case '0' -> new byte[]{0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E};
            case '1' -> new byte[]{0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E};
            case '2' -> new byte[]{0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F};
            case '3' -> new byte[]{0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E};
            case '4' -> new byte[]{0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02};
            case '5' -> new byte[]{0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E};
            case '6' -> new byte[]{0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E};
            case '7' -> new byte[]{0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08};
            case '8' -> new byte[]{0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E};
            case '9' -> new byte[]{0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C};
            case ':' -> new byte[]{0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00};
            case '.' -> new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C};
            case '-' -> new byte[]{0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00};
            case '/' -> new byte[]{0x01, 0x02, 0x04, 0x08, 0x10, 0x00, 0x00};
            case '[' -> new byte[]{0x0E, 0x08, 0x08, 0x08, 0x08, 0x08, 0x0E};
            case ']' -> new byte[]{0x0E, 0x02, 0x02, 0x02, 0x02, 0x02, 0x0E};
            case '>' -> new byte[]{0x10, 0x08, 0x04, 0x02, 0x04, 0x08, 0x10};
            case '|' -> new byte[]{0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04};
            default -> new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
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

    private AssetType resolveAssetType(String extension) {
        return switch (extension) {
            case "obj", "gltf", "glb", "fbx" -> AssetType.MESH_3D;
            case "png", "jpg", "jpeg", "tga", "bmp" -> AssetType.TEXTURE;
            case "metal", "glsl", "hlsl", "spv" -> AssetType.SHADER;
            default -> AssetType.UNKNOWN;
        };
    }
}
