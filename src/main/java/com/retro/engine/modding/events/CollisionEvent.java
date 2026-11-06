package com.retro.engine.modding.events;

import com.retro.engine.ecs.Entity;

/**
 * Posted by PhysicsSystem for every solid (non-trigger) contact in a 60 Hz physics step, before the contact is solved.
 * That includes bodies about to touch, where {@link #penetration} is 0. Subscribers can change {@link #restitution} (bounciness of the response, 0 = dead stop) or cancel the
 * event so the bodies pass through each other for this step (no separation, no collision callbacks).
 *
 * <p>One reused instance is posted for every contact of every step: never store it, and keep handlers cheap.
 */
public final class CollisionEvent extends ModEvent {

    public Entity entityA;
    public Entity entityB;
    /** Unit contact normal pointing from A to B, at the deepest contact point. */
    public float normalX, normalY, normalZ;
    /** Overlap depth along the normal, in world units. */
    public float penetration;
    /** Velocity reflection factor applied along the normal; preset to the bodies' RigidBody restitution. */
    public float restitution;

    @Override
    public boolean isCancelable() {
        return true;
    }
}
