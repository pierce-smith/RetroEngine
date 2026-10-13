package com.retro.engine.ecs;

import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.AssetType;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.serialization.SceneSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SceneManager encapsulates central scene entity lifecycle, entity selection state,
 * scene spawning routines, asset assignment, and persistence (save/load serialization).
 *
 * Target JDK: JDK 21+ / JDK 25 / macOS
 */
public class SceneManager {
    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private SceneEntity selectedEntity = null;
    private String activeScenePath = "Assets/Scenes/MainLevel.scene";

    public SceneManager() {}

    // --- Entity Selection & Retrieval ---

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
    }

    public SceneEntity getSelectedEntity() {
        return selectedEntity;
    }

    public List<SceneEntity> getSceneEntities() {
        return Collections.unmodifiableList(sceneEntities);
    }

    public String getActiveScenePath() {
        return activeScenePath;
    }

    public void setActiveScenePath(String path) {
        if (path != null) {
            this.activeScenePath = path;
        }
    }

    // --- Scene Lifecycle & Persistence ---

    public void newScene() {
        sceneEntities.clear();
        selectedEntity = null;
        activeScenePath = "Assets/Scenes/NewLevel.scene";
        System.out.println("[SceneManager] Created New Empty Scene.");
    }

    public void promptOpenSceneWithNativeDialog() {
        String path = NativeFileDialog.openFileDialog("Open Retro Engine Scene", activeScenePath, "Retro Scene (*.scene)", "scene");
        if (path != null && !path.trim().isEmpty()) {
            loadSceneFromFile(path);
        }
    }

    public void promptSaveSceneWithNativeDialog() {
        String path = NativeFileDialog.saveFileDialog("Save Retro Engine Scene", activeScenePath, "Retro Scene (*.scene)", "scene");
        if (path != null && !path.trim().isEmpty()) {
            if (!path.endsWith(".scene")) path += ".scene";
            saveActiveScene(path);
        }
    }

    public void saveActiveScene() {
        saveActiveScene(this.activeScenePath);
    }

    public void saveActiveScene(String filePath) {
        if (filePath != null) this.activeScenePath = filePath;
        boolean ok = SceneSerializer.saveScene(this.activeScenePath, this.sceneEntities);
        if (ok) {
            System.out.println("[SceneManager] Scene saved to: " + this.activeScenePath);
        }
    }

    public void loadSceneFromFile(String filePath) {
        if (filePath == null) return;
        List<SceneEntity> loaded = SceneSerializer.loadScene(filePath);
        if (!loaded.isEmpty()) {
            this.sceneEntities.clear();
            this.sceneEntities.addAll(loaded);
            this.selectedEntity = sceneEntities.get(0);
            for (int i = 0; i < sceneEntities.size(); i++) {
                sceneEntities.get(i).selected = (i == 0);
            }
            this.activeScenePath = filePath;
            System.out.println("[SceneManager] Active scene loaded -> " + filePath);
        }
    }

    public void deleteSelectedEntity() {
        if (selectedEntity != null) {
            String name = selectedEntity.name;
            sceneEntities.remove(selectedEntity);
            selectedEntity = sceneEntities.isEmpty() ? null : sceneEntities.get(0);
            if (selectedEntity != null) selectedEntity.selected = true;
            System.out.println("[SceneManager] Deleted Entity: " + name);
        }
    }

    // --- Spawning & Asset Assignment ---

    public void spawnDefaultCube() {
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = "Cube_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, "Assets/Models/cube.obj");
        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[SceneManager] Spawned 3D Cube Entity: " + name);
    }

    public void spawnLightSource() {
        String entityId = "light_" + (sceneEntities.size() + 1);
        String name = "DirectionalLight_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new LightComponent());
        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[SceneManager] Spawned Light Source Entity: " + name);
    }

    public void spawnEntityFromAsset(AssetEntry meshAsset) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath);
        sceneEntities.add(entity);
        selectEntity(entity);
        System.out.println("[SceneManager] Spawned ECS Entity: '" + name + "' with Transform, MeshRenderer & Material.");
    }

    public void assignTextureToSelectedEntity(AssetEntry textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name);
        System.out.println("[SceneManager] Linked Texture '" + textureAsset.name + "' to ECS Entity '" + selectedEntity.name + "'");
    }

    public void assignMeshToSelectedEntity(AssetEntry meshAsset) {
        if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        selectedEntity.setMeshAssetPath(meshAsset.absolutePath);
        System.out.println("[SceneManager] Linked 3D Mesh '" + meshAsset.name + "' to ECS Entity '" + selectedEntity.name + "'");
    }
}
