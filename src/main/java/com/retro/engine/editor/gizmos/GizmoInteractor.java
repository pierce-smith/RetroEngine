package com.retro.engine.editor.gizmos;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.graphics.IRenderBackend;

/**
 * Single-Responsibility Interactor Delegate for 3D Gizmo GPU Color-ID Axis Picking,
 * Screen-to-World Mouse Displacement Deltas, and Grid / Angle / Scale Snapping.
 */
public class GizmoInteractor {

    private static double lastRotateAngle = 0.0;
    private static boolean isRotateStart = true;
    // Snapping is the user's default (snapByDefault, flipped by toggleSnap); holding Cmd/Ctrl inverts it for as long as
    // the key is down, so snapped scenes get free placement and free-placement users get snapping on demand
    private static boolean snapByDefault = true;
    private static boolean snapInverted = false;
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

    /** Called every frame by HotkeyRouter with whether Cmd/Ctrl is held. */
    public static void setSnapInverted(boolean inverted) { snapInverted = inverted; }
    public static void setSnapByDefault(boolean enable) { snapByDefault = enable; }
    public static boolean isSnapByDefault() { return snapByDefault; }
    public static void toggleSnap() { snapByDefault = !snapByDefault; }
    /** Whether the drag in progress snaps right now: the default, inverted while Cmd/Ctrl is held. */
    public static boolean isSnapEnabled() { return snapByDefault != snapInverted; }

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
        return isSnapEnabled() ? Math.round(offset / gridSnapSize) * gridSnapSize : offset;
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
            accumulateTranslation(deltaX, deltaY, activeGizmoAxis, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            if (activeGizmoAxis == GizmoAxis.CENTER || activeGizmoAxis == GizmoAxis.X) tc.posX = startPx + snapTranslation((float) accumDragX);
            if (activeGizmoAxis == GizmoAxis.CENTER || activeGizmoAxis == GizmoAxis.Y) tc.posY = startPy + snapTranslation((float) accumDragY);
            if (activeGizmoAxis == GizmoAxis.CENTER || activeGizmoAxis == GizmoAxis.Z) tc.posZ = startPz + snapTranslation((float) accumDragZ);
            return true;
        } else if (currentGizmoMode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            if (activeGizmoAxis == GizmoAxis.CENTER) {
                // Uniform scale: every axis keeps its proportion to the drag-start scale
                accumDragX += ds;
                float factor = isSnapEnabled() ? 1.0f + Math.round((float) accumDragX / scaleSnapStep) * scaleSnapStep : 1.0f + (float) accumDragX;
                factor = Math.max(0.01f, factor);
                tc.scaleX = Math.max(0.01f, startSx * factor);
                tc.scaleY = Math.max(0.01f, startSy * factor);
                tc.scaleZ = Math.max(0.01f, startSz * factor);
            } else if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += ds;
                if (isSnapEnabled()) tc.scaleX = Math.max(0.01f, startSx + Math.round((float) accumDragX / scaleSnapStep) * scaleSnapStep);
                else tc.scaleX = Math.max(0.01f, startSx + (float) accumDragX);
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += ds;
                if (isSnapEnabled()) tc.scaleY = Math.max(0.01f, startSy + Math.round((float) accumDragY / scaleSnapStep) * scaleSnapStep);
                else tc.scaleY = Math.max(0.01f, startSy + (float) accumDragY);
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += ds;
                if (isSnapEnabled()) tc.scaleZ = Math.max(0.01f, startSz + Math.round((float) accumDragZ / scaleSnapStep) * scaleSnapStep);
                else tc.scaleZ = Math.max(0.01f, startSz + (float) accumDragZ);
            }
            return true;
        } else if (currentGizmoMode == GizmoMode.ROTATE) {
            if (activeGizmoAxis == GizmoAxis.CENTER) return false;
            double angleDelta = nextRotateAngleDelta(refX, refY, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            if (Double.isNaN(angleDelta)) return true;

            if (activeGizmoAxis == GizmoAxis.X) {
                accumDragX += angleDelta;
                if (isSnapEnabled()) tc.rotX = (startRx + Math.round((float) accumDragX / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotX = (startRx + (float) accumDragX) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Y) {
                accumDragY += angleDelta;
                if (isSnapEnabled()) tc.rotY = (startRy + Math.round((float) accumDragY / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotY = (startRy + (float) accumDragY) % 360.0f;
            } else if (activeGizmoAxis == GizmoAxis.Z) {
                accumDragZ += angleDelta;
                if (isSnapEnabled()) tc.rotZ = (startRz + Math.round((float) accumDragZ / angleSnapStep) * angleSnapStep) % 360.0f;
                else tc.rotZ = (startRz + (float) accumDragZ) % 360.0f;
            }
            return true;
        }
        return false;
    }

    /**
     * Adds this mouse move's world-space displacement to the drag accumulators: along the active axis's projected screen
     * direction for X / Y / Z (only that axis's accumulator moves), or in the camera's view plane for CENTER.
     */
    private static void accumulateTranslation(double deltaX, double deltaY, GizmoAxis axis, float wx, float wy, float wz,
                                              ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float ndcDx = (float) (deltaX / 1280.0 * 2.0);
        float ndcDy = (float) (-deltaY / 720.0 * 2.0);
        if (axis == GizmoAxis.CENTER) {
            // Free move in the camera's view plane: the design-NDC mouse delta unprojected at the gizmo's depth
            float[] d = GizmoMath.screenPlaneDelta(wx, wy, wz, ndcDx, ndcDy, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            accumDragX += d[0];
            accumDragY += d[1];
            accumDragZ += d[2];
            return;
        }
        float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pA = GizmoMath.projectWorldToNdc(wx + (axis == GizmoAxis.X ? 1.0f : 0.0f), wy + (axis == GizmoAxis.Y ? 1.0f : 0.0f),
                wz + (axis == GizmoAxis.Z ? 1.0f : 0.0f), cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float uX = pA[0] - p0[0], uY = pA[1] - p0[1];
        float len = (float) Math.hypot(uX, uY);
        if (len > 0.0001f) { uX /= len; uY /= len; }
        float move = (ndcDx * uX + ndcDy * uY) * 10.0f;
        if (axis == GizmoAxis.X) accumDragX += move;
        else if (axis == GizmoAxis.Y) accumDragY += move;
        else if (axis == GizmoAxis.Z) accumDragZ += move;
    }

    /**
     * Degrees the cursor turned (counter-clockwise on screen) around the gizmo anchor since the last move, or NaN on the
     * first move of a rotate drag, which only records the starting angle.
     */
    private static double nextRotateAngleDelta(double refX, double refY, float wx, float wy, float wz,
                                               ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float[] p0 = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float ndcX = (float) (refX / 1280.0 * 2.0 - 1.0);
        float ndcY = (float) (1.0 - refY / 720.0 * 2.0);
        double currentAngle = Math.atan2(ndcY - p0[1], ndcX - p0[0]);
        if (isRotateStart) {
            lastRotateAngle = currentAngle;
            isRotateStart = false;
            return Double.NaN;
        }
        double angleDelta = Math.toDegrees(currentAngle - lastRotateAngle);
        // atan2 wraps at +-180 deg: take the short way round instead of a full-turn jump in the stored angle
        if (angleDelta > 180.0) angleDelta -= 360.0;
        else if (angleDelta < -180.0) angleDelta += 360.0;
        lastRotateAngle = currentAngle;
        return angleDelta;
    }

    // Bone drag: the bone's local transform at drag start; every move rebuilds the new local from it plus the accumulators
    private static final float[] boneStartLocal = new float[16];

    public static void startBoneDrag(float[] localTransform) {
        if (localTransform != null && localTransform.length == 16) System.arraycopy(localTransform, 0, boneStartLocal, 0, 16);
        resetAccumulatedDrag();
    }

    /**
     * Bone counterpart of {@link #handleGizmoDrag}: writes the dragged bone's new local transform into {@code outLocal}.
     * Translate moves the joint along world axes (or the view plane), re-expressed in the parent's space; Rotate turns
     * the bone about a world axis through its joint, in the direction the cursor circles on screen; Scale scales the
     * bone along its own local axes (CENTER uniformly). {@code parentWorld} is the world matrix of the space the bone's
     * local transform lives in (its parent bone's world matrix, or the entity's model matrix for a root bone), and
     * (wx, wy, wz) is the joint's world position the gizmo is drawn at.
     */
    public static boolean handleBoneDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis axis, GizmoMode mode,
                                         float[] parentWorld, float wx, float wy, float wz, ViewportCamera cameraController,
                                         float camPanX, float camPanY, float camZoom, float camPitch, float camYaw, float[] outLocal) {
        if (axis == GizmoAxis.NONE || parentWorld == null || outLocal == null) return false;
        System.arraycopy(boneStartLocal, 0, outLocal, 0, 16);

        if (mode == GizmoMode.TRANSLATE) {
            accumulateTranslation(deltaX, deltaY, axis, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            float dx = snapTranslation((float) accumDragX), dy = snapTranslation((float) accumDragY), dz = snapTranslation((float) accumDragZ);
            // World displacement -> parent space through the inverse of the parent's linear part
            float[] inv = invertLinear3x3(parentWorld);
            if (inv == null) return false;
            outLocal[12] += inv[0] * dx + inv[3] * dy + inv[6] * dz;
            outLocal[13] += inv[1] * dx + inv[4] * dy + inv[7] * dz;
            outLocal[14] += inv[2] * dx + inv[5] * dy + inv[8] * dz;
            return true;
        } else if (mode == GizmoMode.ROTATE) {
            if (axis == GizmoAxis.CENTER) return false;
            double angleDelta = nextRotateAngleDelta(refX, refY, wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            if (!Double.isNaN(angleDelta)) accumDragX += angleDelta;
            float degrees = isSnapEnabled() ? Math.round((float) accumDragX / angleSnapStep) * angleSnapStep : (float) accumDragX;

            // Counter-clockwise on screen is a positive turn about the axis pointing at the viewer
            float ax = axis == GizmoAxis.X ? 1.0f : 0.0f, ay = axis == GizmoAxis.Y ? 1.0f : 0.0f, az = axis == GizmoAxis.Z ? 1.0f : 0.0f;
            float[] back = GizmoMath.viewBackAxis(cameraController, camPitch, camYaw);
            if (ax * back[0] + ay * back[1] + az * back[2] < 0.0f) degrees = -degrees;

            // World rotation R about the joint, re-expressed in parent space: local' = A^-1 * R * A * local (A = the
            // parent's linear part). The joint is the pivot, so the local translation is unchanged.
            float[] inv = invertLinear3x3(parentWorld);
            if (inv == null) return false;
            float[] r = rotationMatrix3x3(ax, ay, az, (float) Math.toRadians(degrees));
            float[] parentSpace = new float[9];
            for (int col = 0; col < 3; col++) {
                // Column col of R * A, then A^-1 * that
                float px = parentWorld[col * 4], py = parentWorld[col * 4 + 1], pz = parentWorld[col * 4 + 2];
                float rx = r[0] * px + r[3] * py + r[6] * pz;
                float ry = r[1] * px + r[4] * py + r[7] * pz;
                float rz = r[2] * px + r[5] * py + r[8] * pz;
                parentSpace[col * 3] = inv[0] * rx + inv[3] * ry + inv[6] * rz;
                parentSpace[col * 3 + 1] = inv[1] * rx + inv[4] * ry + inv[7] * rz;
                parentSpace[col * 3 + 2] = inv[2] * rx + inv[5] * ry + inv[8] * rz;
            }
            premultiplyLinear(outLocal, parentSpace);
            return true;
        } else if (mode == GizmoMode.SCALE) {
            float ds = (float) (deltaX - deltaY) * 0.01f;
            float[] factors = { 1.0f, 1.0f, 1.0f };
            if (axis == GizmoAxis.CENTER || axis == GizmoAxis.X) accumDragX += ds;
            else if (axis == GizmoAxis.Y) accumDragY += ds;
            else if (axis == GizmoAxis.Z) accumDragZ += ds;
            float fx = scaleFactor(accumDragX), fy = scaleFactor(accumDragY), fz = scaleFactor(accumDragZ);
            if (axis == GizmoAxis.CENTER) { factors[0] = fx; factors[1] = fx; factors[2] = fx; }
            else if (axis == GizmoAxis.X) factors[0] = fx;
            else if (axis == GizmoAxis.Y) factors[1] = fy;
            else if (axis == GizmoAxis.Z) factors[2] = fz;
            // Post-multiplying by diag(factors) scales the bone's own axes (the local matrix's columns)
            for (int col = 0; col < 3; col++) {
                outLocal[col * 4] *= factors[col];
                outLocal[col * 4 + 1] *= factors[col];
                outLocal[col * 4 + 2] *= factors[col];
            }
            return true;
        }
        return false;
    }

    private static float scaleFactor(double accum) {
        float offset = isSnapEnabled() ? Math.round((float) accum / scaleSnapStep) * scaleSnapStep : (float) accum;
        return Math.max(0.01f, 1.0f + offset);
    }

    /** Column-major 3x3 rotation of {@code rad} about unit axis (x, y, z). */
    private static float[] rotationMatrix3x3(float x, float y, float z, float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad), t = 1.0f - c;
        return new float[] {
            t * x * x + c,     t * x * y + s * z, t * x * z - s * y,
            t * x * y - s * z, t * y * y + c,     t * y * z + s * x,
            t * x * z + s * y, t * y * z - s * x, t * z * z + c
        };
    }

    /** Pre-multiplies the 3x3 linear part of column-major 4x4 {@code m} by column-major 3x3 {@code r}. */
    private static void premultiplyLinear(float[] m, float[] r) {
        for (int col = 0; col < 3; col++) {
            float vx = m[col * 4], vy = m[col * 4 + 1], vz = m[col * 4 + 2];
            m[col * 4] = r[0] * vx + r[3] * vy + r[6] * vz;
            m[col * 4 + 1] = r[1] * vx + r[4] * vy + r[7] * vz;
            m[col * 4 + 2] = r[2] * vx + r[5] * vy + r[8] * vz;
        }
    }

    /**
     * Applies one bone-drag step to {@code animator}: the world-space gizmo delta becomes bone {@code boneIndex}'s new
     * local pose through the inverse of {@code parentWorld} ({@link #handleBoneDrag}), which is stored as its edit pose.
     * The palette is then rebuilt over the whole hierarchy (children inherit the new parent world) and flagged dirty,
     * so the GPU palette re-uploads and the mesh deforms in the viewport this frame.
     */
    public static boolean applyBoneDrag(double deltaX, double deltaY, double refX, double refY, GizmoAxis axis, GizmoMode mode,
                                        AnimatorComponent animator, int boneIndex, float[] parentWorld, float wx, float wy, float wz,
                                        ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch,
                                        float camYaw, SceneWorld sceneWorld, float[] scratchLocal) {
        if (animator == null || !handleBoneDrag(deltaX, deltaY, refX, refY, axis, mode, parentWorld, wx, wy, wz,
                cameraController, camPanX, camPanY, camZoom, camPitch, camYaw, scratchLocal)) {
            return false;
        }
        animator.setEditPose(boneIndex, scratchLocal);
        animator.bindPoseApplied = false;
        if (sceneWorld != null) sceneWorld.refreshEditPose(animator);
        animator.setDirty(true);
        return true;
    }

    /** Column-major 3x3 inverse of the linear part of column-major 4x4 {@code m}, or null when singular. */
    private static float[] invertLinear3x3(float[] m) {
        float a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], k = m[10];
        float A = e * k - f * h, B = f * g - d * k, C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) return null;
        float inv = 1.0f / det;
        return new float[] {
            A * inv, B * inv, C * inv,
            (c * h - b * k) * inv, (a * k - c * g) * inv, (b * g - a * h) * inv,
            (b * f - c * e) * inv, (c * d - a * f) * inv, (a * e - b * d) * inv
        };
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
