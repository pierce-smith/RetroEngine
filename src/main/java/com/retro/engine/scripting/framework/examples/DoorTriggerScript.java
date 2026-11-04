package com.retro.engine.scripting.framework.examples;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.framework.ScriptEventListener;
import com.retro.engine.scripting.framework.ScriptInput;
import com.retro.engine.scripting.framework.ScriptSaveData;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.ScriptTaskQueue;
import com.retro.engine.scripting.framework.ScriptTweener;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Interactable swinging door with an auto-close timer.
 *
 * <p>Setup: put this script and a trigger collider (BoxCollider with IS TRIGGER) covering the doorway on the door
 * entity. Optionally name a child entity in {@code doorPanelName} to swing only that panel (keeps the trigger still).
 * The door swings away from the player, opens on approach (or on the Interact action when
 * {@code requireInteract}), and closes {@code autoCloseDelay} seconds after the player leaves.</p>
 *
 * <p>Locking: set {@code requiredItemId} (e.g. "key_red") and it opens only once the player holds that item, as
 * recorded in save data by {@link PickupItemScript}. Other scripts can command it through events:
 * {@code ScriptEvents.publish("door_command", source, "<door entity name>", 1)} opens, 0 closes.
 * It publishes "door_opened", "door_closed" and "door_locked" with its entity name as the text.</p>
 *
 * <p>Janino-compatible: no lambdas, the timer task is created once in onStart, no generic collections.</p>
 */
public class DoorTriggerScript extends SimpleEntityScript implements ScriptEventListener {

    public float openAngle = 95.0f;
    public float openDuration = 0.6f;
    public float closeDuration = 0.9f;
    public float autoCloseDelay = 3.0f;      // <= 0 keeps the door open until commanded
    public boolean requireInteract = false;
    public String interactAction = "Interact";
    public String doorPanelName = "";        // Child entity to swing; empty = this entity
    public String requiredItemId = "";       // Empty = unlocked
    public String playerName = "Player";
    public String openSound = "";
    public String closeSound = "";
    public String lockedSound = "";

    Transform panel;
    float closedRotX, closedRotY, closedRotZ;
    boolean isOpen = false;
    boolean playerInside = false;
    int closeTimer = ScriptTaskQueue.INVALID_HANDLE;
    String requiredItemKey = null;
    ScriptTask autoCloseTask;

    @Override
    public void onStart() {
        panel = resolvePanel();
        if (panel != null) {
            closedRotX = panel.rotX;
            closedRotY = panel.rotY;
            closedRotZ = panel.rotZ;
        }
        if (requiredItemId != null && requiredItemId.length() > 0) {
            // Same key format PickupItemScript writes; built once here, not on every trigger.
            // (Spelled out rather than calling PickupItemScript: Janino compiles each script on its own.)
            requiredItemKey = "inventory." + requiredItemId;
        }
        autoCloseTask = new ScriptTask() {
            public void run() {
                closeTimer = ScriptTaskQueue.INVALID_HANDLE;
                if (!playerInside) close();
            }
        };
        subscribeEvents(this);
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (requireInteract && playerInside && ScriptInput.isActionPressed(interactAction)) {
            if (isOpen) close();
            else open();
        }
    }

    @Override
    public void onTriggerEnter(Entity other) {
        if (!isPlayer(other, playerName)) return;
        playerInside = true;
        cancelAutoClose();
        if (!requireInteract) open();
    }

    @Override
    public void onTriggerExit(Entity other) {
        if (!isPlayer(other, playerName)) return;
        playerInside = false;
        if (isOpen && autoCloseDelay > 0.0f) {
            cancelAutoClose();
            closeTimer = runAfter(autoCloseDelay, autoCloseTask);
        }
    }

    public void onScriptEvent(String eventName, Entity source, String text, float number) {
        if (!"door_command".equals(eventName) || entity == null || text == null || !text.equals(entity.name)) return;
        if (number > 0.5f) open();
        else close();
    }

    /** Opens the door (if unlocked). Safe to call when already open. */
    public void open() {
        if (isOpen || panel == null) return;
        if (isLocked()) {
            if (lockedSound != null && lockedSound.length() > 0) playSpatialSound(lockedSound, 1.0f);
            publishEvent("door_locked", entity.name, 0.0f);
            return;
        }
        isOpen = true;
        float swing = openAngle * swingSign();
        tweens.tweenRotation(panel, closedRotX, closedRotY + swing, closedRotZ, openDuration, ScriptTweener.EASE_OUT_CUBIC);
        if (openSound != null && openSound.length() > 0) playSpatialSound(openSound, 1.0f);
        publishEvent("door_opened", entity.name, 1.0f);
    }

    /** Closes the door. Safe to call when already closed. */
    public void close() {
        if (!isOpen || panel == null) return;
        isOpen = false;
        cancelAutoClose();
        tweens.tweenRotation(panel, closedRotX, closedRotY, closedRotZ, closeDuration, ScriptTweener.EASE_IN_OUT_QUAD);
        if (closeSound != null && closeSound.length() > 0) playSpatialSound(closeSound, 1.0f);
        publishEvent("door_closed", entity.name, 0.0f);
    }

    public boolean isOpen() {
        return isOpen;
    }

    boolean isLocked() {
        return requiredItemKey != null && ScriptSaveData.getInt(requiredItemKey, 0) <= 0;
    }

    void cancelAutoClose() {
        if (closeTimer != ScriptTaskQueue.INVALID_HANDLE) {
            cancelTask(closeTimer);
            closeTimer = ScriptTaskQueue.INVALID_HANDLE;
        }
    }

    /** +1 or -1: swing away from the player, judged by which side of the door's forward axis they stand on. */
    float swingSign() {
        Entity player = findPlayer(playerName);
        if (player == null || entity == null) return 1.0f;
        float yaw = (float) Math.toRadians(closedRotY);
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw); // Door's closed forward (+Z at yaw 0)
        Transform pt = player.getTransform();
        if (pt == null) return 1.0f;
        float side = (pt.posX - getWorldX()) * fx + (pt.posZ - getWorldZ()) * fz;
        return side > 0.0f ? -1.0f : 1.0f;
    }

    Transform resolvePanel() {
        if (doorPanelName == null || doorPanelName.length() == 0 || sceneWorld == null) return transform();
        java.util.List all = sceneWorld.getSceneEntities(); // Raw list: Janino erases generics
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            if (e != null && doorPanelName.equals(e.name) && entity.id != null && entity.id.equalsIgnoreCase(e.parentId)) {
                return e.getTransform();
            }
        }
        logWarning("Door panel child '" + doorPanelName + "' not found; swinging the door entity itself.");
        return transform();
    }
}
