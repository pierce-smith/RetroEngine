package com.retro.engine.modding.events;

import com.retro.engine.ecs.Entity;

/**
 * Posted once per trigger-volume pair and phase each physics step, alongside the EntityScript
 * onTriggerEnter / onTriggerStay / onTriggerExit callbacks. Reused between posts: never store it.
 */
public final class TriggerEvent extends ModEvent {

    public enum Phase { ENTER, STAY, EXIT }

    public Phase phase;
    /** The entity whose collider is marked isTrigger (when both are, the first of the pair). */
    public Entity trigger;
    /** The entity that entered, is inside, or left the trigger. */
    public Entity other;
}
