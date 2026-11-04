package com.retro.engine.scripting.framework.examples;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.physics.RaycastHit;
import com.retro.engine.scripting.framework.ScriptDebug;
import com.retro.engine.scripting.framework.ScriptState;
import com.retro.engine.scripting.framework.ScriptStateMachine;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.framework.Vector3;

import java.util.Random;

/**
 * Enemy that idles, patrols random points around its spawn, chases the player when it sees them and attacks in
 * melee range, built on {@link ScriptStateMachine} with one inner class per state.
 *
 * <p>Perception runs on a 5 Hz timer instead of every frame: the player must be within {@code detectRadius}, inside the
 * {@code fieldOfView} cone and in line of sight (a raycast that hits the player first). Once chasing, the enemy keeps
 * the player until they are farther than {@code loseRadius} or out of sight for {@code memorySeconds}.</p>
 *
 * <p>Movement: with a DYNAMIC rigidbody the enemy is steered by setting its horizontal velocity (gravity and
 * collisions keep working); without one it moves its transform. Don't give it a CharacterController: those read the
 * player's input. Attacks publish {@code "player_damaged"} (text = this entity's name, number = damage) for a
 * health script to consume, and shake the camera.</p>
 */
public class EnemyAIStateScript extends SimpleEntityScript {

    public float patrolRadius = 6.0f;
    public float patrolSpeed = 1.8f;
    public float chaseSpeed = 4.2f;
    public float turnSpeed = 300.0f;          // Degrees per second
    public float idleTime = 1.5f;
    public float detectRadius = 10.0f;
    public float loseRadius = 16.0f;
    public float fieldOfView = 120.0f;        // Full cone angle in degrees
    public float memorySeconds = 2.5f;
    public float eyeHeight = 1.2f;
    public float attackRange = 1.8f;
    public float attackCooldown = 1.1f;
    public float attackDamage = 10.0f;
    public String attackSound = "";
    public String alertSound = "";
    public String playerName = "Player";
    public boolean debugDraw = true;

    // Shared state, package-private so the inner state classes can use it under Janino
    final ScriptStateMachine fsm = new ScriptStateMachine();
    ScriptState idle, patrol, chase, attack;
    Entity player;
    boolean canSeePlayer = false;
    float timeSinceSeen = 999.0f;
    float homeX, homeY, homeZ;

    final Random rng = new Random();
    final RaycastHit sightHit = new RaycastHit();
    final Vector3 myPos = new Vector3();
    final Vector3 targetPos = new Vector3();
    final Vector3 velocity = new Vector3();
    final Vector3 patrolPoint = new Vector3();

    ScriptTask perceptionTask;

    // =========================================================================
    // States
    // =========================================================================

    class IdleState extends ScriptState {
        IdleState() { super("Idle"); }

        public void onEnter() { stopMoving(); }

        public void onUpdate(float dt) {
            if (canSeePlayer) { fsm.changeState(chase); return; }
            if (fsm.getTimeInState() >= idleTime) fsm.changeState(patrol);
        }
    }

    class PatrolState extends ScriptState {
        PatrolState() { super("Patrol"); }

        public void onEnter() {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radius = Math.sqrt(rng.nextDouble()) * patrolRadius; // Uniform over the disc
            patrolPoint.set(homeX + (float) (Math.cos(angle) * radius), homeY, homeZ + (float) (Math.sin(angle) * radius));
        }

        public void onUpdate(float dt) {
            if (canSeePlayer) { fsm.changeState(chase); return; }
            // Give up on unreachable points (blocked by a wall) after a while
            if (moveTowards(patrolPoint.x, patrolPoint.z, patrolSpeed, dt) < 0.4f || fsm.getTimeInState() > 12.0f) {
                fsm.changeState(idle);
            }
        }
    }

    class ChaseState extends ScriptState {
        ChaseState() { super("Chase"); }

        public void onEnter() {
            if (alertSound != null && alertSound.length() > 0) playSpatialSound(alertSound, 1.0f);
        }

        public void onUpdate(float dt) {
            if (player == null) { fsm.changeState(idle); return; }
            float dist = getFlatDistanceTo(player);
            if (dist > loseRadius || timeSinceSeen > memorySeconds) { fsm.changeState(patrol); return; }
            if (dist <= attackRange) { fsm.changeState(attack); return; }
            getWorldPositionOf(player, targetPos);
            moveTowards(targetPos.x, targetPos.z, chaseSpeed, dt);
        }
    }

    class AttackState extends ScriptState {
        float cooldown;

        AttackState() { super("Attack"); }

        public void onEnter() {
            stopMoving();
            cooldown = attackCooldown * 0.35f; // Short wind-up before the first hit
        }

        public void onUpdate(float dt) {
            if (player == null) { fsm.changeState(idle); return; }
            getWorldPositionOf(player, targetPos);
            turnTowards(targetPos.x, targetPos.z, turnSpeed * dt);
            if (getFlatDistanceTo(player) > attackRange * 1.25f) { fsm.changeState(chase); return; }
            cooldown -= dt;
            if (cooldown <= 0.0f) {
                cooldown = attackCooldown;
                strike();
            }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void onStart() {
        getWorldPosition(myPos);
        homeX = myPos.x;
        homeY = myPos.y;
        homeZ = myPos.z;
        player = findPlayer(playerName);

        idle = new IdleState();
        patrol = new PatrolState();
        chase = new ChaseState();
        attack = new AttackState();

        perceptionTask = new ScriptTask() {
            public void run() {
                if (player == null) player = findPlayer(playerName);
                canSeePlayer = checkCanSeePlayer();
            }
        };
        // Stagger enemies so their raycasts do not all land on the same frame
        tasks.runEvery(0.2f, rng.nextFloat() * 0.2f, perceptionTask);

        fsm.changeState(idle);
    }

    @Override
    public void onUpdate(float deltaTime) {
        timeSinceSeen = canSeePlayer ? 0.0f : timeSinceSeen + deltaTime;
        fsm.update(deltaTime);
        if (debugDraw) drawDebug();
    }

    @Override
    public void onDestroy() {
        fsm.stop();
    }

    /** Current behaviour state ("Idle", "Patrol", "Chase", "Attack") for HUDs and debugging. */
    public String getStateName() {
        return fsm.getCurrentStateName();
    }

    // =========================================================================
    // Behaviour helpers
    // =========================================================================

    /** Turns toward and moves to (x, z) on the ground plane; returns the remaining distance. */
    float moveTowards(float x, float z, float speed, float dt) {
        getWorldPosition(myPos);
        float dx = x - myPos.x, dz = z - myPos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        turnTowards(x, z, turnSpeed * dt);
        if (dist < 1e-3f) { stopMoving(); return 0.0f; }
        float nx = dx / dist, nz = dz / dist;
        getVelocity(velocity);
        // Prefer physics: keeps gravity and collision response. Fall back to moving the transform.
        if (!setVelocity(nx * speed, velocity.y, nz * speed)) {
            float step = Math.min(dist, speed * dt);
            translate(nx * step, 0.0f, nz * step);
        }
        return dist;
    }

    void stopMoving() {
        getVelocity(velocity);
        setVelocity(0.0f, velocity.y, 0.0f);
    }

    boolean checkCanSeePlayer() {
        if (player == null) return false;
        getWorldPosition(myPos);
        getWorldPositionOf(player, targetPos);
        float dx = targetPos.x - myPos.x, dy = (targetPos.y + eyeHeight * 0.5f) - (myPos.y + eyeHeight), dz = targetPos.z - myPos.z;
        float flatDist = (float) Math.sqrt(dx * dx + dz * dz);
        float range = (fsm.isInState(chase) || fsm.isInState(attack)) ? loseRadius : detectRadius;
        if (flatDist > range) return false;

        // Inside the vision cone? (Always aware of a player standing right next to it.)
        if (flatDist > attackRange) {
            float yaw = (float) Math.toRadians(transform() != null ? transform().rotY : 0.0f);
            float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
            float cos = (dx * fx + dz * fz) / flatDist;
            if (cos < (float) Math.cos(Math.toRadians(fieldOfView * 0.5f))) return false;
        }

        // Line of sight: the first solid thing the ray hits must be the player (or nothing before reaching them).
        // Trigger volumes (door zones, pickups) don't block vision, so the ray continues past them.
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-3f) return true;
        float nx = dx / dist, ny = dy / dist, nz = dz / dist;
        float ox = myPos.x, oy = myPos.y + eyeHeight, oz = myPos.z;
        float remaining = dist;
        for (int pass = 0; pass < 4; pass++) {
            if (!raycast(ox, oy, oz, nx, ny, nz, remaining, sightHit)) return true;
            if (sightHit.entity == player || sightHit.distance >= remaining - 0.3f) return true;
            if (!isTriggerOnly(sightHit.entity)) return false;
            float advance = sightHit.distance + 0.05f;
            ox += nx * advance; oy += ny * advance; oz += nz * advance;
            remaining -= advance;
            if (remaining <= 0.0f) return true;
        }
        return false;
    }

    /** True when every collider on {@code e} is a trigger (it has at least one, and none are solid). */
    boolean isTriggerOnly(Entity e) {
        if (e == null) return false;
        BoxCollider box = (BoxCollider) e.getComponent(BoxCollider.class);
        SphereCollider sphere = (SphereCollider) e.getComponent(SphereCollider.class);
        CapsuleCollider capsule = (CapsuleCollider) e.getComponent(CapsuleCollider.class);
        if (box == null && sphere == null && capsule == null) return false;
        return (box == null || box.isTrigger) && (sphere == null || sphere.isTrigger) && (capsule == null || capsule.isTrigger);
    }

    void strike() {
        if (attackSound != null && attackSound.length() > 0) playSpatialSound(attackSound, 1.0f);
        publishEvent("player_damaged", entity != null ? entity.name : null, attackDamage);
        shakeCamera(0.35f, 0.25f);
    }

    void drawDebug() {
        getWorldPosition(myPos);
        int color = fsm.isInState(attack) ? ScriptDebug.RED
                : fsm.isInState(chase) ? ScriptDebug.ORANGE
                : fsm.isInState(patrol) ? ScriptDebug.CYAN : ScriptDebug.GRAY;
        ScriptDebug.drawCircleXZ(myPos.x, myPos.y, myPos.z, detectRadius, color);
        ScriptDebug.drawCircleXZ(homeX, homeY, homeZ, patrolRadius, ScriptDebug.BLUE);
        if (fsm.isInState(patrol)) {
            ScriptDebug.drawLine(myPos.x, myPos.y + 0.1f, myPos.z, patrolPoint.x, patrolPoint.y + 0.1f, patrolPoint.z, ScriptDebug.CYAN);
            ScriptDebug.drawCross(patrolPoint.x, patrolPoint.y + 0.1f, patrolPoint.z, 0.4f, ScriptDebug.CYAN);
        }
        if (player != null && canSeePlayer) {
            getWorldPositionOf(player, targetPos);
            ScriptDebug.drawLine(myPos.x, myPos.y + eyeHeight, myPos.z, targetPos.x, targetPos.y + eyeHeight * 0.5f, targetPos.z, ScriptDebug.RED);
        }
    }
}
