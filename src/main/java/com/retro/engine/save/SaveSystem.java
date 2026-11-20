package com.retro.engine.save;

import com.retro.engine.api.Internal;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.ScriptEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs save games for one scene world: keeps the baseline of the running level, the remembered state of levels the
 * player left, the slot's values and play time, and turns {@link SaveGame} calls into files and scene loads.
 *
 * <p>The scene world calls in at four points: {@link #onLevelStarting()} when a level starts running (after the play
 * snapshot, before physics and scripts), {@link #onLeavingLevel(String)} just before a scene transition tears the level
 * down, {@link #endOfFrame(float)} after every frame's update, and {@link #onPlayStopped()} when the editor stops Play.
 */
@Internal
public final class SaveSystem {

    private static volatile SaveSystem active;

    private final SaveWorld world;
    private final SceneBaseline baseline = new SceneBaseline();
    private final PendingScripts pendingScripts = new PendingScripts();
    private final Map<String, SceneState> levels = new LinkedHashMap<>();
    private final SaveRecord values = new SaveRecord();
    private final List<String[]> queuedSaves = new ArrayList<>();
    private boolean levelRunning;
    private boolean restoring;
    private double playTime;
    private SaveData pendingLoad;

    public SaveSystem(SaveWorld world) {
        this.world = world;
    }

    /** The save system of the scene world that is running, or null outside play. */
    public static SaveSystem active() { return active; }

    // =========================================================================
    // Scene world hooks
    // =========================================================================

    public void onLevelStarting() {
        active = this;
        pendingScripts.clear();
        baseline.captureScene(world.entities());
        levelRunning = true;
        SceneState saved = levels.get(levelKey(world.activeScenePath()));
        if (saved == null) return;
        restoring = true;
        try {
            SceneRestore.apply(world, saved, baseline, pendingScripts);
        } finally {
            restoring = false;
        }
    }

    /** {@code nextScenePath}: the scene about to load. A pending {@link SaveGame#load} replaces everything instead. */
    public void onLeavingLevel(String nextScenePath) {
        levelRunning = false;
        if (pendingLoad != null) {
            install(pendingLoad);
            pendingLoad = null;
            return;
        }
        String current = levelKey(world.activeScenePath());
        // The scene's Remember When Left setting decides; loading the same scene again is always a restart
        if (world.remembersActiveLevel() && !current.equals(levelKey(nextScenePath))) {
            levels.put(current, SceneDiff.capture(world.entities(), baseline));
        } else {
            levels.remove(current);
        }
    }

    /** A queued {@link SaveGame#load} whose scene could not be loaded is dropped, and the current level keeps running. */
    public void onSceneLoadAborted() {
        if (pendingLoad != null) {
            System.err.println("[SaveGame] Loading the save was canceled because its level could not be loaded.");
            pendingLoad = null;
        }
    }

    public void endOfFrame(float deltaTime, boolean simulating) {
        if (simulating && deltaTime > 0) playTime += deltaTime;
        if (queuedSaves.isEmpty()) return;
        List<String[]> saves = new ArrayList<>(queuedSaves);
        queuedSaves.clear();
        for (String[] s : saves) save(s[0], s[1]);
    }

    /** The editor left Play mode: everything from this session is forgotten (save files on disk stay). */
    public void onPlayStopped() {
        baseline.clear();
        pendingScripts.clear();
        levels.clear();
        values.clear();
        queuedSaves.clear();
        pendingLoad = null;
        levelRunning = false;
        playTime = 0;
        if (active == this) active = null;
    }

    /** {@code SceneWorld.instantiatePrefabRuntime} created {@code created} (root first) during play. */
    public void onPrefabSpawned(String prefabPath, List<Entity> created) {
        if (!levelRunning || restoring || created == null || created.isEmpty()) return;
        SceneState.Spawn info = new SceneState.Spawn();
        info.prefabPath = prefabPath;
        baseline.recordSpawn(info, created);
    }

    /** {@code SceneWorld.spawnEntity} created {@code e} during play. */
    public void onEntitySpawned(Entity e, String name, String meshPath) {
        if (!levelRunning || restoring || e == null) return;
        SceneState.Spawn info = new SceneState.Spawn();
        info.name = name;
        info.meshPath = meshPath;
        baseline.recordSpawn(info, List.of(e));
    }

    /** Called by the script engine right after a script's {@code onStart}. */
    public static void onScriptStarted(Entity entity, ScriptComponent sc, EntityScript script) {
        SaveSystem s = active;
        if (s != null) s.pendingScripts.onScriptStarted(entity, sc, script);
    }

    // =========================================================================
    // SaveGame operations
    // =========================================================================

    boolean save(String slot, String displayName) {
        if (!levelRunning || !world.isPlaying()) {
            System.err.println("[SaveGame] save('" + slot + "') ignored: no level is running.");
            return false;
        }
        SaveData data = new SaveData();
        data.scenePath = levelKey(world.activeScenePath());
        data.displayName = displayName != null ? displayName : "";
        data.savedAtMillis = System.currentTimeMillis();
        data.playTimeSeconds = playTime;
        data.values.copyFrom(values);
        // Remembered levels, plus the level the player is in as it is right now (whatever its Remember When Left setting)
        for (Map.Entry<String, SceneState> e : levels.entrySet()) {
            if (!e.getKey().equals(data.scenePath)) data.levels.put(e.getKey(), e.getValue());
        }
        data.levels.put(data.scenePath, SceneDiff.capture(world.entities(), baseline));
        try {
            SaveSlots.write(slot, data);
            System.out.println("[SaveGame] Saved slot '" + slot + "' to " + SaveSlots.fileFor(slot));
            return true;
        } catch (IOException | RuntimeException e) {
            ScriptEngine.reportFailure("[SaveGame] Saving slot '" + slot + "' failed: " + e.getMessage());
            return false;
        }
    }

    void requestSave(String slot, String displayName) {
        queuedSaves.add(new String[]{slot, displayName});
    }

    boolean load(String slot) {
        if (!world.isPlaying()) {
            System.err.println("[SaveGame] load('" + slot + "') ignored: the game is not running.");
            return false;
        }
        SaveData data;
        try {
            data = SaveSlots.read(slot);
        } catch (IOException | RuntimeException e) {
            ScriptEngine.reportFailure("[SaveGame] Could not load slot '" + slot + "': " + e.getMessage() + " The current level keeps running.");
            return false;
        }
        return switchTo(data);
    }

    boolean newGame(String scenePath) {
        if (!world.isPlaying()) {
            System.err.println("[SaveGame] newGame ignored: the game is not running.");
            return false;
        }
        SaveData fresh = new SaveData();
        fresh.scenePath = levelKey(scenePath);
        return switchTo(fresh);
    }

    private boolean switchTo(SaveData data) {
        String loadPath = loadablePath(data.scenePath);
        if (loadPath == null) {
            ScriptEngine.reportFailure("[SaveGame] The level '" + data.scenePath + "' is not in this game. The current level keeps running.");
            return false;
        }
        pendingLoad = data;
        world.requestSceneLoad(loadPath);
        return true;
    }

    /** The Assets/-relative path through the project VFS, else the same file under the project's Assets folder. */
    private String loadablePath(String levelKey) {
        if (levelKey == null || levelKey.isEmpty()) return null;
        if (world.sceneExists(levelKey)) return levelKey;
        String assets = ScriptEngine.getProjectAssetsRoot();
        if (assets == null) return null;
        String rel = levelKey.replace('\\', '/');
        if (rel.regionMatches(true, 0, "Assets/", 0, "Assets/".length())) rel = rel.substring("Assets/".length());
        File file = new File(assets, rel);
        return file.isFile() && world.sceneExists(file.getAbsolutePath()) ? file.getAbsolutePath() : null;
    }

    private void install(SaveData data) {
        levels.clear();
        levels.putAll(data.levels);
        values.copyFrom(data.values);
        playTime = data.playTimeSeconds;
    }

    SaveRecord values() { return values; }

    double playTime() { return playTime; }

    void forgetLevel(String scenePath) { levels.remove(levelKey(scenePath)); }

    void forgetAllLevels() { levels.clear(); }

    /** Scenes are remembered and saved under their path inside Assets/, so a save works wherever the game is installed. */
    static String levelKey(String scenePath) {
        if (scenePath == null) return "";
        return SceneSerializer.sanitizeAssetPath(SceneSerializer.sanitizeScenePath(scenePath));
    }
}
