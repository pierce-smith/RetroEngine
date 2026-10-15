package com.retro.engine.ecs;

import com.retro.engine.ecs.components.CameraComponent;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.serialization.SceneSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SceneManager handles central entity storage, selection, scene persistence,
 * and the Play/Pause runtime simulation state machine (snapshotting & state isolation).
 */
public class SceneManager {
    public enum PlayState { EDITING, SIMULATING, PAUSED }

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private SceneEntity selectedEntity = null;
    private String activeScenePath = "Assets/Scenes/Default.scene";

    private PlayState playState = PlayState.EDITING;
    private List<SceneEntity> sceneSnapshot = null;

    public SceneManager() {}

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
    }

    public String getActiveScenePath() { return activeScenePath; }
    public void setActiveScenePath(String path) { if (path != null) this.activeScenePath = path; }

    public PlayState getPlayState() { return playState; }

    public void play() {
        if (playState == PlayState.EDITING) {
            this.sceneSnapshot = cloneSceneEntities(sceneEntities);
            this.playState = PlayState.SIMULATING;
            System.out.println("[SceneManager] Simulation started.");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
        }
    }

    public void pause() {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            System.out.println("[SceneManager] Simulation paused.");
        }
    }

    public void stop() {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            if (sceneSnapshot != null) {
                this.sceneEntities.clear();
                this.sceneEntities.addAll(sceneSnapshot);
                this.sceneSnapshot = null;
            }
            this.playState = PlayState.EDITING;
            System.out.println("[SceneManager] Simulation stopped; state restored.");
        }
    }

    public void update(float deltaTime) {
        if (playState == PlayState.SIMULATING) {
            for (SceneEntity entity : sceneEntities) {
                TransformComponent tc = entity.getTransform();
                if (tc != null) {
                    tc.rotY = (tc.rotY + 45.0f * deltaTime) % 360.0f;
                }
            }
        }
    }

    private List<SceneEntity> cloneSceneEntities(List<SceneEntity> source) {
        List<SceneEntity> copy = new ArrayList<>();
        for (SceneEntity orig : source) {
            SceneEntity cloned = new SceneEntity(orig.id + "_sim", orig.name, orig.getMeshAssetPath());
            cloned.id = orig.id;
            cloned.selected = orig.selected;

            TransformComponent origTc = orig.getTransform();
            TransformComponent cloneTc = cloned.getTransform();
            if (origTc != null && cloneTc != null) {
                cloneTc.posX = origTc.posX; cloneTc.posY = origTc.posY; cloneTc.posZ = origTc.posZ;
                cloneTc.rotX = origTc.rotX; cloneTc.rotY = origTc.rotY; cloneTc.rotZ = origTc.rotZ;
                cloneTc.scaleX = origTc.scaleX; cloneTc.scaleY = origTc.scaleY; cloneTc.scaleZ = origTc.scaleZ;
            }

            MaterialComponent origMat = orig.getComponent(MaterialComponent.class);
            if (origMat != null) {
                MaterialComponent cloneMat = cloned.getComponent(MaterialComponent.class);
                if (cloneMat == null) { cloneMat = new MaterialComponent(); cloned.addComponent(cloneMat); }
                cloneMat.textureAssetPath = origMat.textureAssetPath;
                cloneMat.textureName = origMat.textureName;
                cloneMat.shaderName = origMat.shaderName;
            }

            LightComponent origLc = orig.getComponent(LightComponent.class);
            if (origLc != null) {
                LightComponent cloneLc = cloned.getComponent(LightComponent.class);
                if (cloneLc == null) { cloneLc = new LightComponent(); cloned.addComponent(cloneLc); }
                cloneLc.type = origLc.type; cloneLc.intensity = origLc.intensity;
                cloneLc.r = origLc.r; cloneLc.g = origLc.g; cloneLc.b = origLc.b;
            }

            CameraComponent origCc = orig.getComponent(CameraComponent.class);
            if (origCc != null) {
                CameraComponent cloneCc = cloned.getComponent(CameraComponent.class);
                if (cloneCc == null) { cloneCc = new CameraComponent(); cloned.addComponent(cloneCc); }
                cloneCc.fov = origCc.fov; cloneCc.nearPlane = origCc.nearPlane;
                cloneCc.farPlane = origCc.farPlane; cloneCc.isPrimary = origCc.isPrimary;
            }

            copy.add(cloned);
        }
        return copy;
    }

    public void newScene() {
        stop();
        this.sceneEntities.clear();
        this.selectedEntity = null;
        this.activeScenePath = "Assets/Scenes/Untitled.scene";
    }

    public void promptOpenSceneWithNativeDialog() {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            loadSceneFromFile(importedPath);
        }
    }

    public void promptSaveSceneWithNativeDialog(List<AssetEntry> assetDirectory) {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String targetPath = NativeFileDialog.saveFileDialog("Save Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            saveActiveScene(targetPath, assetDirectory);
        }
    }

    public void saveActiveScene(String filePath, List<AssetEntry> assetDirectory) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        SceneSerializer.saveScene(filePath, sceneEntities, assetDirectory);
        this.activeScenePath = filePath;
    }

    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath) {
        stop();
        this.activeScenePath = filePath;
        sceneEntities.clear();
        selectedEntity = null;
        SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(filePath);
        if (data != null && data.entities() != null) {
            sceneEntities.addAll(data.entities());
            selectEntity(sceneEntities.isEmpty() ? null : sceneEntities.get(0));
        }
        return data;
    }

    public void deleteSelectedEntity() {
        if (selectedEntity != null) {
            sceneEntities.remove(selectedEntity);
            selectedEntity = null;
        }
    }

    public void duplicateSelectedEntity() {
        if (selectedEntity == null) { spawnDefaultCube(); return; }
        String entityId = "entity_" + System.currentTimeMillis();
        String name = selectedEntity.name + "_Copy";
        SceneEntity copy = new SceneEntity(entityId, name, selectedEntity.getMeshAssetPath());

        TransformComponent origTc = selectedEntity.getTransform();
        TransformComponent copyTc = copy.getTransform();
        if (origTc != null && copyTc != null) {
            copyTc.posX = origTc.posX + 0.5f; copyTc.posY = origTc.posY; copyTc.posZ = origTc.posZ;
            copyTc.rotX = origTc.rotX; copyTc.rotY = origTc.rotY; copyTc.rotZ = origTc.rotZ;
            copyTc.scaleX = origTc.scaleX; copyTc.scaleY = origTc.scaleY; copyTc.scaleZ = origTc.scaleZ;
        }

        MaterialComponent origMat = selectedEntity.getComponent(MaterialComponent.class);
        if (origMat != null) {
            MaterialComponent copyMat = copy.getComponent(MaterialComponent.class);
            if (copyMat == null) { copyMat = new MaterialComponent(); copy.addComponent(copyMat); }
            copyMat.textureAssetPath = origMat.textureAssetPath;
            copyMat.textureName = origMat.textureName;
            copyMat.shaderName = origMat.shaderName;
        }

        LightComponent origLc = selectedEntity.getComponent(LightComponent.class);
        if (origLc != null) {
            LightComponent copyLc = new LightComponent();
            copyLc.type = origLc.type; copyLc.intensity = origLc.intensity;
            copyLc.r = origLc.r; copyLc.g = origLc.g; copyLc.b = origLc.b;
            copy.addComponent(copyLc);
        }

        CameraComponent origCc = selectedEntity.getComponent(CameraComponent.class);
        if (origCc != null) {
            CameraComponent copyCc = new CameraComponent(origCc.fov, origCc.nearPlane, origCc.farPlane);
            copyCc.isPrimary = origCc.isPrimary;
            copy.addComponent(copyCc);
        }

        sceneEntities.add(copy);
        selectEntity(copy);
    }

    public void spawnDefaultCube() {
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = "Cube_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, "Assets/Models/cube.obj");
        sceneEntities.add(entity);
        selectEntity(entity);
    }

    public void spawnLightSource() {
        String entityId = "light_" + (sceneEntities.size() + 1);
        String name = "DirectionalLight_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new LightComponent());
        sceneEntities.add(entity);
        selectEntity(entity);
    }

    public void spawnCameraEntity() {
        String entityId = "camera_" + (sceneEntities.size() + 1);
        String name = "Main Camera_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new CameraComponent());
        sceneEntities.add(entity);
        selectEntity(entity);
    }

    public void spawnEntityFromAsset(AssetEntry meshAsset) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath);
        sceneEntities.add(entity);
        selectEntity(entity);
    }

    public void assignTextureToSelectedEntity(AssetEntry textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name);
    }

    public void assignMeshToSelectedEntity(AssetEntry meshAsset) {
        if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        selectedEntity.setMeshAssetPath(meshAsset.absolutePath);
    }
}
