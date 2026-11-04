package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ParticleSystem;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

/**
 * Animates a torch flame driven by this entity's ParticleSystem (the engine simulates and renders the particles).
 * The flame flickers by modulating the emission rate and start size, throws a burst of embers every few seconds with
 * emit(), and the player can snuff / relight it with F when standing within {@link #interactRange} (stop() / play()).
 * Everything in onUpdate is allocation-free.
 */
public class TorchFlameScript extends SimpleEntityScript {

    public float flickerSpeed = 9.0f;        // flicker cycles per second
    public float flickerAmount = 0.35f;      // 0 = steady flame, 1 = wild
    public float emberInterval = 2.5f;       // average seconds between ember bursts
    public int emberBurst = 6;               // particles per burst
    public float interactRange = 2.5f;       // F toggles the flame within this distance of the player
    public String playerName = "Player";

    private static final int KEY_F = 70;

    private ParticleSystem flame;
    private Entity player;
    private final Vector3 myPos = new Vector3();
    private final Vector3 playerPos = new Vector3();
    private float baseRate, baseSize, time, emberTimer;

    @Override
    public void onStart() {
        flame = particleSystem();
        if (flame == null) {
            logWarning("TorchFlameScript on '" + entity.name + "' needs a ParticleSystem component.");
            return;
        }
        baseRate = flame.emissionRate;
        baseSize = flame.startSize;
        player = findEntity(playerName);
        time = entity.id.hashCode() % 100; // de-sync torches
        emberTimer = emberInterval;
        play();
    }

    @Override
    public void onUpdate(float dt) {
        if (flame == null) return;
        time += dt;

        if (flame.isPlaying()) {
            // Two detuned sines read as an organic flicker
            float n = 0.5f * ((float) Math.sin(time * flickerSpeed) + (float) Math.sin(time * flickerSpeed * 2.37f + 1.3f));
            flame.emissionRate = baseRate * (1.0f + flickerAmount * n);
            flame.startSize = baseSize * (1.0f + 0.5f * flickerAmount * n);

            emberTimer -= dt;
            if (emberTimer <= 0.0f) {
                emit(emberBurst);
                emberTimer = emberInterval * (0.5f + (float) Math.random());
            }
        }

        if (player != null && Input.isKeyPressed(KEY_F)) {
            getWorldPosition(myPos);
            getWorldPositionOf(player, playerPos);
            float dx = myPos.x - playerPos.x, dy = myPos.y - playerPos.y, dz = myPos.z - playerPos.z;
            if (dx * dx + dy * dy + dz * dz <= interactRange * interactRange) {
                if (flame.isPlaying()) {
                    stop();
                } else {
                    play();
                    emit(emberBurst * 2); // relight flare
                }
            }
        }
    }
}
