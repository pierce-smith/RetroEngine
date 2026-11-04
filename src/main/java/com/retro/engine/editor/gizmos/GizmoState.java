package com.retro.engine.editor.gizmos;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorCommands;
import com.retro.engine.editor.ICommand;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.overlays.OverlaySystem;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.ViewportPanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Delegate managing 3D transform gizmo modes, active selection axes, transform drag start vectors,
 * camera orbit/pan state tracking, and procedural 3D viewport grid proxy geometry.
 */
public class GizmoState {

    private GizmoMode currentGizmoMode = GizmoMode.TRANSLATE;
    private GizmoAxis activeGizmoAxis = GizmoAxis.NONE;

    private float dragStartPx, dragStartPy, dragStartPz;
    private float dragStartRx, dragStartRy, dragStartRz;
    private float dragStartSx, dragStartSy, dragStartSz;

    private float camPanX = 0.0f, camPanY = 0.25f, camZoom = -5.0f, camPitch = 0.0f, camYaw = 0.0f;
    private long lastOrbitTime = 0L;

    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    // Left button held since a viewport press; a pick readback resolving after release must not start a drag
    private boolean viewportPressHeld = false;

    private boolean show3DGrid = true;
    private float[] cached3DGridVertices = null;

    public GizmoMode getGizmoMode() { return currentGizmoMode; }
    public void setGizmoMode(GizmoMode mode) { this.currentGizmoMode = mode; }

    public GizmoAxis getActiveAxis() { return activeGizmoAxis; }
    public void setActiveAxis(GizmoAxis axis) {
        this.activeGizmoAxis = axis;
        if (axis == GizmoAxis.NONE) {
            anchorFrozen = false;
            boneDragEntity = null;
            groupDragActive = false;
        }
    }

    // --- Multi-selection group drag ---
    // The interactor drags a proxy transform that starts at the shared pivot with zero rotation and unit scale, so its
    // translation, (single-axis) rotation and scale ratio are the group delta, snapped exactly like a single entity's
    private final Transform groupProxy = new Transform();
    private final List<Entity> groupEntities = new ArrayList<>();
    private float[] groupStartLocal = new float[0];  // 9 per entity: local pos, rot, scale at drag start
    private float[] groupStartWorld = new float[0];  // 3 per entity: world position at drag start
    private float pivotX, pivotY, pivotZ;
    private boolean groupDragActive = false;
    private final float[] groupRot = new float[9], entityRot = new float[9], composedRot = new float[9], eulerOut = new float[3];

    /** True while a gizmo drag is transforming a multi-selection around its shared pivot. */
    public boolean isGroupDragActive() { return groupDragActive && activeGizmoAxis != GizmoAxis.NONE; }

    // --- Marquee (box) selection: a left-drag that starts on empty viewport space ---
    private static final double MARQUEE_MIN_DRAG = 4.0; // reference pixels; less than this is a plain background click
    private boolean marqueeActive = false;
    private double pressRefX, pressRefY, marqueeEndX, marqueeEndY;

    public boolean isMarqueeActive() { return marqueeActive; }

    /** Marquee rectangle in Scene-panel design NDC {x0, y0, x1, y1} (x0 <= x1, y0 <= y1). */
    public float[] getMarqueeDesignRect() {
        float ax = (float) (pressRefX / 640.0 - 1.0), ay = (float) (1.0 - pressRefY / 360.0);
        float bx = (float) (marqueeEndX / 640.0 - 1.0), by = (float) (1.0 - marqueeEndY / 360.0);
        return new float[] { Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by) };
    }

    // Bone drag in flight: the posed entity and bone, and the bone's local pose at drag start (undo + change detection)
    private Entity boneDragEntity = null;
    private int boneDragIndex = -1;
    private final float[] boneDragStartLocal = new float[16];
    private boolean boneDragStartActive = false;
    private final float[] boneDragLocal = new float[16];
    private final float[] boneMatrixScratch = new float[16];

    /** True while a gizmo drag is posing a skeleton bone rather than moving an entity. */
    public boolean isBoneDragActive() { return boneDragEntity != null && activeGizmoAxis != GizmoAxis.NONE; }

    /** Where the gizmo is drawn: the selected bone's joint while bone editing, else {@link #getGizmoAnchor(Entity, SceneWorld)}. */
    public float[] getGizmoAnchor(EditorWorkspace manager) {
        if (isGroupDragActive()) return new float[] { groupProxy.posX, groupProxy.posY, groupProxy.posZ };
        if (manager.getSelectionCount() > 1) return GizmoMath.computeGroupCenter(manager.getSelectedEntities(), manager.getSceneManager());
        Entity selected = manager.getSelectedEntity();
        if (manager.isBoneEditActive()) {
            float[] joint = jointWorldPosition(selected, manager.getSelectedBoneIndex(), manager.getSceneManager());
            if (joint != null) return joint;
        }
        return getGizmoAnchor(selected, manager.getSceneManager());
    }

    private float[] jointWorldPosition(Entity entity, int boneIndex, SceneWorld sceneManager) {
        if (!GizmoMath.computeBoneWorldMatrix(entity, boneIndex, sceneManager, boneMatrixScratch)) return null;
        return new float[] { boneMatrixScratch[12], boneMatrixScratch[13], boneMatrixScratch[14] };
    }

    // During a rotate drag the anchor is pinned to its drag-start offset from the pivot: the world-bounds base moves as
    // the mesh turns, and the rotate angle is measured around the anchor, so a live anchor feeds back into the rotation
    private boolean anchorFrozen = false;
    private float frozenOffX, frozenOffY, frozenOffZ;

    /** Where the gizmo is drawn and dragged from: the mesh base ({@link GizmoMath#computeGizmoAnchor}), pinned while rotating. */
    public float[] getGizmoAnchor(Entity entity, SceneWorld sceneManager) {
        if (anchorFrozen && activeGizmoAxis != GizmoAxis.NONE && entity != null && sceneManager != null) {
            SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(entity);
            return new float[] { wt.px() + frozenOffX, wt.py() + frozenOffY, wt.pz() + frozenOffZ };
        }
        return GizmoMath.computeGizmoAnchor(entity, sceneManager);
    }

    public float getDragStartPx() { return dragStartPx; }
    public float getDragStartPy() { return dragStartPy; }
    public float getDragStartPz() { return dragStartPz; }
    public float getDragStartRx() { return dragStartRx; }
    public float getDragStartRy() { return dragStartRy; }
    public float getDragStartRz() { return dragStartRz; }
    public float getDragStartSx() { return dragStartSx; }
    public float getDragStartSy() { return dragStartSy; }
    public float getDragStartSz() { return dragStartSz; }

    public float getCamPanX() { return camPanX; }
    public float getCamPanY() { return camPanY; }
    public float getCamZoom() { return camZoom; }
    public float getCamPitch() { return camPitch; }
    public float getCamYaw() { return camYaw; }
    public long getLastOrbitTime() { return lastOrbitTime; }

    public void setCameraState(float panX, float panY, float zoom, float pitch, float yaw) {
        if (Math.abs(this.camPitch - pitch) > 0.05f || Math.abs(this.camYaw - yaw) > 0.05f || Math.abs(this.camPanX - panX) > 0.01f || Math.abs(this.camPanY - panY) > 0.01f) {
            this.lastOrbitTime = System.currentTimeMillis();
        }
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    public void captureDragStart(Transform tc) {
        if (tc == null) return;
        this.dragStartPx = tc.posX; this.dragStartPy = tc.posY; this.dragStartPz = tc.posZ;
        this.dragStartRx = tc.rotX; this.dragStartRy = tc.rotY; this.dragStartRz = tc.rotZ;
        this.dragStartSx = tc.scaleX; this.dragStartSy = tc.scaleY; this.dragStartSz = tc.scaleZ;
        GizmoInteractor.startDrag(tc);
    }

    public boolean is3DGridVisible() { return show3DGrid; }
    public void toggle3DGrid() { this.show3DGrid = !this.show3DGrid; cached3DGridVertices = null; }

    public float[] get3DGridVertices() {
        if (!show3DGrid) return new float[0];
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) cached3DGridVertices = generate3DGridVertices();
        return cached3DGridVertices;
    }

    public int get3DGridVertexCount() {
        if (!show3DGrid) return 0;
        if (cached3DGridVertices == null || cached3DGridVertices.length == 0) get3DGridVertices();
        return cached3DGridVertices.length / 9;
    }

    private float[] generate3DGridVertices() {
        // 6 Vertices for 1000m x 1000m Procedural Ray-Plane Infinite Floor Quad + 2 Vertices for Vertical Green Y-Axis Pillar
        return new float[] {
            // Proxy Quad Triangles (Vertices 0..5) - Y=0.0 Plane
            -1000.0f, 0.0f, -1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  0.0f, 0.0f,
            -1000.0f, 0.0f,  1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  0.0f, 1.0f,
             1000.0f, 0.0f,  1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  1.0f, 1.0f,

            -1000.0f, 0.0f, -1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  0.0f, 0.0f,
             1000.0f, 0.0f,  1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  1.0f, 1.0f,
             1000.0f, 0.0f, -1000.0f,  0.5f, 0.5f, 0.5f, 1.0f,  1.0f, 0.0f,

            // Vertical Green Y-Axis Line (Vertices 6..7) - World Origin (0,0,0) -> (0,4,0)
             0.0f, 0.0f, 0.0f,  0.2f, 1.0f, 0.2f, 1.0f,  0.0f, 0.0f,
             0.0f, 4.0f, 0.0f,  0.2f, 1.0f, 0.2f, 1.0f,  0.0f, 0.0f
        };
    }

    public boolean processViewportToolbarClick(float ndcX, float ndcY) {
        if (ndcY >= 0.80f && ndcY <= 0.86f) {
            if (ndcX >= -0.51f && ndcX <= -0.38f) { setGizmoMode(GizmoMode.TRANSLATE); return true; }
            if (ndcX >= -0.37f && ndcX <= -0.25f) { setGizmoMode(GizmoMode.ROTATE); return true; }
            if (ndcX >= -0.24f && ndcX <= -0.11f) { setGizmoMode(GizmoMode.SCALE); return true; }
        }
        return false;
    }

    public void processViewportGizmoClick(double refX, double refY, EditorWorkspace manager) {
        if (manager == null) return;
        viewportPressHeld = true;
        marqueeActive = false;
        pressRefX = marqueeEndX = refX;
        pressRefY = marqueeEndY = refY;
        // One pixel-exact GPU Color-ID pick resolves the press: gizmo handles and skeleton joints are drawn over every mesh
        // in the pick pass, so a handle pixel starts a drag and a joint selects that bone; anything else selects that
        // entity (the background PICK_ID_NONE deselects)
        ViewportPanel.requestPickAtDesign(manager.getDockSpace(), (float) (refX / 640.0 - 1.0), (float) (1.0 - refY / 360.0),
                pickId -> resolveViewportPick(pickId, manager));
    }

    private void resolveViewportPick(int pickId, EditorWorkspace manager) {
        if (GizmoInteractor.isBonePickId(pickId)) {
            // A skeleton joint selects its entity and that bone; a joint whose entity has left the scene falls through
            Entity owner = ViewportPanel.resolveBonePick(pickId, manager.getSceneEntities());
            if (owner != null) {
                manager.selectBone(owner, GizmoInteractor.boneIndexForPickId(pickId));
                return;
            }
        }
        if (GizmoInteractor.isGizmoPickId(pickId)) {
            // Clicking a handle never changes the selection
            Entity selected = manager.getSelectedEntity();
            GizmoAxis axis = GizmoInteractor.axisForPickId(pickId, currentGizmoMode);
            Transform tc = (selected != null) ? selected.getTransform() : null;
            if (axis != GizmoAxis.NONE && viewportPressHeld && manager.isBoneEditActive()) {
                startBoneDrag(selected, manager.getSelectedBoneIndex(), axis);
                return;
            }
            if (axis != GizmoAxis.NONE && viewportPressHeld && manager.getSelectionCount() > 1) {
                startGroupDrag(manager, axis);
                return;
            }
            if (axis != GizmoAxis.NONE && tc != null && viewportPressHeld) {
                SceneWorld sceneManager = manager.getSceneManager();
                anchorFrozen = false;
                if (currentGizmoMode == GizmoMode.ROTATE && sceneManager != null) {
                    float[] anchor = GizmoMath.computeGizmoAnchor(selected, sceneManager);
                    SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(selected);
                    frozenOffX = anchor[0] - wt.px();
                    frozenOffY = anchor[1] - wt.py();
                    frozenOffZ = anchor[2] - wt.pz();
                    anchorFrozen = true;
                }
                this.activeGizmoAxis = axis;
                captureDragStart(tc);
            }
            return;
        }
        // Entity or background: Cmd/Ctrl+Click toggles, Shift+Click adds, a plain click replaces the selection. A press on
        // the background that is still held becomes a marquee, resolved on release (a click without travel deselects).
        Entity hit = ViewportPanel.resolvePick(pickId, manager.getSceneEntities());
        boolean toggle = manager.isToggleModifierHeld();
        boolean shift = manager.isShiftHeld();
        if (hit != null) {
            if (toggle) manager.toggleEntitySelection(hit);
            else if (shift) manager.setSelection(List.of(hit), hit, true);
            else manager.selectEntity(hit);
            return;
        }
        if (viewportPressHeld && !manager.isAltHeld()) { // Alt+LMB orbits the camera
            marqueeActive = true;
            marqueeEndX = lastMouseX;
            marqueeEndY = lastMouseY;
            return;
        }
        if (!toggle && !shift) manager.selectEntity(null);
    }

    private void startGroupDrag(EditorWorkspace manager, GizmoAxis axis) {
        SceneWorld scene = manager.getSceneManager();
        List<Entity> selection = manager.getSelectedEntities();
        groupEntities.clear();
        for (Entity e : selection) {
            // A child whose ancestor is also selected already moves with that ancestor
            if (e.getTransform() != null && !hasSelectedAncestor(e, manager)) groupEntities.add(e);
        }
        if (groupEntities.isEmpty()) return;

        float[] pivot = GizmoMath.computeGroupCenter(selection, scene);
        pivotX = pivot[0]; pivotY = pivot[1]; pivotZ = pivot[2];
        int n = groupEntities.size();
        groupStartLocal = new float[n * 9];
        groupStartWorld = new float[n * 3];
        for (int i = 0; i < n; i++) {
            Entity e = groupEntities.get(i);
            Transform tc = e.getTransform();
            int b = i * 9;
            groupStartLocal[b] = tc.posX; groupStartLocal[b + 1] = tc.posY; groupStartLocal[b + 2] = tc.posZ;
            groupStartLocal[b + 3] = tc.rotX; groupStartLocal[b + 4] = tc.rotY; groupStartLocal[b + 5] = tc.rotZ;
            groupStartLocal[b + 6] = tc.scaleX; groupStartLocal[b + 7] = tc.scaleY; groupStartLocal[b + 8] = tc.scaleZ;
            SceneWorld.WorldTransform wt = scene.getComputedWorldTransform(e);
            groupStartWorld[i * 3] = wt.px(); groupStartWorld[i * 3 + 1] = wt.py(); groupStartWorld[i * 3 + 2] = wt.pz();
        }

        groupProxy.posX = pivotX; groupProxy.posY = pivotY; groupProxy.posZ = pivotZ;
        groupProxy.rotX = groupProxy.rotY = groupProxy.rotZ = 0.0f;
        groupProxy.scaleX = groupProxy.scaleY = groupProxy.scaleZ = 1.0f;
        anchorFrozen = false;
        this.activeGizmoAxis = axis;
        groupDragActive = true;
        GizmoInteractor.startDrag(groupProxy);
    }

    private static boolean hasSelectedAncestor(Entity e, EditorWorkspace manager) {
        SceneWorld scene = manager.getSceneManager();
        String parentId = e.parentId;
        for (int depth = 0; parentId != null && depth < 256; depth++) { // depth cap guards a corrupt parent cycle
            Entity parent = scene.findEntityById(parentId);
            if (parent == null) return false;
            if (manager.isSelected(parent)) return true;
            parentId = parent.parentId;
        }
        return false;
    }

    /**
     * Re-applies the proxy's current delta to every group member from its drag-start state: translation moves each by
     * the same offset; rotation turns each one's position about the pivot and pre-multiplies its orientation by the
     * same world rotation; scale multiplies each one's scale and its offset from the pivot. Offsets are computed in
     * world space and added to the local position (as single-entity drags do), which is exact for root entities and
     * for children of translated-only parents.
     */
    private void applyGroupDrag() {
        float dx = groupProxy.posX - pivotX, dy = groupProxy.posY - pivotY, dz = groupProxy.posZ - pivotZ;
        float ksx = groupProxy.scaleX, ksy = groupProxy.scaleY, ksz = groupProxy.scaleZ;
        boolean rotate = currentGizmoMode == GizmoMode.ROTATE;
        if (rotate) GizmoMath.computeRotationBasis(groupProxy.rotX, groupProxy.rotY, groupProxy.rotZ, groupRot);

        for (int i = 0; i < groupEntities.size(); i++) {
            Transform tc = groupEntities.get(i).getTransform();
            if (tc == null) continue;
            int b = i * 9;
            float relX = groupStartWorld[i * 3] - pivotX, relY = groupStartWorld[i * 3 + 1] - pivotY, relZ = groupStartWorld[i * 3 + 2] - pivotZ;
            float offX = 0.0f, offY = 0.0f, offZ = 0.0f;

            tc.rotX = groupStartLocal[b + 3]; tc.rotY = groupStartLocal[b + 4]; tc.rotZ = groupStartLocal[b + 5];
            tc.scaleX = groupStartLocal[b + 6]; tc.scaleY = groupStartLocal[b + 7]; tc.scaleZ = groupStartLocal[b + 8];
            switch (currentGizmoMode) {
                case TRANSLATE -> { offX = dx; offY = dy; offZ = dz; }
                case ROTATE -> {
                    offX = groupRot[0] * relX + groupRot[3] * relY + groupRot[6] * relZ - relX;
                    offY = groupRot[1] * relX + groupRot[4] * relY + groupRot[7] * relZ - relY;
                    offZ = groupRot[2] * relX + groupRot[5] * relY + groupRot[8] * relZ - relZ;
                    GizmoMath.computeRotationBasis(tc.rotX, tc.rotY, tc.rotZ, entityRot);
                    GizmoMath.multiply3x3(groupRot, entityRot, composedRot);
                    GizmoMath.eulerFromRotationBasis(composedRot, eulerOut);
                    tc.rotX = eulerOut[0]; tc.rotY = eulerOut[1]; tc.rotZ = eulerOut[2];
                }
                case SCALE -> {
                    offX = relX * (ksx - 1.0f); offY = relY * (ksy - 1.0f); offZ = relZ * (ksz - 1.0f);
                    tc.scaleX = Math.max(0.01f, tc.scaleX * ksx);
                    tc.scaleY = Math.max(0.01f, tc.scaleY * ksy);
                    tc.scaleZ = Math.max(0.01f, tc.scaleZ * ksz);
                }
            }
            tc.posX = groupStartLocal[b] + offX; tc.posY = groupStartLocal[b + 1] + offY; tc.posZ = groupStartLocal[b + 2] + offZ;
            tc.markDirty();
        }
    }

    /** Left-button release ending a group drag: every member that changed is recorded as one undo step. */
    public void finishGroupDrag(EditorWorkspace manager) {
        List<ICommand> commands = new ArrayList<>();
        for (int i = 0; i < groupEntities.size(); i++) {
            Transform tc = groupEntities.get(i).getTransform();
            if (tc == null) continue;
            float[] st = Arrays.copyOfRange(groupStartLocal, i * 9, i * 9 + 9);
            if (st[0] == tc.posX && st[1] == tc.posY && st[2] == tc.posZ && st[3] == tc.rotX && st[4] == tc.rotY && st[5] == tc.rotZ
                    && st[6] == tc.scaleX && st[7] == tc.scaleY && st[8] == tc.scaleZ) continue;
            commands.add(new EditorCommands.TransformCommand(tc, st[0], st[1], st[2], st[3], st[4], st[5], st[6], st[7], st[8],
                    tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ));
        }
        int count = groupEntities.size();
        groupEntities.clear();
        setActiveAxis(GizmoAxis.NONE);
        if (!commands.isEmpty()) {
            manager.getCommandHistory().executeCommand(new EditorCommands.CompositeCommand("Transform " + count + " Entities", commands));
        }
    }

    /** Resolves a released marquee: every entity whose world-bounds center projects inside the box is selected. */
    private void finishMarquee(EditorWorkspace manager) {
        boolean additive = manager.isShiftHeld() || manager.isToggleModifierHeld();
        if (Math.hypot(marqueeEndX - pressRefX, marqueeEndY - pressRefY) < MARQUEE_MIN_DRAG) {
            if (!additive) manager.selectEntity(null); // A background click
            return;
        }
        float[] rect = getMarqueeDesignRect();
        SceneWorld scene = manager.getSceneManager();
        float[] b = new float[6];
        List<Entity> hits = new ArrayList<>();
        for (Entity e : manager.getSceneEntities()) {
            if (!GizmoMath.computeWorldBounds(e, scene, b)) continue;
            float cx = (b[0] + b[3]) * 0.5f, cy = (b[1] + b[4]) * 0.5f, cz = (b[2] + b[5]) * 0.5f;
            if (GizmoMath.viewDepth(cx, cy, cz, manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw) <= 0.0f) continue;
            float[] p = GizmoMath.projectWorldToNdc(cx, cy, cz, manager);
            if (p[0] >= rect[0] && p[0] <= rect[2] && p[1] >= rect[1] && p[1] <= rect[3]) hits.add(e);
        }
        if (hits.isEmpty()) {
            if (!additive) manager.selectEntity(null);
        } else {
            manager.setSelection(hits, null, additive);
        }
    }

    private void startBoneDrag(Entity entity, int boneIndex, GizmoAxis axis) {
        AnimatorComponent animator = entity.getComponent(AnimatorComponent.class);
        float[] local = (animator != null) ? animator.getLocalPose(boneIndex) : null;
        if (local == null) return;
        System.arraycopy(local, 0, boneDragStartLocal, 0, 16);
        boneDragStartActive = animator.editPoseActive[boneIndex];
        boneDragEntity = entity;
        boneDragIndex = boneIndex;
        anchorFrozen = false; // Rotating and scaling a bone leave its joint in place
        this.activeGizmoAxis = axis;
        GizmoInteractor.startBoneDrag(local);
    }

    /** Applies one mouse move to the dragged bone's edit pose and rebuilds the palette so the mesh deforms live. */
    private boolean dragBone(double deltaX, double deltaY, double refX, double refY, EditorWorkspace manager) {
        AnimatorComponent animator = boneDragEntity.getComponent(AnimatorComponent.class);
        Skeleton.BoneNode bone = (animator != null && animator.skeleton != null) ? animator.skeleton.getBone(boneDragIndex) : null;
        if (bone == null) return false;
        SceneWorld sceneManager = manager.getSceneManager();
        float[] joint = jointWorldPosition(boneDragEntity, boneDragIndex, sceneManager);
        int parent = (bone.parentId >= 0 && bone.parentId < animator.skeleton.getBoneCount()) ? bone.parentId : -1;
        if (joint == null || !GizmoMath.computeBoneWorldMatrix(boneDragEntity, parent, sceneManager, boneMatrixScratch)) return false;
        if (bone.hasPreTransform) {
            // The bone's local is posed in its parent frame times the folded non-bone nodes above it
            float[] parentWorld = boneMatrixScratch.clone();
            AnimationSystem.multiply4x4(parentWorld, bone.preTransform, boneMatrixScratch);
        }

        return GizmoInteractor.applyBoneDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, animator,
                boneDragIndex, boneMatrixScratch, joint[0], joint[1], joint[2], manager.getCameraController(),
                camPanX, camPanY, camZoom, camPitch, camYaw, sceneManager, boneDragLocal);
    }

    /**
     * Left-button release ending a bone drag: an actual change becomes one undoable pose edit, and the bone's new pose
     * is keyed into the animator's recorded pose.
     */
    public void finishBoneDrag(EditorWorkspace manager) {
        Entity entity = boneDragEntity;
        int boneIndex = boneDragIndex;
        setActiveAxis(GizmoAxis.NONE);
        AnimatorComponent animator = (entity != null) ? entity.getComponent(AnimatorComponent.class) : null;
        float[] local = (animator != null) ? animator.getLocalPose(boneIndex) : null;
        if (local == null) return;
        if (Arrays.equals(local, boneDragStartLocal) && animator.editPoseActive[boneIndex] == boneDragStartActive) return;

        manager.getCommandHistory().executeCommand(new EditorCommands.BonePoseCommand(manager.getSceneManager(), animator, boneIndex,
                boneDragStartLocal.clone(), boneDragStartActive, local.clone(), animator.editPoseActive[boneIndex]));
        manager.recordBoneKeyframe(entity, boneIndex);
    }

    /** Left-button release anywhere: a gizmo pick still in flight resolves without starting a drag; a marquee resolves. */
    public void onPrimaryRelease(EditorWorkspace manager) {
        viewportPressHeld = false;
        if (marqueeActive) {
            marqueeActive = false;
            if (manager != null) finishMarquee(manager);
        }
    }

    public boolean onCursorPos(double rawX, double rawY, EditorWorkspace manager, OverlaySystem overlayManager) {
        if (manager == null) return false;

        // Gizmo math is authored against the Scene panel's design rect; map the docked tile back into it
        float screenX = (float) ((rawX / (double) manager.getWindowWidth()) * 2.0 - 1.0);
        float screenY = (float) (1.0 - (rawY / (double) manager.getWindowHeight()) * 2.0);
        // Keep the GPU pick probe on the cursor so a click usually finds its texel already read back
        DockSpace dock = manager.getDockSpace();
        ViewportPanel.setPickProbeScreen(dock, screenX, screenY);
        if (dock.isVisible(DockPanel.SCENE)) {
            screenX = dock.toDesignX(DockPanel.SCENE, screenX);
            screenY = dock.toDesignY(DockPanel.SCENE, screenY);
        }
        double refX = (screenX + 1.0) * 640.0;
        double refY = (1.0 - screenY) * 360.0;

        double deltaX = refX - lastMouseX;
        double deltaY = refY - lastMouseY;
        this.lastMouseX = refX;
        this.lastMouseY = refY;

        if (overlayManager != null && overlayManager.isRightMouseDown()) {
            overlayManager.addRightClickDragDistance(Math.hypot(deltaX, deltaY));
            this.lastOrbitTime = System.currentTimeMillis();
        }

        if (SplineGizmo.isDragging()) {
            return SplineGizmo.drag(refX, refY, manager.getCameraController());
        }
        if (marqueeActive) {
            marqueeEndX = refX;
            marqueeEndY = refY;
            return true;
        }
        if (isBoneDragActive()) {
            return dragBone(deltaX, deltaY, refX, refY, manager);
        }
        if (isGroupDragActive()) {
            boolean moved = GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, groupProxy,
                    groupProxy.posX, groupProxy.posY, groupProxy.posZ, manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw);
            if (moved) applyGroupDrag();
            return moved;
        }
        if (activeGizmoAxis != GizmoAxis.NONE && manager.getSelectedEntity() != null) {
            float[] anchor = getGizmoAnchor(manager.getSelectedEntity(), manager.getSceneManager());
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, manager.getSelectedEntity(), anchor[0], anchor[1], anchor[2], manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
    }
}
