package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state for the Retro Engine Editor:
 * 1. Scene Manager (Hierarchy) - Left Panel (X: -1.0 to -0.55, Y: -0.5 to 1.0)
 * 2. 3D Viewport (Render Target) - Center Panel (X: -0.55 to 0.55, Y: -0.5 to 1.0)
 * 3. Inspector (Entity Properties) - Right Panel (X: 0.55 to 1.0, Y: -0.5 to 1.0)
 * 4. Asset Directory (Resource Files) - Bottom Panel (X: -1.0 to 1.0, Y: -1.0 to -0.5)
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
        public String textureAssetPath;
        public int vertexCount = 0;
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
    private float[] cachedUiVertices = new float[0];

    // Compact 5x7 Font Bitmaps for ASCII UI Rendering
    private static final long[] FONT = new long[128];
    static {
        FONT['A'] = 0x1c223e2222L; FONT['B'] = 0x3c223c223cL; FONT['C'] = 0x1c2220221cL;
        FONT['D'] = 0x3824242438L; FONT['E'] = 0x3e203c203eL; FONT['F'] = 0x3e203c2020L;
        FONT['G'] = 0x1c202e221eL; FONT['H'] = 0x22223e2222L; FONT['I'] = 0x1c0808081cL;
        FONT['J'] = 0x0e04042418L; FONT['K'] = 0x2224382422L; FONT['L'] = 0x202020203eL;
        FONT['M'] = 0x22362a2222L; FONT['N'] = 0x22322a2622L; FONT['O'] = 0x1c2222221cL;
        FONT['P'] = 0x3c223c2020L; FONT['Q'] = 0x1c22222a1cL; FONT['R'] = 0x3c223c2422L;
        FONT['S'] = 0x1e201c023cL; FONT['T'] = 0x3e08080808L; FONT['U'] = 0x222222221cL;
        FONT['V'] = 0x2222221408L; FONT['W'] = 0x22222a3622L; FONT['X'] = 0x2214081422L;
        FONT['Y'] = 0x2214080808L; FONT['Z'] = 0x3e0408103eL;
        FONT['0'] = 0x1c262a321cL; FONT['1'] = 0x081808081cL; FONT['2'] = 0x1c021c203eL;
        FONT['3'] = 0x3e040e023cL; FONT['4'] = 0x24243e0404L; FONT['5'] = 0x3e203c023cL;
        FONT['6'] = 0x1c203c221cL; FONT['7'] = 0x3e02040810L; FONT['8'] = 0x1c221c221cL;
        FONT['9'] = 0x1c221e021cL;
        FONT[':'] = 0x0008000800L; FONT['-'] = 0x00003e0000L; FONT['.'] = 0x0000000008L;
        FONT['['] = 0x1c1010101cL; FONT[']'] = 0x1c0404041cL; FONT['('] = 0x0810101008L;
        FONT[')'] = 0x1008080810L; FONT['_'] = 0x000000003eL; FONT['/'] = 0x0204081020L;
        FONT['>'] = 0x2010081020L; FONT['<'] = 0x0810201008L; FONT['='] = 0x003e003e00L;
    }

    public EditorPanelManager() {
        // Scene Hierarchy starts completely empty on launch per user requirement
    }

    public void update(float deltaTime) {
        // Stationary, controlled transform execution.
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = resolveAssetType(ext);
            AssetEntry asset = new AssetEntry(name, path, type);
            assetDirectory.add(asset);
            System.out.println("[Asset Directory] Registered asset: " + asset);

            if (type == AssetType.MESH_3D) {
                String entityId = "mesh_entity_" + (sceneEntities.size() + 1);
                String entityName = removeExtension(name);
                SceneEntity newEntity = new SceneEntity(entityId, entityName, path);

                sceneEntities.add(newEntity);
                selectEntity(newEntity);

                System.out.println("[Scene Manager] Spawned 3D Entity: " + entityName + " (" + entityId + ")");
            } else if (type == AssetType.TEXTURE) {
                if (selectedEntity != null) {
                    selectedEntity.textureAssetPath = path;
                    System.out.println("[Inspector] Assigned texture asset to " + selectedEntity.name + ": " + name);
                }
            }
        }
    }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
    }

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public List<AssetEntry> getAssetDirectory() { return Collections.unmodifiableList(assetDirectory); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null || cachedUiVertices.length == 0) {
            getUiVertices();
        }
        return cachedUiVertices.length / 6; // 6 floats per 2D vertex: x, y, r, g, b, a
    }

    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();

        // 1. SCENE MANAGER (Left Panel: X -1.0 to -0.55, Y -0.5 to 1.0)
        addQuad(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        addQuad(verts, -1.0f, 0.88f, -0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f); // Top Header
        addFrameBorder(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "SCENE MANAGER", -0.96f, 0.95f, -0.57f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (sceneEntities.isEmpty()) {
            addBoundedText(verts, "EMPTY SCENE", -0.95f, 0.80f, -0.58f, 1.3f, 0.5f, 0.5f, 0.55f, 1.0f);
            addBoundedText(verts, "DROP 3D FILE TO SPAWN", -0.95f, 0.72f, -0.58f, 1.1f, 0.4f, 0.4f, 0.45f, 1.0f);
        } else {
            float itemY = 0.80f;
            for (SceneEntity entity : sceneEntities) {
                if (entity.selected) {
                    addQuad(verts, -0.98f, itemY - 0.02f, -0.57f, itemY + 0.05f, 0.0f, 0.35f, 0.70f, 1.0f);
                    addBoundedText(verts, "> " + entity.name, -0.95f, itemY + 0.03f, -0.58f, 1.3f, 1.0f, 1.0f, 1.0f, 1.0f);
                } else {
                    addBoundedText(verts, "  " + entity.name, -0.95f, itemY + 0.03f, -0.58f, 1.3f, 0.7f, 0.7f, 0.7f, 1.0f);
                }
                itemY -= 0.08f;
            }
        }

        // 2. INSPECTOR (Right Panel: X 0.55 to 1.0, Y -0.5 to 1.0)
        addQuad(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        addQuad(verts, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f); // Top Header
        addFrameBorder(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "INSPECTOR", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            addBoundedText(verts, "SELECTED: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.2f, 1.0f, 0.8f, 0.2f, 1.0f);
            addBoundedText(verts, "--- TRANSFORM ---", 0.58f, 0.70f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            addBoundedText(verts, String.format("POS: X %.1f Y %.1f Z %.1f", selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ), 0.58f, 0.62f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            addBoundedText(verts, String.format("ROT: X %.1f Y %.1f Z %.1f", selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ), 0.58f, 0.54f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            addBoundedText(verts, String.format("SCL: X %.1f Y %.1f Z %.1f", selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ), 0.58f, 0.46f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);

            addBoundedText(verts, "--- MATERIAL ---", 0.58f, 0.36f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            addBoundedText(verts, "SHADER: RETRO_MSL_3D", 0.58f, 0.28f, 0.98f, 1.0f, 0.4f, 0.8f, 1.0f, 1.0f);
            
            String texName = "NONE";
            if (selectedEntity.textureAssetPath != null) {
                texName = new File(selectedEntity.textureAssetPath).getName().toUpperCase();
            }
            addBoundedText(verts, "TEX: " + texName, 0.58f, 0.20f, 0.98f, 1.0f, 0.8f, 0.8f, 0.8f, 1.0f);

            if (selectedEntity.meshAssetPath != null) {
                File f = new File(selectedEntity.meshAssetPath);
                addBoundedText(verts, "SRC: " + f.getName(), 0.58f, 0.12f, 0.98f, 1.0f, 0.7f, 0.7f, 0.7f, 1.0f);
            }
        } else {
            addBoundedText(verts, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
        }

        // 3. ASSET DIRECTORY (Bottom Panel: X -1.0 to 1.0, Y -1.0 to -0.5)
        addQuad(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.06f, 0.06f, 0.08f, 1.0f);
        addQuad(verts, -1.0f, -0.5f, 1.0f, -0.42f, 0.14f, 0.14f, 0.18f, 1.0f); // Top Header
        addFrameBorder(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "ASSET DIRECTORY (DROP .OBJ / .GLTF / .PNG)", -0.96f, -0.45f, 0.95f, 1.4f, 1.0f, 1.0f, 1.0f, 1.0f);

        float cardX = -0.95f;
        for (AssetEntry asset : assetDirectory) {
            if (cardX + 0.35f > 0.95f) break;
            addQuad(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.10f, 0.10f, 0.13f, 1.0f);
            addFrameBorder(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.2f, 0.2f, 0.25f, 1.0f);

            // Badge Box
            addQuad(verts, cardX + 0.02f, -0.66f, cardX + 0.16f, -0.58f, 0.0f, 0.45f, 0.65f, 1.0f);
            addBoundedText(verts, "." + asset.type.name(), cardX + 0.03f, -0.60f, cardX + 0.15f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

            addBoundedText(verts, asset.name, cardX + 0.02f, -0.75f, cardX + 0.30f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            cardX += 0.36f;
        }

        // 4. CENTER VIEWPORT FRAMING (X -0.55 to 0.55, Y -0.50 to 1.00)
        addQuad(verts, -0.55f, 0.88f, 0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        addFrameBorder(verts, -0.55f, -0.5f, 0.55f, 0.88f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "3D VIEWPORT [METAL RHI CANVAS]", -0.42f, 0.95f, 0.52f, 1.4f, 0.3f, 0.8f, 1.0f, 1.0f);

        // Convert list to float array
        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) {
            array[i] = verts.get(i);
        }
        return array;
    }

    private void addQuad(List<Float> verts, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        addVert(verts, x1, y1, r, g, b, a);
        addVert(verts, x2, y1, r, g, b, a);
        addVert(verts, x1, y2, r, g, b, a);

        addVert(verts, x2, y1, r, g, b, a);
        addVert(verts, x2, y2, r, g, b, a);
        addVert(verts, x1, y2, r, g, b, a);
    }

    private void addFrameBorder(List<Float> verts, float x1, float y1, float x2, float y2, float t, float r, float g, float b, float a) {
        addQuad(verts, x1, y2 - t, x2, y2, r, g, b, a); // Top
        addQuad(verts, x1, y1, x2, y1 + t, r, g, b, a); // Bottom
        addQuad(verts, x1, y1, x1 + t, y2, r, g, b, a); // Left
        addQuad(verts, x2 - t, y1, x2, y2, r, g, b, a); // Right
    }

    private void addVert(List<Float> verts, float x, float y, float r, float g, float b, float a) {
        verts.add(x); verts.add(y);
        verts.add(r); verts.add(g); verts.add(b); verts.add(a);
    }

    private void addBoundedText(List<Float> verts, String text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        float charW = size * 0.010f;
        float charH = size * 0.018f;
        float pixelW = charW / 6.0f;
        float pixelH = charH / 6.0f;

        String upper = text.toUpperCase();
        for (int i = 0; i < upper.length(); i++) {
            float cx = startX + i * (charW + pixelW * 1.2f);
            if (cx + charW > maxX) break; // Hard right boundary clip!

            char c = upper.charAt(i);
            if (c >= 0 && c < FONT.length) {
                long mask = FONT[c];
                if (mask != 0L) {
                    for (int row = 0; row < 5; row++) {
                        for (int col = 0; col < 6; col++) {
                            int shift = (4 - row) * 8 + (5 - col);
                            if (((mask >> shift) & 1L) != 0L) {
                                float px1 = cx + col * pixelW;
                                float py1 = startY - row * pixelH;
                                addQuad(verts, px1, py1 - pixelH, px1 + pixelW, py1, r, g, b, a);
                            }
                        }
                    }
                }
            }
        }
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
}
