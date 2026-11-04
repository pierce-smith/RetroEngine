package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

/**
 * AI enemy on a NavMeshAgent: chases the player over the baked NavMesh and attacks when within reach.
 *
 * Chasing: the destination is re-sent with setDestination() whenever the player has moved more than repathDistance
 * from the last requested destination (or the agent has no path), so it tracks continuously without re-planning
 * every frame. Attacking: inside attackRange the agent is stopped (stopNavigation), turns to face the player and
 * publishes a "player_damaged" event (GameManagerScript listens) every attackCooldown seconds.
 *
 * Zero-allocation per frame: the player is resolved once in onStart and positions go into pre-allocated Vector3s.
 */
public class ChaserScript extends SimpleEntityScript {

    public static final String EVENT_PLAYER_DAMAGED = "player_damaged";

    public String playerName = "";       // empty = the entity with a CharacterController
    public float attackRange = 1.6f;     // flat distance at which the chaser stops and attacks
    public float attackDamage = 10.0f;
    public float attackCooldown = 1.0f;  // seconds between hits
    public float repathDistance = 0.3f;  // player movement that triggers a new destination
    public float turnSpeed = 540.0f;     // degrees per second while facing the player in attack range
    public float hitShake = 0.2f;        // camera shake on a landed hit (0 = off)

    private Entity player;
    private final Vector3 playerPos = new Vector3();
    private final Vector3 lastDest = new Vector3();
    private boolean hasDest;
    private float cooldown;

    @Override
    public void onStart() {
        player = findPlayer(playerName); // cached: scans the scene once
        if (player == null) logWarning("ChaserScript: no player found; the chaser will idle.");
        if (navMeshAgent() == null) logWarning("ChaserScript: needs a NavMeshAgent on the same entity.");
        hasDest = false;
        cooldown = 0.0f;
    }

    @Override
    public void onUpdate(float dt) {
        if (cooldown > 0.0f) cooldown -= dt;
        if (player == null) return;
        NavMeshAgent agent = navMeshAgent();
        if (agent == null) return;

        getWorldPositionOf(player, playerPos);
        float dist = getFlatDistanceTo(player);

        if (dist <= attackRange) {
            // In reach: hold position, face the player and strike on cooldown
            if (hasPath() || isPathPending()) stopNavigation();
            hasDest = false;
            turnTowards(playerPos.x, playerPos.z, turnSpeed * dt);
            if (cooldown <= 0.0f) {
                cooldown = attackCooldown;
                publishEvent(EVENT_PLAYER_DAMAGED, null, attackDamage);
                if (hitShake > 0.0f) shakeCamera(hitShake, 0.25f);
            }
            return;
        }

        // Chasing: only re-send the destination when the player moved enough (A* is cheap but not free)
        float dx = playerPos.x - lastDest.x, dz = playerPos.z - lastDest.z;
        boolean moved = dx * dx + dz * dz > repathDistance * repathDistance;
        if (!hasDest || moved) {
            setDestination(playerPos);
            lastDest.set(playerPos);
            hasDest = true;
        }
    }
}
