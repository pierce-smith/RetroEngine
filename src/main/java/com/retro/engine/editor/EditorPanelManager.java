package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state for the Retro Engine Editor.
 * Includes Unity-style 3D Perspective Ground Grid, Viewport Transform Gizmos,
 * and Viewport Toolbar Switcher.
 */
public class EditorPanelManager {

    public enum AssetType { MESH_3D, TEXTURE, SHADER, UNKNOWN }
    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }

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
    }

    public static class SceneEntity {
        public final String id;
        public String name;
        public final Transform transform = new Transform();
        public String meshAssetPath;
        public String textureAssetPath = null;
        public String textureName = "NONE";
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

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;

    private float camPanX = 0.0f, camPanY = 0.25f, camZoom = -5.0f, camPitch = 0.0f, camYaw = 0.0f;

    private float[] cached3DGridVertices = new float[0];
    private float[] cachedUiVertices = new float[0];

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

    public EditorPanelManager() {}

    public void update(float deltaTime) {}

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        float aspect = 1280.0f / 720.0f;
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        float vx = cy * wx - sy * wz + camPanX;
        float vy = sy * sp * wx + cp * wy + cy * sp * wz + camPanY;
        float vz = sy * cp * wx - sp * wy + cy * cp * wz + camZoom;

        float clipW = -vz;
        if (clipW <= 0.001f) clipW = 0.001f;

        float ndcX = (p00 * vx) / clipW;
        float ndcY = (p11 * vy) / clipW;

        return new float[] { ndcX, ndcY };
    }

    public float[] get3DGridVertices() {
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) {
            cached3DGridVertices = generate3DGridVertices();
        }
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    public float[] generate3DGridVertices() {
        List<Float> verts = new ArrayList<>();
        int extent = 15;
        float y = 0.0f;

        for (int z_i = -extent; z_i <= extent; z_i++) {
            float z = (float) z_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (z_i == 0) { r = 1.0f; g = 0.25f; b = 0.25f; a = 1.0f; } // Red X-Axis

            addGridLine(verts, -extent, y, z, extent, y, z, r, g, b, a);
        }

        for (int x_i = -extent; x_i <= extent; x_i++) {
            float x = (float) x_i;
            float r = 0.22f, g = 0.22f, b = 0.28f, a = 0.8f;
            if (x_i == 0) { r = 0.25f; g = 0.55f; b = 1.0f; a = 1.0f; } // Blue Z-Axis

            addGridLine(verts, x, y, -extent, x, y, extent, r, g, b, a);
        }

        // Green Y-Axis Vertical Origin Indicator Line
        addGridLine(verts, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.25f, 1.0f, 0.25f, 1.0f);

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void addGridLine(List<Float> verts, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        verts.add(x1); verts.add(y1); verts.add(z1); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
        verts.add(x2); verts.add(y2); verts.add(z2); verts.add(r); verts.add(g); verts.add(b); verts.add(a); verts.add(0.0f); verts.add(0.0f);
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = resolveAssetType(ext);
            AssetEntry asset = new AssetEntry(name, path, type);
            assetDirectory.add(asset);
            System.out.println("[Asset Directory] Ingested file into Asset Directory: " + asset);
        }
    }

    public SceneEntity spawnEntityFromAsset(AssetEntry asset) {
        if (asset == null || asset.type != AssetType.MESH_3D) return null;

        String entityId = "mesh_entity_" + (sceneEntities.size() + 1);
        String entityName = removeExtension(asset.name);
        SceneEntity newEntity = new SceneEntity(entityId, entityName, asset.absolutePath);

        sceneEntities.add(newEntity);
        selectEntity(newEntity);

        System.out.println("[Scene Hierarchy] Spawned Entity in Scene: " + entityName);
        return newEntity;
    }

    public void assignTextureToSelectedEntity(AssetEntry textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.textureAssetPath = textureAsset.absolutePath;
        selectedEntity.textureName = textureAsset.name;
        System.out.println("[Inspector] Linked Texture '" + textureAsset.name + "' to Scene Entity '" + selectedEntity.name + "'");
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

    public GizmoMode getCurrentGizmoMode() { return currentGizmoMode; }
    public void setCurrentGizmoMode(GizmoMode mode) {
        this.currentGizmoMode = mode;
        System.out.println("[Gizmo] Switched Gizmo Mode -> " + mode);
    }

    public GizmoAxis getActiveGizmoAxis() { return activeGizmoAxis; }
    public void setActiveGizmoAxis(GizmoAxis axis) { this.activeGizmoAxis = axis; }

    public float[] getUiVertices() {
        this.cachedUiVertices = generateUiVertices();
        return this.cachedUiVertices;
    }

    public int getUiVertexCount() {
        if (cachedUiVertices == null || cachedUiVertices.length == 0) getUiVertices();
        return cachedUiVertices.length / 6;
    }

    public float[] generateUiVertices() {
        List<Float> verts = new ArrayList<>();

        // 1. SCENE MANAGER (Left Panel)
        addQuad(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        addQuad(verts, -1.0f, 0.88f, -0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        addFrameBorder(verts, -1.0f, -0.5f, -0.55f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "SCENE MANAGER", -0.96f, 0.95f, -0.57f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (sceneEntities.isEmpty()) {
            addBoundedText(verts, "NO ENTITIES IN SCENE", -0.95f, 0.80f, -0.58f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            addBoundedText(verts, "CLICK 3D ASSET CARD", -0.95f, 0.72f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
            addBoundedText(verts, "BELOW TO SPAWN", -0.95f, 0.65f, -0.58f, 1.1f, 0.3f, 0.7f, 0.9f, 1.0f);
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

        // 2. INSPECTOR (Right Panel)
        addQuad(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        addQuad(verts, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        addFrameBorder(verts, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "INSPECTOR", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            addBoundedText(verts, "ENTITY: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.3f, 1.0f, 0.8f, 0.2f, 1.0f);
            addBoundedText(verts, "--- TRANSFORM ---", 0.58f, 0.70f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            addBoundedText(verts, String.format("POS: X %.1f Y %.1f Z %.1f", selectedEntity.transform.posX, selectedEntity.transform.posY, selectedEntity.transform.posZ), 0.58f, 0.62f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            addBoundedText(verts, String.format("ROT: X %.1f Y %.1f Z %.1f", selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ), 0.58f, 0.54f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
            addBoundedText(verts, String.format("SCL: X %.1f Y %.1f Z %.1f", selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ), 0.58f, 0.46f, 0.98f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);

            addBoundedText(verts, "--- MATERIAL & TEXTURE ---", 0.58f, 0.36f, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
            addBoundedText(verts, "SHADER: RETRO_MSL_3D", 0.58f, 0.28f, 0.98f, 1.0f, 0.4f, 0.8f, 1.0f, 1.0f);

            addQuad(verts, 0.58f, 0.16f, 0.95f, 0.24f, 0.12f, 0.16f, 0.22f, 1.0f);
            addFrameBorder(verts, 0.58f, 0.16f, 0.95f, 0.24f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
            addBoundedText(verts, "TEX: " + selectedEntity.textureName, 0.60f, 0.21f, 0.93f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            addBoundedText(verts, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
        }

        // 3. ASSET DIRECTORY (Bottom Panel)
        addQuad(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.06f, 0.06f, 0.08f, 1.0f);
        addQuad(verts, -1.0f, -0.5f, 1.0f, -0.42f, 0.14f, 0.14f, 0.18f, 1.0f);
        addFrameBorder(verts, -1.0f, -1.0f, 1.0f, -0.5f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "ASSET DIRECTORY (DROP FILES TO INGEST)", -0.96f, -0.45f, 0.95f, 1.4f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (assetDirectory.isEmpty()) {
            addBoundedText(verts, "NO ASSETS LOADED - DRAG & DROP .OBJ / .FBX / .PNG FILES FROM FINDER", -0.80f, -0.70f, 0.90f, 1.2f, 0.4f, 0.4f, 0.4f, 1.0f);
        } else {
            float cardX = -0.95f;
            for (AssetEntry asset : assetDirectory) {
                if (cardX + 0.35f > 0.95f) break;
                addQuad(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.10f, 0.10f, 0.13f, 1.0f);
                addFrameBorder(verts, cardX, -0.92f, cardX + 0.32f, -0.55f, 0.002f, 0.2f, 0.2f, 0.25f, 1.0f);

                addQuad(verts, cardX + 0.02f, -0.66f, cardX + 0.18f, -0.58f, 0.0f, 0.45f, 0.65f, 1.0f);
                addBoundedText(verts, "." + asset.type.name(), cardX + 0.03f, -0.60f, cardX + 0.17f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

                addBoundedText(verts, asset.name, cardX + 0.02f, -0.75f, cardX + 0.30f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                addBoundedText(verts, asset.type == AssetType.MESH_3D ? "[CLICK: SPAWN]" : "[CLICK: LINK]", cardX + 0.02f, -0.85f, cardX + 0.30f, 0.9f, 0.2f, 0.8f, 1.0f, 1.0f);
                cardX += 0.36f;
            }
        }

        // 4. CENTER VIEWPORT FRAMING
        addQuad(verts, -0.55f, 0.88f, 0.55f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        addFrameBorder(verts, -0.55f, -0.5f, 0.55f, 0.88f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        addBoundedText(verts, "3D VIEWPORT [METAL RHI CANVAS]", -0.42f, 0.95f, 0.52f, 1.4f, 0.3f, 0.8f, 1.0f, 1.0f);

        // 5. VIEWPORT GIZMO TOOLBAR
        renderViewportGizmoToolbar(verts);

        // 6. VIEWPORT 3D TRANSFORM GIZMOS (When entity selected)
        if (selectedEntity != null) {
            renderEntityGizmos(verts, selectedEntity);
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void renderViewportGizmoToolbar(List<Float> verts) {
        float barX1 = -0.52f, barY1 = 0.80f, barX2 = -0.10f, barY2 = 0.86f;
        addQuad(verts, barX1, barY1, barX2, barY2, 0.10f, 0.10f, 0.13f, 1.0f);
        addFrameBorder(verts, barX1, barY1, barX2, barY2, 0.002f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Button 1: [1/W] MOVE
        float btn1X1 = -0.51f, btn1X2 = -0.38f;
        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            addQuad(verts, btn1X1, barY1 + 0.005f, btn1X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            addBoundedText(verts, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            addBoundedText(verts, "1:MOVE", btn1X1 + 0.005f, barY2 - 0.015f, btn1X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 2: [2/E] ROT
        float btn2X1 = -0.37f, btn2X2 = -0.25f;
        if (currentGizmoMode == GizmoMode.ROTATE) {
            addQuad(verts, btn2X1, barY1 + 0.005f, btn2X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            addBoundedText(verts, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            addBoundedText(verts, "2:ROT", btn2X1 + 0.005f, barY2 - 0.015f, btn2X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }

        // Button 3: [3/R] SCL
        float btn3X1 = -0.24f, btn3X2 = -0.11f;
        if (currentGizmoMode == GizmoMode.SCALE) {
            addQuad(verts, btn3X1, barY1 + 0.005f, btn3X2, barY2 - 0.005f, 0.0f, 0.45f, 0.70f, 1.0f);
            addBoundedText(verts, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            addBoundedText(verts, "3:SCL", btn3X1 + 0.005f, barY2 - 0.015f, btn3X2, 1.0f, 0.6f, 0.6f, 0.6f, 1.0f);
        }
    }

    private void renderEntityGizmos(List<Float> verts, SceneEntity entity) {
        float[] anchorNdc = projectWorldToNdc(entity.transform.posX, entity.transform.posY, entity.transform.posZ);
        float cx = Math.max(-0.50f, Math.min(0.50f, anchorNdc[0]));
        float cy = Math.max(-0.40f, Math.min(0.75f, anchorNdc[1]));

        // Center Origin Core Box
        addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        float rx = 1.0f, rg = 0.25f, rb = 0.25f; // RED X
        float gx = 0.25f, gg = 1.0f, gb = 0.25f; // GREEN Y
        float bx = 0.25f, bg = 0.55f, bb = 1.0f; // BLUE Z

        if (activeGizmoAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeGizmoAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeGizmoAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        float len = 0.14f;
        float lineThick = 0.006f;

        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            // RED X AXIS
            addLineSegment(verts, cx, cy, cx + len, cy, lineThick, rx, rg, rb, 1.0f);
            addArrowhead(verts, cx + len + 0.025f, cy, 1.0f, 0.0f, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            addBoundedText(verts, "X", cx + len + 0.035f, cy + 0.010f, cx + len + 0.08f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y AXIS
            addLineSegment(verts, cx, cy, cx, cy + len, lineThick, gx, gg, gb, 1.0f);
            addArrowhead(verts, cx, cy + len + 0.025f, 0.0f, 1.0f, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            addBoundedText(verts, "Y", cx - 0.010f, cy + len + 0.040f, cx + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z AXIS
            float zEndX = cx - len * 0.707f;
            float zEndY = cy - len * 0.707f;
            addLineSegment(verts, cx, cy, zEndX, zEndY, lineThick, bx, bg, bb, 1.0f);
            addArrowhead(verts, zEndX - 0.018f, zEndY - 0.018f, -0.707f, -0.707f, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            addBoundedText(verts, "Z", zEndX - 0.045f, zEndY - 0.025f, zEndX, 1.1f, bx, bg, bb, 1.0f);

        } else if (currentGizmoMode == GizmoMode.SCALE) {
            // RED X SCALE
            addLineSegment(verts, cx, cy, cx + len, cy, lineThick, rx, rg, rb, 1.0f);
            addSquareEnd(verts, cx + len + 0.015f, cy, 0.024f, rx, rg, rb, 1.0f);
            addBoundedText(verts, "X", cx + len + 0.035f, cy + 0.010f, cx + len + 0.08f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y SCALE
            addLineSegment(verts, cx, cy, cx, cy + len, lineThick, gx, gg, gb, 1.0f);
            addSquareEnd(verts, cx, cy + len + 0.015f, 0.024f, gx, gg, gb, 1.0f);
            addBoundedText(verts, "Y", cx - 0.010f, cy + len + 0.040f, cx + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z SCALE
            float zEndX = cx - len * 0.707f;
            float zEndY = cy - len * 0.707f;
            addLineSegment(verts, cx, cy, zEndX, zEndY, lineThick, bx, bg, bb, 1.0f);
            addSquareEnd(verts, zEndX - 0.012f, zEndY - 0.012f, 0.024f, bx, bg, bb, 1.0f);
            addBoundedText(verts, "Z", zEndX - 0.045f, zEndY - 0.025f, zEndX, 1.1f, bx, bg, bb, 1.0f);

        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            // RED PITCH ARC
            addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            // GREEN YAW ARC
            addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            // BLUE ROLL ARC
            addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }

    private void addLineSegment(List<Float> verts, float x1, float y1, float x2, float y2, float thickness, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0.0f) return;

        float nx = -dy / len * (thickness * 0.5f);
        float ny = dx / len * (thickness * 0.5f);

        addVert(verts, x1 + nx, y1 + ny, r, g, b, a);
        addVert(verts, x2 + nx, y2 + ny, r, g, b, a);
        addVert(verts, x1 - nx, y1 - ny, r, g, b, a);

        addVert(verts, x2 + nx, y2 + ny, r, g, b, a);
        addVert(verts, x2 - nx, y2 - ny, r, g, b, a);
        addVert(verts, x1 - nx, y1 - ny, r, g, b, a);
    }

    private void addArrowhead(List<Float> verts, float tipX, float tipY, float dirX, float dirY, float length, float width, float r, float g, float b, float a) {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len == 0.0f) return;
        float ux = dirX / len;
        float uy = dirY / len;

        float perpX = -uy * width;
        float perpY = ux * width;

        float baseX = tipX - ux * length;
        float baseY = tipY - uy * length;

        addTriangle(verts, tipX, tipY, baseX + perpX, baseY + perpY, baseX - perpX, baseY - perpY, r, g, b, a);
    }

    private void addSquareEnd(List<Float> verts, float centerX, float centerY, float size, float r, float g, float b, float a) {
        float h = size * 0.5f;
        addQuad(verts, centerX - h, centerY - h, centerX + h, centerY + h, r, g, b, a);
    }

    private void addArcRing(List<Float> verts, float cx, float cy, float rx, float ry, float startAngle, float endAngle, float thickness, float r, float g, float b, float a) {
        int segments = 24;
        float startRad = (float) Math.toRadians(startAngle);
        float endRad = (float) Math.toRadians(endAngle);
        float step = (endRad - startRad) / segments;

        float prevX = cx + rx * (float) Math.cos(startRad);
        float prevY = cy + ry * (float) Math.sin(startRad);

        for (int i = 1; i <= segments; i++) {
            float angle = startRad + i * step;
            float currX = cx + rx * (float) Math.cos(angle);
            float currY = cy + ry * (float) Math.sin(angle);

            addLineSegment(verts, prevX, prevY, currX, currY, thickness, r, g, b, a);
            prevX = currX;
            prevY = currY;
        }
    }

    private void addTriangle(List<Float> verts, float x1, float y1, float x2, float y2, float x3, float y3, float r, float g, float b, float a) {
        addVert(verts, x1, y1, r, g, b, a);
        addVert(verts, x2, y2, r, g, b, a);
        addVert(verts, x3, y3, r, g, b, a);
    }

    private void addQuad(List<Float> verts, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        addVert(verts, x1, y1, r, g, b, a); addVert(verts, x2, y1, r, g, b, a); addVert(verts, x1, y2, r, g, b, a);
        addVert(verts, x2, y1, r, g, b, a); addVert(verts, x2, y2, r, g, b, a); addVert(verts, x1, y2, r, g, b, a);
    }

    private void addFrameBorder(List<Float> verts, float x1, float y1, float x2, float y2, float t, float r, float g, float b, float a) {
        addQuad(verts, x1, y2 - t, x2, y2, r, g, b, a);
        addQuad(verts, x1, y1, x2, y1 + t, r, g, b, a);
        addQuad(verts, x1, y1, x1 + t, y2, r, g, b, a);
        addQuad(verts, x2 - t, y1, x2, y2, r, g, b, a);
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
            if (cx + charW > maxX) break;

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
