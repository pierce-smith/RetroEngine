package com.retro.engine.editor.gizmos;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorCommands;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.GizmoAxis;
import com.retro.engine.editor.EditorWorkspace.GizmoMode;
import com.retro.engine.editor.overlays.OverlaySystem;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.editor.panels.ViewportPanel;

import java.util.Arrays;

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
        }
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
        manager.selectEntity(ViewportPanel.resolvePick(pickId, manager.getSceneEntities()));
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

    /** Left-button release anywhere: a gizmo pick still in flight resolves without starting a drag. */
    public void onPrimaryRelease() {
        viewportPressHeld = false;
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

        if (isBoneDragActive()) {
            return dragBone(deltaX, deltaY, refX, refY, manager);
        }
        if (activeGizmoAxis != GizmoAxis.NONE && manager.getSelectedEntity() != null) {
            float[] anchor = getGizmoAnchor(manager.getSelectedEntity(), manager.getSceneManager());
            return GizmoInteractor.handleGizmoDrag(deltaX, deltaY, refX, refY, activeGizmoAxis, currentGizmoMode, manager.getSelectedEntity(), anchor[0], anchor[1], anchor[2], manager.getCameraController(), camPanX, camPanY, camZoom, camPitch, camYaw);
        }
        return false;
    }
}
