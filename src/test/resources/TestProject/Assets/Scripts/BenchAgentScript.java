package com.retro.engine.scripting.user;

import com.retro.engine.scripting.framework.SimpleEntityScript;

/** Sends a benchmark NavMeshAgent somewhere new every second, with agents spread across that second. */
public class BenchAgentScript extends SimpleEntityScript {

    private static final float INTERVAL_SECONDS = 1.0f;
    private static final float AREA = 45.0f;

    private float nextPath;
    private int seed;

    public void onStart() {
        seed = entity.id.hashCode();
        nextPath = next() * INTERVAL_SECONDS;
    }

    public void onUpdate(float deltaTime) {
        nextPath -= deltaTime;
        if (nextPath > 0.0f) return;
        nextPath += INTERVAL_SECONDS;
        setDestination((next() * 2.0f - 1.0f) * AREA, 0.0f, (next() * 2.0f - 1.0f) * AREA);
    }

    /** A small repeatable generator, so every run walks the same paths. */
    private float next() {
        seed = seed * 1664525 + 1013904223;
        return (seed >>> 8) / 16777216.0f;
    }
}
