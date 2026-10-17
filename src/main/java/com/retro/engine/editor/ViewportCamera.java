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

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown) {
        update(deltaTime, isWDown, isSDown, isADown, isDDown, isQDown, isEDown, (isDraggingRmb || isDraggingMmb));
    }

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown, boolean isRmbOrMmbActive) {
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

    public void onCursorPos(double x, double y, boolean isShiftDown, boolean isAltDown) {
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
        zoom += (float) yoffset * 0.75f;
        clamp();
    }

    public void onScroll(double xoffset, double yoffset) {
        zoom += (float) yoffset * 0.75f;
        clamp();
    }

    public void reset() {
        panX = 0.0f;
        panY = -1.2f;
        zoom = -8.5f;
        pitch = 22.0f;
        yaw = -35.0f;
        System.out.println("[Camera] Reset Viewport Camera to Unity Startup View.");
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
    public boolean isDraggingRmb() { return isDraggingRmb; }
    public boolean isDraggingMmb() { return isDraggingMmb; }
}
