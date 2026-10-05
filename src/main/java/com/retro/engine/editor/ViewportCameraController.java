package com.retro.engine.editor;

/**
 * ViewportCameraController provides Unity-style camera navigation in the 3D Viewport:
 * - Orbit / Rotate (Yaw & Pitch): Right Mouse Drag OR Alt + Left Mouse Drag
 * - Pan (X / Y): Middle Mouse Drag OR Shift + Right Mouse Drag
 * - Zoom (In / Out): Mouse Scroll Wheel OR Q / E Keys
 * - Focus / Reset: 'F' Key
 * - WASD / Arrow Keys: Camera panning and navigation
 */
public class ViewportCameraController {

    private float panX = 0.0f;
    private float panY = 0.25f; // Centered in 3D viewport height
    private float zoom = -5.0f;
    private float pitch = 0.0f;
    private float yaw = 0.0f;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private boolean isDraggingRmb = false;
    private boolean isDraggingMmb = false;
    private boolean isDraggingLmb = false;

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown) {
        float speed = 2.5f * deltaTime;

        if (isWDown) panY += speed;
        if (isSDown) panY -= speed;
        if (isADown) panX -= speed;
        if (isDDown) panX += speed;
        if (isQDown) zoom += speed * 3.0f;
        if (isEDown) zoom -= speed * 3.0f;

        clamp();
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
            panX += (float) dx * 0.005f;
            panY -= (float) dy * 0.005f;
        }

        clamp();
    }

    public void onScroll(double yoffset) {
        zoom += (float) yoffset * 0.5f;
        clamp();
    }

    public void onScroll(double xoffset, double yoffset) {
        zoom += (float) yoffset * 0.5f;
        clamp();
    }

    public void reset() {
        panX = 0.0f;
        panY = 0.25f;
        zoom = -5.0f;
        pitch = 0.0f;
        yaw = 0.0f;
        System.out.println("[Camera] Reset Viewport Camera to Default Position.");
    }

    private void clamp() {
        if (zoom > -0.8f) zoom = -0.8f;
        if (zoom < -60.0f) zoom = -60.0f;
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;
    }

    public float getPanX() { return panX; }
    public float getPanY() { return panY; }
    public float getZoom() { return zoom; }
    public float getPitch() { return pitch; }
    public float getYaw() { return yaw; }
}
