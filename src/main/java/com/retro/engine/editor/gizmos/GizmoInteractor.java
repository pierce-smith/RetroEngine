package com.retro.engine.editor.gizmos;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.components.transform.Transform;

/**
 * Single-Responsibility Interactor Delegate for 3D Gizmo GPU Color-ID Axis Picking,
 * Screen-to-World Mouse Displacement Deltas, and Grid / Angle / Scale Snapping.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;
    private static boolean isRotateStart = true;
    private static boolean snapEnabled = true;     // Snapping ENABLED by default
    private static float gridSnapSize = 0.5f;     // 0.5u translation steps
    private static float angleSnapStep = 15.0f;    // 15.0 deg rotation steps
    private static float scaleSnapStep = 0.25f;    // 0.25x scale steps

    // Unquantized accumulators for smooth continuous snapping without dropping deltas
    private static float startPx, startPy, startPz;
    private static float startRx, startRy, startRz;
    private static float startSx, startSy, startSz;
    private static double accumDragX = 0.0;
    private static double accumDragY = 0.0;
    private static double accumDragZ = 0.0;

    public static void setSnapEnabled(boolean enable) { snapEnabled = enable; }
    public static boolean isSnapEnabled() { return snapEnabled; }
    public static void toggleSnap() { snapEnabled = !snapEnabled; }

    public static float getGridSnapSize() { return gridSnapSize; }
    public static void setGridSnapSize(float size) { gridSnapSize = Math.max(0.05f, size); }

    public static float getAngleSnapStep() { return angleSnapStep; }
    public static void setAngleSnapStep(float step) { angleSnapStep = Math.max(1.0f, step); }

    public static float getScaleSnapStep() { return scaleSnapStep; }
    public static void setScaleSnapStep(float step) { scaleSnapStep = Math.max(0.05f, step); }

    public static void resetAccumulatedDrag() {
        accumDragX = 0.0;
        accumDragY = 0.0;
        accumDragZ = 0.0;
        lastRotateAngle = 0.0;
        isRotateStart = true;
    }

    private static float snapTranslation(float offset) {
        return snapEnabled ? Math.round(offset / gridSnapSize) * gridSnapSize : offset;
    }

    public static void startDrag(Transform tc) {
        if (tc != null) {
            startPx = tc.posX; startPy = tc.posY; startPz = tc.posZ;
            startRx = tc.rotX; startRy = tc.rotY; startRz = tc.rotZ;
            startSx = tc.scaleX; startSy = tc.scaleY; startSz = tc.scaleZ;
        }
        resetAccumulatedDrag();
    }

    public static void captureDragStart(Transform tc) {
        startDrag(tc);
    }

    /**
     * Maps a GPU Color-ID pick value to the gizmo handle drawn at that pixel (see {@link GizmoRenderer} PICK_ID_*).
     * Anything else (meshes, background, an unavailable readback) is {@link GizmoAxis#NONE}. Rotate has no center handle.
     */
    public static GizmoAxis axisForPickId(int pickId, GizmoMode currentGizmoMode) {
        return switch (pickId) {
            case GizmoRenderer.PICK_ID_AXIS_X -> GizmoAxis.X;
            case GizmoRenderer.PICK_ID_AXIS_Y -> GizmoAxis.Y;
            case GizmoRenderer.PICK_ID_AXIS_Z -> GizmoAxis.Z;
            case GizmoRenderer.PICK_ID_CENTER -> currentGizmoMode == GizmoMode.ROTATE ? GizmoAxis.NONE : GizmoAxis.CENTER;
            default -> GizmoAxis.NONE;
        };
    }

    /** True for any pick value in the gizmo handle range, including handles inactive in the current mode. */
    public static boolean isGizmoPickId(int pickId) {
        return (pickId & 0xFFFF0000) == IRenderBackend.PICK_OVERLAY_ID_BASE && !isBonePickId(pickId);
    }

    /** True for a skeleton joint drawn by {@link GizmoRenderer#renderSkeletons} (see {@link GizmoRenderer#bonePickId}). */
    public static boolean isBonePickId(int pickId) {
        return (pickId & (0xFFFF0000 | GizmoRenderer.BONE_PICK_FLAG)) == (IRenderBackend.PICK_OVERLAY_ID_BASE | GizmoRenderer.BONE_PICK_FLAG);
    }

    /** Bone index of a joint pick value, or -1 when {@code pickId} is not a joint. */
    public static int boneIndexForPickId(int pickId) {
        return isBonePickId(pickId) ? (pickId & GizmoRenderer.BONE_PICK_INDEX_MASK) : -1;
    }

    /** Skeleton pick slot (see ViewportPanel#bonePickSlotFor) of a joint pick value, or -1 when it is not a joint. */
    public static int boneSlotForPickId(int pickId) {
        return isBonePickId(pickId) ? ((pickId >>> GizmoRenderer.BONE_PICK_SLOT_SHIFT) & (GizmoRenderer.BONE_PICK_MAX_SLOTS - 1)) : -1;
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, float wx, float wy, float wz, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (activeGizmoAxis == GizmoAxis.NONE || selectedEntity == null) return false;
        Transform tc = selectedEntity.getTransform();
        if (tc == null) return false;

        if (currentGizmoMode == GizmoMode.TRANSLATE) {
            float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float axisLen = 1.0f;
            float[] pX = GizmoMath.projectWorldToNdc(wx + axisLen, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pY = GizmoMath.projectWorldToNdc(wx, wy + axisLen, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float[] pZ = GizmoMath.projectWorldToNdc(wx, wy, wz + axisLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

            float uXX = pX[0] - p0[0], uXY = pX[1] - p0[1];
            float uYX = pY[0] - p0[0], uYY = pY[1] - p0[1];
            float uZX = pZ[0] - p0[0], uZY = pZ[1] - p0[1];

            float lenX = (float) Math.hypot(uXX, uXY);
            float lenY = (float) Math.hypot(uYX, uYY);
            float lenZ = (float) Math.hypot(uZX, uZY);

            if (lenX > 0.0001f) { uXX /= lenX; uXY /= lenX; }
            if (lenY > 0.0001f) { uYX /= lenY; uYY /= lenY; }
            if (lenZ > 0.0001f) { uZX /= lenZ; uZY /= lenZ; }

            float ndcDx = (float) (deltaX / 1280.0 * 2.0);
            float ndcDy = (float) (-deltaY / 720.0 * 2.0);

            float moveX = (ndcDx * uXX + ndcDy * uXY) * 10.0f;
            float moveY = (ndcDx * uYX + ndcDy * uYY) * 10.0f;
            float moveZ = (ndcDx * uZX + ndcDy * uZY) * 10.0f;

            if (activeGizmoAxis == GizmoAxis.CENTER) {
                // Free move in the camera's view plane: the design-NDC mouse delta unprojected at the gizmo's depth
                float[] d = GizmoMath.screenPlaneDelta(wx, wy, wz, ndcDx, ndcDy,
                        cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
                accumDragX += d[0];
                accumDragY += d[1];
                accumDragZ += d[2];
                tc.posX = startPx + snapTranslation((float) accumDragX);
                tc.posY = startPy + snapTranslation((float) accumDragY);
                tc.posZ = startPz + snapTranslation((float) accumDragZ);
            } else if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += moveX;
                if (snapEnabled) tc.posX = startPx + Math.round((float) accumDragX / gridSnapSize) * gridSnapSize;
                else tc.posX = startPx + (float) accumDragX;
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += moveY;
                if (snapEnabled) tc.posY = startPy + Math.round((float) accumDragY / gridSnapSize) * gridSnapSize;
                else tc.posY = startPy + (float) accumDragY;
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += moveZ;
                if (snapEnabled) tc.posZ = startPz + Math.round((float) accumDragZ / gridSnapSize) * gridSnapSize;
                else tc.posZ = startPz + (float) accumDragZ;
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            if (activeGizmoAxis == GizmoAxis.CENTER) {
                // Uniform scale: every axis keeps its proportion to the drag-start scale
                accumDragX += ds;
                float factor = snapEnabled ? 1.0f + Math.round((float) accumDragX / scaleSnapStep) * scaleSnapStep : 1.0f + (float) accumDragX;
                factor = Math.max(0.01f, factor);
                tc.scaleX = Math.max(0.01f, startSx * factor);
                tc.scaleY = Math.max(0.01f, startSy * factor);
                tc.scaleZ = Math.max(0.01f, startSz * factor);
            } else if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += ds;
                if (snapEnabled) tc.scaleX = Math.max(0.01f, startSx + Math.round((float) accumDragX / scaleSnapStep) * scaleSnapStep);
                else tc.scaleX = Math.max(0.01f, startSx + (float) accumDragX);
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += ds;
                if (snapEnabled) tc.scaleY = Math.max(0.01f, startSy + Math.round((float) accumDragY / scaleSnapStep) * scaleSnapStep);
                else tc.scaleY = Math.max(0.01f, startSy + (float) accumDragY);
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += ds;
                if (snapEnabled) tc.scaleZ = Math.max(0.01f, startSz + Math.round((float) accumDragZ / scaleSnapStep) * scaleSnapStep);
                else tc.scaleZ = Math.max(0.01f, startSz + (float) accumDragZ);
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            if (activeGizmoAxis == GizmoAxis.CENTER) return false;
            float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
            float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
            double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
            if (isRotateStart) {
                lastRotateAngle = currentAngle;
                isRotateStart = false;
                return true;
            }
            double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
            // atan2 wraps at +-180 deg: take the short way round instead of a full-turn jump in the stored angle
            if (angleDelta > 180.0) angleDelta -= 360.0;
            else if (angleDelta < -180.0) angleDelta += 360.0;
            lastRotateAngle = currentAngle;

            if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += angleDelta;
                if (snapEnabled) tc.rotX = (startRx + Math.round((float) accumDragX / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotX = (startRx + (float) accumDragX) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += angleDelta;
                if (snapEnabled) tc.rotY = (startRy + Math.round((float) accumDragY / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotY = (startRy + (float) accumDragY) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += angleDelta;
                if (snapEnabled) tc.rotZ = (startRz + Math.round((float) accumDragZ / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotZ = (startRz + (float) accumDragZ) % 360.0f;
            }
            return true;
        }
        return false;
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        Transform tc = selectedEntity.getTransform();
        if (tc == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, tc.posX, tc.posY, tc.posZ, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, SceneWorld sceneManager, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (selectedEntity == null) return false;
        if (sceneManager != null) {
            float[] anchor = GizmoMath.computeGizmoAnchor(selectedEntity, sceneManager);
            return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, anchor[0], anchor[1], anchor[2], cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static boolean handleGizmoDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis activeGizmoAxis, GizmoMode currentGizmoMode, Entity selectedEntity, EditorWorkspace manager) {
        if (manager == null) return false;
        return handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, selectedEntity, manager.getSceneManager(), manager.getCameraController(), 0.0f, 0.25f, -5.0f, 0.0f, 0.0f);
    }
}
