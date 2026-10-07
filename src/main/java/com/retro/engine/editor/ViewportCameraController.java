package com.retro.engine.editor;

/**
 * ViewportCameraController handles camera orbit, pan, and zoom in the 3D Viewport.
 * Keyboard WASDQE camera movement is active when holding Right Mouse Button (RMB) or Middle Mouse Button (MMB).
 */
public class ViewportCameraController {

    private float panX = 0.0f;
    private float panY = 0.25f;
    private float zoom = -5.0f;
    private float pitch = 0.0f;
    private float yaw = 0.0f;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private boolean isDraggingRmb = false;
    private boolean isDraggingMmb = false;
    private boolean isDraggingLmb = false;

    public void update(float deltaTime, boolean isWDown, boolean isSDown, boolean isADown, boolean isDDown, boolean isQDown, boolean isEDown) {
        // Camera WASDQE navigation is gated to RMB / MMB flight mode
        if (isDraggingRmb || isDraggingMmb) {
            float speed = 2.5f * deltaTime;
            if (isWDown) panY += speed;
            if (isSDown) panY -= speed;
            if (isADown) panX -= speed;
            if (isDDown) panX += speed;
            if (isQDown) zoom += speed * 3.0f;
            if (isEDown) zoom -= speed * 3.0f;
        }

        clamp();
    }

    public void onMouseButton(int button, int action, double mouseX, double mouseY) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (button == 1) { // RMB
            isDraggingRmb = (action == 1);
        } else if (button == 2) { // MMB
            isDraggingMmb = (action == 1);
        } else if (button == 0) { // LMB
            isDraggingLmb = (action == 1);
        }
    }

    public void onCursorPos(double mouseX, double mouseY, boolean isShiftDown, boolean isAltDown) {
        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if ((isDraggingRmb && !isShiftDown) || (isDraggingLmb && isAltDown)) {
            // Orbit Yaw & Pitch
            yaw += (float) dx * 0.35f;
            pitch -= (float) dy * 0.35f;
        } else if (isDraggingMmb || (isDraggingRmb && isShiftDown)) {
            // Pan Horizontal & Vertical
            panX += (float) dx * 0.005f;
            panY -= (float) dy * 0.005f;
        }

        clamp();
    }

    public void onScroll(double yoffset) {
        zoom += (float) yoffset * 0.5f;
        clamp();
    }

    public void reset() {
        panX = 0.0f;
        panY = 0.25f;
        zoom = -5.0f;
        pitch = 0.0f;
        yaw = 0.0f;
    }

    private void clamp() {
        pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
        zoom = Math.max(-60.0f, Math.min(-0.8f, zoom));
    }

    public float getPanX() { return panX; }
    public float getPanY() { return panY; }
    public float getZoom() { return zoom; }
    public float getPitch() { return pitch; }
    public float getYaw() { return yaw; }
    public boolean isDraggingRmb() { return isDraggingRmb; }
    public boolean isDraggingMmb() { return isDraggingMmb; }
}
