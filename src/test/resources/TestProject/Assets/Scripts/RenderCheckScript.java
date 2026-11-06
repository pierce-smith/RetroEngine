package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.UIText;
import com.retro.engine.scripting.framework.SimpleEntityScript;

/**
 * Tours the RenderCheck scene so the renderer can be judged by eye, with no input: each station shows one fix and says
 * what correct looks like. The frame readout is the real time between frames, so it covers CPU and GPU together.
 */
public class RenderCheckScript extends SimpleEntityScript {

    private static final float STATION_SECONDS = 12.0f;
    private static final String[] TITLES = {
        "1 of 3   DRAW CAP",
        "2 of 3   GROUND SHADOWS",
        "3 of 3   MIPMAPS"
    };
    private static final String[] HINTS = {
        "PASS: full 40 x 40 grid with no missing cubes, and the arms floating above the grid",
        "PASS: the floor is dark under every cube, right out to the far edges of the grid",
        "PASS: the far floor fades to calm, flat gray. FAIL: harsh noise that crawls all the way to the horizon"
    };

    private UIText title, status, hint;
    private float time = 0.0f;
    private float windowTime = 0.0f;
    private float worstMs = 0.0f;
    private int windowFrames = 0;

    public void onStart() {
        title = text("HUD Title");
        status = text("HUD Status");
        hint = text("HUD Hint");
    }

    private UIText text(String name) {
        Entity e = findEntity(name);
        return e != null ? (UIText) e.getComponent(UIText.class) : null;
    }

    public void onUpdate(float deltaTime) {
        time += deltaTime;
        int station = (int) (time / STATION_SECONDS) % TITLES.length;
        moveCamera(station);
        if (title != null) title.text = TITLES[station];
        if (hint != null) hint.text = HINTS[station];

        windowTime += deltaTime;
        windowFrames++;
        worstMs = Math.max(worstMs, deltaTime * 1000.0f);
        if (windowTime >= 0.5f) {
            float averageMs = windowTime * 1000.0f / windowFrames;
            if (status != null) {
                status.text = "FRAME " + Math.round(averageMs * 10.0f) / 10.0f + " ms   WORST " + Math.round(worstMs * 10.0f) / 10.0f
                        + " ms   FPS " + Math.round(1000.0f / averageMs) + "   ENTITIES " + sceneWorld.getSceneEntities().size();
            }
            windowTime = 0.0f;
            windowFrames = 0;
            worstMs = 0.0f;
        }
    }

    private void moveCamera(int station) {
        float angle = time * 0.15f;
        if (station == 0) {
            setPosition((float) Math.cos(angle) * 72.0f, 48.0f, (float) Math.sin(angle) * 72.0f);
            lookAt(0.0f, 2.0f, 0.0f);
        } else if (station == 1) {
            setPosition((float) Math.cos(angle) * 24.0f, 8.0f, (float) Math.sin(angle) * 24.0f);
            lookAt(0.0f, 0.5f, 0.0f);
        } else {
            // Past the edge of the grid, skimming the floor and looking away from it
            float sweep = (float) Math.sin(time * 0.35f) * 30.0f;
            setPosition(44.0f, 1.6f, sweep);
            lookAt(300.0f, 0.0f, sweep);
        }
    }
}
