package com.retro.engine.editor.gizmos;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.SplineComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ICommand;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.spline.SplineEvaluator;
import com.retro.engine.ui.UIGeometryBatch;

/**
 * Scene-viewport authoring for {@link SplineComponent}: draws the selected entity's curve, its knots and (for Bezier
 * curves) the tangent handles, and lets the user drag knots / handles in the camera plane. A finished drag commits the
 * knots and pushes one undoable command. Projection matches {@link ScriptDebugRenderer} (UI design space of the Scene
 * panel; press/drag coordinates are the 1280x720 reference space GizmoState uses). Drawing is allocation-free.
 */
public final class SplineGizmo {

    private static final float LINE_THICKNESS = 0.003f;
    private static final float NEAR_CLIP = 0.05f;
    private static final float MIN_X = -0.55f, MAX_X = 0.55f, MIN_Y = -0.50f, MAX_Y = 0.88f;
    private static final float KNOT_HALF_X = 0.0075f, KNOT_HALF_Y = 0.0133f;
    private static final double PICK_RADIUS_REF = 10.0;

    private static final int CURVE_RGB = 0x33DDFF;
    private static final int KNOT_RGB = 0xFFFFFF;
    private static final int ACTIVE_RGB = 0xFFCC22;
    private static final int HANDLE_RGB = 0xFF66AA;

    private static final float[] view = new float[16];
    private static final float[] pt = new float[3];
    private static final float[] proj = new float[3]; // uiX, uiY, depth
    private static float p00, p11;

    // Drag state
    private static SplineComponent dragSpline;
    private static int dragKnot = -1;
    /** 0 = knot, 1 = in handle, 2 = out handle. */
    private static int dragPart;
    private static float dragDepth;
    private static double lastRefX, lastRefY;
    private static byte[] dragBefore;
    private static int selectedKnot = -1;
    private static SplineComponent selectedSpline;

    private SplineGizmo() {}

    public static boolean isDragging() { return dragSpline != null; }

    // -----------------------------------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------------------------------

    public static void render(UIGeometryBatch.Buffer buffer, ViewportCamera camera, Entity selected) {
        SplineComponent s = (selected != null) ? selected.getComponent(SplineComponent.class) : null;
        if (buffer == null || camera == null || s == null) return;
        s.owner = selected;
        int n = s.getCount();
        if (n < 1) return;
        setupProjection(camera);
        float ox = 0, oy = 0, oz = 0;
        Transform tr = selected.getTransform();
        if (tr != null) { ox = tr.posX; oy = tr.posY; oz = tr.posZ; }

        // Curve polyline from the baked samples
        int samples = s.lutCount;
        float px = 0, py = 0, pz = 0;
        for (int i = 0; i < samples; i++) {
            SplineEvaluator.evaluate(s, n, s.lutT[i], pt, false);
            float x = pt[0] + ox, y = pt[1] + oy, z = pt[2] + oz;
            if (i > 0) drawLine(buffer, px, py, pz, x, y, z, CURVE_RGB);
            px = x; py = y; pz = z;
        }

        boolean bezier = s.curveType == SplineComponent.CurveType.BEZIER;
        for (int i = 0; i < n; i++) {
            float kx = s.points[i * 3] + ox, ky = s.points[i * 3 + 1] + oy, kz = s.points[i * 3 + 2] + oz;
            boolean active = (s == selectedSpline && i == selectedKnot);
            if (bezier) {
                for (int h = 0; h < 2; h++) {
                    int o = i * 6 + h * 3;
                    float hx = kx + s.handles[o], hy = ky + s.handles[o + 1], hz = kz + s.handles[o + 2];
                    drawLine(buffer, kx, ky, kz, hx, hy, hz, HANDLE_RGB);
                    drawSquare(buffer, hx, hy, hz, 0.7f, HANDLE_RGB);
                }
            }
            drawSquare(buffer, kx, ky, kz, active ? 1.3f : 1.0f, active ? ACTIVE_RGB : KNOT_RGB);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Interaction (called by GizmoState)
    // -----------------------------------------------------------------------------------------------------------------

    /** CPU hit-test of the selected spline's knots / handles; starts a drag and returns true on a hit. */
    public static boolean tryBeginDrag(double refX, double refY, Entity selected, ViewportCamera camera) {
        SplineComponent s = (selected != null) ? selected.getComponent(SplineComponent.class) : null;
        if (s == null || camera == null) return false;
        s.owner = selected;
        int n = s.getCount();
        setupProjection(camera);
        Transform tr = selected.getTransform();
        float ox = tr != null ? tr.posX : 0, oy = tr != null ? tr.posY : 0, oz = tr != null ? tr.posZ : 0;
        boolean bezier = s.curveType == SplineComponent.CurveType.BEZIER;

        double best = PICK_RADIUS_REF * PICK_RADIUS_REF;
        int bestKnot = -1, bestPart = 0;
        float bestDepth = 0;
        for (int i = 0; i < n; i++) {
            float kx = s.points[i * 3] + ox, ky = s.points[i * 3 + 1] + oy, kz = s.points[i * 3 + 2] + oz;
            for (int part = 0; part < (bezier ? 3 : 1); part++) {
                float x = kx, y = ky, z = kz;
                if (part > 0) {
                    int o = i * 6 + (part - 1) * 3;
                    x += s.handles[o]; y += s.handles[o + 1]; z += s.handles[o + 2];
                }
                if (!project(x, y, z)) continue;
                double dx = (proj[0] + 1.0) * 640.0 - refX, dy = (1.0 - proj[1]) * 360.0 - refY;
                double d2 = dx * dx + dy * dy;
                // Knots win ties against their own handles
                if (d2 < best || (part == 0 && d2 <= best + 1e-6)) {
                    best = d2; bestKnot = i; bestPart = part; bestDepth = proj[2];
                }
            }
        }
        if (bestKnot < 0) return false;
        if (s.splineData == null) s.commit();
        dragSpline = s;
        dragKnot = bestKnot;
        dragPart = bestPart;
        dragDepth = bestDepth;
        dragBefore = s.splineData;
        lastRefX = refX;
        lastRefY = refY;
        selectedSpline = s;
        selectedKnot = bestKnot;
        return true;
    }

    /** Moves the dragged knot / handle in the camera plane at its original depth. */
    public static boolean drag(double refX, double refY, ViewportCamera camera) {
        SplineComponent s = dragSpline;
        if (s == null || camera == null) return false;
        setupProjection(camera);
        float dUiX = (float) ((refX - lastRefX) / 640.0);
        float dUiY = (float) (-(refY - lastRefY) / 360.0);
        lastRefX = refX;
        lastRefY = refY;
        float vx = dUiX / 0.55f * dragDepth / p00;
        float vy = dUiY / 0.69f * dragDepth / p11;
        // Camera right / up in world space are the view matrix's first two rows
        float wx = view[0] * vx + view[1] * vy;
        float wy = view[4] * vx + view[5] * vy;
        float wz = view[8] * vx + view[9] * vy;
        int i = dragKnot;
        if (dragPart == 0) {
            s.setPoint(i, s.points[i * 3] + wx, s.points[i * 3 + 1] + wy, s.points[i * 3 + 2] + wz);
        } else {
            int o = i * 6 + (dragPart == 2 ? 3 : 0);
            s.setHandle(i, dragPart == 2, s.handles[o] + wx, s.handles[o + 1] + wy, s.handles[o + 2] + wz);
        }
        return true;
    }

    /** Commits the drag and records it on the undo stack. */
    public static void endDrag(EditorWorkspace workspace) {
        SplineComponent s = dragSpline;
        dragSpline = null;
        if (s == null) return;
        s.commit();
        byte[] before = dragBefore, after = s.splineData;
        dragBefore = null;
        if (workspace != null && !java.util.Arrays.equals(before, after)) {
            workspace.getCommandHistory().executeCommand(new SplineEditCommand(s, before, after));
        }
    }

    private static final class SplineEditCommand implements ICommand {
        private final SplineComponent spline;
        private final byte[] before, after;

        SplineEditCommand(SplineComponent spline, byte[] before, byte[] after) {
            this.spline = spline; this.before = before; this.after = after;
        }

        @Override public void execute() { spline.splineData = after; spline.markEdited(); }
        @Override public void undo() { spline.splineData = before; spline.markEdited(); }
        @Override public String getName() { return "Edit Spline"; }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Projection helpers
    // -----------------------------------------------------------------------------------------------------------------

    private static void setupProjection(ViewportCamera camera) {
        camera.computeViewMatrix(view);
        float f = (float) (1.0 / Math.tan(Math.toRadians(camera.getFov()) / 2.0));
        p00 = f / camera.getSceneAspect();
        p11 = f;
    }

    /** World point -> (uiX, uiY, depth) in {@link #proj}; false when behind the near plane. */
    private static boolean project(float x, float y, float z) {
        float vx = view[0] * x + view[4] * y + view[8] * z + view[12];
        float vy = view[1] * x + view[5] * y + view[9] * z + view[13];
        float d = -(view[2] * x + view[6] * y + view[10] * z + view[14]);
        if (d < NEAR_CLIP) return false;
        proj[0] = (p00 * vx / d) * 0.55f;
        proj[1] = 0.19f + (p11 * vy / d) * 0.69f;
        proj[2] = d;
        return true;
    }

    private static void drawSquare(UIGeometryBatch.Buffer buffer, float x, float y, float z, float scale, int rgb) {
        if (!project(x, y, z)) return;
        float ux = proj[0], uy = proj[1];
        if (ux < MIN_X || ux > MAX_X || uy < MIN_Y || uy > MAX_Y) return;
        float hx = KNOT_HALF_X * scale, hy = KNOT_HALF_Y * scale;
        UIGeometryBatch.addQuad(buffer, ux - hx - 0.002f, uy - hy - 0.0035f, ux + hx + 0.002f, uy + hy + 0.0035f, 0x000000, 0.8f);
        UIGeometryBatch.addQuad(buffer, ux - hx, uy - hy, ux + hx, uy + hy, rgb, 1.0f);
    }

    private static void drawLine(UIGeometryBatch.Buffer buffer, float x1, float y1, float z1, float x2, float y2, float z2, int rgb) {
        float ax = view[0] * x1 + view[4] * y1 + view[8] * z1 + view[12];
        float ay = view[1] * x1 + view[5] * y1 + view[9] * z1 + view[13];
        float da = -(view[2] * x1 + view[6] * y1 + view[10] * z1 + view[14]);
        float bx = view[0] * x2 + view[4] * y2 + view[8] * z2 + view[12];
        float by = view[1] * x2 + view[5] * y2 + view[9] * z2 + view[13];
        float db = -(view[2] * x2 + view[6] * y2 + view[10] * z2 + view[14]);
        if (da < NEAR_CLIP && db < NEAR_CLIP) return;
        if (da < NEAR_CLIP) {
            float t = (NEAR_CLIP - da) / (db - da);
            ax += (bx - ax) * t; ay += (by - ay) * t; da = NEAR_CLIP;
        } else if (db < NEAR_CLIP) {
            float t = (NEAR_CLIP - db) / (da - db);
            bx += (ax - bx) * t; by += (ay - by) * t; db = NEAR_CLIP;
        }
        float sx1 = (p00 * ax / da) * 0.55f, sy1 = 0.19f + (p11 * ay / da) * 0.69f;
        float sx2 = (p00 * bx / db) * 0.55f, sy2 = 0.19f + (p11 * by / db) * 0.69f;
        if (sx1 < MIN_X || sx1 > MAX_X || sy1 < MIN_Y || sy1 > MAX_Y) return;
        if (sx2 < MIN_X || sx2 > MAX_X || sy2 < MIN_Y || sy2 > MAX_Y) return;
        UIGeometryBatch.addLineSegment(buffer, sx1, sy1, sx2, sy2, LINE_THICKNESS, rgb, 1.0f);
    }
}
