package com.retro.engine.scripting.user;

import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.Input;

/**
 * Handles level transitions between scenes in RetroEngine.
 * Can be triggered via a 3D Trigger Volume (onTriggerEnter) 
 * or manually via a hotkey (e.g. pressing 'E' or 'ENTER').
 */
public class LevelPortalScript extends EntityScript {

    // Reflected field in RetroEngine Inspector UI
    public String targetScenePath = "Assets/Scenes/test.rscene";
    public boolean triggerOnTouch = true;
    public boolean triggerOnKeyPress = false;
    
    // Keycode 69 = GLFW_KEY_E, Keycode 257 = GLFW_KEY_ENTER
    public int interactKey = 69; 

    private boolean transitionRequested = false;

    @Override
    public void onStart() {
        logInfo("LevelPortalScript initialized on '" + (entity != null ? entity.name : "null") + "'. Target: " + targetScenePath);
    }

    @Override
    public void onUpdate(float deltaTime) {
        // Option to trigger level load via interaction key (e.g., pressing 'E')
        if (triggerOnKeyPress && !transitionRequested) {
            if (Input.isKeyDown(interactKey)) {
                triggerSceneLoad();
            }
        }
    }

    @Override
    public void onTriggerEnter(Entity other) {
        // Triggered when a character/rigidbody enters a Box, Sphere, or Capsule Trigger Collider
        if (triggerOnTouch && !transitionRequested) {
            logInfo("Entity '" + (other != null ? other.name : "Unknown") + "' entered transition zone!");
            triggerSceneLoad();
        }
    }

    private void triggerSceneLoad() {
        if (transitionRequested) return;
        
        transitionRequested = true;
        logInfo("Queuing deferred scene transition to -> " + targetScenePath);
        
        // Calls RetroEngine's deferred scene loading queue (safely executes at the end of the frame tick)
        loadScene(targetScenePath);
    }
}
