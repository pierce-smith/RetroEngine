package com.retro.engine.editor;

import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.ecs.serialization.SceneSerializer;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crash-safety net: every 5 minutes a daemon timer flags an autosave, and the next editor tick writes the dirty scene
 * to {@code [SceneName].rscene.bak} beside the scene file. The timer only sets a flag; serialization itself runs on the
 * main thread in {@link #tick} so it never races the ECS while entities are being mutated. Edit state is "dirty" once a
 * command executes / undoes / redoes after the last backup.
 */
public final class AutoSaveSystem implements CommandStack.CommandListener {

    public static final long INTERVAL_MINUTES = 5;

    private final EditorWorkspace workspace;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean saveDue = new AtomicBoolean(false);
    private ScheduledExecutorService timer;

    public AutoSaveSystem(EditorWorkspace workspace) {
        this.workspace = workspace;
        workspace.getCommandHistory().addListener(this);
    }

    public synchronized void start() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RetroEngine-AutoSave");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> saveDue.set(true), INTERVAL_MINUTES, INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public synchronized void shutdown() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    public void markDirty() { dirty.set(true); }
    public boolean isDirty() { return dirty.get(); }

    /** Main-thread hook (called from EditorWorkspace.update): performs a pending autosave when it is safe to. */
    public void tick() {
        if (!saveDue.get()) return;
        // Play mode holds simulated (not authored) state, and mod sessions must not bake deltas; retry next tick window
        if (workspace.getPlayState() != PlayState.EDITING) return;
        saveDue.set(false);
        if (!workspace.hasActiveProject() || com.retro.engine.modding.ModLoader.isActive()) return;
        if (!dirty.getAndSet(false)) return;

        String target = backupPathFor(workspace.getActiveScenePath());
        boolean ok = SceneSerializer.saveBinaryScene(target, workspace.getSceneManager().getSceneEntities(),
                workspace.getAssetDirectory(), workspace.getAssetDatabase());
        if (ok) {
            System.out.println("[AutoSave] Scene backed up to " + target);
        } else {
            dirty.set(true);
            System.err.println("[AutoSave] Failed to write " + target);
        }
    }

    /** {@code Foo.rscene} -> {@code Foo.rscene.bak}; relative (untitled) scenes go to the project's Scenes folder. */
    private String backupPathFor(String scenePath) {
        String path = (scenePath == null || scenePath.isBlank()) ? "Untitled.rscene" : scenePath;
        File file = new File(path);
        String name = file.getName();
        if (!name.toLowerCase().endsWith(".rscene")) name += ".rscene";
        File dir = file.isAbsolute() ? file.getParentFile() : new File(workspace.getProjectScenesDir());
        return new File(dir, name + ".bak").getAbsolutePath();
    }

    // --- CommandStack.CommandListener ---
    @Override public void onCommandExecuted(ICommand command) { markDirty(); }
    @Override public void onCommandUndone(ICommand command) { markDirty(); }
    @Override public void onCommandRedone(ICommand command) { markDirty(); }
    @Override public void onStackCleared() { dirty.set(false); } // scene load / project switch: nothing new to back up
}
