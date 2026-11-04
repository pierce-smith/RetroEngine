package com.retro.engine.scripting.user;

import com.retro.engine.ecs.components.Transform;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.ScriptTweener;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Starter test script: spins its entity around the Y axis and gives it a little "pop" every few seconds,
 * using SimpleEntityScript's cached transform(), runEvery() timer and scale tweens.
 */
public class StarterRotateScript extends SimpleEntityScript {

    public float rotationSpeed = 45.0f; // degrees per second
    public float pulseInterval = 2.0f;  // seconds between pops (<= 0 disables)
    public float pulseScale = 1.2f;

    private float baseX, baseY, baseZ;
    private ScriptTask pulse;
    private ScriptTask settle;

    @Override
    public void onStart() {
        Transform t = transform();
        if (t != null) { baseX = t.scaleX; baseY = t.scaleY; baseZ = t.scaleZ; }
        // Tasks are created once and reused: no per-frame allocation
        settle = new ScriptTask() {
            public void run() { tweenScaleTo(baseX, baseY, baseZ, 0.35f, ScriptTweener.EASE_OUT_BACK); }
        };
        pulse = new ScriptTask() {
            public void run() {
                tweenScaleTo(baseX * pulseScale, baseY * pulseScale, baseZ * pulseScale, 0.15f, ScriptTweener.EASE_OUT_QUAD);
                runAfter(0.15f, settle);
            }
        };
        if (pulseInterval > 0.0f) runEvery(pulseInterval, pulse);
    }

    @Override
    public void onUpdate(float deltaTime) {
        rotate(0.0f, rotationSpeed * deltaTime, 0.0f);
    }
}
