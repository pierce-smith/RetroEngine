package com.retro.engine.ecs.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retro.engine.ecs.HierarchySystem;
import org.junit.jupiter.api.Test;

/** An emitter has to spray along the +Y its entity is drawn with, so it must share the renderers' Euler convention. */
class ParticleSystemRotationTest {

    private static final float[][] ROTATIONS = {
            {0, 0, 0}, {60, 0, 0}, {0, 0, 60}, {90, 0, 0}, {0, 90, 0}, {35, 0, -20}, {20, 35, 50}, {-110, 75, 140}
    };

    private final HierarchySystem hierarchy = new HierarchySystem();

    private static ParticleSystem emitter(ParticleSystem.EmitterShape shape, float radius) {
        ParticleSystem ps = new ParticleSystem();
        ps.emitterShape = shape;
        ps.shapeAngle = 0.0f;
        ps.shapeRadius = radius;
        ps.speed = 1.0f;
        ps.emissionRate = 0.0f;
        ps.playOnStart = false;
        return ps;
    }

    private static void assertDirection(float[] expected, float x, float y, float z, String label) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        assertEquals(expected[0], x / length, 1e-4f, label + " x");
        assertEquals(expected[1], y / length, 1e-4f, label + " y");
        assertEquals(expected[2], z / length, 1e-4f, label + " z");
    }

    @Test
    void particlesLeaveAlongTheEntitysRenderedUpAxis() {
        for (ParticleSystem.EmitterShape shape : new ParticleSystem.EmitterShape[] {
                ParticleSystem.EmitterShape.CONE, ParticleSystem.EmitterShape.BOX}) {
            for (float[] r : ROTATIONS) {
                ParticleSystem ps = emitter(shape, 0.0f);
                ps.emit(1);
                ps.simulate(1e-4f, 0, 0, 0, r[0], r[1], r[2]);
                float[] up = hierarchy.rotateVector(0, 1, 0, r[0], r[1], r[2], new float[3]);
                assertDirection(up, ps.velX[0], ps.velY[0], ps.velZ[0], shape + " at " + r[0] + "," + r[1] + "," + r[2]);
            }
        }
    }

    @Test
    void spawnOffsetsTurnWithTheEmitter() {
        for (float[] r : ROTATIONS) {
            ParticleSystem ps = emitter(ParticleSystem.EmitterShape.CONE, 1.0f);
            ps.emit(8);
            ps.simulate(1e-4f, 0, 0, 0, r[0], r[1], r[2]);
            float[] up = hierarchy.rotateVector(0, 1, 0, r[0], r[1], r[2], new float[3]);
            for (int i = 0; i < ps.getAliveCount(); i++) {
                // A cone spawns on a disc square to its axis
                float along = ps.posX[i] * up[0] + ps.posY[i] * up[1] + ps.posZ[i] * up[2];
                assertEquals(0.0f, along, 1e-3f, "disc tilted at " + r[0] + "," + r[1] + "," + r[2]);
            }
        }
    }
}
