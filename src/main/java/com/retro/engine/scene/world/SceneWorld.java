package com.retro.engine.scene.world;

import com.retro.engine.physics.PhysicsSystem;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.serialization.PrefabSerializer;
import com.retro.engine.scene.serialization.SceneSerializer;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.entity.ComponentPool;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntityHandle;
import com.retro.engine.scene.entity.EntitySpawner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refactored SceneWorld acting as a Backwards-Compatible Facade Bridge.
 * Coordinates entity storage, selection state, and scene file path state while delegating
 * generational handle and ComponentPool bookkeeping (EntityManager), matrix evaluation,
 * simulation state machine, and entity instantiation.
 * Hardened with hardware audio clip disposal, contiguous ComponentPool ECS storage,
 * and per-frame skeletal AnimationSystem updates.
 */
public class SceneWorld {

    public PhysicsSystem getPhysicsWorld() {
        return simulationEngine.getPhysicsWorld();
    }


    public enum PlayState { EDITING, SIMULATING, PAUSED }

    public record WorldTransform(
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz
    ) {}

    private final List<Entity> sceneEntities = new ArrayList<>();
    // Live read-only view, created once so per-frame scene traversal does not allocate a wrapper
    private final List<Entity> sceneEntitiesView = Collections.unmodifiableList(sceneEntities);
    
    // 64-bit generational handle registry and contiguous ComponentPool storage
    private final EntityManager entityManager = new EntityManager();
    private EntityHandle selectedHandle = EntityHandle.NULL;
    private Entity selectedEntity = null;

    private String activeScenePath = "Assets/Scenes/Default.rscene";
    // Scene transition requested by a script; applied at the end of update() so no entity list is mid-iteration
    private volatile String pendingScenePath = null;
    // Last project database used to load a scene, reused so deferred transitions resolve project-relative assets
    private AssetDatabase lastAssetDatabase = null;
    // Scene that was open when Play was pressed, parked (already restored to its pre-play state) while a
    // script-triggered transition runs another scene; Stop swaps it back in. Null when no transition happened.
    private List<Entity> originSceneEntities = null;
    private String originScenePath = null;
    private String originSelectedId = null;

    // ECS Subsystem Delegates, Contiguous Component Storage, & Animation Engine
    private final HierarchySystem graphEvaluator = new HierarchySystem();
    private final SimulationSystem simulationEngine = new SimulationSystem();
    private final EntitySpawner entityFactory = new EntitySpawner();
    private final AnimationSystem animationEngine = new AnimationSystem();

    public SceneWorld() {}

    public List<Entity> getSceneEntities() { return sceneEntitiesView; }

    public <T extends Component> ComponentPool<T> getComponentPool(Class<T> clazz) {
        return entityManager.getComponentPool(clazz);
    }

    public void syncEntityToPools(Entity entity) {
        entityManager.syncEntityToPools(entity);
    }

    public void removeComponentFromPool(Entity entity, Component component) {
        entityManager.removeComponentFromPool(entity, component);
    }

    public void removeEntityFromPools(Entity entity) {
        entityManager.removeEntityFromPools(entity);
    }

    // --- GENERATIONAL HANDLE REGISTRY API ---
    public EntityHandle registerEntityHandle(Entity entity) {
        return entityManager.registerEntityHandle(entity);
    }

    public void unregisterEntityHandle(Entity entity) {
        entityManager.unregisterEntityHandle(entity);
    }

    public boolean isValidHandle(EntityHandle handle) {
        return entityManager.isValidHandle(handle);
    }

    public Entity getEntityByHandle(EntityHandle handle) {
        return entityManager.getEntityByHandle(handle);
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
        boolean enteringPlayMode = simulationEngine.getPlayState() == PlayState.EDITING;
        simulationEngine.play(sceneEntities, this);
        syncAnimationPlayState();
        if (enteringPlayMode) enterAnimatorEntryStates();
    }

    public void pause() {
        simulationEngine.pause();
        syncAnimationPlayState();
    }

    /** Animation follows the simulation's play state: it only ticks while SIMULATING. */
    private void syncAnimationPlayState() {
        animationEngine.setPlayState(simulationEngine.getPlayState());
    }

    public void stop() {
        stopSimulation(true);
    }

    /** restoreOrigin: if a script transitioned scenes during play, return to the scene Play was pressed in. */
    private void stopSimulation(boolean restoreOrigin) {
        boolean leavingPlayMode = simulationEngine.getPlayState() != PlayState.EDITING;
        List<Entity> restored = simulationEngine.stop(sceneEntities);
        syncAnimationPlayState();
        if (restored != null) {
            clearRegistryAndRebind(restored);
        }
        if (leavingPlayMode) stopAnimators();
        if (leavingPlayMode && restoreOrigin) {
            pendingScenePath = null;
            restoreOriginScene();
        }
    }

    private void restoreOriginScene() {
        if (originSceneEntities == null) return;
        List<Entity> origin = originSceneEntities;
        originSceneEntities = null;
        this.activeScenePath = originScenePath;
        clearRegistryAndRebind(origin, true);
        selectEntity(findEntityById(originSelectedId));
        // Re-arms the parked scene's animators in the bind pose; its components were never disposed
        attachAndArmAnimators();
    }

    /** Restarts every animator from its controller's Entry State (resuming from Pause does not call this). */
    private void enterAnimatorEntryStates() {
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e == null) continue;
            AnimatorComponent animator = e.getComponent(AnimatorComponent.class);
            if (animator != null) animationEngine.enterEntryState(animator);
        }
    }

    /** Halts every animator, rewinds it to its Entry State, and snaps its mesh back to the bind pose. */
    private void stopAnimators() {
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e == null) continue;
            AnimatorComponent animator = e.getComponent(AnimatorComponent.class);
            if (animator != null) animationEngine.stop(animator);
        }
    }

    /**
     * Skeletons and controllers are not serialized (only the authored pose data is), so after a scene load rigged
     * meshes would otherwise sit unanimated until selected in the editor. Rebuild their skeleton/controller from the
     * mesh asset and arm the Entry State at frame 0 in the bind pose; the clip starts when Play is pressed.
     */
    private void attachAndArmAnimators() {
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e == null) continue;
            String meshPath = e.getMeshAssetPath();
            AnimatorComponent loaded = e.getComponent(AnimatorComponent.class);
            if ((loaded == null || loaded.skeleton == null) && meshPath != null && !meshPath.trim().isEmpty()) {
                EntitySpawner.attachAnimatorIfRigged(e, meshPath);
            }
            AnimatorComponent animator = e.getComponent(AnimatorComponent.class);
            if (animator != null) animationEngine.armEntryState(animator);
        }
    }

    private void clearRegistryAndRebind(List<Entity> newEntities) {
        clearRegistryAndRebind(newEntities, true);
    }

    /** disposeOld=false keeps the outgoing entities' native resources alive so they can be rebound later. */
    private void clearRegistryAndRebind(List<Entity> newEntities, boolean disposeOld) {
        for (Entity e : sceneEntities) {
            if (e != null) {
                AudioSource ac = e.getComponent(AudioSource.class);
                if (ac != null) {
                    if (disposeOld) ac.dispose();
                    else ac.stop();
                }
            }
        }

        entityManager.clear();

        this.sceneEntities.clear();
        if (newEntities != null) {
            this.sceneEntities.addAll(newEntities);
            for (Entity e : sceneEntities) {
                registerEntityHandle(e);
            }
        }

        graphEvaluator.clearCache();

        if (selectedEntity != null && selectedEntity.id != null) {
            Entity matched = findEntityById(selectedEntity.id);
            selectEntity(matched);
        } else {
            selectEntity((Entity) null);
        }
    }

    public void update(float deltaTime) {
        simulationEngine.update(deltaTime, sceneEntities, this);
        syncAnimationPlayState();
        
        // Per-frame Skeletal Animation Keyframe Sampling & Pose Matrix Palette Evaluation
        if (sceneEntities != null) {
            for (int i = 0; i < sceneEntities.size(); i++) {
                Entity e = sceneEntities.get(i);
                if (e != null) {
                    AnimatorComponent animator = e.getComponent(AnimatorComponent.class);
                    if (animator != null) {
                        animationEngine.update(animator, deltaTime);
                    }
                }
            }
        }

        applyPendingSceneLoad();
    }

    /**
     * Rebuilds an animator's bone palette from its rest pose and editor pose edits right away, so the skinned mesh
     * deforms in the same frame a bone is edited. Only while EDITING: playing and paused palettes belong to playback.
     */
    public void refreshEditPose(AnimatorComponent animator) {
        if (animator == null || getPlayState() != PlayState.EDITING) return;
        animationEngine.resetToBindPose(animator);
    }

    /** Queues a scene transition; the load runs after the current frame's physics, script, and animation ticks. */
    public void requestSceneLoad(String scenePath) {
        if (scenePath == null || scenePath.trim().isEmpty()) return;
        this.pendingScenePath = scenePath;
    }

    public boolean hasPendingSceneLoad() { return pendingScenePath != null; }

    /**
     * Swaps in the queued scene: stop() destroys scripts and tears down physics bodies, the registry rebind
     * disposes audio and invalidates old handles, and the new scene's animators are armed. GPU meshes/textures
     * no longer referenced are released by AssetStreamer's residency sweep. If the old scene was running,
     * the new one re-enters play so its physics world and scripts initialize.
     */
    private void applyPendingSceneLoad() {
        String path = pendingScenePath;
        if (path == null) return;
        pendingScenePath = null;

        boolean wasRunning = simulationEngine.getPlayState() != PlayState.EDITING;
        // Tear down the running scene without jumping back to the origin; this restores it to its pre-play state
        stopSimulation(false);
        if (wasRunning && originSceneEntities == null) {
            // First transition this play session: park the scene Play was pressed in instead of disposing it
            originSceneEntities = new ArrayList<>(sceneEntities);
            originScenePath = activeScenePath;
            Entity selected = getSelectedEntity();
            originSelectedId = selected != null ? selected.id : null;
            clearRegistryAndRebind(new ArrayList<>(), false);
        }
        loadSceneFromFile(path, lastAssetDatabase);
        if (wasRunning) play();
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
        boolean result = graphEvaluator.setParent(child, parent, sceneEntities);
        if (result) {
            graphEvaluator.clearCache();
        }
        return result;
    }

    // --- DELEGATED ENTITY FACTORY OPERATIONS ---
    public void deleteEntity(Entity target) {
        if (target != null) {
            AudioSource ac = target.getComponent(AudioSource.class);
            if (ac != null) ac.dispose();

            unregisterEntityHandle(target);
            entityFactory.deleteEntity(target, sceneEntities);
            graphEvaluator.clearCache();
            if (selectedEntity == target) {
                selectEntity((Entity) null);
            }
        }
    }

    public void addEntity(Entity target, int index) {
        if (target != null) {
            if (!sceneEntities.contains(target)) {
                if (index >= 0 && index <= sceneEntities.size()) {
                    sceneEntities.add(index, target);
                } else {
                    sceneEntities.add(target);
                }
            }
            registerEntityHandle(target);
            if (target.parentId != null && !target.parentId.trim().isEmpty()) {
                Entity parent = findEntityById(target.parentId);
                if (parent != null) {
                    parent.addChildId(target.id);
                }
            }
            selectEntity(target);
            graphEvaluator.clearCache();
        }
    }

    public void deleteSelectedEntity() {
        deleteEntity(getSelectedEntity());
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
        if (getSelectedEntity() != null) syncEntityToPools(getSelectedEntity());
    }

    public void assignMeshToSelectedEntity(AssetMeta meshAsset) {
        entityFactory.assignMeshToSelectedEntity(getSelectedEntity(), meshAsset);
        if (getSelectedEntity() != null) syncEntityToPools(getSelectedEntity());
    }

    public void assignAudioToSelectedEntity(AssetMeta audioAsset) {
        entityFactory.assignAudioToSelectedEntity(getSelectedEntity(), audioAsset);
        if (getSelectedEntity() != null) syncEntityToPools(getSelectedEntity());
    }

    // --- PREFAB BLUEPRINT SERIALIZATION & INSTANTIATION ---
    public void promptSaveSelectedAsPrefab(List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        Entity selected = getSelectedEntity();
        if (selected == null) return;
        String defaultDir = projectSubDir(assetDb, "Prefabs");
        String targetPath = NativeFileDialog.saveFileDialog("Save Entity Prefab Blueprint", defaultDir, "Prefab Blueprint (*.prefab.bin)", "prefab.bin", "prefab");
        if (targetPath != null && !targetPath.trim().isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            PrefabSerializer.savePrefab(targetPath, selected, sceneEntities, assetDb);
            if (assetDb != null) {
                assetDb.addAssetDirectoryIfNotExists(targetPath, AssetType.PREFAB);
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public List<Entity> instantiatePrefab(String filePath, AssetDatabase assetDb) {
        List<Entity> spawned = PrefabSerializer.instantiatePrefab(filePath, this, assetDb);
        if (spawned != null && !spawned.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
        }
        return spawned;
    }

    /** Dialog start directory inside the active project's Assets/ folder, falling back to the working directory. */
    private static String projectSubDir(AssetDatabase assetDb, String sub) {
        String assetsRoot = (assetDb != null && assetDb.hasActiveProject()) ? assetDb.getProjectAssetsPath() : "Assets";
        File dir = new File(assetsRoot, sub);
        return (dir.isDirectory() ? dir : new File(assetsRoot)).getAbsolutePath() + File.separator;
    }

    // --- SCENE SERIALIZATION & DIALOGS ---
    public void newScene() {
        stop();
        pendingScenePath = null;
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
        String defaultDir = projectSubDir(assetDb, "Scenes");
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
        if (assetDb != null) this.lastAssetDatabase = assetDb;
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        this.activeScenePath = sanitized;
        SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(sanitized, assetDb);
        List<Entity> loadedEntities = (data != null && data.entities() != null) ? data.entities() : new ArrayList<>();
        clearRegistryAndRebind(loadedEntities);
        attachAndArmAnimators();
        // A freshly loaded scene starts with nothing selected (rebind may have matched a stale selection by id)
        selectEntity((Entity) null);
        return data;
    }
}
