package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.SceneManager;
import com.retro.engine.scripting.framework.ScriptEventListener;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Tracks score and remaining collectibles (listening for CollectibleScript's "collectible_collected" event),
 * declares victory when all are collected, then reloads the scene after restartDelay (runAfter timer) or
 * immediately on R. Falling below killPlaneY also restarts.
 */
public class GameManagerScript extends SimpleEntityScript implements ScriptEventListener {

    public String collectibleScriptName = "CollectibleScript.java";
    public String playerName = "";           // empty = the entity with a CharacterController
    public float restartDelay = 3.0f;
    public float killPlaneY = -20.0f;
    public String victorySound = "";         // optional, e.g. "Assets/Audio/victory.wav"

    private static final int KEY_R = 82;

    private int score;
    private int totalCollectibles;
    private int collectedCount;
    private boolean victory;
    private boolean reloadRequested;
    private Entity player;
    private ScriptTask restartTask;

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
        player = findPlayer(playerName); // cached: scans the scene once
        restartTask = new ScriptTask() {
            public void run() { restart(); }
        };
        subscribeEvents(this);           // auto-unsubscribed when this script is destroyed
        logInfo("Collect all " + totalCollectibles + " pickups! (R to restart)");
    }

    public void onScriptEvent(String eventName, Entity source, String text, float number) {
        if ("collectible_collected".equals(eventName)) addScore((int) number);
    }

    public void addScore(int points) {
        if (victory) return;
        score += points;
        collectedCount++;
        logInfo("Score: " + score + "  (" + collectedCount + "/" + totalCollectibles + ")");
        if (totalCollectibles > 0 && collectedCount >= totalCollectibles) {
            victory = true;
            logInfo("VICTORY! Final score: " + score + ". Restarting in " + restartDelay + "s...");
            if (victorySound != null && victorySound.length() > 0) playGlobalSound(victorySound);
            shakeCamera(0.3f, 0.5f);
            runAfter(restartDelay, restartTask);
        }
    }

    @Override
    public void onUpdate(float dt) {
        if (Input.isKeyPressed(KEY_R)) { restart(); return; }
        if (victory || player == null) return;
        if (getWorldPositionY(player) < killPlaneY) {
            logInfo("Fell out of the world. Restarting...");
            restart();
        }
    }

    private float getWorldPositionY(Entity e) {
        com.retro.engine.ecs.components.Transform t = e.getTransform();
        return t != null ? t.posY : 0.0f;
    }

    private void restart() {
        if (reloadRequested) return;
        reloadRequested = true;
        SceneManager.reloadCurrentScene();
    }

    public int getScore() { return score; }
    public boolean isVictory() { return victory; }
}
