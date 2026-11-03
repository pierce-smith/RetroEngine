package com.example.examplemod;

import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scripting.EntityScript;

/**
 * Per-frame behaviour for ModScript.WobbleComponent. EntityScripts are compiled by the engine's ScriptEngine when an
 * entity's ScriptComponent references them (example.rpatch attaches one via mod://example_mod/Scripts/...), with
 * the mod class loader as parent, so they can use mod component classes directly.
 * Not listed in mod.json "script_sources": those are for the entry point and the classes it needs.
 */
public class WobbleScript extends EntityScript {

    private float time = 0.0f;
    private float baseY = 0.0f;

    public void onStart() {
        Transform t = getTransform();
        if (t != null) baseY = t.posY;
    }

    public void onUpdate(float deltaTime) {
        // Janino ignores generic type arguments, so cast generic return values explicitly
        ModScript.WobbleComponent wobble = (ModScript.WobbleComponent) getComponent(ModScript.WobbleComponent.class);
        Transform t = getTransform();
        if (wobble == null || t == null) return;
        time += deltaTime;
        t.posY = baseY + (float) Math.sin(time * wobble.speed) * wobble.amplitude;
    }
}
