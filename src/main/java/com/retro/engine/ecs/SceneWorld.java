package com.retro.engine.ecs;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.ecs.serialization.SceneBackups;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.modding.ModLoader;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.scripting.ScriptEngine;
import com.retro.engine.scripting.framework.ScriptRuntime;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The loaded scene: its entity list, the current selection, the active scene file and the play state.
 * This is the one object scripts, mods and the editor talk to. The work itself lives in small helpers:
 * {@link EntityManager} (handles and component pools), {@link HierarchySystem} (world transforms),
 * {@link SimulationSystem} (play/pause/stop and physics), {@link EntitySpawner} (creating and deleting entities)
 * and {@link AnimationSystem} (skeletal animation).
 */
public class SceneWorld {

    public PhysicsSystem getPhysicsWorld() {
        return simulationEngine.getPhysicsWorld();
    }

    /** NavMesh owner and agent ticker for the active scene. */
    public com.retro.engine.navigation.NavMeshSystem getNavigation() {
        return simulationEngine.getNavigation();
    }


    public enum PlayState { EDITING, SIMULATING, PAUSED }

    /**
     * World-space position, rotation (degrees) and scale of an entity. HierarchySystem caches one instance per entity
     * and updates it in place when the entity or a parent moves, so the per-frame hierarchy pass does not allocate:
     * read the values when you get it, and do not keep the object across frames.
     */
    public static final class WorldTransform {
        private float px, py, pz, rx, ry, rz, sx, sy, sz;

        public WorldTransform(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            set(px, py, pz, rx, ry, rz, sx, sy, sz);
        }

        void set(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            this.px = px; this.py = py; this.pz = pz;
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.sx = sx; this.sy = sy; this.sz = sz;
        }

        public float px() { return px; }
        public float py() { return py; }
        public float pz() { return pz; }
        public float rx() { return rx; }
        public float ry() { return ry; }
        public float rz() { return rz; }
        public float sx() { return sx; }
        public float sy() { return sy; }
        public float sz() { return sz; }
    }

    private final List<Entity> sceneEntities = new ArrayList<>();
    // Live read-only view, created once so per-frame scene traversal does not allocate a wrapper
    private final List<Entity> sceneEntitiesView = Collections.unmodifiableList(sceneEntities);
    
    // 64-bit generational handle registry and contiguous ComponentPool storage
    private final EntityManager entityManager = new EntityManager();
    private EntityHandle selectedHandle = EntityHandle.NULL;
    private Entity selectedEntity = null;
    // Full multi-selection in selection order. selectedEntity is its primary (last-picked) member: single-entity tools
    // (bone editing, prefab save, the inspector's full view) keep operating on it, group tools use the whole set.
    private final LinkedHashSet<Entity> selection = new LinkedHashSet<>();
    private final List<Entity> selectionSnapshot = new ArrayList<>();
    private final List<Entity> selectionView = Collections.unmodifiableList(selectionSnapshot);

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
    // Scene file that failed to load. Saving over it is refused, because the file may be recoverable and the world isn't it
    private File unreadableSceneFile = null;
    private java.util.function.Consumer<String> dataErrorListener = null;

    // ECS Subsystem Delegates, Contiguous Component Storage, & Animation Engine
    // Entities scripts asked to destroy this frame; removed after the script tick so no update loop shifts mid-iteration
    private final List<Entity> pendingDestroys = new ArrayList<>();
    private final List<Entity> destroyScratch = new ArrayList<>();

    private final HierarchySystem graphEvaluator = new HierarchySystem();
    private final SimulationSystem simulationEngine = new SimulationSystem();
    private final EntitySpawner entityFactory = new EntitySpawner();
    private final AnimationSystem animationEngine = new AnimationSystem();

    public SceneWorld() {}

    /** Receives a readable message when a scene or prefab can't be loaded or saved. Errors are always logged as well. */
    public void setDataErrorListener(java.util.function.Consumer<String> listener) {
        this.dataErrorListener = listener;
    }

    private void reportDataError(String message) {
        System.err.println("[SceneWorld] " + message);
        if (dataErrorListener != null) dataErrorListener.accept(message);
    }

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

    /** Replaces the selection with just {@code entity} (null clears it). */
    public void selectEntity(Entity entity) {
        selection.clear();
        if (entity != null) selection.add(entity);
        setPrimary(entity);
    }

    /** Replaces the selection with {@code entities} (scene members only); {@code primary} must be one of them, else the last. */
    public void setSelection(Collection<Entity> entities, Entity primary) {
        selection.clear();
        if (entities != null) {
            for (Entity e : entities) {
                if (e != null && sceneEntities.contains(e)) selection.add(e);
            }
        }
        setPrimary(selection.contains(primary) ? primary : lastSelected());
    }

    /** Cmd/Ctrl+Click: adds {@code entity} (making it primary) or removes it (the most recent remaining becomes primary). */
    public void toggleSelection(Entity entity) {
        if (entity == null) return;
        if (selection.remove(entity)) {
            setPrimary(entity == selectedEntity ? lastSelected() : selectedEntity);
        } else {
            selection.add(entity);
            setPrimary(entity);
        }
    }

    /** Adds {@code entities} to the selection; the last added becomes primary. */
    public void addToSelection(Collection<Entity> entities) {
        if (entities == null || entities.isEmpty()) return;
        Entity last = null;
        for (Entity e : entities) {
            if (e != null && sceneEntities.contains(e)) {
                selection.remove(e); // Re-insert so selection order tracks the latest pick
                selection.add(e);
                last = e;
            }
        }
        if (last != null) setPrimary(last);
    }

    public boolean isSelected(Entity entity) {
        return entity != null && selection.contains(entity);
    }

    public int getSelectionCount() {
        pruneSelection();
        return selection.size();
    }

    /** Every selected entity in selection order (primary included). Read-only view, valid until the selection next changes. */
    public List<Entity> getSelectedEntities() {
        pruneSelection();
        return selectionView;
    }

    private Entity lastSelected() {
        Entity last = null;
        for (Entity e : selection) last = e;
        return last;
    }

    /** Drops members that have left the scene (deleted through a path that bypassed {@link #deleteEntity}). */
    private void pruneSelection() {
        if (selection.removeIf(e -> e.handle == null || !isValidHandle(e.handle))) {
            setPrimary(selection.contains(selectedEntity) ? selectedEntity : lastSelected());
        }
    }

    private void setPrimary(Entity entity) {
        if (entity != null) {
            if (entity.handle == null || !isValidHandle(entity.handle)) {
                registerEntityHandle(entity);
            }
            selection.add(entity);
            this.selectedHandle = entity.getHandle();
            this.selectedEntity = entity;
        } else {
            this.selectedHandle = EntityHandle.NULL;
            this.selectedEntity = null;
        }
        selectionSnapshot.clear();
        selectionSnapshot.addAll(selection);
        for (Entity e : sceneEntities) {
            e.selected = selection.contains(e);
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
        simulationEngine.pause(sceneEntities);
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
        pendingDestroys.clear();
        // Undo framework frame state (camera shake offsets, debug lines, event listeners) before the snapshot restore
        if (leavingPlayMode) ScriptRuntime.onPlayStopped(sceneEntities);
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
        getNavigation().loadForScene(resolveScenePath(originScenePath));
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

        // Re-match the whole selection by id onto the rebound entity instances
        Entity primary = (selectedEntity != null && selectedEntity.id != null) ? findEntityById(selectedEntity.id) : null;
        List<Entity> matched = new ArrayList<>();
        for (Entity old : selection) {
            Entity m = (old.id != null) ? findEntityById(old.id) : null;
            if (m != null) matched.add(m);
        }
        setSelection(matched, primary);
    }

    public void update(float deltaTime) {
        graphEvaluator.invalidateEntityIndex(); // Once per frame: entities may have been added, removed or re-id'd
        boolean simulating = simulationEngine.getPlayState() == PlayState.SIMULATING;
        // Scripts and physics see the un-shaken camera pose; this frame's shake is re-applied after they ran
        if (simulating) ScriptRuntime.beginFrame(deltaTime);
        simulationEngine.update(deltaTime, sceneEntities, this);
        syncAnimationPlayState();
        if (simulating) {
            flushPendingDestroys();
            ScriptRuntime.endFrame(sceneEntities);
        }
        
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
        // Abort before tearing anything down: a bad path or file must not swap the running scene for an empty one
        if (resolveScenePath(path) == null) {
            System.err.println("[SceneWorld] Scene transition aborted: '" + path + "' could not be resolved through the project VFS.");
            return;
        }
        SceneSerializer.SceneLoadData data = readScene(path, lastAssetDatabase);
        if (data.failed()) return;

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
        stop();
        bindLoadedScene(path, data);
        if (wasRunning) play();
    }

    /**
     * Creates an entity in the live scene (Transform, plus MeshFilter + Material when {@code meshAssetPath} is set; the
     * path resolves through the mod VFS) without changing the selection. Fires {@code EntitySpawnEvent} (source
     * SCRIPT); returns null when a mod canceled it. A null or duplicate id gets a generated one.
     */
    public Entity spawnEntity(String id, String name, String meshAssetPath) {
        String entityId = (id == null || id.isBlank() || findEntityById(id) != null)
                ? "entity_" + java.util.UUID.randomUUID().toString().substring(0, 8) : id.trim();
        String mesh = (meshAssetPath != null && lastAssetDatabase != null) ? lastAssetDatabase.resolveAsset(meshAssetPath)
                : ModLoader.resolveAssetPath(meshAssetPath);
        Entity entity = new Entity(entityId, (name != null && !name.isBlank()) ? name : entityId, mesh);
        List<Entity> single = new ArrayList<>(1);
        single.add(entity);
        ModLoader.onScriptSpawned(single);
        if (single.isEmpty()) return null;
        sceneEntities.add(entity);
        registerEntityHandle(entity);
        graphEvaluator.clearCache();
        return entity;
    }

    /**
     * Destroys {@code target} and all of its descendants at the end of the current frame (after physics and every
     * script ran), so it is safe from any script callback. Their scripts get onDestroy and their physics bodies leave
     * the simulation. Stop restores them like any other play-mode change. Outside play the entity is deleted at once.
     */
    public void requestEntityDestroy(Entity target) {
        if (target == null) return;
        if (simulationEngine.getPlayState() == PlayState.EDITING) {
            deleteEntity(target);
            return;
        }
        if (!pendingDestroys.contains(target)) pendingDestroys.add(target);
    }

    /** True when {@code target} was queued with {@link #requestEntityDestroy(Entity)} and not yet removed. */
    public boolean isPendingDestroy(Entity target) {
        return target != null && pendingDestroys.contains(target);
    }

    private void flushPendingDestroys() {
        if (pendingDestroys.isEmpty()) return;
        destroyScratch.clear();
        for (int i = 0; i < pendingDestroys.size(); i++) {
            Entity e = pendingDestroys.get(i);
            if (e != null && sceneEntities.contains(e) && !destroyScratch.contains(e)) destroyScratch.add(e);
        }
        pendingDestroys.clear();
        // Breadth-first: append every descendant so a destroyed parent does not leave orphaned children behind
        for (int i = 0; i < destroyScratch.size(); i++) {
            String parentId = destroyScratch.get(i).id;
            if (parentId == null) continue;
            for (int j = 0; j < sceneEntities.size(); j++) {
                Entity child = sceneEntities.get(j);
                if (child != null && parentId.equalsIgnoreCase(child.parentId) && !destroyScratch.contains(child)) {
                    destroyScratch.add(child);
                }
            }
        }
        PhysicsSystem physics = getPhysicsWorld();
        for (int i = 0; i < destroyScratch.size(); i++) {
            Entity e = destroyScratch.get(i);
            ScriptEngine.destroyEntityScripts(e);
            if (physics != null) physics.removeRuntimeEntity(e);
        }
        for (int i = destroyScratch.size() - 1; i >= 0; i--) {
            deleteEntity(destroyScratch.get(i));
        }
        destroyScratch.clear();
    }

    /**
     * Instantiates a prefab during play: resolves the path through the project VFS, adds the entities without changing
     * the editor selection or playing the editor spawn sound, and registers their physics bodies (simulated from the
     * next physics step). Scripts on the spawned entities start on the next script tick. Returns the root entity, or
     * null when the prefab could not be loaded. Reads the prefab from disk, so avoid calling it every frame.
     */
    public Entity instantiatePrefabRuntime(String prefabPath) {
        if (prefabPath == null || prefabPath.isBlank()) return null;
        String resolved = (lastAssetDatabase != null) ? lastAssetDatabase.resolveAsset(prefabPath) : ModLoader.resolveAssetPath(prefabPath);
        if (resolved == null || !new File(resolved).exists()) resolved = prefabPath;

        Entity previousPrimary = selectedEntity;
        List<Entity> previousSelection = new ArrayList<>(selection);
        List<Entity> spawned = PrefabSerializer.instantiatePrefab(resolved, this, lastAssetDatabase);
        ModLoader.onPrefabSpawned(resolved, spawned, this::deleteEntity);
        setSelection(previousSelection, previousPrimary);
        if (spawned == null || spawned.isEmpty()) {
            System.err.println("[SceneWorld] Prefab '" + prefabPath + "' could not be instantiated (missing or unreadable).");
            return null;
        }
        if (simulationEngine.getPlayState() != PlayState.EDITING) {
            PhysicsSystem physics = getPhysicsWorld();
            for (int i = 0; i < spawned.size(); i++) {
                Entity e = spawned.get(i);
                if (e != null && sceneEntities.contains(e)) physics.registerRuntimeEntity(e);
            }
        }
        for (int i = 0; i < spawned.size(); i++) {
            Entity e = spawned.get(i);
            if (e != null && sceneEntities.contains(e)) return e;
        }
        return null;
    }

    /** First entity with exactly this name, or null. */
    public Entity findEntityByName(String name) {
        if (name == null) return null;
        for (Entity e : sceneEntities) {
            if (name.equals(e.name)) return e;
        }
        return null;
    }

    /** Physics raycast that ignores {@code exclude} (typically the caller's own entity). Allocates one RaycastHit. */
    public com.retro.engine.physics.RaycastHit raycastExcluding(Entity exclude, float originX, float originY, float originZ,
                                                               float dirX, float dirY, float dirZ, float maxDistance) {
        return getPhysicsWorld().raycastExcluding(exclude, originX, originY, originZ, dirX, dirY, dirZ, maxDistance);
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
            if (selection.remove(target) || selectedEntity == target) {
                setPrimary(selectedEntity == target ? lastSelected() : selectedEntity);
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

    /**
     * Moves an existing entity to {@code index} in the scene list (clamped). List order is sibling order in the
     * Hierarchy and is what .rscene saves persist. Returns false when the entity is not in the scene.
     */
    public boolean moveEntity(Entity target, int index) {
        int from = sceneEntities.indexOf(target);
        if (from < 0) return false;
        sceneEntities.remove(from);
        sceneEntities.add(Math.max(0, Math.min(sceneEntities.size(), index)), target);
        graphEvaluator.clearCache();
        return true;
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
    /** Writes {@code entity} (and its children) as a prefab blueprint and registers it in the asset browser. */
    public void saveEntityAsPrefab(Entity entity, String targetPath, AssetDatabase assetDb) {
        if (entity == null || targetPath == null || targetPath.isBlank()) return;
        if (!PrefabSerializer.savePrefab(targetPath, entity, sceneEntities, assetDb)) {
            reportDataError("Could not save the prefab " + new File(targetPath).getName() + ". The file on disk has not changed. See the log for details.");
        }
        if (assetDb != null) {
            assetDb.addAssetDirectoryIfNotExists(targetPath, AssetType.PREFAB);
        }
    }

    public List<Entity> instantiatePrefab(String filePath, AssetDatabase assetDb) {
        List<Entity> spawned = PrefabSerializer.instantiatePrefab(filePath, this, assetDb);
        ModLoader.onPrefabSpawned(filePath, spawned, this::deleteEntity);
        if (spawned != null && !spawned.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
        }
        return spawned;
    }

    /** Dialog start directory inside the active project's Assets/ folder, falling back to the working directory. */
    public static String projectSubDir(AssetDatabase assetDb, String sub) {
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
        getNavigation().setMesh(null);
    }

    public void saveActiveScene(String filePath, List<AssetMeta> assetDirectory) {
        saveActiveScene(filePath, assetDirectory, null);
    }

    public void saveActiveScene(String filePath, List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        if (com.retro.engine.modding.ModWorkspace.isActive()) {
            // Creation Kit mode: vanilla scenes save as .rpatch deltas; scenes inside the mod folder save normally
            if (com.retro.engine.modding.ModWorkspace.saveScene(this, SceneSerializer.sanitizeScenePath(filePath), assetDb)) return;
        } else if (ModLoader.isActive()) {
            // The loaded scene contains .rpatch deltas and mod event edits; saving would bake them into vanilla files
            System.err.println("[SceneWorld] Scene saving is disabled while mods are loaded (editor started with --mods). "
                    + "Use File > Open Mod Workspace... to author scene changes as a mod.");
            return;
        }
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        File target = new File(sanitized).getAbsoluteFile();
        if (target.equals(unreadableSceneFile)) {
            reportDataError("Not saved: " + target.getName() + " could not be loaded earlier, and saving now would replace it. "
                    + "Save under a new name, or restore the file from its .bak backups.");
            return;
        }
        try {
            SceneBackups.backUpBeforeSave(target);
        } catch (java.io.IOException e) {
            System.err.println("[SceneWorld] Could not back up " + target + " before saving: " + e.getMessage());
        }
        if (!SceneSerializer.saveScene(sanitized, sceneEntities, assetDirectory, assetDb)) {
            reportDataError("Could not save " + target.getName() + ". The file on disk has not changed. See the log for details.");
            return;
        }
        this.activeScenePath = sanitized;
    }

    /**
     * On-disk file for a scene path ({@code Assets/...}, Assets-relative, {@code mod://} or absolute; the .rscene
     * extension is optional) through the project/mod VFS, or null when no such file exists.
     */
    public String resolveScenePath(String scenePath) {
        if (scenePath == null || scenePath.isBlank()) return null;
        String sanitized = SceneSerializer.sanitizeScenePath(scenePath);
        String resolved = (lastAssetDatabase != null) ? lastAssetDatabase.resolveAsset(sanitized) : ModLoader.resolveAssetPath(sanitized);
        if (resolved != null && new java.io.File(resolved).isFile()) return new java.io.File(resolved).getAbsolutePath();
        return null;
    }

    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath) {
        return loadSceneFromFile(filePath, null);
    }

    /**
     * Loads a scene over the current one. If the file can't be read, the current scene stays open and untouched, the
     * returned data reports the failure, and that file is protected from being saved over.
     */
    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath, AssetDatabase assetDb) {
        if (assetDb != null) this.lastAssetDatabase = assetDb;
        SceneSerializer.SceneLoadData data = readScene(filePath, assetDb);
        if (data.failed()) return data;
        stop();
        bindLoadedScene(filePath, data);
        return data;
    }

    /** Reads a scene file without touching the world, reporting any failure. */
    private SceneSerializer.SceneLoadData readScene(String filePath, AssetDatabase assetDb) {
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        // Read from the VFS-resolved file, never relative to user.dir (the packaged editor's install folder)
        String diskPath = resolveScenePath(sanitized);
        if (diskPath == null) {
            System.err.println("[SceneWorld] Scene '" + sanitized + "' could not be resolved through the project VFS; loading an empty scene.");
            diskPath = sanitized;
        }
        SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(diskPath, assetDb);
        File file = new File(diskPath).getAbsoluteFile();
        if (data.failed()) {
            unreadableSceneFile = file;
            reportDataError("Could not load " + file.getName() + ". " + data.error() + " The current scene was left open, and "
                    + "the file is protected from being saved over. Autosave backups (.bak) are next to it.");
        } else if (file.equals(unreadableSceneFile)) {
            unreadableSceneFile = null;
        }
        return data;
    }

    private void bindLoadedScene(String filePath, SceneSerializer.SceneLoadData data) {
        String sanitized = SceneSerializer.sanitizeScenePath(filePath);
        this.activeScenePath = sanitized;
        List<Entity> loadedEntities = data.entities();
        // Mods: .rpatch deltas, SceneLoadEvent and EntitySpawnEvent run on the fresh list before it is bound
        ModLoader.onSceneDeserialized(sanitized, loadedEntities);
        clearRegistryAndRebind(loadedEntities);
        getNavigation().loadForScene(resolveScenePath(sanitized));
        attachAndArmAnimators();
        // A freshly loaded scene starts with nothing selected (rebind may have matched a stale selection by id)
        selectEntity((Entity) null);
    }

    /** First entity with a primary Camera, falling back to the first entity with any Camera (null if none). */
    public static Entity findMainCamera(List<Entity> entities) {
        if (entities == null) return null;
        Entity fallback = null;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            Camera camera = entity.getComponent(Camera.class);
            if (camera == null) continue;
            if (camera.isPrimary) return entity;
            if (fallback == null) fallback = entity;
        }
        return fallback;
    }
}
