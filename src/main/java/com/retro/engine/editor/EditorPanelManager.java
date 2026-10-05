package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state for the Retro Engine Editor:
 * 1. Top Navigation Bar
 * 2. Scene Manager (Hierarchy) - Left Panel
 * 3. 3D Viewport Frame - Center Panel
 * 4. Inspector (Entity Properties & Transform) - Right Panel
 * 5. Asset Directory - Bottom Panel
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
            return "[" + type + "] " + name;
        }
    }

    public static class Transform {
        public float posX = 0.0f, posY = 0.0f, posZ = 0.0f;
        public float rotX = 0.0f, rotY = 0.0f, rotZ = 0.0f;
        public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;

        @Override
        public String toString() {
            return String.format("Pos(%.2f, %.2f, %.2f) Rot(%.2f, %.2f, %.2f) Scl(%.2f, %.2f, %.2f)",
                    posX, posY, posZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ);
        }
    }

    public static class SceneEntity {
        public final String id;
        public String name;
        public final Transform transform = new Transform();
        public String meshAssetPath;
        public int vertexCount = 0;
        public boolean selected = false;

        public SceneEntity(String id, String name, String meshAssetPath) {
            this.id = id;
            this.name = name;
            this.meshAssetPath = meshAssetPath;
        }
    }

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private SceneEntity selectedEntity = null;

    private float[] uiVertexBuffer = new float[100000];
    private int uiVertexCount = 0;

    // 5x7 Retro Font Bitmaps (ASCII 0..127)
    private static final byte[][] FONT_5X7 = new byte[128][7];

    static {
        // A..Z
        FONT_5X7['A'] = new byte[]{0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
        FONT_5X7['B'] = new byte[]{0b11110, 0b10001, 0b11110, 0b10001, 0b10001, 0b10001, 0b11110};
        FONT_5X7['C'] = new byte[]{0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111};
        FONT_5X7['D'] = new byte[]{0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100};
        FONT_5X7['E'] = new byte[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111};
        FONT_5X7['F'] = new byte[]{0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000};
        FONT_5X7['G'] = new byte[]{0b01111, 0b10000, 0b10000, 0b10011, 0b10001, 0b10001, 0b01110};
        FONT_5X7['H'] = new byte[]{0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001};
        FONT_5X7['I'] = new byte[]{0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
        FONT_5X7['J'] = new byte[]{0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100};
        FONT_5X7['K'] = new byte[]{0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001};
        FONT_5X7['L'] = new byte[]{0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111};
        FONT_5X7['M'] = new byte[]{0b10001, 0b11011, 0b10101, 0b10001, 0b10001, 0b10001, 0b10001};
        FONT_5X7['N'] = new byte[]{0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001};
        FONT_5X7['O'] = new byte[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
        FONT_5X7['P'] = new byte[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000};
        FONT_5X7['Q'] = new byte[]{0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101};
        FONT_5X7['R'] = new byte[]{0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001};
        FONT_5X7['S'] = new byte[]{0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110};
        FONT_5X7['T'] = new byte[]{0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100};
        FONT_5X7['U'] = new byte[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110};
        FONT_5X7['V'] = new byte[]{0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100};
        FONT_5X7['W'] = new byte[]{0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001};
        FONT_5X7['X'] = new byte[]{0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001};
        FONT_5X7['Y'] = new byte[]{0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100};
        FONT_5X7['Z'] = new byte[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111};

        // 0..9
        FONT_5X7['0'] = new byte[]{0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110};
        FONT_5X7['1'] = new byte[]{0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110};
        FONT_5X7['2'] = new byte[]{0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111};
        FONT_5X7['3'] = new byte[]{0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110};
        FONT_5X7['4'] = new byte[]{0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010};
        FONT_5X7['5'] = new byte[]{0b11111, 0b10000, 0b10000, 0b11110, 0b00001, 0b00001, 0b11110};
        FONT_5X7['6'] = new byte[]{0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110};
        FONT_5X7['7'] = new byte[]{0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000};
        FONT_5X7['8'] = new byte[]{0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110};
        FONT_5X7['9'] = new byte[]{0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110};

        // Symbols
        FONT_5X7[':'] = new byte[]{0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000};
        FONT_5X7['-'] = new byte[]{0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000};
        FONT_5X7['.'] = new byte[]{0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100};
        FONT_5X7['_'] = new byte[]{0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111};
        FONT_5X7['('] = new byte[]{0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010};
        FONT_5X7[')'] = new byte[]{0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000};
        FONT_5X7['['] = new byte[]{0b01110, 0b01000, 0b01000, 0b01000, 0b01000, 0b01000, 0b01110};
        FONT_5X7[']'] = new byte[]{0b01110, 0b00010, 0b00010, 0b00010, 0b00010, 0b00010, 0b01110};
        FONT_5X7['/'] = new byte[]{0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b00000, 0b00000};
        FONT_5X7['>'] = new byte[]{0b10000, 0b01000, 0b00100, 0b00010, 0b00100, 0b01000, 0b10000};
        FONT_5X7['<'] = new byte[]{0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010};
        FONT_5X7['|'] = new byte[]{0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100};
    }

    public EditorPanelManager() {
        rebuildUiGeometry();
    }

    public void update(float deltaTime) {
        rebuildUiGeometry();
    }

    public float[] generateUiVertices() {
        return getUiVertices();
    }

    public float[] getUiVertices() {
        return uiVertexBuffer;
    }

    public int getUiVertexCount() {
        return uiVertexCount;
    }

    public void renderImGuiLayout() {
        renderImGuiLayout(0L);
    }

    public void renderImGuiLayout(long viewportTextureHandle) {}

    private void rebuildUiGeometry() {
        uiVertexCount = 0;

        // 1. Top Navigation Bar (0, 0 to 1280, 32)
        addQuad(0, 0, 1280, 32, 0.12f, 0.12f, 0.14f, 1.0f);
        addQuad(0, 30, 1280, 2, 0.22f, 0.22f, 0.25f, 1.0f);
        addTextClipped("RETRO ENGINE STUDIO  |  WASD: NAV  |  RMB: ORBIT  |  F: FOCUS", 12, 10, 1260, 1.0f, 0.85f, 0.3f, 1.0f, 2);

        // 2. Left Panel - Scene Hierarchy (0, 32 to 280, 520)
        addQuad(0, 32, 280, 488, 0.09f, 0.09f, 0.11f, 1.0f);
        addQuad(0, 32, 280, 28, 0.15f, 0.15f, 0.18f, 1.0f);
        addQuad(278, 32, 2, 488, 0.20f, 0.20f, 0.24f, 1.0f);
        addTextClipped("SCENE HIERARCHY", 12, 40, 270, 0.9f, 0.9f, 0.95f, 1.0f, 2);

        if (sceneEntities.isEmpty()) {
            addTextClipped("NO ENTITIES IN SCENE", 12, 75, 270, 0.5f, 0.5f, 0.55f, 1.0f, 2);
            addTextClipped("DROP 3D MODEL TO SPAWN", 12, 95, 270, 0.4f, 0.4f, 0.45f, 1.0f, 2);
        } else {
            int yPos = 70;
            for (SceneEntity e : sceneEntities) {
                if (e.selected) {
                    addQuad(4, yPos - 2, 270, 22, 0.20f, 0.35f, 0.65f, 1.0f);
                    addTextClipped("> " + e.name.toUpperCase(), 10, yPos + 2, 270, 1.0f, 1.0f, 1.0f, 1.0f, 2);
                } else {
                    addTextClipped("  " + e.name.toUpperCase(), 10, yPos + 2, 270, 0.8f, 0.8f, 0.85f, 1.0f, 2);
                }
                yPos += 26;
            }
        }

        // 3. Center Panel - 3D Viewport Header Frame (280, 32 to 1000, 60)
        addQuad(280, 32, 720, 28, 0.13f, 0.13f, 0.16f, 1.0f);
        addQuad(280, 60, 2, 460, 0.20f, 0.20f, 0.24f, 1.0f);
        addQuad(998, 60, 2, 460, 0.20f, 0.20f, 0.24f, 1.0f);
        addQuad(280, 518, 720, 2, 0.20f, 0.20f, 0.24f, 1.0f);
        addTextClipped("3D VIEWPORT CANVAS", 292, 40, 990, 0.9f, 0.9f, 0.95f, 1.0f, 2);

        // 4. Right Panel - Inspector (1000, 32 to 1280, 520)
        addQuad(1000, 32, 280, 488, 0.09f, 0.09f, 0.11f, 1.0f);
        addQuad(1000, 32, 280, 28, 0.15f, 0.15f, 0.18f, 1.0f);
        addQuad(1000, 32, 2, 488, 0.20f, 0.20f, 0.24f, 1.0f);
        addTextClipped("INSPECTOR", 1012, 40, 1270, 0.9f, 0.9f, 0.95f, 1.0f, 2);

        if (selectedEntity == null) {
            addTextClipped("NO SELECTION", 1012, 75, 1270, 0.5f, 0.5f, 0.55f, 1.0f, 2);
        } else {
            addTextClipped("ENTITY: " + selectedEntity.name.toUpperCase(), 1012, 75, 1270, 0.3f, 0.8f, 1.0f, 1.0f, 2);
            addTextClipped("ID: " + selectedEntity.id.toUpperCase(), 1012, 95, 1270, 0.7f, 0.7f, 0.75f, 1.0f, 2);

            addQuad(1010, 118, 260, 1, 0.25f, 0.25f, 0.28f, 1.0f);

            addTextClipped("TRANSFORM READOUT:", 1012, 130, 1270, 0.9f, 0.7f, 0.2f, 1.0f, 2);

            String posStr = String.format("POS: [%.1f, %.1f, %.1f]", selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ);
            String rotStr = String.format("ROT: [%.1f, %.1f, %.1f]", selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ);
            String sclStr = String.format("SCL: [%.1f, %.1f, %.1f]", selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ);

            addTextClipped(posStr, 1012, 155, 1270, 0.85f, 0.85f, 0.9f, 1.0f, 2);
            addTextClipped(rotStr, 1012, 175, 1270, 0.85f, 0.85f, 0.9f, 1.0f, 2);
            addTextClipped(sclStr, 1012, 195, 1270, 0.85f, 0.85f, 0.9f, 1.0f, 2);

            if (selectedEntity.meshAssetPath != null) {
                addQuad(1010, 220, 260, 1, 0.25f, 0.25f, 0.28f, 1.0f);
                addTextClipped("MESH RESOURCE:", 1012, 232, 1270, 0.9f, 0.7f, 0.2f, 1.0f, 2);
                String fileName = new File(selectedEntity.meshAssetPath).getName().toUpperCase();
                addTextClipped(fileName, 1012, 252, 1270, 0.4f, 0.9f, 0.5f, 1.0f, 2);
            }
        }

        // 5. Bottom Panel - Asset Directory (0, 520 to 1280, 720)
        addQuad(0, 520, 1280, 200, 0.08f, 0.08f, 0.10f, 1.0f);
        addQuad(0, 520, 1280, 28, 0.13f, 0.13f, 0.16f, 1.0f);
        addQuad(0, 520, 1280, 2, 0.20f, 0.20f, 0.24f, 1.0f);
        addTextClipped("ASSET DIRECTORY (PROJECT RESOURCES)", 12, 528, 1260, 0.9f, 0.9f, 0.95f, 1.0f, 2);

        if (assetDirectory.isEmpty()) {
            addTextClipped("NO RESOURCE ASSETS REGISTERED YET", 12, 560, 1260, 0.5f, 0.5f, 0.55f, 1.0f, 2);
        } else {
            int xPos = 12;
            int yPos = 558;
            for (AssetEntry a : assetDirectory) {
                addQuad(xPos, yPos, 140, 48, 0.15f, 0.15f, 0.19f, 1.0f);
                addQuad(xPos, yPos, 140, 2, 0.30f, 0.50f, 0.80f, 1.0f);
                addTextClipped(a.type.name(), xPos + 6, yPos + 6, xPos + 134, 0.4f, 0.8f, 1.0f, 1.0f, 2);
                String shortName = a.name.length() > 10 ? a.name.substring(0, 10).toUpperCase() : a.name.toUpperCase();
                addTextClipped(shortName, xPos + 6, yPos + 26, xPos + 134, 0.9f, 0.9f, 0.9f, 1.0f, 2);

                xPos += 150;
                if (xPos + 140 > 1260) {
                    xPos = 12;
                    yPos += 54;
                }
            }
        }
    }

    private void addTextClipped(String text, int screenX, int screenY, int maxScreenX, float r, float g, float b, float a, int scale) {
        if (text == null) return;
        text = text.toUpperCase();
        int curX = screenX;
        for (int i = 0; i < text.length(); i++) {
            if (curX + (5 * scale) > maxScreenX) break; // Clip text at panel boundary
            char c = text.charAt(i);
            if (c >= 0 && c < 128 && FONT_5X7[c] != null && FONT_5X7[c].length == 7) {
                byte[] bitmap = FONT_5X7[c];
                for (int row = 0; row < 7; row++) {
                    byte bRow = bitmap[row];
                    for (int col = 0; col < 5; col++) {
                        if ((bRow & (1 << (4 - col))) != 0) {
                            addQuad(curX + col * scale, screenY + row * scale, scale, scale, r, g, b, a);
                        }
                    }
                }
            }
            curX += (5 + 1) * scale;
        }
    }

    private void addQuad(int screenX, int screenY, int width, int height, float r, float g, float b, float a) {
        float x0 = (screenX / 1280.0f) * 2.0f - 1.0f;
        float y0 = 1.0f - (screenY / 720.0f) * 2.0f;
        float x1 = ((screenX + width) / 1280.0f) * 2.0f - 1.0f;
        float y1 = 1.0f - ((screenY + height) / 720.0f) * 2.0f;

        ensureCapacity(6 * 7);

        // Triangle 1
        putVertex(x0, y0, r, g, b, a);
        putVertex(x0, y1, r, g, b, a);
        putVertex(x1, y0, r, g, b, a);

        // Triangle 2
        putVertex(x1, y0, r, g, b, a);
        putVertex(x0, y1, r, g, b, a);
        putVertex(x1, y1, r, g, b, a);
    }

    private void putVertex(float x, float y, float r, float g, float b, float a) {
        int idx = uiVertexCount * 7;
        uiVertexBuffer[idx]     = x;
        uiVertexBuffer[idx + 1] = y;
        uiVertexBuffer[idx + 2] = 0.0f; // Metal NDC depth 0.0
        uiVertexBuffer[idx + 3] = r;
        uiVertexBuffer[idx + 4] = g;
        uiVertexBuffer[idx + 5] = b;
        uiVertexBuffer[idx + 6] = a;
        uiVertexCount++;
    }

    private void ensureCapacity(int additionalFloats) {
        int needed = uiVertexCount * 7 + additionalFloats;
        if (needed > uiVertexBuffer.length) {
            float[] newBuf = new float[uiVertexBuffer.length * 2];
            System.arraycopy(uiVertexBuffer, 0, newBuf, 0, uiVertexBuffer.length);
            uiVertexBuffer = newBuf;
        }
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
        rebuildUiGeometry();
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

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }

    private AssetType resolveAssetType(String extension) {
        return switch (extension) {
            case "obj", "gltf", "glb", "fbx", "dae" -> AssetType.MESH_3D;
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
}
