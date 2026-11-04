package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.scripting.framework.ScriptDebug;
import com.retro.engine.ui.UIGeometryBatch;

/**
 * Draws the world-space lines gameplay scripts queued through {@link ScriptDebug} over the Scene viewport, using the
 * same projection and viewport bounds as {@link ColliderDebugRenderer}. Segments crossing the camera's near plane are
 * clipped rather than dropped. Uses only preallocated scratch arrays, so it adds no garbage per line.
 */
public final class ScriptDebugRenderer {

    private static final float LINE_THICKNESS = 0.0025f;
    private static final float NEAR_CLIP = 0.05f;
    // Scene viewport rectangle in UI space (matches ColliderDebugRenderer's visibility test)
    private static final float MIN_X = -0.55f, MAX_X = 0.55f, MIN_Y = -0.50f, MAX_Y = 0.88f;

    private static final float[] view = new float[16];
    private static final float[] line = new float[6];

    private ScriptDebugRenderer() {}

    public static void render(UIGeometryBatch.Buffer buffer, ViewportCamera camera) {
        int count = ScriptDebug.getLineCount();
        if (buffer == null || camera == null || count == 0) return;

        camera.computeViewMatrix(view);
        float aspect = camera.getSceneAspect();
        float f = (float) (1.0 / Math.tan(Math.toRadians(camera.getFov()) / 2.0));
        float p00 = f / aspect;
        float p11 = f;

        for (int i = 0; i < count; i++) {
            int rgb = ScriptDebug.getLine(i, line);
            // World -> view space
            float ax = view[0] * line[0] + view[4] * line[1] + view[8] * line[2] + view[12];
            float ay = view[1] * line[0] + view[5] * line[1] + view[9] * line[2] + view[13];
            float az = view[2] * line[0] + view[6] * line[1] + view[10] * line[2] + view[14];
            float bx = view[0] * line[3] + view[4] * line[4] + view[8] * line[5] + view[12];
            float by = view[1] * line[3] + view[5] * line[4] + view[9] * line[5] + view[13];
            float bz = view[2] * line[3] + view[6] * line[4] + view[10] * line[5] + view[14];

            // The camera looks down -Z: depth = -z. Clip the segment to the near plane.
            float da = -az, db = -bz;
            if (da < NEAR_CLIP && db < NEAR_CLIP) continue;
            if (da < NEAR_CLIP) {
                float t = (NEAR_CLIP - da) / (db - da);
                ax += (bx - ax) * t; ay += (by - ay) * t; da = NEAR_CLIP;
            } else if (db < NEAR_CLIP) {
                float t = (NEAR_CLIP - db) / (da - db);
                bx += (ax - bx) * t; by += (ay - by) * t; db = NEAR_CLIP;
            }

            float x1 = (p00 * ax / da) * 0.55f;
            float y1 = 0.19f + (p11 * ay / da) * 0.69f;
            float x2 = (p00 * bx / db) * 0.55f;
            float y2 = 0.19f + (p11 * by / db) * 0.69f;

            if (x1 < MIN_X || x1 > MAX_X || y1 < MIN_Y || y1 > MAX_Y) continue;
            if (x2 < MIN_X || x2 > MAX_X || y2 < MIN_Y || y2 > MAX_Y) continue;
            UIGeometryBatch.addLineSegment(buffer, x1, y1, x2, y2, LINE_THICKNESS, rgb, 1.0f);
        }
    }
}
