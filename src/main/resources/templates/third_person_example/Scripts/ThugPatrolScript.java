package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

/**
 * NPC on a NavMeshAgent: walks between its start and patrolOffset, pausing at each end, and backs away while the player
 * is close.
 *
 * <p>The panic clip is a backward walk, so the agent mustn't face its direction of travel. Setting its angularSpeed to 0
 * hands the turning to this script, which keeps the NPC facing the player as it retreats. The speeds match the ground
 * speed each clip was authored for, so the feet don't slide.
 */
public class ThugPatrolScript extends SimpleEntityScript {

    public String idleClip = "npc_idle";
    public String patrolClip = "npc_patrol";
    public String panicClip = "npc_panic";

    public float patrolSpeed = 0.89f;
    public float panicSpeed = 1.15f;

    public float patrolOffsetX = 8.0f;   // far end of the patrol, relative to where the NPC starts
    public float patrolOffsetZ = 0.0f;
    public float waitTime = 1.5f;        // seconds to pause at each end

    public float alarmRange = 3.5f;      // player distance that starts the retreat
    public float calmRange = 5.0f;       // player distance that ends it; the gap stops flicker at the edge
    public float retreatStep = 2.0f;     // how far behind itself each retreat path aims
    public float turnSpeed = 360.0f;     // degrees per second while facing the player
    public float clipFade = 0.25f;

    private static final float REPATH_SECONDS = 0.25f;

    private NavMeshAgent agent;
    private AnimatorComponent animator;
    private Entity player;
    private final Vector3 playerPos = new Vector3();
    private float startX, startZ, agentTurnSpeed, wait, repath;
    private boolean headingOut, panicking;

    @Override
    public void onStart() {
        agent = navMeshAgent();
        animator = animator();
        player = findPlayer("");
        if (agent == null) {
            logWarning("ThugPatrolScript needs a NavMeshAgent on '" + entity.name + "'.");
            return;
        }
        agentTurnSpeed = agent.angularSpeed;
        agent.speed = patrolSpeed;
        startX = getWorldX();
        startZ = getWorldZ();
    }

    @Override
    public void onUpdate(float dt) {
        if (agent == null) return;
        if (player != null) {
            float distance = getFlatDistanceTo(player);
            if (panicking ? distance > calmRange : distance < alarmRange) setPanicking(!panicking);
        }
        if (panicking) retreat(dt);
        else patrol(dt);
    }

    private void setPanicking(boolean on) {
        panicking = on;
        agent.angularSpeed = on ? 0.0f : agentTurnSpeed;
        agent.speed = on ? panicSpeed : patrolSpeed;
        stopNavigation();
        repath = 0.0f;
        wait = on ? 0.0f : waitTime;
    }

    private void patrol(float dt) {
        if (hasPath() || isPathPending()) {
            play(patrolClip);
            return;
        }
        play(idleClip);
        wait -= dt;
        if (wait > 0.0f) return;
        wait = waitTime;
        headingOut = !headingOut;
        float x = headingOut ? startX + patrolOffsetX : startX;
        float z = headingOut ? startZ + patrolOffsetZ : startZ;
        setDestination(x, getWorldY(), z);
    }

    private void retreat(float dt) {
        getWorldPositionOf(player, playerPos);
        turnTowards(playerPos.x, playerPos.z, turnSpeed * dt);
        repath -= dt;
        if (repath <= 0.0f) {
            repath = REPATH_SECONDS;
            float awayX = getWorldX() - playerPos.x, awayZ = getWorldZ() - playerPos.z;
            float length = (float) Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length > 0.001f) {
                float scale = retreatStep / length;
                setDestination(getWorldX() + awayX * scale, getWorldY(), getWorldZ() + awayZ * scale);
            }
        }
        // Stands its ground once it runs out of floor to back onto
        play(hasPath() || isPathPending() ? panicClip : idleClip);
    }

    private void play(String clip) {
        if (animator != null) animator.crossFade(clip, clipFade);
    }
}
