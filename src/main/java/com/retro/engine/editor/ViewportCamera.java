package com.retro.engine.editor;

/**
 * ViewportCamera provides Unity-style camera navigation in the 3D Viewport:
 * - Camera WASDQE flight navigation executes ONLY when Right Mouse Button (RMB) or Middle Mouse Button (MMB) is held.
 * - W / S: Move Forward / Backward (Zoom / Depth Flight)
 * - A / D: Strafe Left / Right
 * - Q / E: Elevation Down / Up
 * - Orbit / Rotate (Yaw & Pitch): Right Mouse Drag OR Alt + Left Mouse Drag
 * - Pan (X / Y): Middle Mouse Drag OR Shift + Right Mouse Drag
 * - Zoom (In / Out): Mouse Scroll Wheel
 * - Focus / Reset: 'F' Key
 */
public class ViewportCamera {

    // Default Unity-style Elevated Viewport Camera Angle
    private float panX = 0.0f;
    private float panY = -1.2f;  // Elevated camera height
    private float zoom = -8.5f;  // Pulled back perspective
    private float pitch = 22.0f; // Angled down toward ground origin
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

    // Play mode: the bound camera entity's world-to-view matrix (inverse of its world transform) and eye, used
    // verbatim instead of the orbit parameters, which cannot represent roll or arbitrary entity rotations
    private final float[] entityView = new float[16];
    private float entityEyeX, entityEyeY, entityEyeZ;
    private boolean entityViewActive = false;

    // Play-mode lock: the view follows the scene's Main Camera and editor navigation is disabled
    private boolean playLocked = false;
    private float savedPanX, savedPanY, savedZoom, savedPitch, savedYaw;

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown) {
        update(deltaTime, isWDown, isSDown, isADown, isDDown, isQDown, isEDown, (isDraggingRmb || isDraggingMmb));
    }

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown, boolean isRmbOrMmbActive) {
        if (playLocked) return;
        if (isRmbOrMmbActive || isDraggingRmb || isDraggingMmb) {
            float speed = 3.5f * deltaTime;
            
            // W / S: Move FORWARD / BACKWARD along camera view depth
            if (isWDown) zoom += speed * 4.0f;
            if (isSDown) zoom -= speed * 4.0f;

            // A / D: Strafe LEFT / RIGHT
            if (isADown) panX += speed;
            if (isDDown) panX -= speed;

            // Q / E: Elevation DOWN / UP
            if (isQDown) panY -= speed;
            if (isEDown) panY += speed;

            clamp();
        }
    }

    public void onMouseButton(int button, int action, double x, double y) {
        if (playLocked) return;
        if (action == 1) { // PRESS
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

    public void onCursorPos(double x, double y, boolean isShiftDown, boolean isAltDown) {
        if (playLocked) return;
        double dx = x - lastMouseX;
        double dy = y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;

        // Orbit / Rotate (RMB Drag OR Alt + LMB Drag)
        if (isDraggingRmb && !isShiftDown) {
            yaw += (float) dx * 0.35f;
            pitch -= (float) dy * 0.35f;
        } else if (isDraggingLmb && isAltDown) {
            yaw += (float) dx * 0.35f;
            pitch -= (float) dy * 0.35f;
        }
        // Pan X/Y (MMB Drag OR Shift + RMB Drag)
        else if (isDraggingMmb || (isDraggingRmb && isShiftDown)) {
            panX += (float) dx * 0.008f;
            panY -= (float) dy * 0.008f;
        }

        clamp();
    }

    public void onScroll(double yoffset) {
        if (playLocked) return;
        zoom += (float) yoffset * 0.75f;
        clamp();
    }

    public void onScroll(double xoffset, double yoffset) {
        if (playLocked) return;
        zoom += (float) yoffset * 0.75f;
        clamp();
    }

    public void reset() {
        if (playLocked) return;
        panX = 0.0f;
        panY = -1.2f;
        zoom = -8.5f;
        pitch = 22.0f;
        yaw = -35.0f;
        System.out.println("[Camera] Reset Viewport Camera to Unity Startup View.");
    }

    /** Enters Play-mode lock, remembering the editor pose for {@link #unlockFromPlay()}. Navigation input is ignored while locked. */
    public void lockForPlay() {
        if (playLocked) return;
        savedPanX = panX; savedPanY = panY; savedZoom = zoom; savedPitch = pitch; savedYaw = yaw;
        isDraggingRmb = isDraggingMmb = isDraggingLmb = false;
        playLocked = true;
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

    private void clamp() {
        if (zoom > -0.8f) zoom = -0.8f;
        if (zoom < -150.0f) zoom = -150.0f;
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;
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
