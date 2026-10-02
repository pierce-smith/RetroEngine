package com.retro.engine.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EditorPanelManager handles the 4-panel layout state for the Retro Engine Editor:
 * 1. Scene Manager (Hierarchy) - Left Panel
 * 2. 3D Viewport (Render Target) - Center Panel
 * 3. Inspector (Entity Properties & Transform) - Right Panel
 * 4. Asset Directory (Project Resource Files) - Bottom Panel
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

    /**
     * Frame-by-frame update logic for editor panels and active scene entities.
     * @param deltaTime Time elapsed since last frame in seconds.
     */
    public void update(float deltaTime) {
        // Frame tick processing for scene hierarchy and inspector updates
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