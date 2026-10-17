package com.retro.engine.scene;

import com.retro.engine.scene.SceneSerializer.SceneLoadData;
import com.retro.engine.scene.EntitySpawner;
import com.retro.engine.scene.HierarchySystem;
import com.retro.engine.scene.SimulationSystem;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.assets.AssetDatabase;
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
 * Coordinates entity storage, 64-bit generational entity handle lifecycle management,
 * selection state, and scene file path state while delegating matrix evaluation,
 * simulation state machine, and entity instantiation.
 * Hardened with hardware audio clip disposal on entity deletion and scene unloads.
 */
public class SceneWorld {

    public enum PlayState { EDITING, SIMULATING, PAUSED }

    public record WorldTransform(
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz
    ) {}

    private final List<Entity> sceneEntities = new ArrayList<>();
    
    // --- 64-Bit Generational Handle Registry ---
    private final List<Entity> entitySlots = new ArrayList<>();
    private final List<Integer> entityGenerations = new ArrayList<>();
    private EntityHandle selectedHandle = EntityHandle.NULL;
    private Entity selectedEntity = null;

    private String activeScenePath = "Assets/Scenes/Default.rscene";

    // ECS Subsystem Delegates
    private final HierarchySystem graphEvaluator = new HierarchySystem();
    private final SimulationSystem simulationEngine = new SimulationSystem();
    private final EntitySpawner entityFactory = new EntitySpawner();

    public SceneWorld() {}

    public List<Entity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }

    // --- GENERATIONAL HANDLE REGISTRY API ---
    public EntityHandle registerEntityHandle(Entity entity) {
        if (entity == null) return EntityHandle.NULL;
        
        // Find existing reusable empty slot or expand array
        int slotIndex = -1;
        for (int i = 0; i < entitySlots.size(); i++) {
            if (entitySlots.get(i) == null) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex == -1) {
            slotIndex = entitySlots.size();
            entitySlots.add(entity);
            entityGenerations.add(1);
        } else {
            entitySlots.set(slotIndex, entity);
        }

        int gen = entityGenerations.get(slotIndex);
        EntityHandle handle = new EntityHandle(slotIndex, gen);
        entity.setHandle(handle);
        return handle;
    }

    public void unregisterEntityHandle(Entity entity) {
        if (entity == null || entity.handle == null || !entity.handle.isValid()) return;
        int idx = entity.handle.index();
        if (idx >= 0 && idx < entitySlots.size()) {
            entitySlots.set(idx, null);
            entityGenerations.set(idx, entityGenerations.get(idx) + 1); // Increment generation to invalidate stale handles
        }
        entity.setHandle(EntityHandle.NULL);
    }

    public boolean isValidHandle(EntityHandle handle) {
        if (handle == null || !handle.isValid()) return false;
        int idx = handle.index();
        if (idx < 0 || idx >= entitySlots.size()) return false;
        return entityGenerations.get(idx) == handle.generation() && entitySlots.get(idx) != null;
    }

    public Entity getEntityByHandle(EntityHandle handle) {
        if (!isValidHandle(handle)) return null;
        return entitySlots.get(handle.index());
    }

    public EntityHandle getSelectedEntityHandle() {
        if (!isValidHandle(selectedHandle)) {
            selectedHandle = EntityHandle.NULL;
            selectedEntity = null;
        }
        return selectedHandle;
    }

    public Entity getSelectedEntity() {
        if (!isValidHandle(selectedHandle)) {
            selectedHandle = EntityHandle.NULL;
            selectedEntity = null;
            return null;
        }
        return selectedEntity;
    }

    public void selectEntity(Entity entity) {
        if (entity != null) {
            if (entity.handle == null || !isValidHandle(entity.handle)) {
                registerEntityHandle(entity);
            }
            this.selectedHandle = entity.getHandle();
            this.selectedEntity = entity;
        } else {
            this.selectedHandle = EntityHandle.NULL;
            this.selectedEntity = null;
        }

        for (Entity e : sceneEntities) {
            e.selected = (e == selectedEntity);
        }
    }

    public void selectEntityByHandle(EntityHandle handle) {
        Entity entity = getEntityByHandle(handle);
        selectEntity(entity);
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
            clearRegistryAndRebind(restored);
        }
    }

    private void clearRegistryAndRebind(List<Entity> newEntities) {
        // Dispose hardware audio resources for all active entities before clearing
        for (Entity e : sceneEntities) {
            if (e != null) {
                AudioSource ac = e.getComponent(AudioSource.class);
                if (ac != null) ac.dispose();
            }
        }

        // Increment generations for all active slots
        for (int i = 0; i < entitySlots.size(); i++) {
            if (entitySlots.get(i) != null) {
                entityGenerations.set(i, entityGenerations.get(i) + 1);
                entitySlots.set(i, null);
            }
        }

        this.sceneEntities.clear();
        if (newEntities != null) {
            this.sceneEntities.addAll(newEntities);
            for (Entity e : sceneEntities) {
                registerEntityHandle(e);
            }
        }

        // Rebind selection handle if target entity ID exists
        if (selectedEntity != null && selectedEntity.id != null) {
            Entity matched = findEntityById(selectedEntity.id);
            selectEntity(matched);
        } else {
            selectEntity((Entity) null);
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
        Entity target = getSelectedEntity();
        if (target != null) {
            AudioSource ac = target.getComponent(AudioSource.class);
            if (ac != null) ac.dispose();

            unregisterEntityHandle(target);
            entityFactory.deleteEntity(target, sceneEntities);
            selectEntity((Entity) null);
        }
    }

    public void duplicateSelectedEntity() {
        Entity target = getSelectedEntity();
        Entity copy = entityFactory.duplicateEntity(target, sceneEntities);
        registerEntityHandle(copy);
        selectEntity(copy);
    }

    public void spawnDefaultCube() {
        Entity entity = entityFactory.spawnDefaultCube(sceneEntities);
        registerEntityHandle(entity);
        selectEntity(entity);
    }

    public void spawnLightSource() {
        Entity entity = entityFactory.spawnLightSource(sceneEntities);
        registerEntityHandle(entity);
        selectEntity(entity);
    }

    public void spawnAudioSource() {
        Entity entity = entityFactory.spawnAudioSource(sceneEntities);
        registerEntityHandle(entity);
        selectEntity(entity);
    }

    public void spawnCameraEntity() {
        Entity entity = entityFactory.spawnCameraEntity(sceneEntities);
        registerEntityHandle(entity);
        selectEntity(entity);
    }

    public void spawnEntityFromAsset(AssetMeta meshAsset) {
        Entity entity = entityFactory.spawnEntityFromAsset(meshAsset, sceneEntities);
        if (entity != null) {
            registerEntityHandle(entity);
            selectEntity(entity);
        }
    }

    public void assignTextureToSelectedEntity(AssetMeta textureAsset) {
        entityFactory.assignTextureToSelectedEntity(getSelectedEntity(), textureAsset);
    }

    public void assignMeshToSelectedEntity(AssetMeta meshAsset) {
        entityFactory.assignMeshToSelectedEntity(getSelectedEntity(), meshAsset);
    }

    public void assignAudioToSelectedEntity(AssetMeta audioAsset) {
        entityFactory.assignAudioToSelectedEntity(getSelectedEntity(), audioAsset);
    }

    // --- SCENE SERIALIZATION & DIALOGS ---
    public void newScene() {
        stop();
        clearRegistryAndRebind(new ArrayList<>());
        this.activeScenePath = "Assets/Scenes/Untitled.rscene";
    }

    public void promptOpenSceneWithNativeDialog() {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
        if (importedPath != null && !importedPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            loadSceneFromFile(importedPath, null);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void promptSaveSceneWithNativeDialog(List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        String defaultDir = new File("Assets/Scenes").getAbsolutePath();
        String targetPath = NativeFileDialog.saveFileDialog("Save Scene File", defaultDir, "Retro Scene (*.rscene)", "rscene");
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            saveActiveScene(targetPath, assetDirectory, assetDb);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public void saveActiveScene(String filePath, List<AssetMeta> assetDirectory) {
        saveActiveScene(filePath, assetDirectory, null);
    }

    public void saveActiveScene(String filePath, List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        SceneSerializer.saveScene(sanitized, sceneEntities, assetDirectory, assetDb);
        this.activeScenePath = sanitized;
    }

    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath) {
        return loadSceneFromFile(filePath, null);
    }

    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath, AssetDatabase assetDb) {
        stop();
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        this.activeScenePath = sanitized;
        SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(sanitized, assetDb);
        List<Entity> loadedEntities = (data != null && data.entities() != null) ? data.entities() : new ArrayList<>();
        clearRegistryAndRebind(loadedEntities);
        if (!sceneEntities.isEmpty()) {
            selectEntity(sceneEntities.get(0));
        }
        return data;
    }
}
