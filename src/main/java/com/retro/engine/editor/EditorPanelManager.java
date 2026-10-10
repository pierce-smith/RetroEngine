package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager orchestrates the editor panel layout and state for the Retro Engine Editor.
 * Includes 3D Perspective Ground Grid, 3D Projected Viewport Transform Gizmos,
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

    private float[] cached3DGridVertices = new float[0];
    private float[] cachedUiVertices = new float[0];

    // Camera Transform State for 3D Viewport Projection
    private float camPanX = 0.0f;
    private float camPanY = -1.2f;
    private float camZoom = -8.5f;
    private float camPitch = 22.0f;
    private float camYaw = -35.0f;

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
        verts.add(r); verts.add(g); verts.add(b); verts.add(a);
        verts.add(0.0f); verts.add(0.0f);

        verts.add(x2); verts.add(y2); verts.add(z2);
        verts.add(r); verts.add(g); verts.add(b); verts.add(a);
        verts.add(0.0f); verts.add(0.0f);
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
        float px = entity.transform.posX;
        float py = entity.transform.posY;
        float pz = entity.transform.posZ;

        float[] p0 = projectWorldToNdc(px, py, pz);
        float cx = Math.max(-0.50f, Math.min(0.50f, p0[0]));
        float cy = Math.max(-0.40f, Math.min(0.75f, p0[1]));

        float axisLen = 0.8f;
        float[] pX = projectWorldToNdc(px + axisLen, py, pz);
        float[] pY = projectWorldToNdc(px, py + axisLen, pz);
        float[] pZ = projectWorldToNdc(px, py, pz + axisLen);

        float dxX = pX[0] - p0[0], dyX = pX[1] - p0[1];
        float dxY = pY[0] - p0[0], dyY = pY[1] - p0[1];
        float dxZ = pZ[0] - p0[0], dyZ = pZ[1] - p0[1];

        float lenX = (float) Math.hypot(dxX, dyX);
        float lenY = (float) Math.hypot(dxY, dyY);
        float lenZ = (float) Math.hypot(dxZ, dyZ);

        float handleVisualLen = 0.14f;

        float dirXX = lenX > 0.0001f ? (dxX / lenX) * handleVisualLen : handleVisualLen;
        float dirXY = lenX > 0.0001f ? (dyX / lenX) * handleVisualLen : 0.0f;

        float dirYX = lenY > 0.0001f ? (dxY / lenY) * handleVisualLen : 0.0f;
        float dirYY = lenY > 0.0001f ? (dyY / lenY) * handleVisualLen : handleVisualLen;

        float dirZX = lenZ > 0.0001f ? (dxZ / lenZ) * handleVisualLen : -handleVisualLen * 0.707f;
        float dirZY = lenZ > 0.0001f ? (dyZ / lenZ) * handleVisualLen : -handleVisualLen * 0.707f;

        float xTipX = cx + dirXX, xTipY = cy + dirXY;
        float yTipX = cx + dirYX, yTipY = cy + dirYY;
        float zTipX = cx + dirZX, zTipY = cy + dirZY;

        // Center Origin Quad
        UIBatchBuilder.addSquareEnd(verts, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);

        // Highlight Colors
        float rx = 1.0f, rg = 0.25f, rb = 0.25f; // RED
        float gx = 0.25f, gg = 1.0f, gb = 0.25f; // GREEN
        float bx = 0.25f, bg = 0.55f, bb = 1.0f; // BLUE

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        float lineThick = 0.006f;

        if (mode == GizmoMode.TRANSLATE) {
            // RED X AXIS
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, xTipX, xTipY, dirXX, dirXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "X", xTipX + dirXX * 0.15f, xTipY + dirXY * 0.15f, xTipX + dirXX * 0.15f + 0.04f, 1.1f, rx, rg, rb, 1.0f);

            // GREEN Y AXIS
            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, yTipX, yTipY, dirYX, dirYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Y", yTipX + dirYX * 0.15f, yTipY + dirYY * 0.15f, yTipX + dirYX * 0.15f + 0.04f, 1.1f, gx, gg, gb, 1.0f);

            // BLUE Z AXIS
            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addArrowhead(verts, zTipX, zTipY, dirZX, dirZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            UIBatchBuilder.addBoundedText(verts, "Z", zTipX + dirZX * 0.15f, zTipY + dirZY * 0.15f, zTipX + dirZX * 0.15f + 0.04f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            // RED X SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);

            // GREEN Y SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);

            // BLUE Z SCALE
            UIBatchBuilder.addLineSegment(verts, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIBatchBuilder.addSquareEnd(verts, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            // RED PITCH ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            // GREEN YAW ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            // BLUE ROLL ARC
            UIBatchBuilder.addArcRing(verts, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
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
