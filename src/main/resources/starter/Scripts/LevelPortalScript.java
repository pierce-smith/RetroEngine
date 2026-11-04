package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.ScriptTweener;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Level transition zone. Triggered when the player enters this entity's trigger collider (Box/Sphere/Capsule with
 * IS TRIGGER), or by pressing interactKey while standing inside it. Plays an optional sound, shakes the camera,
 * shrinks the portal, and loads the target scene after transitionDelay seconds (runAfter timer).
 */
public class LevelPortalScript extends SimpleEntityScript {

    public String targetScenePath = "Assets/Scenes/Main.rscene";
    public boolean triggerOnTouch = true;
    public boolean triggerOnKeyPress = false;
    public int interactKey = 69;               // GLFW_KEY_E
    public String playerName = "";             // empty = any entity with a CharacterController
    public float transitionDelay = 0.6f;
    public String portalSound = "";            // optional, e.g. "Assets/Audio/portal.wav"

    private boolean playerInside = false;
    private boolean transitionRequested = false;
    private ScriptTask loadTask;

    @Override
    public void onStart() {
        loadTask = new ScriptTask() {
            public void run() { loadScene(targetScenePath); }
        };
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (triggerOnKeyPress && playerInside && !transitionRequested && Input.isKeyPressed(interactKey)) {
            beginTransition();
        }
    }

    @Override
    public void onTriggerEnter(Entity other) {
        if (!isPlayer(other, playerName)) return;
        playerInside = true;
        if (triggerOnTouch) beginTransition();
    }

    @Override
    public void onTriggerExit(Entity other) {
        if (isPlayer(other, playerName)) playerInside = false;
    }

    private void beginTransition() {
        if (transitionRequested) return;
        transitionRequested = true;
        logInfo("Portal -> " + targetScenePath);
        if (portalSound != null && portalSound.length() > 0) playSpatialSound(portalSound, 1.0f);
        shakeCamera(0.35f, transitionDelay);
        tweenScaleTo(0.0f, 0.0f, 0.0f, transitionDelay, ScriptTweener.EASE_IN_CUBIC);
        // Deferred scene load (executes safely at the end of the frame)
        runAfter(Math.max(0.0f, transitionDelay), loadTask);
    }
}
