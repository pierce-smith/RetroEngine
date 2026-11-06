package com.retro.engine.scripting.user;

import com.retro.engine.scripting.framework.SimpleEntityScript;

/** Aims the benchmark camera at the middle of the scene, so every scene is measured from a fixed view. */
public class BenchCameraScript extends SimpleEntityScript {

    public void onStart() {
        lookAt(0.0f, 0.0f, 0.0f);
    }

    public void onUpdate(float deltaTime) {
    }
}
