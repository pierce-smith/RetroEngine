package com.retro.engine.scene;

import com.retro.engine.scene.SceneSerializer.SceneLoadData;

import com.retro.engine.scene.EntitySpawner;
import com.retro.engine.scene.HierarchySystem;
import com.retro.engine.scene.SimulationSystem;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.scene.SceneSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refactored SceneWorld acting as a Backwards-Compatible Facade Bridge.
 * Coordinates entity storage, selection state, and scene file path state while
 * delegating matrix evaluation, simulation state machine, and entity instantiation.
 */
public class SceneWorld {

    public enum PlayState { EDITING, SIMULATING, PAUSED }

    public record WorldTransform(
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz
    ) {}

    private final List<Entity> sceneEntities = new ArrayList<>();
    private Entity selectedEntity = null;
    private String activeScenePath = "Assets/Scenes/Default.scene";

    // ECS Subsystem Delegates
    private final HierarchySystem graphEvaluator = new HierarchySystem();
    private final SimulationSystem simulationEngine = new SimulationSystem();
    private final EntitySpawner entityFactory = new EntitySpawner();

    public SceneWorld() {}

    public List<Entity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public Entity getSelectedEntity() { return selectedEntity; }

    public void selectEntity(Entity entity) {
        for (Entity e : sceneEntities) {
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
        List<Entity> restored = simulationEngine.stop(sceneEntities);
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

    public Entity findEntityById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Entity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    // --- DELEGATED SCENE GRAPH MATRIX EVALUATION ---
    public WorldTransform getComputedWorldTransform(Entity entity) {
        return graphEvaluator.getComputedWorldTransform(entity, sceneEntities);
    }

    public boolean hasHierarchyCycle(Entity startEntity) {
        return graphEvaluator.hasHierarchyCycle(startEntity, sceneEntities);
    }

    public boolean setParent(Entity child, Entity parent) {
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
        Entity copy = entityFactory.duplicateEntity(selectedEntity, sceneEntities);
        selectEntity(copy);
    }

    public void spawnDefaultCube() {
        Entity entity = entityFactory.spawnDefaultCube(sceneEntities);
        selectEntity(entity);
    }

    public void spawnLightSource() {
        Entity entity = entityFactory.spawnLightSource(sceneEntities);
        selectEntity(entity);
    }

    public void spawnAudioSource() {
        Entity entity = entityFactory.spawnAudioSource(sceneEntities);
        selectEntity(entity);
    }

    public void spawnCameraEntity() {
        Entity entity = entityFactory.spawnCameraEntity(sceneEntities);
        selectEntity(entity);
    }

    public void spawnEntityFromAsset(AssetMeta meshAsset) {
        Entity entity = entityFactory.spawnEntityFromAsset(meshAsset, sceneEntities);
        if (entity != null) selectEntity(entity);
    }

    public void assignTextureToSelectedEntity(AssetMeta textureAsset) {
        entityFactory.assignTextureToSelectedEntity(selectedEntity, textureAsset);
    }

    public void assignMeshToSelectedEntity(AssetMeta meshAsset) {
        entityFactory.assignMeshToSelectedEntity(selectedEntity, meshAsset);
    }

    public void assignAudioToSelectedEntity(AssetMeta audioAsset) {
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
            UISoundPlayer.play(UISound.SPAWN);
            loadSceneFromFile(importedPath);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog(List<AssetMeta> assetDirectory) {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String targetPath = NativeFileDialog.saveFileDialog("Save Scene File", defaultDir, "Retro Scene (*.scene)", "scene");
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            saveActiveScene(targetPath, assetDirectory);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void saveActiveScene(String filePath, List<AssetMeta> assetDirectory) {
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
