package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.ScriptTweener;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Spins around Y and bobs on a sine wave (move() deltas). When the player walks into its trigger (any entity with a
 * CharacterController, or the entity named collectorName if set) or punches it, it publishes a "collectible_collected"
 * event (GameManagerScript listens), plays an optional spatial sound, pops and shrinks, then destroys itself.
 */
public class CollectibleScript extends SimpleEntityScript {

    public static final String EVENT_COLLECTED = "collectible_collected";

    public float spinSpeed = 90.0f;      // degrees per second
    public float bobHeight = 0.25f;
    public float bobFrequency = 1.5f;    // cycles per second
    public int points = 1;
    public String collectorName = "";    // empty = any entity with a CharacterController
    public String pickupSound = "";      // optional, e.g. "Assets/Audio/pickup.wav"
    public float shrinkTime = 0.25f;

    private float time;
    private float bobOffset;
    private boolean collected;
    private ScriptTask shrinkTask;
    private ScriptTask removeTask;

    @Override
    public void onStart() {
        time = (Math.abs(entity.id.hashCode()) % 1000) / 1000.0f; // de-sync neighbours
        shrinkTask = new ScriptTask() {
            public void run() { tweenScaleTo(0.0f, 0.0f, 0.0f, shrinkTime * 0.6f, ScriptTweener.EASE_IN_CUBIC); }
        };
        removeTask = new ScriptTask() {
            public void run() { destroySelf(); }
        };
    }

    @Override
    public void onUpdate(float dt) {
        if (collected) return;
        time += dt;
        rotate(0.0f, spinSpeed * dt, 0.0f);
        float offset = (float) Math.sin(time * bobFrequency * Math.PI * 2.0) * bobHeight;
        move(0.0f, offset - bobOffset, 0.0f); // dy is world up, so spinning doesn't affect the bob
        bobOffset = offset;
    }

    @Override
    public void onTriggerEnter(Entity other) {
        if (isPlayer(other, collectorName)) collect(other);
    }

    /** Collects this pickup once (called on trigger touch, or by PlayerCombatScript on a punch). */
    public void collect(Entity by) {
        if (collected) return;
        collected = true;
        if (pickupSound != null && pickupSound.length() > 0) playSpatialSound(pickupSound, 1.0f);
        publishEvent(EVENT_COLLECTED, null, points);
        float s = transform() != null ? transform().scaleX * 1.4f : 1.4f;
        tweenScaleTo(s, s, s, shrinkTime * 0.4f, ScriptTweener.EASE_OUT_QUAD);
        runAfter(shrinkTime * 0.4f, shrinkTask);
        runAfter(shrinkTime, removeTask);
    }

    public boolean isCollected() { return collected; }
}
