package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager orchestrates the editor panel layout and state for the Retro Engine Editor.
 * Updated: Transform Gizmos are anchored directly to the model's 3D base/origin,
 * with X, Y, Z axes projected directly from 3D world space so handles remain
 * perfectly aligned with the Global Grid in the 3D viewport.
 */
public class EditorPanelManager {

    public enum AssetType { MESH_3D, TEXTURE, SHADER, UNKNOWN }
    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }

    public static class Transform {
        public float posX = 0.0f, posY = 0.0f, posZ = 0.0f;
        public float rotX = 0.0f, rotY = 0.0f, rotZ = 0.0f;
        public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;
    }

    public static class SceneEntity {
        public String id;
        public String name;
        public Transform transform = new Transform();
        public String meshAssetPath;
        public String textureAssetPath;
        public String textureName = "DEFAULT_CHECKER";
        public boolean selected = false;

        public SceneEntity(String id, String name, String meshAssetPath) {
            this.id = id;
            this.name = name;
            this.meshAssetPath = meshAssetPath;
        }
    }

    public static class AssetEntry {
        public String name;
        public String absolutePath;
        public AssetType type;

        public AssetEntry(String name, String absolutePath, AssetType type) {
            this.name = name;
            this.absolutePath = absolutePath;
            this.type = type;
        }
    }

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private SceneEntity selectedEntity = null;

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;

    private float[] cached3DGridVertices = new float[0];
    private float[] cachedUiVertices = new float[0];

    // Camera Transform State for 3D Viewport Projection
    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX;
        this.camPanY = panY;
        this.camZoom = zoom;
        this.camPitch = pitch;
        this.camYaw = yaw;
    }

    /**
     * Projects 3D World coordinates to 2D UI NDC coordinates.
     * Maps 3D perspective projection into 2D Viewport Panel quad space:
     * Viewport Quad Bounds: X [-0.55, 0.55], Y [-0.50, 0.88]
     * Viewport Center: X = 0.0f, Y = 0.19f
     * Viewport Half-Scale: X = 0.55f, Y = 0.69f
     */
    public float[] projectWorldToNdc(float wx, float wy, float wz) {
        float aspect = 712.0f / 488.0f;
        float fov = (float) Math.toRadians(50.0);
        float f = (float) (1.0 / Math.tan(fov / 2.0));

        float p00 = f / aspect;
        float p11 = f;

        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        // View space transformation matching MetalBackend GPU camera
        float vx = cy * wx - sy * wz + camPanX;
        float vy = sy * sp * wx + cp * wy + cy * sp * wz + camPanY;
        float vz = sy * cp * wx - sp * wy + cy * cp * wz + camZoom;

        float clipW = -vz;
        if (clipW <= 0.001f) clipW = 0.001f;

        // Raw 3D NDC [-1.0, 1.0]
        float ndcX = (p00 * vx) / clipW;
        float ndcY = (p11 * vy) / clipW;

        // Map 3D NDC into 2D Viewport Canvas Region in UI space
        float uiX = 0.0f + ndcX * 0.55f;
        float uiY = 0.19f + ndcY * 0.69f;

        return new float[] { uiX, uiY };
    }

    public EditorPanelManager() {}

    public void update(float deltaTime) {}

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
        verts.add(x1); verts.add(y1); verts.add(z1);
        verts.add(r);  verts.add(g);  verts.add(b);  verts.add(a);
        verts.add(0.0f); verts.add(0.0f);

        verts.add(x2); verts.add(y2); verts.add(z2);
        verts.add(r);  verts.add(g);  verts.add(b);  verts.add(a);
        verts.add(0.0f); verts.add(0.0f);
    }

    public void onFilesDropped(List<String> filePaths) {
        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();

            AssetType type = AssetType.UNKNOWN;
            if (ext.equals("obj") || ext.equals("fbx") || ext.equals("gltf") || ext.equals("glb")) {
                type = AssetType.MESH_3D;
            } else if (ext.equals("png") || ext.equals("jpg") || ext.equals("tga")) {
                type = AssetType.TEXTURE;
            }

            AssetEntry entry = new AssetEntry(name, path, type);
            assetDirectory.add(entry);
            System.out.println("[Asset Directory] Registered File: " + name + " (" + type + ")");
        }
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }

    public void spawnEntityFromAsset(AssetEntry meshAsset) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;

        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath);

        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[Scene Hierarchy] Spawned Entity: '" + name + "' from " + meshAsset.name);
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

        // 1. Scene Hierarchy Panel
        SceneHierarchyPanel.render(verts, sceneEntities);

        // 2. Inspector Panel
        InspectorPanel.render(verts, selectedEntity, assetDirectory);

        // 3. Asset Directory Panel
        AssetDirectoryPanel.render(verts, assetDirectory);

        // 4. Center Viewport Framing, Toolbar & Gizmos
        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, currentGizmoMode);

        if (selectedEntity != null) {
            render3DGizmos(verts, selectedEntity, currentGizmoMode, activeGizmoAxis);
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }

    private void render3DGizmos(List<Float> verts, SceneEntity entity, GizmoMode mode, GizmoAxis activeAxis) {
        // 1. Anchor directly to the model's base/origin in 3D world space
        float px = entity.transform.posX;
        float py = entity.transform.posY;
        float pz = entity.transform.posZ;

        float[] p0 = projectWorldToNdc(px, py, pz);
        float cx = p0[0];
        float cy = p0[1];

        // 2. Project World X, Y, Z axis points (aligned to Global Grid in 3D)
        float axisStep = 1.0f;
        float[] pX = projectWorldToNdc(px + axisStep, py, pz);
        float[] pY = projectWorldToNdc(px, py + axisStep, pz);
        float[] pZ = projectWorldToNdc(px, py, pz + axisStep);

        // 3. Compute 2D screen directions along projected Global Grid axes
        float dxX = pX[0] - cx, dyX = pX[1] - cy;
        float dxY = pY[0] - cx, dyY = pY[1] - cy;
        float dxZ = pZ[0] - cx, dyZ = pZ[1] - cy;

        float lenX = (float) Math.hypot(dxX, dyX);
        float lenY = (float) Math.hypot(dxY, dyY);
        float lenZ = (float) Math.hypot(dxZ, dyZ);

        float handleVisualLen = 0.16f;

        // Normalized direction vectors along 3D Global Grid
        float uX_x = lenX > 0.0001f ? (dxX / lenX) : 1.0f;
        float uX_y = lenX > 0.0001f ? (dyX / lenX) : 0.0f;

        float uY_x = lenY > 0.0001f ? (dxY / lenY) : 0.0f;
        float uY_y = lenY > 0.0001f ? (dyY / lenY) : 1.0f;

        float uZ_x = lenZ > 0.0001f ? (dxZ / lenZ) : -0.707f;
        float uZ_y = lenZ > 0.0001f ? (dyZ / lenZ) : -0.707f;

        float xTipX = cx + uX_x * handleVisualLen;
        float xTipY = cy + uX_y * handleVisualLen;

        float yTipX = cx + uY_x * handleVisualLen;
        float yTipY = cy + uY_y * handleVisualLen;

        float zTipX = cx + uZ_x * handleVisualLen;
        float zTipY = cy + uZ_y * handleVisualLen;

        // Center Origin Quad
        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        // Highlight Colors
        float rx = 1.0f, rg = 0.25f, rb = 0.25f; // RED
        float gx = 0.25f, gg = 1.0f, gb = 0.25f; // GREEN
        float bx = 0.25f, bg = 0.55f, bb = 1.0f; // BLUE

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        float lineThick = 0.008f;

        if (mode == GizmoMode.TRANSLATE) {
            // RED X AXIS (Aligned to World X Grid line)
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, xTipX, xTipY, uX_x, uX_y, 0.035f, 0.018f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uX_x * 0.025f, xTipY + uX_y * 0.025f, xTipX + uX_x * 0.025f + 0.04f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y AXIS (Aligned to World Y Vertical Axis)
            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, yTipX, yTipY, uY_x, uY_y, 0.035f, 0.018f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uY_x * 0.025f, yTipY + uY_y * 0.025f, yTipX + uY_x * 0.025f + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z AXIS (Aligned to World Z Grid line)
            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zTipX, zTipY, uZ_x, uZ_y, 0.035f, 0.018f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZ_x * 0.025f, zTipY + uZ_y * 0.025f, zTipX + uZ_x * 0.025f + 0.04f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            // RED X SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, xTipX, xTipY, 0.026f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + uX_x * 0.025f, xTipY + uX_y * 0.025f, xTipX + uX_x * 0.025f + 0.04f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, yTipX, yTipY, 0.026f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + uY_x * 0.025f, yTipY + uY_y * 0.025f, yTipX + uY_x * 0.025f + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zTipX, zTipY, 0.026f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + uZ_x * 0.025f, zTipY + uZ_y * 0.025f, zTipX + uZ_x * 0.025f + 0.04f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            // RED PITCH ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            // GREEN YAW ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            // BLUE ROLL ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }
    }
}
