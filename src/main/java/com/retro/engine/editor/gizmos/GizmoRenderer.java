package com.retro.engine.editor.gizmos;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.ViewportCamera;
import com.retro.engine.editor.panels.ViewportPanel;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.List;

/**
 * Single-Responsibility Rendering Delegate for Immediate-Mode 3D Vector Gizmo Geometry Batching.
 */
public class GizmoRenderer {

    // GPU Color-ID pick IDs per gizmo handle (IRenderBackend.PICK_OVERLAY_ID_BASE | low16)
    public static final int PICK_ID_AXIS_X = 0xFFFE0001;
    public static final int PICK_ID_AXIS_Y = 0xFFFE0002;
    public static final int PICK_ID_AXIS_Z = 0xFFFE0003;
    public static final int PICK_ID_CENTER = 0xFFFE0004;

    // Skeleton joint pick IDs: PICK_OVERLAY_ID_BASE | BONE_PICK_FLAG | slot << 6 | bone index (AnimatorComponent.MAX_BONES = 64)
    public static final int BONE_PICK_FLAG = 0x8000;
    public static final int BONE_PICK_SLOT_SHIFT = 6;
    public static final int BONE_PICK_INDEX_MASK = (1 << BONE_PICK_SLOT_SHIFT) - 1;
    public static final int BONE_PICK_MAX_SLOTS = BONE_PICK_FLAG >> BONE_PICK_SLOT_SHIFT;

    private static final float JOINT_SIZE = 0.012f;
    private static final float SELECTED_JOINT_SIZE = 0.02f;
    private static final float BONE_SEGMENT_THICKNESS = 0.003f;
    private static final float JOINT_PICK_PX = 12.0f;
    private static final float JOINT_MIN_DEPTH = 0.05f;

    // Per-skeleton scratch: model-space joints, then their design-space projections (zero per-frame allocations)
    private static final float[] jointModel = new float[AnimatorComponent.MAX_BONES * 3];
    private static final float[] jointX = new float[AnimatorComponent.MAX_BONES];
    private static final float[] jointY = new float[AnimatorComponent.MAX_BONES];
    private static final boolean[] jointVisible = new boolean[AnimatorComponent.MAX_BONES];
    private static final float[] basisScratch = new float[9];

    // Pick-only hitbox sizes in window pixels; the visual strokes stay ~2-4 px
    private static final float PICK_STROKE_PX = 12.0f;
    private static final float PICK_CENTER_PX = 14.0f;
    private static final int PICK_ARC_SEGMENTS = 24; // Same tessellation as UIGeometryBatch.addArcRing

    // Screen-space NDC triangles of this frame's pickable handles: x, y, pick ID low 16 bits per vertex
    private static float[] pickVertices = new float[4096];
    private static int pickFloats = 0;

    /**
     * Pick-only stroke {@code widthPx} window pixels wide from (x1, y1) to (x2, y2) in design space, extended by half its
     * width past the end. It is emitted through the mapped buffer (same panel mapping and clipping as the visuals),
     * captured for {@code pickId}, then truncated away so it is never drawn.
     */
    private static void capturePickStroke(UIGeometryBatch.Buffer buffer, float x1, float y1, float x2, float y2, float widthPx, int pickId) {
        if (!buffer.isMapped()) return;
        // Window pixels per design unit along each axis (the panel mapping may scale X and Y differently)
        float pxPerX = (buffer.mapX(1.0f) - buffer.mapX(0.0f)) * buffer.getWindowWidth() * 0.5f;
        float pxPerY = (buffer.mapY(1.0f) - buffer.mapY(0.0f)) * buffer.getWindowHeight() * 0.5f;
        if (pxPerX <= 0.0f || pxPerY <= 0.0f) return;

        float dxPx = (x2 - x1) * pxPerX, dyPx = (y2 - y1) * pxPerY;
        float lenPx = (float) Math.hypot(dxPx, dyPx);
        if (lenPx < 1e-4f) return;
        float half = widthPx * 0.5f;
        float ux = dxPx / lenPx, uy = dyPx / lenPx;
        float ex = x2 + ux * half / pxPerX, ey = y2 + uy * half / pxPerY;
        float nx = -uy * half / pxPerX, ny = ux * half / pxPerY;

        int mark = buffer.getSize();
        UIGeometryBatch.addTriangle(buffer, x1 + nx, y1 + ny, ex + nx, ey + ny, x1 - nx, y1 - ny, 0.0f, 0.0f, 0.0f, 0.0f);
        UIGeometryBatch.addTriangle(buffer, ex + nx, ey + ny, ex - nx, ey - ny, x1 - nx, y1 - ny, 0.0f, 0.0f, 0.0f, 0.0f);
        capturePick(buffer, mark, pickId);
        buffer.truncate(mark);
    }

    /** Pick-only arc band: one {@link #capturePickStroke} per segment of the visual addArcRing path. */
    private static void capturePickArc(UIGeometryBatch.Buffer buffer, float cx, float cy, float rx, float ry,
                                       float startAngle, float endAngle, float widthPx, int pickId) {
        float startRad = (float) Math.toRadians(startAngle);
        float step = ((float) Math.toRadians(endAngle) - startRad) / PICK_ARC_SEGMENTS;
        float prevX = cx + rx * (float) Math.cos(startRad), prevY = cy + ry * (float) Math.sin(startRad);
        for (int i = 1; i <= PICK_ARC_SEGMENTS; i++) {
            float angle = startRad + i * step;
            float currX = cx + rx * (float) Math.cos(angle), currY = cy + ry * (float) Math.sin(angle);
            capturePickStroke(buffer, prevX, prevY, currX, currY, widthPx, pickId);
            prevX = currX;
            prevY = currY;
        }
    }

    /** Pick-only square {@code sizePx} window pixels across centered on (cx, cy), captured then truncated away. */
    private static void capturePickSquare(UIGeometryBatch.Buffer buffer, float cx, float cy, float sizePx, int pickId) {
        if (!buffer.isMapped()) return;
        float pxPerX = (buffer.mapX(1.0f) - buffer.mapX(0.0f)) * buffer.getWindowWidth() * 0.5f;
        float pxPerY = (buffer.mapY(1.0f) - buffer.mapY(0.0f)) * buffer.getWindowHeight() * 0.5f;
        if (pxPerX <= 0.0f || pxPerY <= 0.0f) return;
        float hx = sizePx * 0.5f / pxPerX, hy = sizePx * 0.5f / pxPerY;
        int mark = buffer.getSize();
        UIGeometryBatch.addQuad(buffer, cx - hx, cy - hy, cx + hx, cy + hy, 0.0f, 0.0f, 0.0f, 0.0f);
        capturePick(buffer, mark, pickId);
        buffer.truncate(mark);
    }

    /** Drops last frame's captured pick triangles; call before the editor UI is rebuilt. */
    public static void beginPickCapture() { pickFloats = 0; }

    public static float[] getPickVertices() { return pickVertices; }
    public static int getPickVertexCount() { return pickFloats / IRenderBackend.PICK_OVERLAY_VERTEX_FLOATS; }

    /**
     * Copies the triangles emitted into {@code buffer} since float offset {@code start} as pick geometry for {@code pickId}.
     * They are read back after the panel mapping and clipping, so the pick shape is exactly the drawn shape on screen.
     */
    private static void capturePick(UIGeometryBatch.Buffer buffer, int start, int pickId) {
        if (!buffer.isMapped()) return; // Unmapped scratch buffers are not on screen
        int end = buffer.getSize();
        int vertexCount = (end - start) / UIGeometryBatch.VERTEX_STRIDE;
        int needed = pickFloats + vertexCount * IRenderBackend.PICK_OVERLAY_VERTEX_FLOATS;
        if (needed > pickVertices.length) {
            float[] grown = new float[Math.max(needed, pickVertices.length * 2)];
            System.arraycopy(pickVertices, 0, grown, 0, pickFloats);
            pickVertices = grown;
        }
        float idLow = (float) (pickId & 0xFFFF);
        float[] src = buffer.getArray();
        for (int o = start; o + 1 < end; o += UIGeometryBatch.VERTEX_STRIDE) {
            pickVertices[pickFloats++] = src[o];
            pickVertices[pickFloats++] = src[o + 1];
            pickVertices[pickFloats++] = idLow;
        }
    }

    /** Overlay pick ID of joint {@code boneIndex} in the skeleton holding pick slot {@code slot}. */
    public static int bonePickId(int slot, int boneIndex) {
        return IRenderBackend.PICK_OVERLAY_ID_BASE | BONE_PICK_FLAG | (slot << BONE_PICK_SLOT_SHIFT) | (boneIndex & BONE_PICK_INDEX_MASK);
    }

    /**
     * Draws the bone segments and joints of every entity with an animated skeleton in its current pose, and captures each
     * joint as pick geometry carrying its own {@link #bonePickId}. The selected entity's skeleton is drawn last (and its
     * selected joint last of all) so it wins the pick where skeletons overlap; call before the transform gizmo so the
     * gizmo handles still win over joints.
     */
    public static void renderSkeletons(UIGeometryBatch.Buffer buffer, List<Entity> sceneEntities, SceneWorld sceneManager,
                                       Entity selectedEntity, int selectedBoneIndex, ViewportCamera cameraController,
                                       float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (buffer == null || sceneEntities == null || sceneManager == null) return;
        for (int i = 0, n = sceneEntities.size(); i < n; i++) {
            Entity entity = sceneEntities.get(i);
            if (entity == null || entity == selectedEntity) continue;
            renderSkeleton(buffer, entity, sceneEntities, sceneManager, false, -1, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        if (selectedEntity != null) {
            renderSkeleton(buffer, selectedEntity, sceneEntities, sceneManager, true, selectedBoneIndex, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        }
    }

    private static void renderSkeleton(UIGeometryBatch.Buffer buffer, Entity entity, List<Entity> sceneEntities, SceneWorld sceneManager,
                                       boolean isSelected, int selectedBoneIndex, ViewportCamera cameraController,
                                       float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        AnimatorComponent animator = entity.getComponent(AnimatorComponent.class);
        Skeleton skeleton = (animator != null) ? animator.skeleton : null;
        if (skeleton == null) return;
        MeshFilter mf = entity.getComponent(MeshFilter.class);
        if (mf != null && !mf.isVisible) return;

        int count = skeleton.computeJointModelPositions(animator.bonePaletteMatrices, jointModel);
        if (count == 0) return;

        // Joints go through the same model matrix the skinned mesh renders with (model * palette * vertex)
        SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(entity);
        float[] m = basisScratch;
        GizmoMath.computeModelBasis(wt.rx(), wt.ry(), wt.rz(), wt.sx(), wt.sy(), wt.sz(), m);
        for (int b = 0; b < count; b++) {
            float lx = jointModel[b * 3], ly = jointModel[b * 3 + 1], lz = jointModel[b * 3 + 2];
            float wx = wt.px() + m[0] * lx + m[3] * ly + m[6] * lz;
            float wy = wt.py() + m[1] * lx + m[4] * ly + m[7] * lz;
            float wz = wt.pz() + m[2] * lx + m[5] * ly + m[8] * lz;
            jointVisible[b] = GizmoMath.viewDepth(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw) > JOINT_MIN_DEPTH;
            if (!jointVisible[b]) continue;
            float[] p = GizmoMath.projectWorldToNdc(wx, wy, wz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
            jointX[b] = p[0];
            jointY[b] = p[1];
        }

        float segR = isSelected ? 0.35f : 0.60f, segG = isSelected ? 0.80f : 0.60f, segB = isSelected ? 1.00f : 0.65f;
        float segA = isSelected ? 0.95f : 0.45f;
        for (int b = 0; b < count; b++) {
            Skeleton.BoneNode bone = skeleton.getBone(b);
            int parent = (bone != null) ? bone.parentId : -1;
            if (parent < 0 || parent >= count || !jointVisible[b] || !jointVisible[parent]) continue;
            UIGeometryBatch.addLineSegment(buffer, jointX[parent], jointY[parent], jointX[b], jointY[b], BONE_SEGMENT_THICKNESS, segR, segG, segB, segA);
        }

        int slot = ViewportPanel.bonePickSlotFor(entity, sceneEntities);
        float jointA = isSelected ? 1.0f : 0.6f;
        for (int b = 0; b < count; b++) {
            if (b == selectedBoneIndex || !isJointOnViewport(b)) continue;
            addJointSquare(buffer, jointX[b], jointY[b], JOINT_SIZE, 0.90f, 0.95f, 1.00f, jointA);
            if (slot >= 0) capturePickSquare(buffer, jointX[b], jointY[b], JOINT_PICK_PX, bonePickId(slot, b));
        }

        if (selectedBoneIndex >= 0 && selectedBoneIndex < count && isJointOnViewport(selectedBoneIndex)) {
            float cx = jointX[selectedBoneIndex], cy = jointY[selectedBoneIndex];
            addJointSquare(buffer, cx, cy, SELECTED_JOINT_SIZE, 1.0f, 0.75f, 0.15f, 1.0f);
            if (slot >= 0) capturePickSquare(buffer, cx, cy, JOINT_PICK_PX, bonePickId(slot, selectedBoneIndex));
            Skeleton.BoneNode bone = skeleton.getBone(selectedBoneIndex);
            if (bone != null && bone.name != null) {
                UIGeometryBatch.addBoundedText(buffer, bone.name, cx + 0.015f, cy + 0.025f, cx + 0.20f, 0.9f, 1.0f, 0.75f, 0.15f, 1.0f);
            }
        }
    }

    /** In front of the camera and inside the Scene design rect below the toolbar (same idea as the gizmo's own cull). */
    private static boolean isJointOnViewport(int b) {
        return jointVisible[b] && jointX[b] >= -0.55f && jointX[b] <= 0.55f && jointY[b] >= -0.50f && jointY[b] <= 0.78f;
    }

    /** Joint square {@code size} design units tall and the same width on screen. */
    private static void addJointSquare(UIGeometryBatch.Buffer buffer, float cx, float cy, float size, float r, float g, float b, float a) {
        float hy = size * 0.5f, hx = UIGeometryBatch.squareWidth(buffer, size) * 0.5f;
        UIGeometryBatch.addQuad(buffer, cx - hx, cy - hy, cx + hx, cy + hy, r, g, b, a);
    }

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        Transform tc = entity.getTransform();
        if (tc == null) return;
        render3DGizmosAtWorldPosition(buffer, tc.posX, tc.posY, tc.posZ, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmos(tempBuf, entity, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render3DGizmos(UIGeometryBatch.Buffer buffer, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        if (entity == null) return;
        float[] anchor = GizmoMath.computeGizmoAnchor(entity, sceneManager);
        render3DGizmosAtWorldPosition(buffer, anchor[0], anchor[1], anchor[2], mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
    }

    public static void render3DGizmos(List<Float> verts, Entity entity, SceneWorld sceneManager, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmos(tempBuf, entity, sceneManager, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render3DGizmosAtWorldPosition(UIGeometryBatch.Buffer buffer, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        float[] p0 = GizmoMath.projectWorldToNdc(px, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float cx = p0[0], cy = p0[1];

        if (cx < -0.55f || cx > 0.55f || cy < -0.35f || cy > 0.75f) {
            return;
        }

        float axisWorldLen = 1.0f;

        float[] pX = GizmoMath.projectWorldToNdc(px + axisWorldLen, py, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pY = GizmoMath.projectWorldToNdc(px, py + axisWorldLen, pz, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        float[] pZ = GizmoMath.projectWorldToNdc(px, py, pz + axisWorldLen, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);

        float dirXX = pX[0] - cx, dirXY = pX[1] - cy;
        float dirYX = pY[0] - cx, dirYY = pY[1] - cy;
        float dirZX = pZ[0] - cx, dirZY = pZ[1] - cy;

        float lenX = (float) Math.hypot(dirXX, dirXY);
        float lenY = (float) Math.hypot(dirYX, dirYY);
        float lenZ = (float) Math.hypot(dirZX, dirZY);

        float screenLen = 0.16f;

        float uXX = lenX > 0.0001f ? (dirXX / lenX) : 1.0f;
        float uXY = lenX > 0.0001f ? (dirXY / lenX) : 0.0f;

        float uYX = lenY > 0.0001f ? (dirYX / lenY) : 0.0f;
        float uYY = lenY > 0.0001f ? (dirYY / lenY) : 1.0f;

        float uZX = lenZ > 0.0001f ? (dirZX / lenZ) : -0.707f;
        float uZY = lenZ > 0.0001f ? (dirZY / lenZ) : -0.707f;

        float xTipX = cx + uXX * screenLen, xTipY = cy + uXY * screenLen;
        float yTipX = cx + uYX * screenLen, yTipY = cy + uYY * screenLen;
        float zTipX = cx + uZX * screenLen, zTipY = cy + uZY * screenLen;

        float lineThick = 0.006f;

        UIGeometryBatch.addSquareEnd(buffer, cx, cy, 0.02f, 0.9f, 0.9f, 0.9f, 1.0f);
        int mark;

        float rx = 1.0f, rg = 0.25f, rb = 0.25f;
        float gx = 0.25f, gg = 1.0f, gb = 0.25f;
        float bx = 0.25f, bg = 0.55f, bb = 1.0f;

        if (activeAxis == GizmoAxis.X) { rx = 1.0f; rg = 0.9f; rb = 0.2f; }
        if (activeAxis == GizmoAxis.Y) { gx = 0.2f; gg = 1.0f; gb = 0.9f; }
        if (activeAxis == GizmoAxis.Z) { bx = 0.9f; bg = 0.2f; bb = 1.0f; }
        if (activeAxis == GizmoAxis.CENTER) { rx = 1.0f; rg = 0.9f; rb = 0.2f; gx = 0.2f; gg = 1.0f; gb = 0.9f; bx = 0.9f; bg = 0.2f; bb = 1.0f; }

        // The drawn handle shapes are captured as pick geometry, then widened by the pick-only hitboxes below;
        // the text labels are not pickable

        if (mode == GizmoMode.TRANSLATE) {
            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, xTipX + uXX * 0.02f, xTipY + uXY * 0.02f, uXX, uXY, 0.03f, 0.015f, rx, rg, rb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_X);
            UIGeometryBatch.addBoundedText(buffer, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, yTipX + uYX * 0.02f, yTipY + uYY * 0.02f, uYX, uYY, 0.03f, 0.015f, gx, gg, gb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Y);
            UIGeometryBatch.addBoundedText(buffer, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIGeometryBatch.addArrowhead(buffer, zTipX + uZX * 0.02f, zTipY + uZY * 0.02f, uZX, uZY, 0.03f, 0.015f, bx, bg, bb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Z);
            UIGeometryBatch.addBoundedText(buffer, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.SCALE) {
            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, xTipX, xTipY, lineThick, rx, rg, rb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, xTipX, xTipY, 0.024f, rx, rg, rb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_X);
            UIGeometryBatch.addBoundedText(buffer, "X", xTipX + uXX * 0.03f, xTipY + uXY * 0.03f, xTipX + uXX * 0.08f, 1.1f, rx, rg, rb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, yTipX, yTipY, lineThick, gx, gg, gb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, yTipX, yTipY, 0.024f, gx, gg, gb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Y);
            UIGeometryBatch.addBoundedText(buffer, "Y", yTipX + uYX * 0.03f, yTipY + uYY * 0.03f, yTipX + uYX * 0.08f, 1.1f, gx, gg, gb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addLineSegment(buffer, cx, cy, zTipX, zTipY, lineThick, bx, bg, bb, 1.0f);
            UIGeometryBatch.addSquareEnd(buffer, zTipX, zTipY, 0.024f, bx, bg, bb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Z);
            UIGeometryBatch.addBoundedText(buffer, "Z", zTipX + uZX * 0.03f, zTipY + uZY * 0.03f, zTipX + uZX * 0.08f, 1.1f, bx, bg, bb, 1.0f);

        } else if (mode == GizmoMode.ROTATE) {
            mark = buffer.getSize();
            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, lineThick, rx, rg, rb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_X);
            UIGeometryBatch.addBoundedText(buffer, "X:PITCH", cx + 0.06f, cy + 0.08f, cx + 0.20f, 1.0f, rx, rg, rb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, lineThick, gx, gg, gb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Y);
            UIGeometryBatch.addBoundedText(buffer, "Y:YAW", cx - 0.05f, cy + 0.08f, cx + 0.08f, 1.0f, gx, gg, gb, 1.0f);

            mark = buffer.getSize();
            UIGeometryBatch.addArcRing(buffer, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, lineThick + 0.002f, bx, bg, bb, 1.0f);
            capturePick(buffer, mark, PICK_ID_AXIS_Z);
            UIGeometryBatch.addBoundedText(buffer, "Z:ROLL", cx - 0.18f, cy - 0.12f, cx - 0.02f, 1.0f, bx, bg, bb, 1.0f);
        }

        // Pick-only hitboxes (never drawn): wide strokes along each handle out past its arrowhead / end cube, then the
        // center last so it owns the middle where the axis strokes overlap. Later pick triangles overwrite earlier ones.
        if (mode == GizmoMode.ROTATE) {
            capturePickArc(buffer, cx, cy, 0.05f, 0.14f, -90.0f, 90.0f, PICK_STROKE_PX, PICK_ID_AXIS_X);
            capturePickArc(buffer, cx, cy, 0.14f, 0.05f, 0.0f, 180.0f, PICK_STROKE_PX, PICK_ID_AXIS_Y);
            capturePickArc(buffer, cx, cy, 0.14f, 0.14f, 0.0f, 360.0f, PICK_STROKE_PX, PICK_ID_AXIS_Z);
        } else {
            // Translate reaches the arrow tip; Scale reaches the end cube's far edge
            float reach = (mode == GizmoMode.TRANSLATE) ? 0.02f : 0.012f;
            capturePickStroke(buffer, cx, cy, xTipX + uXX * reach, xTipY + uXY * reach, PICK_STROKE_PX, PICK_ID_AXIS_X);
            capturePickStroke(buffer, cx, cy, yTipX + uYX * reach, yTipY + uYY * reach, PICK_STROKE_PX, PICK_ID_AXIS_Y);
            capturePickStroke(buffer, cx, cy, zTipX + uZX * reach, zTipY + uZY * reach, PICK_STROKE_PX, PICK_ID_AXIS_Z);
            // The center drags in the view plane (Move) or scales uniformly (Scale); Rotate has no center action
            capturePickSquare(buffer, cx, cy, PICK_CENTER_PX, PICK_ID_CENTER);
        }
    }

    public static void render3DGizmosAtWorldPosition(List<Float> verts, float px, float py, float pz, GizmoMode mode, GizmoAxis activeAxis, ViewportCamera cameraController, float camPanX, float camPanY, float camZoom, float camPitch, float camYaw) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render3DGizmosAtWorldPosition(tempBuf, px, py, pz, mode, activeAxis, cameraController, camPanX, camPanY, camZoom, camPitch, camYaw);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}
