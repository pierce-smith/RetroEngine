package com.retro.engine.editor;

import com.retro.engine.core.GamepadManager;

/**
 * ViewportCamera is a free-flying camera for the 3D Viewport, with no limit on where it can go:
 * - Camera WASDQE flight navigation executes ONLY when Right Mouse Button (RMB) or Middle Mouse Button (MMB) is held.
 * - W / S: Fly Forward / Backward along the view direction
 * - A / D: Strafe Left / Right
 * - Q / E: Elevation Down / Up
 * - Look (Yaw & Pitch, in place): Right Mouse Drag
 * - Orbit the selected entity: Shift + Right Mouse Drag OR Alt + Left Mouse Drag
 * - Pan (X / Y): Middle Mouse Drag, or Shift + Right Mouse Drag with nothing selected
 * - Dolly (In / Out): Mouse Scroll Wheel
 * - Frame Selected: 'F' Key
 * - Reset to Startup View: Shift + 'F'
 * Gamepad (no button hold required):
 * - Left Stick: Strafe (X) / Forward-Backward flight (Y), same axes as WASD
 * - Right Stick: Look (Yaw & Pitch, in place)
 * - Right / Left Trigger: Analog Zoom In / Out
 * - Right / Left Bumper: Elevation Up / Down
 */
public class ViewportCamera {

    // Default Unity-style Elevated Viewport Camera Angle
    private float panX = 0.0f;
    private float panY = -1.2f;  // Elevated camera height
    private float zoom = -8.5f;  // Pulled back perspective
    private float pitch = -22.0f; // Negative pitch tilts the view down toward the ground origin (eye ~4.3 above it)
    private float yaw = -35.0f; // Angled slightly to highlight 3D depth

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private boolean isDraggingRmb = false;
    private boolean isDraggingMmb = false;
    private boolean isDraggingLmb = false;

    // Projection (backends and culling read these; a Play-mode Main Camera overrides them)
    public static final float EDITOR_FOV = 50.0f, EDITOR_NEAR = 0.1f, EDITOR_FAR = 500.0f;
    private float fov = EDITOR_FOV, near = EDITOR_NEAR, far = EDITOR_FAR;
    private float sceneAspect = 712.0f / 488.0f;

    private final float[] rotateScratch = new float[3];

    // Play mode: the bound camera entity's world-to-view matrix (inverse of its world transform) and eye, used
    // verbatim instead of the orbit parameters, which cannot represent roll or arbitrary entity rotations
    private final float[] entityView = new float[16];
    private float entityEyeX, entityEyeY, entityEyeZ;
    private boolean entityViewActive = false;

    // Play-mode lock: the view follows the scene's Main Camera and editor navigation is disabled
    private boolean playLocked = false;
    private float savedPanX, savedPanY, savedZoom, savedPitch, savedYaw;

    // Frame Selected: world-space pivot and distance the view glides toward; targets are re-derived each tick so an
    // orbit mid-glide still ends centered. Any manual navigation cancels the glide.
    private static final float FOCUS_SPEED = 12.0f;
    private boolean focusActive = false;
    private float focusX, focusY, focusZ, focusDistance;

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown) {
        update(deltaTime, isWDown, isSDown, isADown, isDDown, isQDown, isEDown, (isDraggingRmb || isDraggingMmb));
    }

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown, boolean isRmbOrMmbActive) {
        if (playLocked) return;
        tickFocus(deltaTime);
        if (isRmbOrMmbActive || isDraggingRmb || isDraggingMmb) {
            float speed = 3.5f * deltaTime;
            if (isWDown || isSDown || isADown || isDDown || isQDown || isEDown) focusActive = false;
            
            // W / S: Move FORWARD / BACKWARD along camera view depth
            if (isWDown) zoom += speed * 4.0f;
            if (isSDown) zoom -= speed * 4.0f;

            // A / D: Strafe LEFT / RIGHT
            if (isADown) panX += speed;
            if (isDDown) panX -= speed;

            // Q / E: Elevation DOWN / UP
            if (isQDown) panY -= speed;
            if (isEDown) panY += speed;
        }
    }

    public void applyGamepad(GamepadManager pad, float deltaTime) {
        if (playLocked || pad == null || !pad.isConnected()) return;
        float speed = 3.5f * deltaTime;

        // Left Stick: strafe like A/D, forward/back like W/S (stick Y is +1 when pushed down)
        panX -= pad.getAxis(GamepadManager.AXIS_LEFT_X) * speed;
        zoom -= pad.getAxis(GamepadManager.AXIS_LEFT_Y) * speed * 4.0f;

        // Right Stick: look; pushing up looks up, matching an upward mouse drag
        look(-pad.getAxis(GamepadManager.AXIS_RIGHT_X) * 120.0f * deltaTime, -pad.getAxis(GamepadManager.AXIS_RIGHT_Y) * 90.0f * deltaTime);

        // Triggers: analog zoom in (RT) / out (LT)
        zoom += (pad.getTrigger(GamepadManager.AXIS_RIGHT_TRIGGER) - pad.getTrigger(GamepadManager.AXIS_LEFT_TRIGGER)) * speed * 4.0f;

        // Bumpers: elevation like Q/E
        if (pad.isButtonPressed(GamepadManager.BUTTON_RIGHT_BUMPER)) panY += speed;
        if (pad.isButtonPressed(GamepadManager.BUTTON_LEFT_BUMPER)) panY -= speed;
    }

    public void onMouseButton(int button, int action, double x, double y) {
        if (playLocked) return;
        if (action == 1) { // PRESS
            if (button == 1 || button == 2) focusActive = false;
            lastMouseX = x;
            lastMouseY = y;
            if (button == 1) isDraggingRmb = true;
            if (button == 2) isDraggingMmb = true;
            if (button == 0) isDraggingLmb = true;
        } else if (action == 0) { // RELEASE
            if (button == 1) isDraggingRmb = false;
            if (button == 2) isDraggingMmb = false;
            if (button == 0) isDraggingLmb = false;
        }
    }

    /** Ends any RMB orbit/fly capture, e.g. when the RMB release was swallowed by a UI overlay. */
    public void releaseRightMouse() {
        isDraggingRmb = false;
    }

    /**
     * Applies a cursor move to any active drag. {@code orbitPivot} is the world position of the selected entity, or
     * null with nothing selected: orbiting needs a pivot, so without one Shift + RMB pans and Alt + LMB does nothing.
     */
    public void onCursorPos(double x, double y, boolean isShiftDown, boolean isAltDown, float[] orbitPivot) {
        if (playLocked) return;
        double dx = x - lastMouseX;
        double dy = y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;

        boolean orbitDrag = (isDraggingRmb && isShiftDown) || (isDraggingLmb && isAltDown);
        if (orbitDrag && orbitPivot != null) {
            // The scene follows the cursor around the pivot, so yaw runs opposite to look
            rotateAbout(orbitPivot[0], orbitPivot[1], orbitPivot[2], (float) dx * 0.35f, (float) -dy * 0.35f);
        } else if (isDraggingRmb && !isShiftDown) {
            look((float) -dx * 0.35f, (float) -dy * 0.35f);
        } else if (isDraggingMmb || isDraggingRmb) {
            focusActive = false;
            panX += (float) dx * 0.008f;
            panY -= (float) dy * 0.008f;
        }
    }

    /** Turns the view in place: positive yaw turns left, positive pitch looks up. */
    private void look(float deltaYaw, float deltaPitch) {
        if (deltaYaw == 0.0f && deltaPitch == 0.0f) return;
        computeEyePosition(rotateScratch);
        rotateAbout(rotateScratch[0], rotateScratch[1], rotateScratch[2], deltaYaw, deltaPitch);
    }

    /** Changes yaw/pitch while the world point (px, py, pz) keeps its view-space position: t' = R * P + t - R' * P. */
    private void rotateAbout(float px, float py, float pz, float deltaYaw, float deltaPitch) {
        rotateIntoView(px, py, pz, rotateScratch);
        float viewX = rotateScratch[0] + panX, viewY = rotateScratch[1] + panY, viewZ = rotateScratch[2] + zoom;
        yaw += deltaYaw;
        pitch = Math.max(-89.0f, Math.min(89.0f, pitch + deltaPitch));
        rotateIntoView(px, py, pz, rotateScratch);
        panX = viewX - rotateScratch[0];
        panY = viewY - rotateScratch[1];
        zoom = viewZ - rotateScratch[2];
    }

    /** Applies the view rotation R(yaw, pitch) to a world-space vector. */
    private void rotateIntoView(float x, float y, float z, float[] out) {
        float radYaw = (float) Math.toRadians(yaw), radPitch = (float) Math.toRadians(pitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        out[0] = cy * x - sy * z;
        out[1] = sy * sp * x + cp * y + cy * sp * z;
        out[2] = sy * cp * x - sp * y + cy * cp * z;
    }

    public void onScroll(double yoffset) {
        if (playLocked) return;
        focusActive = false;
        zoom += (float) yoffset * 0.75f;
    }

    public void onScroll(double xoffset, double yoffset) {
        if (playLocked) return;
        focusActive = false;
        zoom += (float) yoffset * 0.75f;
    }

    public void reset() {
        if (playLocked) return;
        focusActive = false;
        panX = 0.0f;
        panY = -1.2f;
        zoom = -8.5f;
        pitch = -22.0f;
        yaw = -35.0f;
        System.out.println("[Camera] Reset Viewport Camera to Unity Startup View.");
    }

    /** Enters Play-mode lock, remembering the editor pose for {@link #unlockFromPlay()}. Navigation input is ignored while locked. */
    public void lockForPlay() {
        if (playLocked) return;
        savedPanX = panX; savedPanY = panY; savedZoom = zoom; savedPitch = pitch; savedYaw = yaw;
        isDraggingRmb = isDraggingMmb = isDraggingLmb = false;
        focusActive = false;
        playLocked = true;
    }

    /**
     * Frame Selected: glides the view (keeping the current yaw/pitch) until the world-space point
     * ({@code x}, {@code y}, {@code z}) sits at the screen center, {@code distance} units in front of the eye.
     * The glide advances in {@link #update}; manual navigation cancels it.
     */
    public void focusOn(float x, float y, float z, float distance) {
        if (playLocked) return;
        focusX = x; focusY = y; focusZ = z;
        focusDistance = Math.max(0.8f, distance);
        focusActive = true;
    }

    public boolean isFocusing() { return focusActive; }

    /** Exponentially eases pan/zoom toward the focus pose: view-space pivot R * P + t = (0, 0, -distance). */
    private void tickFocus(float deltaTime) {
        if (!focusActive) return;
        float radYaw = (float) Math.toRadians(yaw), radPitch = (float) Math.toRadians(pitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        float targetPanX = -(cy * focusX - sy * focusZ);
        float targetPanY = -(sy * sp * focusX + cp * focusY + cy * sp * focusZ);
        float targetZoom = -focusDistance - (sy * cp * focusX - sp * focusY + cy * cp * focusZ);

        float t = 1.0f - (float) Math.exp(-FOCUS_SPEED * deltaTime);
        panX += (targetPanX - panX) * t;
        panY += (targetPanY - panY) * t;
        zoom += (targetZoom - zoom) * t;

        if (Math.abs(targetPanX - panX) < 1e-3f && Math.abs(targetPanY - panY) < 1e-3f && Math.abs(targetZoom - zoom) < 1e-3f) {
            panX = targetPanX; panY = targetPanY; zoom = targetZoom;
            focusActive = false;
        }
    }

    /**
     * Binds a camera entity's view matrix (column-major, world-to-view: the inverse of its world transform), eye
     * position and projection. {@link #computeViewMatrix} returns this matrix verbatim until {@link #clearEntityView}
     * or {@link #unlockFromPlay}; the orbit parameters keep the pre-play editor pose.
     */
    public void snapToView(float[] view, float eyeX, float eyeY, float eyeZ, float fovDeg, float nearPlane, float farPlane) {
        System.arraycopy(view, 0, entityView, 0, 16);
        entityEyeX = eyeX; entityEyeY = eyeY; entityEyeZ = eyeZ;
        entityViewActive = true;
        fov = fovDeg; near = nearPlane; far = farPlane;
    }

    /** Falls back to the (locked) orbit pose, e.g. when the bound camera entity no longer exists. */
    public void clearEntityView() {
        entityViewActive = false;
    }

    /** True while the view comes from a camera entity's transform rather than the orbit parameters. */
    public boolean hasEntityView() { return entityViewActive; }

    /** Column-major world-to-view matrix: the bound entity view, else the orbit pose R(yaw, pitch) then pan/zoom. */
    public void computeViewMatrix(float[] out) {
        if (entityViewActive) {
            System.arraycopy(entityView, 0, out, 0, 16);
            return;
        }
        float radYaw = (float) Math.toRadians(yaw), radPitch = (float) Math.toRadians(pitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        out[0] = cy;    out[1] = sy * sp;  out[2] = sy * cp;   out[3] = 0.0f;
        out[4] = 0.0f;  out[5] = cp;       out[6] = -sp;       out[7] = 0.0f;
        out[8] = -sy;   out[9] = cy * sp;  out[10] = cy * cp;  out[11] = 0.0f;
        out[12] = panX; out[13] = panY;    out[14] = zoom;     out[15] = 1.0f;
    }

    /** World-space eye position matching {@link #computeViewMatrix} (-R^T * t for the orbit pose). */
    public void computeEyePosition(float[] out) {
        if (entityViewActive) {
            out[0] = entityEyeX; out[1] = entityEyeY; out[2] = entityEyeZ;
            return;
        }
        float radYaw = (float) Math.toRadians(yaw), radPitch = (float) Math.toRadians(pitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        out[0] = -(cy * panX + sy * sp * panY + sy * cp * zoom);
        out[1] = -(cp * panY - sp * zoom);
        out[2] = -(-sy * panX + cy * sp * panY + cy * cp * zoom);
    }

    /** Leaves Play-mode lock, restoring the pre-play editor pose and projection. */
    public void unlockFromPlay() {
        if (!playLocked) return;
        playLocked = false;
        entityViewActive = false;
        panX = savedPanX; panY = savedPanY; zoom = savedZoom; pitch = savedPitch; yaw = savedYaw;
        fov = EDITOR_FOV; near = EDITOR_NEAR; far = EDITOR_FAR;
        isDraggingRmb = isDraggingMmb = isDraggingLmb = false;
    }

    public float getPanX() { return panX; }
    public float getPanY() { return panY; }
    public float getZoom() { return zoom; }
    public float getPitch() { return pitch; }
    public float getYaw() { return yaw; }
    public float getFov() { return fov; }
    public float getNear() { return near; }
    public float getFar() { return far; }

    /** Projection aspect of the viewport this camera renders into (refreshed by the editor after each dock layout). */
    public float getSceneAspect() { return sceneAspect; }
    public void setSceneAspect(float aspect) { if (aspect > 0.0f) this.sceneAspect = aspect; }
    public boolean isPlayLocked() { return playLocked; }
    public boolean isDraggingRmb() { return isDraggingRmb; }
    public boolean isDraggingMmb() { return isDraggingMmb; }
}
