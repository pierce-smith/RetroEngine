package com.retro.engine.scripting;

import com.retro.engine.ecs.SceneWorld;

/**
 * Static scene-flow facade for gameplay scripts. Bound to the running SceneWorld whenever a script is initialized;
 * all loads are deferred to the end of the current frame via {@link SceneWorld#requestSceneLoad(String)}.
 */
public final class SceneManager {

    private static volatile SceneWorld world;

    private SceneManager() {}

    public static void bind(SceneWorld sceneWorld) { if (sceneWorld != null) world = sceneWorld; }

    public static String getCurrentScenePath() {
        SceneWorld w = world;
        return w != null ? w.getActiveScenePath() : null;
    }

    public static void loadScene(String scenePath) {
        SceneWorld w = world;
        if (w == null) {
            System.err.println("[SceneManager] loadScene('" + scenePath + "') ignored: no active SceneWorld.");
            return;
        }
        if (w.resolveScenePath(scenePath) == null) {
            // System.err is captured by the Engine Console panel
            System.err.println("[SceneManager] loadScene('" + scenePath + "') failed: scene not found in the project VFS (Assets/ or mod overlays). The current scene stays loaded.");
            return;
        }
        w.requestSceneLoad(scenePath);
    }

    /** Reloads the active scene from disk at the end of the frame, re-entering play if it was running. */
    public static void reloadCurrentScene() {
        loadScene(getCurrentScenePath());
    }
}
