package com.retro.engine.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewportCameraTest {

    private static final int LMB = 0, RMB = 1, MMB = 2, PRESS = 1, RELEASE = 0;

    private static float[] eye(ViewportCamera camera) {
        float[] out = new float[3];
        camera.computeEyePosition(out);
        return out;
    }

    /** World-space view direction: the view matrix's third row, negated. */
    private static float[] forward(ViewportCamera camera) {
        float[] v = new float[16];
        camera.computeViewMatrix(v);
        return new float[] { -v[2], -v[6], -v[10] };
    }

    /** View-space position of a world point. */
    private static float[] toView(ViewportCamera camera, float[] p) {
        float[] v = new float[16];
        camera.computeViewMatrix(v);
        return new float[] {
            v[0] * p[0] + v[4] * p[1] + v[8] * p[2] + v[12],
            v[1] * p[0] + v[5] * p[1] + v[9] * p[2] + v[13],
            v[2] * p[0] + v[6] * p[1] + v[10] * p[2] + v[14],
        };
    }

    private static void flyForward(ViewportCamera camera, float seconds) {
        for (float t = 0.0f; t < seconds; t += 0.1f) {
            camera.update(0.1f, true, false, false, false, false, false);
        }
    }

    @Test
    void holdingWKeepsMovingPastTheWorldOriginAtASteadySpeed() {
        ViewportCamera camera = new ViewportCamera();
        camera.onMouseButton(RMB, PRESS, 0, 0);
        float[] dir = forward(camera);

        float previous = 0.0f;
        float[] start = eye(camera);
        for (int second = 1; second <= 30; second++) {
            flyForward(camera, 1.0f);
            float[] now = eye(camera);
            float travelled = (now[0] - start[0]) * dir[0] + (now[1] - start[1]) * dir[1] + (now[2] - start[2]) * dir[2];
            assertEquals(14.0f, travelled - previous, 0.5f, "second " + second);
            previous = travelled;
        }
    }

    @Test
    void rightDragLooksInPlace() {
        ViewportCamera camera = new ViewportCamera();
        float[] start = eye(camera);
        float yaw = camera.getYaw();

        camera.onMouseButton(RMB, PRESS, 0, 0);
        camera.onCursorPos(200, 80, false, false, null);

        assertArrayEquals(start, eye(camera), 1e-3f);
        assertTrue(camera.getYaw() < yaw, "dragging right turns the view right");
    }

    @Test
    void lookingFarFromTheOriginDoesNotDriftTheEye() {
        ViewportCamera camera = new ViewportCamera();
        camera.onMouseButton(RMB, PRESS, 0, 0);
        flyForward(camera, 20.0f);
        float[] start = eye(camera);

        for (int i = 1; i <= 2000; i++) {
            camera.onCursorPos(i * 3.0, Math.sin(i * 0.1) * 40.0, false, false, null);
        }

        assertArrayEquals(start, eye(camera), 0.05f);
    }

    @Test
    void lookAtThePitchLimitStillHoldsTheEye() {
        ViewportCamera camera = new ViewportCamera();
        float[] start = eye(camera);

        camera.onMouseButton(RMB, PRESS, 0, 0);
        camera.onCursorPos(0, 5000, false, false, null);

        assertEquals(-89.0f, camera.getPitch(), 1e-3f);
        assertArrayEquals(start, eye(camera), 1e-3f);
    }

    @Test
    void shiftRightDragOrbitsTheSelectedEntity() {
        ViewportCamera camera = new ViewportCamera();
        float[] pivot = { 12.0f, 3.0f, -7.0f };
        float[] pivotInView = toView(camera, pivot);
        float[] start = eye(camera);

        camera.onMouseButton(RMB, PRESS, 0, 0);
        camera.onCursorPos(150, 60, true, false, pivot);

        assertArrayEquals(pivotInView, toView(camera, pivot), 1e-3f);
        assertTrue(Math.abs(eye(camera)[0] - start[0]) > 0.1f, "the eye swings around the pivot");
    }

    @Test
    void altLeftDragOrbitsTheSelectedEntityAndDoesNothingWithoutOne() {
        float[] pivot = { 1.0f, 0.0f, 2.0f };
        ViewportCamera camera = new ViewportCamera();
        float[] pivotInView = toView(camera, pivot);
        camera.onMouseButton(LMB, PRESS, 0, 0);
        camera.onCursorPos(90, 40, false, true, pivot);
        assertArrayEquals(pivotInView, toView(camera, pivot), 1e-3f);

        ViewportCamera unselected = new ViewportCamera();
        float yaw = unselected.getYaw();
        float[] start = eye(unselected);
        unselected.onMouseButton(LMB, PRESS, 0, 0);
        unselected.onCursorPos(90, 40, false, true, null);
        assertEquals(yaw, unselected.getYaw());
        assertArrayEquals(start, eye(unselected), 0.0f);
    }

    @Test
    void shiftRightDragPansWithNothingSelected() {
        ViewportCamera camera = new ViewportCamera();
        float yaw = camera.getYaw(), panX = camera.getPanX();

        camera.onMouseButton(RMB, PRESS, 0, 0);
        camera.onCursorPos(100, 0, true, false, null);

        assertEquals(yaw, camera.getYaw());
        assertEquals(panX + 0.8f, camera.getPanX(), 1e-4f);
    }

    @Test
    void middleDragPansEvenWithASelection() {
        ViewportCamera camera = new ViewportCamera();
        float yaw = camera.getYaw(), panX = camera.getPanX();

        camera.onMouseButton(MMB, PRESS, 0, 0);
        camera.onCursorPos(100, 0, false, false, new float[3]);
        camera.onMouseButton(MMB, RELEASE, 100, 0);

        assertEquals(yaw, camera.getYaw());
        assertEquals(panX + 0.8f, camera.getPanX(), 1e-4f);
    }

    @Test
    void scrollDollyPassesThroughTheWorldOrigin() {
        ViewportCamera camera = new ViewportCamera();
        for (int i = 0; i < 40; i++) camera.onScroll(1.0);
        assertEquals(-8.5f + 30.0f, camera.getZoom(), 1e-3f);
    }

    @Test
    void frameSelectedCentersAnEntityFarFromTheOrigin() {
        ViewportCamera camera = new ViewportCamera();
        float[] target = { 300.0f, 20.0f, -400.0f };

        camera.focusOn(target[0], target[1], target[2], 5.0f);
        for (int i = 0; i < 300 && camera.isFocusing(); i++) {
            camera.update(1.0f / 60.0f, false, false, false, false, false, false);
        }

        assertArrayEquals(new float[] { 0.0f, 0.0f, -5.0f }, toView(camera, target), 0.01f);
    }
}
