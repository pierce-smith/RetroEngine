package com.retro.engine.scripting.framework.examples;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.framework.ScriptDebug;
import com.retro.engine.scripting.framework.ScriptSaveData;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.ScriptTaskQueue;
import com.retro.engine.scripting.framework.ScriptTweener;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

/**
 * Collectible item that spins and bobs in place, is pulled toward the player once they come within
 * {@code magnetRadius}, and is collected on contact.
 *
 * <p>On collection it adds {@code amount} to the save-data counter {@code "inventory.<itemId>"} (read it with
 * {@code ScriptSaveData.getInt("inventory.key_red", 0)}; {@link DoorTriggerScript} uses this for locked doors),
 * publishes an {@code "item_picked"} event (text = itemId, number = amount) for HUDs / quest scripts, plays its
 * sound, shrinks away and destroys itself.</p>
 *
 * <p>A trigger collider is optional: without one the item collects by distance ({@code collectRadius}).
 * Per-frame work allocates nothing; the player lookup retries on a timer until a player exists.</p>
 */
public class PickupItemScript extends SimpleEntityScript {

    public String itemId = "coin";
    public int amount = 1;
    public float magnetRadius = 4.0f;
    public float magnetAcceleration = 30.0f;  // m/s^2 while pulled in
    public float magnetMaxSpeed = 18.0f;
    public float collectRadius = 0.7f;
    public float targetHeight = 0.9f;         // Aim this far above the player's origin (chest, not feet)
    public float spinSpeed = 120.0f;          // Degrees per second
    public float bobHeight = 0.15f;
    public float bobSpeed = 2.5f;
    public String pickupSound = "";
    public String playerName = "Player";
    public boolean debugDraw = false;

    Entity player;
    boolean collected = false;
    boolean magnetized = false;
    float magnetSpeed = 0.0f;
    float bobTime = 0.0f;
    float lastBobOffset = 0.0f;
    String inventoryKey;
    int playerSearchTimer = ScriptTaskQueue.INVALID_HANDLE;

    final Vector3 itemPos = new Vector3();
    final Vector3 playerPos = new Vector3();
    final Vector3 toPlayer = new Vector3();

    ScriptTask findPlayerTask;
    ScriptTask finishPickupTask;

    @Override
    public void onStart() {
        inventoryKey = inventoryKey(itemId);
        // Randomize the bob phase so a row of coins does not move in lockstep
        bobTime = (float) (Math.random() * Math.PI * 2.0);

        findPlayerTask = new ScriptTask() {
            public void run() {
                player = findPlayer(playerName);
                if (player != null && playerSearchTimer != ScriptTaskQueue.INVALID_HANDLE) {
                    cancelTask(playerSearchTimer);
                    playerSearchTimer = ScriptTaskQueue.INVALID_HANDLE;
                }
            }
        };
        finishPickupTask = new ScriptTask() {
            public void run() {
                destroySelf();
            }
        };

        player = findPlayer(playerName);
        if (player == null) playerSearchTimer = runEvery(0.5f, findPlayerTask);
    }

    @Override
    public void onUpdate(float deltaTime) {
        if (collected) return;
        Transform t = transform();
        if (t == null) return;

        if (!magnetized) {
            // Idle presentation: spin and bob (applied as a delta so it composes with anything else moving the item)
            rotate(0.0f, spinSpeed * deltaTime, 0.0f);
            bobTime += deltaTime * bobSpeed;
            float bob = (float) Math.sin(bobTime) * bobHeight;
            t.setPosition(t.posX, t.posY + (bob - lastBobOffset), t.posZ);
            lastBobOffset = bob;
        }

        if (player == null) return;

        getWorldPosition(itemPos);
        getWorldPositionOf(player, playerPos);
        playerPos.y += targetHeight;
        toPlayer.set(playerPos).sub(itemPos);
        float dist = toPlayer.length();

        if (debugDraw) {
            ScriptDebug.drawSphere(itemPos.x, itemPos.y, itemPos.z, magnetRadius, magnetized ? ScriptDebug.YELLOW : ScriptDebug.GRAY);
        }

        if (dist <= collectRadius) {
            collect();
            return;
        }

        if (dist <= magnetRadius) magnetized = true;
        if (magnetized) {
            magnetSpeed = Math.min(magnetMaxSpeed, magnetSpeed + magnetAcceleration * deltaTime);
            float step = Math.min(dist, magnetSpeed * deltaTime);
            toPlayer.scale(step / dist);
            translate(toPlayer.x, toPlayer.y, toPlayer.z);
        }
    }

    @Override
    public void onTriggerEnter(Entity other) {
        if (isPlayer(other, playerName)) collect();
    }

    /** Adds the item to the inventory, notifies listeners, then shrinks and removes the pickup. Runs once. */
    public void collect() {
        if (collected) return;
        collected = true;

        int total = ScriptSaveData.getInt(inventoryKey, 0) + amount;
        setSaveData(inventoryKey, Integer.valueOf(total));
        publishEvent("item_picked", itemId, (float) amount);
        if (pickupSound != null && pickupSound.length() > 0) playSpatialSound(pickupSound, 1.0f);
        logInfo("Picked up " + amount + " x " + itemId + " (now " + total + ")");

        int shrink = tweenScaleTo(0.0f, 0.0f, 0.0f, 0.15f, ScriptTweener.EASE_IN_QUAD);
        if (shrink != ScriptTweener.INVALID_HANDLE) tweens.setOnComplete(shrink, finishPickupTask);
        else destroySelf();
    }

    /** Save-data key holding how many of {@code id} the player owns. */
    public static String inventoryKey(String id) {
        return "inventory." + id;
    }
}
