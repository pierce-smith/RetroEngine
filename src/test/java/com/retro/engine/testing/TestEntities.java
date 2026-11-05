package com.retro.engine.testing;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.RigidBodyComponent.MotionType;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.physics.PhysicsSystem;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small builders shared by the physics and scene tests. */
public final class TestEntities {

    public static final float FRAME = 1.0f / 60.0f;
    private static int nextId = 0;

    private TestEntities() {}

    public static Entity at(String name, float x, float y, float z) {
        Entity e = new Entity("test_" + (nextId++), name, null);
        Transform t = e.getTransform();
        t.posX = x;
        t.posY = y;
        t.posZ = z;
        return e;
    }

    public static Entity box(float x, float y, float z, float sizeX, float sizeY, float sizeZ, MotionType motion) {
        Entity e = at("box", x, y, z);
        BoxCollider box = new BoxCollider();
        box.sizeX = sizeX;
        box.sizeY = sizeY;
        box.sizeZ = sizeZ;
        e.addComponent(box);
        e.addComponent(body(motion));
        return e;
    }

    /** A sphere with gravity and drag off, so it moves only when something acts on it. */
    public static Entity sphere(float x, float y, float z, float radius, MotionType motion) {
        Entity e = at("sphere", x, y, z);
        SphereCollider sphere = new SphereCollider();
        sphere.radius = radius;
        e.addComponent(sphere);
        RigidBodyComponent rb = body(motion);
        rb.useGravity = false;
        rb.linearDrag = 0.0f;
        e.addComponent(rb);
        return e;
    }

    private static RigidBodyComponent body(MotionType motion) {
        RigidBodyComponent rb = new RigidBodyComponent();
        rb.motionType = motion;
        return rb;
    }

    public static RigidBodyComponent rigidBody(Entity e) {
        return e.getComponent(RigidBodyComponent.class);
    }

    public static List<Entity> scene(Entity... entities) {
        return new ArrayList<>(Arrays.asList(entities));
    }

    public static PhysicsSystem physics(List<Entity> scene) {
        PhysicsSystem physics = new PhysicsSystem();
        physics.initWorld(scene);
        return physics;
    }

    public static void step(PhysicsSystem physics, List<Entity> scene, int frames) {
        for (int i = 0; i < frames; i++) physics.step(FRAME, scene);
    }

    /** Bytes allocated so far by the calling thread. */
    public static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
