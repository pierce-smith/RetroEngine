package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.EntityScript;

/**
 * Spins around Y, bobs on a sine wave, and reports to the GameManager entity when the player walks into its
 * trigger (any entity with a CharacterController, or the entity named collectorName if set) or punches it.
 */
public class CollectibleScript extends EntityScript {

    public float spinSpeed = 90.0f;      // degrees per second
    public float bobHeight = 0.25f;
    public float bobFrequency = 1.5f;    // cycles per second
    public int points = 1;
    public String collectorName = "";        // empty = any entity with a CharacterController
    public String gameManagerName = "GameManager";
    public String pickupSound = "";      // optional, e.g. "Assets/Audio/pickup.wav"

    private float baseY;
    private float time;
    private boolean collected;

    @Override
    public void onStart() {
        baseY = getPositionY();
        time = (Math.abs(entity.id.hashCode()) % 1000) / 1000.0f; // de-sync neighbours
    }

    @Override
    public void onUpdate(float dt) {
        if (collected) return;
        time += dt;
        rotate(0.0f, spinSpeed * dt, 0.0f);
        float y = baseY + (float) Math.sin(time * bobFrequency * Math.PI * 2.0) * bobHeight;
        setPosition(getPositionX(), y, getPositionZ());
    }

    @Override
    public void onTriggerEnter(Entity other) {
        if (other == null) return;
        boolean isCollector = (collectorName == null || collectorName.isEmpty())
            ? other.getComponent(CharacterControllerComponent.class) != null
            : collectorName.equals(other.name);
        if (isCollector) collect(other);
    }

    /** Collects this pickup once (called on trigger touch, or by PlayerCombatScript on a punch). */
    public void collect(Entity by) {
        if (collected) return;
        collected = true;
        if (pickupSound != null && !pickupSound.isEmpty()) playSound(pickupSound);
        notifyGameManager();
        // No runtime destroy API: hide it and park it far below the level so its trigger can't fire again
        setScale(0.0f, 0.0f, 0.0f);
        setPosition(getPositionX(), -10000.0f, getPositionZ());
    }

    public boolean isCollected() { return collected; }

    /** Scripts compile independently, so call GameManagerScript.addScore(int) reflectively. */
    private void notifyGameManager() {
        Entity gm = sceneWorld != null ? sceneWorld.findEntityByName(gameManagerName) : null;
        ScriptComponent sc = gm != null ? (ScriptComponent) gm.getComponent(ScriptComponent.class) : null;
        Object inst = sc != null ? sc.activeScriptInstance : null;
        if (inst == null) { logWarning("No '" + gameManagerName + "' script found; pickup not scored."); return; }
        try {
            inst.getClass().getMethod("addScore", new Class[] { int.class }).invoke(inst, new Object[] { Integer.valueOf(points) });
        } catch (ReflectiveOperationException e) {
            logError("GameManager has no addScore(int): " + e);
        }
    }
}
