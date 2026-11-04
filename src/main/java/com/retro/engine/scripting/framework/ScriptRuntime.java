package com.retro.engine.scripting.framework;

import com.retro.engine.ecs.Entity;

import java.util.List;

/**
 * Engine-side frame hooks for the scripting framework's global services, called by SceneWorld while the simulation
 * runs. Gameplay scripts never need to call these.
 *
 * <p>Frame order: {@link #beginFrame} (camera shake removed, debug lines aged) -> physics + scripts ->
 * destroyed entities removed -> {@link #endFrame} (camera shake applied) -> render.</p>
 */
public final class ScriptRuntime {

    private ScriptRuntime() {}

    public static void beginFrame(float deltaTime) {
        ScriptCameraShake.beginFrame(deltaTime);
        ScriptDebug.beginFrame(deltaTime);
    }

    public static void endFrame(List<Entity> sceneEntities) {
        ScriptCameraShake.endFrame(sceneEntities);
    }

    /** Play stopped (or the scene is being swapped): undo frame state and persist save data. */
    public static void onPlayStopped(List<Entity> sceneEntities) {
        ScriptCameraShake.reset();
        ScriptDebug.clear();
        ScriptEvents.clear();
        ScriptSaveData.save();
    }
}
