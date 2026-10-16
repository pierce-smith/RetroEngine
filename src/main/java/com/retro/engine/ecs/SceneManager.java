package com.retro.engine.ecs;

import com.retro.engine.ecs.delegates.SceneEntityFactory;
import com.retro.engine.ecs.delegates.SceneGraphEvaluator;
import com.retro.engine.ecs.delegates.SceneSimulationEngine;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.audio.UISoundManager;
import com.retro.engine.editor.audio.UISoundManager.UISound;
import com.retro.engine.serialization.SceneSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refactored SceneManager acting as a Backwards-Compatible Facade Bridge.
 * Coordinates entity storage, selection state, and scene file path state while
 * delegating matrix evaluation, simulation state machine, and entity instantiation.
 */
public class SceneManager {

    public enum PlayState { EDITING, SIMULATING, PAUSED }

    public record WorldTransform(
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz
    ) {}

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private SceneEntity selectedEntity = null;
    private String activeScenePath = "Assets/Scenes/Default.scene";

    // ECS Subsystem Delegates
    private final SceneGraphEvaluator graphEvaluator = new SceneGraphEvaluator();
    private final SceneSimulationEngine simulationEngine = new SceneSimulationEngine();
    private final SceneEntityFactory entityFactory = new SceneEntityFactory();

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

    // --- DELEGATED SIMULATION STATE MACHINE ---
    public PlayState getPlayState() {
        return switch (simulationEngine.getPlayState()) {
            case EDITING -> PlayState.EDITING;
            case SIMULATING -> PlayState.SIMULATING;
            case PAUSED -> PlayState.PAUSED;
        };
    }

    public void play() {
        simulationEngine.play(sceneEntities);
    }

    public void pause() {
        simulationEngine.pause();
    }

    public void stop() {
        List<SceneEntity> restored = simulationEngine.stop(sceneEntities);
        if (restored != null) {
            this.sceneEntities.clear();
            this.sceneEntities.addAll(restored);
            // Re-bind selected entity pointer if lost
            if (selectedEntity != null) {
                this.selectedEntity = findEntityById(selectedEntity.id);
            }
        }
    }

    public void update(float deltaTime) {
        simulationEngine.update(deltaTime, sceneEntities);
    }

    public SceneEntity findEntityById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (SceneEntity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    // --- DELEGATED SCENE GRAPH MATRIX EVALUATION ---
    public WorldTransform getComputedWorldTransform(SceneEntity entity) {
        return graphEvaluator.getComputedWorldTransform(entity, sceneEntities);
    }

    public boolean hasHierarchyCycle(SceneEntity startEntity) {
        return graphEvaluator.hasHierarchyCycle(startEntity, sceneEntities);
    }

    public boolean setParent(SceneEntity child, SceneEntity parent) {
        return graphEvaluator.setParent(child, parent, sceneEntities);
    }

    // --- DELEGATED ENTITY FACTORY OPERATIONS ---
    public void deleteSelectedEntity() {
        if (selectedEntity != null) {
            entityFactory.deleteEntity(selectedEntity, sceneEntities);
            selectedEntity = null;
        }
    }

    public void duplicateSelectedEntity() {
        SceneEntity copy = entityFactory.duplicateEntity(selectedEntity, sceneEntities);
        selectEntity(copy);
    }

    public void spawnDefaultCube() {
        SceneEntity entity = entityFactory.spawnDefaultCube(sceneEntities);
        selectEntity(entity);
    }

    public void spawnLightSource() {
        SceneEntity entity = entityFactory.spawnLightSource(sceneEntities);
        selectEntity(entity);
    }

    public void spawnAudioSource() {
        SceneEntity entity = entityFactory.spawnAudioSource(sceneEntities);
        selectEntity(entity);
    }

    public void spawnCameraEntity() {
        SceneEntity entity = entityFactory.spawnCameraEntity(sceneEntities);
        selectEntity(entity);
    }

    public void spawnEntityFromAsset(AssetEntry meshAsset) {
        SceneEntity entity = entityFactory.spawnEntityFromAsset(meshAsset, sceneEntities);
        if (entity != null) selectEntity(entity);
    }

    public void assignTextureToSelectedEntity(AssetEntry textureAsset) {
        entityFactory.assignTextureToSelectedEntity(selectedEntity, textureAsset);
    }

    public void assignMeshToSelectedEntity(AssetEntry meshAsset) {
        entityFactory.assignMeshToSelectedEntity(selectedEntity, meshAsset);
    }

    public void assignAudioToSelectedEntity(AssetEntry audioAsset) {
        entityFactory.assignAudioToSelectedEntity(selectedEntity, audioAsset);
    }

    // --- SCENE SERIALIZATION & DIALOGS ---
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
            UISoundManager.play(UISound.SPAWN);
            loadSceneFromFile(importedPath);
        } else {
            UISoundManager.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog(List<AssetEntry> assetDirectory) {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String targetPath = NativeFileDialog.saveFileDialog("Save Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            UISoundManager.play(UISound.SPAWN);
            saveActiveScene(targetPath, assetDirectory);
        } else {
            UISoundManager.play(UISound.CLOSE_MODAL);
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
}
