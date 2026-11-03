package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.SceneManager;

/**
 * Tracks score and remaining collectibles, declares victory when all are collected, then reloads the scene
 * after a short delay (or immediately on R). Falling out of the world also restarts.
 */
public class GameManagerScript extends EntityScript {

    public String collectibleScriptName = "CollectibleScript.java";
    public String playerName = "";           // empty = the entity with a CharacterController
    public float restartDelay = 3.0f;
    public float killPlaneY = -20.0f;

    private static final int KEY_R = 82;

    private int score;
    private int totalCollectibles;
    private int collectedCount;
    private boolean victory;
    private float victoryTimer;
    private boolean reloadRequested;

    @Override
    public void onStart() {
        totalCollectibles = 0;
        if (sceneWorld != null) {
            java.util.List entities = sceneWorld.getSceneEntities(); // raw: Janino erases generics
            for (int i = 0; i < entities.size(); i++) {
                Entity e = (Entity) entities.get(i);
                ScriptComponent sc = (ScriptComponent) e.getComponent(ScriptComponent.class);
                if (sc != null && collectibleScriptName.equals(sc.scriptName)) totalCollectibles++;
            }
        }
        logInfo("Collect all " + totalCollectibles + " pickups! (R to restart)");
    }

    /** Called by CollectibleScript on pickup. */
    public void addScore(int points) {
        if (victory) return;
        score += points;
        collectedCount++;
        logInfo("Score: " + score + "  (" + collectedCount + "/" + totalCollectibles + ")");
        if (totalCollectibles > 0 && collectedCount >= totalCollectibles) {
            victory = true;
            victoryTimer = restartDelay;
            logInfo("VICTORY! Final score: " + score + ". Restarting in " + restartDelay + "s...");
        }
    }

    @Override
    public void onUpdate(float dt) {
        if (Input.isKeyPressed(KEY_R)) { restart(); return; }
        if (victory) {
            victoryTimer -= dt;
            if (victoryTimer <= 0.0f) restart();
            return;
        }
        Entity player = findPlayer();
        com.retro.engine.ecs.components.Transform t = player != null ? (com.retro.engine.ecs.components.Transform) player.getComponent(com.retro.engine.ecs.components.Transform.class) : null;
        if (t != null && t.posY < killPlaneY) {
            logInfo("Fell out of the world. Restarting...");
            restart();
        }
    }

    private Entity findPlayer() {
        if (sceneWorld == null) return null;
        if (playerName != null && !playerName.isEmpty()) return sceneWorld.findEntityByName(playerName);
        java.util.List entities = sceneWorld.getSceneEntities();
        for (int i = 0; i < entities.size(); i++) {
            Entity e = (Entity) entities.get(i);
            if (e.getComponent(com.retro.engine.ecs.components.CharacterControllerComponent.class) != null) return e;
        }
        return null;
    }

    private void restart() {
        if (reloadRequested) return;
        reloadRequested = true;
        SceneManager.reloadCurrentScene();
    }

    public int getScore() { return score; }
    public boolean isVictory() { return victory; }
}
