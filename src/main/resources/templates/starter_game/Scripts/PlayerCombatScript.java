package com.retro.engine.scripting.user;

import com.retro.engine.animation.AnimatorCondition;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorTransition;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.Input;

/**
 * Layers combat on top of the built-in CharacterController (which keeps handling movement, mouse look and jump).
 * Put it on the FPSController prefab root (the rigged arms entity, with the Main Camera as its child).
 *
 * <p>Left click: alternating jab.L / jab.R punches (click again mid-swing to chain a combo). Hold right click: guard.
 * Your Animator's idle state stays the entry state; at Play this script adds the punch/guard states' transitions
 * and the "PunchL", "PunchR" (triggers) and "Guard" (bool) parameters, so nothing extra needs authoring.
 * A punch that lands on a collectible within {@link #punchRange} in front of the camera collects it.
 */
public class PlayerCombatScript extends EntityScript {

    public String idleState = "";            // empty = the Animator's entry/current state (your idle)
    public String punchLeftClip = "jab.L";
    public String punchRightClip = "jab.R";
    public String guardClip = "guard_idle";
    public float punchCooldown = 0.3f;
    public float punchImpactDelay = 0.12f;   // seconds into the swing when the hit registers
    public float punchRange = 2.2f;
    public float punchConeDot = 0.5f;        // 0.5 = within 60 degrees of view direction
    public float comboWindow = 0.45f;        // normalized swing time after which a queued punch chains
    public float returnToIdle = 0.85f;       // normalized swing time when the arms blend back to idle
    public String collectibleScriptName = "CollectibleScript.java";

    private static final int MOUSE_LEFT = 0, MOUSE_RIGHT = 1;

    private AnimatorComponent animator;
    private Entity cameraEntity;
    private boolean nextIsLeft = true;
    private float cooldown;
    private float pendingHit = -1.0f;

    @Override
    public void onStart() {
        cameraEntity = findChildCamera();
        animator = getAnimator();
        if (animator == null || animator.controller == null) {
            logWarning("No Animator controller on '" + entity.name + "'; punches will hit but not animate.");
            animator = null;
            return;
        }
        wireController(animator.controller);
    }

    @Override
    public void onUpdate(float dt) {
        cooldown -= dt;
        boolean guarding = Input.isMouseButtonDown(MOUSE_RIGHT);
        if (animator != null) animator.setBool("Guard", guarding);

        if (!guarding && cooldown <= 0.0f && Input.isCursorLocked() && Input.isMouseButtonPressed(MOUSE_LEFT)) {
            cooldown = punchCooldown;
            pendingHit = punchImpactDelay;
            if (animator != null) animator.setTrigger(nextIsLeft ? "PunchL" : "PunchR");
            nextIsLeft = !nextIsLeft;
        }
        if (guarding && animator != null) clearPunchTriggers(); // don't fire a buffered punch when guard drops

        if (pendingHit >= 0.0f) {
            pendingHit -= dt;
            if (pendingHit < 0.0f) resolvePunchHit();
        }
    }

    // --- Animator wiring (idempotent; the controller is rebuilt from the mesh on every scene load) ---

    private void wireController(AnimatorController c) {
        AnimatorState idle = c.getState(idleState);
        if (idle == null) idle = animator.getCurrentState();
        if (idle == null) idle = c.getEntryState();
        AnimatorState jabL = c.getState(punchLeftClip);
        AnimatorState jabR = c.getState(punchRightClip);
        AnimatorState guard = c.getState(guardClip);
        if (idle == null || jabL == null || jabR == null) {
            logWarning("Animator is missing '" + punchLeftClip + "'/'" + punchRightClip + "' or an idle state; punches won't animate.");
            return;
        }
        ensureParam(c, "PunchL", AnimatorParameter.ParameterType.TRIGGER);
        ensureParam(c, "PunchR", AnimatorParameter.ParameterType.TRIGGER);
        ensureParam(c, "Guard", AnimatorParameter.ParameterType.BOOL);
        jabL.isLooping = false;
        jabR.isLooping = false;

        link(idle, jabL.name, 0.06f, false, 0.0f, "PunchL", AnimatorCondition.Comparison.TRUE);
        link(idle, jabR.name, 0.06f, false, 0.0f, "PunchR", AnimatorCondition.Comparison.TRUE);
        // Combo chains are checked before the return-to-idle transition
        link(jabL, jabR.name, 0.06f, true, comboWindow, "PunchR", AnimatorCondition.Comparison.TRUE);
        link(jabR, jabL.name, 0.06f, true, comboWindow, "PunchL", AnimatorCondition.Comparison.TRUE);
        link(jabL, idle.name, 0.15f, true, returnToIdle, null, null);
        link(jabR, idle.name, 0.15f, true, returnToIdle, null, null);
        if (guard != null && guard != idle) {
            link(idle, guard.name, 0.12f, false, 0.0f, "Guard", AnimatorCondition.Comparison.TRUE);
            link(guard, idle.name, 0.15f, false, 0.0f, "Guard", AnimatorCondition.Comparison.FALSE);
        }
        logInfo("Combat wired: idle '" + idle.name + "', punches '" + jabL.name + "'/'" + jabR.name + "'"
            + (guard != null ? ", guard '" + guard.name + "'" : "") + ".");
    }

    private static void ensureParam(AnimatorController c, String name, AnimatorParameter.ParameterType type) {
        AnimatorParameter existing = (AnimatorParameter) c.getParameters().get(name);
        if (existing == null || existing.type != type) c.getParameters().put(name, new AnimatorParameter(name, type));
    }

    private static void link(AnimatorState from, String to, float duration, boolean hasExit, float exitTime,
                             String param, AnimatorCondition.Comparison mode) {
        for (int i = 0; i < from.transitions.size(); i++) {
            AnimatorTransition t = (AnimatorTransition) from.transitions.get(i);
            if (!to.equals(t.destinationStateName)) continue;
            if (param == null ? t.conditions.isEmpty()
                : (t.conditions.size() == 1 && param.equals(((AnimatorCondition) t.conditions.get(0)).parameterName))) return;
        }
        AnimatorTransition t = new AnimatorTransition(to, duration, hasExit, exitTime);
        if (param != null) t.addCondition(param, mode);
        from.transitions.add(t);
    }

    private void clearPunchTriggers() {
        AnimatorParameter l = (AnimatorParameter) animator.getParameters().get("PunchL");
        AnimatorParameter r = (AnimatorParameter) animator.getParameters().get("PunchR");
        if (l != null) l.resetTrigger();
        if (r != null) r.resetTrigger();
    }

    // --- Punch hit: nearest collectible inside a cone in front of the camera ---

    private void resolvePunchHit() {
        if (sceneWorld == null) return;
        Entity viewer = cameraEntity != null ? cameraEntity : entity;
        SceneWorld.WorldTransform view = sceneWorld.getComputedWorldTransform(viewer);
        if (view == null) return;
        double yaw = Math.toRadians(view.ry());
        float fx = (float) Math.sin(yaw), fz = (float) -Math.cos(yaw); // yaw 0 looks down -Z (CharacterController convention)

        java.util.List all = sceneWorld.getSceneEntities();
        Entity best = null;
        float bestDist = punchRange;
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            ScriptComponent sc = (ScriptComponent) e.getComponent(ScriptComponent.class);
            if (sc == null || !collectibleScriptName.equals(sc.scriptName)) continue;
            SceneWorld.WorldTransform w = sceneWorld.getComputedWorldTransform(e);
            if (w == null) continue;
            float dx = w.px() - view.px(), dy = w.py() - view.py(), dz = w.pz() - view.pz();
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float flat = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > bestDist || flat < 0.0001f) continue;
            if ((dx * fx + dz * fz) / flat < punchConeDot) continue;
            best = e;
            bestDist = dist;
        }
        if (best == null) return;
        ScriptComponent sc = (ScriptComponent) best.getComponent(ScriptComponent.class);
        Object inst = sc.activeScriptInstance;
        if (inst == null) return;
        try {
            inst.getClass().getMethod("collect", new Class[] { Entity.class }).invoke(inst, new Object[] { entity });
        } catch (ReflectiveOperationException ex) {
            logError("Collectible has no collect(Entity): " + ex);
        }
    }

    private Entity findChildCamera() {
        if (sceneWorld == null) return null;
        java.util.List all = sceneWorld.getSceneEntities();
        for (int i = 0; i < all.size(); i++) {
            Entity e = (Entity) all.get(i);
            if (entity.id.equals(e.parentId) && e.getComponent(Camera.class) != null) return e;
        }
        return null;
    }
}
