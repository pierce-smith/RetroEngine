package com.retro.engine.editor;

import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimationClipIO;
import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.animation.AnimatorTransition;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.core.ProjectSettings;
import com.retro.engine.ecs.*;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.EntitySpawner;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.SceneWorld.PlayState;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.editor.animator.AnimatorGraphPanel;
import com.retro.engine.editor.gizmos.ColliderDebugRenderer;
import com.retro.engine.editor.gizmos.GizmoRenderer;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.overlays.ContextMenuOverlay;
import com.retro.engine.editor.overlays.ModalDialogOverlay;
import com.retro.engine.editor.overlays.OverlaySystem;
import com.retro.engine.editor.overlays.ProjectLauncherOverlay;
import com.retro.engine.editor.overlays.ProjectSettingsOverlay;
import com.retro.engine.editor.panels.*;
import com.retro.engine.editor.panels.DockPanel;
import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.ui.UIGeometryBatch;

import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * EditorWorkspace is the central Session Context & Service Registry for RetroEngine.
 * SRP Responsibility: Owns top-level window/viewport bounds, camera references, active workspace tab state,
 * and exposes direct getters for core domain managers (SceneWorld, AssetDatabase, GizmoState, OverlaySystem, CommandStack).
 */
@SuppressWarnings("unused")
public class EditorWorkspace {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z, CENTER }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER, PARENT_PICKER, BUILD_EXPORTER, PROJECT_SETTINGS }
    public enum WorkspaceTab { SCENE_3D, ANIMATOR }

    // Domain Subsystem Managers (Service Registry)
    private final SceneWorld sceneManager;
    private final AssetDatabase assetDirectoryManager = new AssetDatabase();
    private final GizmoState gizmoStateController = new GizmoState();
    private final OverlaySystem overlayManager = new OverlaySystem();
    private final CommandStack commandHistory = new CommandStack();

    // Session Environment State
    private WorkspaceTab activeWorkspaceTab = WorkspaceTab.SCENE_3D;
    private AnimatorController activeAnimatorController = null;
    private String selectedStateName = "Idle";
    private AnimatorTransition selectedTransition = null;

    // Bone picked in the Viewport; only meaningful while its entity is still the selected entity
    private Entity selectedBoneEntity = null;
    private int selectedBoneIndex = -1;

    private ViewportCamera cameraController;
    private long windowHandle = 0L;
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private float lastDeltaTime = 0.016f;
    private long cameraPreviewTexture = 0L;

    private float lastNdcX = 0.0f;
    private float lastNdcY = 0.0f;

    private float[] cachedUiVertices = new float[16384 * UIGeometryBatch.VERTEX_STRIDE];
    private int cachedUiVertexCount = 0;
    // Editor UI vertex buffer + dock layout; panels receive it rather than reaching for global UI state
    private final UIContext uiContext = new UIContext(16384 * UIGeometryBatch.VERTEX_STRIDE);
    private final UIGeometryBatch.Buffer uiGeometryBuffer = uiContext.geometry();
    private final DockSpace dockSpace = uiContext.dock();

    public EditorWorkspace() { this(new SceneWorld()); }

    public EditorWorkspace(SceneWorld sceneManager) {
        this.sceneManager = (sceneManager != null) ? sceneManager : new SceneWorld();
        overlayManager.setDockSpace(dockSpace);
        EngineConsolePanel.installConsoleRedirect();
        autoSaveSystem.start();
    }

    private final AutoSaveSystem autoSaveSystem = new AutoSaveSystem(this);
    public AutoSaveSystem getAutoSaveSystem() { return autoSaveSystem; }

    /**
     * OS drag-and-drop entry point (GLFW drop callback). Supported files (.png/.obj/.gltf/.rscene) go to the panel
     * under the cursor (Viewport or Asset Browser); anything else, or drops elsewhere, keeps the plain asset import.
     */
    public void handleFileDrop(List<String> files) {
        if (files == null || files.isEmpty() || !hasActiveProject()) return;
        List<String> supported = new java.util.ArrayList<>(), other = new java.util.ArrayList<>();
        for (String f : files) (AssetBrowserPanel.isSupportedDrop(f) ? supported : other).add(f);

        PanelRegion region = PanelRegion.ASSET_DIRECTORY;
        if (windowHandle != 0L && windowWidth > 0 && windowHeight > 0) {
            double[] cx = new double[1], cy = new double[1];
            org.lwjgl.glfw.GLFW.glfwGetCursorPos(windowHandle, cx, cy);
            float ndcX = (float) (cx[0] / windowWidth * 2.0 - 1.0);
            float ndcY = (float) (1.0 - cy[0] / windowHeight * 2.0);
            region = PanelRegion.getRegionAt(ndcX, ndcY, overlayManager, dockSpace);
        }
        if (!supported.isEmpty()) {
            if (region == PanelRegion.VIEWPORT) ViewportPanel.handleFileDrop(this, supported);
            else if (region == PanelRegion.ASSET_DIRECTORY) AssetBrowserPanel.handleFileDrop(this, supported);
            else other.addAll(supported);
        }
        if (!other.isEmpty()) assetDirectoryManager.onFilesDropped(other);
    }

    // --- Subsystem Service Registry Getters ---
    public SceneWorld getSceneManager() { return sceneManager; }
    public AssetDatabase getAssetDatabase() { return assetDirectoryManager; }
    public GizmoState getGizmoStateController() { return gizmoStateController; }
    public OverlaySystem getOverlayManager() { return overlayManager; }
    public CommandStack getCommandHistory() { return commandHistory; }
    public UIContext getUIContext() { return uiContext; }
    public DockSpace getDockSpace() { return dockSpace; }

    // --- Session Window & Camera Properties ---
    public void setWindowHandle(long handle) { this.windowHandle = handle; }
    public long getWindowHandle() { return windowHandle; }

    public void setWindowDimensions(int width, int height) {
        if (width > 0 && height > 0) {
            this.windowWidth = width;
            this.windowHeight = height;
        }
    }

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }

    public void setCameraController(ViewportCamera cameraController) {
        this.cameraController = cameraController;
        overlayManager.setCameraController(cameraController);
    }
    public ViewportCamera getCameraController() { return cameraController; }
    public void resetViewportCamera() { if (cameraController != null) cameraController.reset(); }

    public float getLastNdcX() { return lastNdcX; }
    public float getLastNdcY() { return lastNdcY; }
    public void setLastNdcPos(float ndcX, float ndcY) { this.lastNdcX = ndcX; this.lastNdcY = ndcY; }

    // --- Active Workspace Tab & Animator State ---
    // The focused workspace follows whichever of the docked Scene / Animator tabs the user last activated
    public WorkspaceTab getActiveWorkspaceTab() {
        activeWorkspaceTab = (dockSpace.getFocusedWorkspace() == DockPanel.ANIMATOR) ? WorkspaceTab.ANIMATOR : WorkspaceTab.SCENE_3D;
        return activeWorkspaceTab;
    }
    public void setActiveWorkspaceTab(WorkspaceTab tab) {
        if (tab == null) return;
        this.activeWorkspaceTab = tab;
        dockSpace.activate(tab == WorkspaceTab.ANIMATOR ? DockPanel.ANIMATOR : DockPanel.SCENE);
    }

    public String getSelectedStateName() { return selectedStateName; }
    public void setSelectedStateName(String name) { if (name != null) this.selectedStateName = name; }
    public void clearAnimatorSelection() { this.selectedStateName = null; this.selectedTransition = null; }

    public AnimatorController getActiveAnimatorController() {
        Entity selected = sceneManager.getSelectedEntity();
        if (selected != null) {
            AnimatorComponent animator = selected.getComponent(AnimatorComponent.class);
            if ((animator == null || animator.skeleton == null) && selected.getMeshAssetPath() != null && !selected.getMeshAssetPath().trim().isEmpty()) {
                EntitySpawner.attachAnimatorIfRigged(selected, selected.getMeshAssetPath());
                animator = selected.getComponent(AnimatorComponent.class);
            }
            if (animator != null && animator.controller != null && !animator.controller.getStates().isEmpty()) {
                activeAnimatorController = animator.controller;
                return animator.controller;
            }
        }

        if (activeAnimatorController != null && !activeAnimatorController.getStates().isEmpty()) {
            return activeAnimatorController;
        }

        for (Entity e : sceneManager.getSceneEntities()) {
            AnimatorComponent anim = e.getComponent(AnimatorComponent.class);
            if (anim != null && anim.controller != null && !anim.controller.getStates().isEmpty()) {
                activeAnimatorController = anim.controller;
                return anim.controller;
            }
        }

        if (activeAnimatorController == null || activeAnimatorController.getStates().isEmpty()) {
            activeAnimatorController = createDefaultAnimatorController();
        }
        return activeAnimatorController;
    }

    /**
     * The animator whose live state the animator panels show and edit: the selected entity's when it owns the active
     * controller, else the animator that does. Null for the placeholder controller.
     */
    public AnimatorComponent getActiveAnimator() {
        AnimatorController controller = getActiveAnimatorController();
        Entity selected = sceneManager.getSelectedEntity();
        AnimatorComponent animator = (selected != null) ? selected.getComponent(AnimatorComponent.class) : null;
        if (animator != null && animator.controller == controller) return animator;
        for (Entity e : sceneManager.getSceneEntities()) {
            AnimatorComponent anim = e.getComponent(AnimatorComponent.class);
            if (anim != null && anim.controller == controller) return anim;
        }
        return null;
    }

    private AnimatorController createDefaultAnimatorController() {
        AnimatorController ctrl = new AnimatorController("ArmsAnimatorController");
        ctrl.setFloat("Speed", 1.25f);
        ctrl.setBool("IsGrounded", true);

        AnimatorState idle = new AnimatorState("Idle", null, 1.0f, true);
        idle.graphX = -0.20f; idle.graphY = 0.65f;

        AnimatorState walk = new AnimatorState("Walk", null, 1.0f, true);
        walk.graphX = 0.18f; walk.graphY = 0.65f;

        AnimatorState run = new AnimatorState("Run", null, 1.0f, true);
        run.graphX = 0.18f; run.graphY = 0.25f;

        ctrl.addState(idle);
        ctrl.addState(walk);
        ctrl.addState(run);
        ctrl.setEntryState("Idle");

        return ctrl;
    }

    public void setActiveAnimatorController(AnimatorController controller) { this.activeAnimatorController = controller; }

    // --- High-Level Convenience Accessors ---
    public List<Entity> getSceneEntities() { return sceneManager.getSceneEntities(); }
    public Entity getSelectedEntity() { return sceneManager.getSelectedEntity(); }

    /** Backend camera preview target shown in the Scene panel's picture-in-picture card; 0L shows an empty viewfinder. */
    public void setCameraPreviewTexture(long textureHandle) { this.cameraPreviewTexture = textureHandle; }
    public void selectEntity(Entity entity) {
        sceneManager.selectEntity(entity);
        clearBoneSelection();
        InspectorPanel.resetScroll();
    }

    // --- Multi-selection (see SceneWorld: the primary stays getSelectedEntity(), group tools use the whole set) ---
    public List<Entity> getSelectedEntities() { return sceneManager.getSelectedEntities(); }
    public int getSelectionCount() { return sceneManager.getSelectionCount(); }
    public boolean isSelected(Entity entity) { return sceneManager.isSelected(entity); }

    /** Cmd/Ctrl+Click: toggles {@code entity} in the selection. */
    public void toggleEntitySelection(Entity entity) {
        sceneManager.toggleSelection(entity);
        onSelectionSetChanged();
    }

    /** Replaces the selection with {@code entities}, or adds them to it when {@code additive}. */
    public void setSelection(java.util.Collection<Entity> entities, Entity primary, boolean additive) {
        if (additive) sceneManager.addToSelection(entities);
        else sceneManager.setSelection(entities, primary);
        onSelectionSetChanged();
    }

    private void onSelectionSetChanged() {
        // Bone editing is a single-entity mode; it ends as soon as a second entity joins the selection
        if (sceneManager.getSelectionCount() != 1 || selectedBoneEntity != sceneManager.getSelectedEntity()) clearBoneSelection();
        InspectorPanel.resetScroll();
    }

    /** Polled Shift state (either key). Used to read click modifiers, which the mouse callbacks do not carry. */
    public boolean isShiftHeld() {
        return windowHandle != 0L && (glfwGetKey(windowHandle, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS
            || glfwGetKey(windowHandle, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS);
    }

    /** Polled Alt / Option state (either key). */
    public boolean isAltHeld() {
        return windowHandle != 0L && (glfwGetKey(windowHandle, GLFW_KEY_LEFT_ALT) == GLFW_PRESS
            || glfwGetKey(windowHandle, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS);
    }

    /** Polled Cmd (macOS) / Ctrl state; like HotkeyRouter, either counts on every platform. */
    public boolean isToggleModifierHeld() {
        if (windowHandle == 0L) return false;
        for (int key : new int[] { GLFW_KEY_LEFT_SUPER, GLFW_KEY_RIGHT_SUPER,
                GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL }) {
            if (glfwGetKey(windowHandle, key) == GLFW_PRESS) return true;
        }
        return false;
    }

    /** Selects {@code entity} (if it is not already selected) and the joint {@code boneIndex} of its skeleton. */
    public void selectBone(Entity entity, int boneIndex) {
        if (entity == null) {
            selectEntity(null);
            return;
        }
        if (sceneManager.getSelectedEntity() != entity) selectEntity(entity);
        this.selectedBoneEntity = entity;
        this.selectedBoneIndex = boneIndex;
    }

    public void clearBoneSelection() {
        this.selectedBoneEntity = null;
        this.selectedBoneIndex = -1;
    }

    /**
     * Selected bone index in the selected entity's skeleton, or -1. Selection changes that bypass {@link #selectEntity}
     * (hierarchy, undo, deletion) invalidate it here rather than leaving a stale bone on another entity.
     */
    public int getSelectedBoneIndex() {
        Entity selected = sceneManager.getSelectedEntity();
        if (selected == null || selected != selectedBoneEntity) return -1;
        Skeleton skeleton = skeletonOf(selected);
        int boneCount = (skeleton != null) ? Math.min(skeleton.getBoneCount(), AnimatorComponent.MAX_BONES) : 0;
        return (selectedBoneIndex >= 0 && selectedBoneIndex < boneCount) ? selectedBoneIndex : -1;
    }

    /** The selected bone of the selected entity's skeleton, or null. */
    public Skeleton.BoneNode getSelectedBone() {
        int index = getSelectedBoneIndex();
        return (index >= 0) ? skeletonOf(sceneManager.getSelectedEntity()).getBone(index) : null;
    }

    /** True when the gizmo poses the selected bone instead of moving the entity: a bone is selected and the scene is EDITING. */
    public boolean isBoneEditActive() {
        return sceneManager.getPlayState() == PlayState.EDITING && getSelectedBoneIndex() >= 0;
    }

    /** Keys the selected bone's current pose (the 'K' hotkey); false when no bone is selected. */
    public boolean recordSelectedBoneKeyframe() {
        return recordBoneKeyframe(getSelectedEntity(), getSelectedBoneIndex());
    }

    /**
     * Records bone {@code boneIndex}'s current local position / rotation / scale (its edit pose, else its bind pose) as a
     * keyframe at the animator's record time in its recorded-pose track buffer.
     */
    public boolean recordBoneKeyframe(Entity entity, int boneIndex) {
        AnimatorComponent animator = (entity != null) ? entity.getComponent(AnimatorComponent.class) : null;
        Skeleton.BoneNode bone = (animator != null && animator.skeleton != null) ? animator.skeleton.getBone(boneIndex) : null;
        float[] local = (bone != null) ? animator.getLocalPose(boneIndex) : null;
        if (local == null) return false;

        float[] pos = new float[3], quat = new float[4], scale = new float[3];
        AnimationSystem.decomposeTRS(local, pos, quat, scale);
        animator.recordKeyframe(bone, pos, quat, scale);
        // The recorded clip is a regular controller state, so it shows on the dope sheet and can be the Play entry
        AnimationSystem.assignClipToState(animator.controller, animator.recordedPose);
        EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO,
                "[Animation] Keyed bone '" + bone.name + "' on '" + entity.name + "' at " + formatSeconds(animator.recordTime) + "s.");
        return true;
    }

    /** The selected entity's animator once its skeleton is bound, or null. */
    public AnimatorComponent getSelectedAnimator() {
        Entity selected = sceneManager.getSelectedEntity();
        AnimatorComponent animator = (selected != null) ? selected.getComponent(AnimatorComponent.class) : null;
        return (animator != null && animator.skeleton != null) ? animator : null;
    }

    /** The animator whose record playhead the dope sheet and the , / . keys drive: the selected one, while EDITING. */
    public AnimatorComponent getRecordingAnimator() {
        return (sceneManager.getPlayState() == PlayState.EDITING) ? getSelectedAnimator() : null;
    }

    /**
     * Moves the record playhead (where 'K' and drag-release keys land) and poses the keyed bones at that time, so the
     * viewport shows the recorded motion there and new edits start from it.
     */
    public void setRecordTime(float seconds) {
        AnimatorComponent animator = getRecordingAnimator();
        if (animator == null) return;
        animator.recordTime = Math.max(0.0f, seconds);
        animator.applyRecordedPose(animator.recordTime);
        sceneManager.refreshEditPose(animator);
    }

    /** Steps the record playhead by whole frames at the recorded clip's frame rate. */
    public void stepRecordFrame(int frames) {
        AnimatorComponent animator = getRecordingAnimator();
        if (animator == null) return;
        float fps = recordFrameRate(animator);
        setRecordTime((Math.round(animator.recordTime * fps) + frames) / fps);
    }

    public static float recordFrameRate(AnimatorComponent animator) {
        float fps = (animator != null && animator.recordedPose != null) ? animator.recordedPose.ticksPerSecond : 30.0f;
        return (fps > 0.0f && fps <= 240.0f) ? fps : 30.0f;
    }

    /**
     * Exports the selected animator's recorded pose to Assets/Animations/<name>.ranim (name prompted, defaulting to the
     * clip's name) and assigns the exported clip to a controller state of that name, so it can be wired into the graph
     * and played. Then offers to start a new blank recording clip; the exported state stays in the controller either way.
     */
    public void exportRecordedClip() {
        AnimatorComponent animator = getSelectedAnimator();
        if (animator == null || animator.recordedPose == null || animator.recordedPose.getTracks().isEmpty()) {
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.WARN, "[Animation] Nothing recorded to export: key a bone with K first.");
            return;
        }
        String clipName = animator.recordedPose.name;
        String suggested = (clipName == null || clipName.equals(AnimatorComponent.RECORDED_CLIP_NAME)) ? "NewClip" : clipName;
        String input = NativeFileDialog.promptInput("Export Animation Clip", "Clip name:", suggested);
        if (input == null || input.isBlank()) return;
        String name = sanitizeClipName(input);

        String assetsRoot = hasActiveProject() ? assetDirectoryManager.getProjectAssetsPath() : "Assets";
        java.io.File file = new java.io.File(new java.io.File(assetsRoot, "Animations"), name + AnimationClipIO.EXTENSION).getAbsoluteFile();
        if (!animator.exportRecordedClip(file.getPath())) {
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.ERROR, "[Animation] Failed to export clip to " + file.getPath());
            return;
        }
        assetDirectoryManager.addAssetDirectoryIfNotExists(file.getPath(), AssetType.ANIMATION);
        // A state already named after the clip (the recording's own, when exported under its name) now plays the asset
        assignClipAsset(animator, file.getPath());
        EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Animation] Exported '" + name + "' to " + file.getPath());

        if (NativeFileDialog.confirm("New Recording", "Exported '" + name + "'. Start a new blank recording clip?", true)) {
            String next = NativeFileDialog.promptInput("New Animation Clip", "Clip name:", animator.controller.uniqueStateName("NewClip"));
            if (next != null && !next.isBlank()) {
                startRecordedClip(animator, next);
                return;
            }
        }
        // Keep editing the recording: rebind it if the export took over its state (keys land there again)
        if (animator.recordedState() == null) AnimationSystem.assignClipToState(animator.controller, animator.recordedPose);
    }

    /**
     * Replaces the selected animator's recording with a blank clip named {@code name}, bound to a new controller state
     * that the dope sheet then shows, with the record playhead at 0. Asks before discarding keys. False when not EDITING
     * an animator or cancelled.
     */
    public boolean createNewRecordedClip(String name) {
        AnimatorComponent animator = getRecordingAnimator();
        if (animator == null || animator.controller == null) {
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.WARN, "[Animation] Select a rigged entity in edit mode to record a new clip.");
            return false;
        }
        AnimationClip current = animator.recordedPose;
        if (current != null && !current.getTracks().isEmpty() && animator.recordedState() != null
                && !NativeFileDialog.confirm("New Recording", "Discard the keys recorded in '" + current.name
                        + "'? Keys that were not exported are lost.", false)) {
            return false;
        }
        return startRecordedClip(animator, name);
    }

    private boolean startRecordedClip(AnimatorComponent animator, String name) {
        AnimatorState state = animator.newRecordedClip(sanitizeClipName(name));
        if (state == null) return false;
        // The previous recording's state (and transitions into it) may be gone; show the new clip on the dope sheet
        clearAnimatorSelection();
        setSelectedStateName(state.name);
        EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Animation] Started new clip '" + state.name + "' on '" + getSelectedEntity().name + "'.");
        return true;
    }

    /** Renames the selected animator's recorded clip and its controller state (starts a clip of that name if there is none). */
    public boolean renameActiveRecordedClip(String name) {
        AnimatorComponent animator = getRecordingAnimator();
        if (animator == null || animator.controller == null || name == null || name.isBlank()) return false;
        if (animator.recordedPose == null) return startRecordedClip(animator, name);

        String oldName = animator.recordedPose.name;
        String applied = animator.renameRecordedClip(sanitizeClipName(name));
        if (applied == null) return false;
        setSelectedStateName(applied);
        EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Animation] Renamed clip '" + oldName + "' to '" + applied + "'.");
        return true;
    }

    /** Clip names double as state names and .ranim file names, so keep them file-name safe. */
    private static String sanitizeClipName(String name) {
        String clean = name.trim().replaceAll("[^A-Za-z0-9 _.-]", "_");
        return clean.isEmpty() ? AnimatorComponent.RECORDED_CLIP_NAME : clean;
    }

    /** Assigns a .ranim asset to the selected animator's controller as a state (added from the graph's context menu). */
    public void addClipAsset(AssetMeta asset) {
        AnimatorComponent animator = getSelectedAnimator();
        if (animator == null || asset == null || asset.absolutePath == null) return;
        if (assignClipAsset(animator, asset.absolutePath)) {
            EngineConsolePanel.log(EngineConsolePanel.LogLevel.INFO, "[Animation] Added clip '" + asset.name + "' to the animator.");
        }
    }

    private boolean assignClipAsset(AnimatorComponent animator, String path) {
        AnimationClip clip = AnimationSystem.loadClipAsset(path);
        if (clip == null || animator.controller == null) return false;
        if (!animator.clipAssetPaths.contains(path)) animator.clipAssetPaths.add(path);
        AnimatorState state = AnimationSystem.assignClipToState(animator.controller, clip);
        if (state != null) setSelectedStateName(state.name);
        return true;
    }

    private static String formatSeconds(float seconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", seconds);
    }

    private static Skeleton skeletonOf(Entity entity) {
        AnimatorComponent animator = (entity != null) ? entity.getComponent(AnimatorComponent.class) : null;
        return (animator != null) ? animator.skeleton : null;
    }

    // --- Active Project ---
    public boolean hasActiveProject() { return assetDirectoryManager.hasActiveProject(); }
    public String getActiveProjectPath() { return assetDirectoryManager.getActiveProjectPath(); }

    // Active project's ProjectSettings.bin contents; defaults while no project is bound. Read every frame by
    // SceneRenderPipeline (render environment) and edited in place by the Project Settings modal.
    private volatile ProjectSettings projectSettings = ProjectSettings.defaults();

    public ProjectSettings getProjectSettings() { return projectSettings; }
    public void setProjectSettings(ProjectSettings settings) {
        this.projectSettings = (settings != null) ? settings : ProjectSettings.defaults();
    }

    /** Unbinds the active project (flushing its manifest) and resets the scene; the launcher takes over the UI. */
    public void closeProject() {
        sceneManager.newScene();
        clearBoneSelection();
        commandHistory.clear();
        overlayManager.closeModal();
        overlayManager.closeContextMenu();
        overlayManager.closeDropdownMenu();
        assetDirectoryManager.saveManifest();
        assetDirectoryManager.clear();
        projectSettings = ProjectSettings.defaults();
        com.retro.engine.modding.ModWorkspace.onProjectClosed();
        com.retro.engine.modding.ModLoader.unloadActive();
        ProjectLauncherOverlay.setStatus("", false);
    }

    /** Default directory for scene open dialogs: the active project's Assets/Scenes (or Assets/) folder. */
    public String getProjectScenesDir() {
        String assetsRoot = hasActiveProject() ? assetDirectoryManager.getProjectAssetsPath() : "Assets";
        java.io.File scenes = new java.io.File(assetsRoot, "Scenes");
        return (scenes.isDirectory() ? scenes : new java.io.File(assetsRoot)).getAbsolutePath() + java.io.File.separator;
    }

    public List<AssetMeta> getAssetDirectory() { return assetDirectoryManager.getAssetDirectory(); }
    public String getCurrentFolderPath() { return assetDirectoryManager.getCurrentFolderPath(); }
    public String getActiveScenePath() { return sceneManager.getActiveScenePath(); }

    // --- Session LifeCycle ---
    public PlayState getPlayState() { return sceneManager.getPlayState(); }
    public void play() { sceneManager.play(); }
    public void pause() { sceneManager.pause(); }
    public void stop() { sceneManager.stop(); }

    public void update(float deltaTime) {
        this.lastDeltaTime = deltaTime;
        assetDirectoryManager.pollFileChanges();
        sceneManager.update(deltaTime);
        autoSaveSystem.tick();
    }

    public float getLastDeltaTime() { return lastDeltaTime; }

    // --- UI Panel Geometry Batch Orchestration ---
    public float[] getUiVertices() { return generateUiVertices(); }
    public int getUiVertexCount() { return this.cachedUiVertexCount; }

    public float[] generateUiVertices() {
        uiContext.beginFrame(windowWidth, windowHeight);

        // No bound project: the launcher replaces the entire editor UI
        if (!hasActiveProject()) {
            ProjectLauncherOverlay.render(uiGeometryBuffer, lastNdcX, lastNdcY);
            return commitUiVertices();
        }

        // Master dock layout: every panel below the menu bar is a docked, tabbed tile
        dockSpace.layout(windowWidth, windowHeight);
        if (cameraController != null) cameraController.setSceneAspect(ViewportPanel.getSceneAspect(dockSpace));
        dockSpace.renderBackgrounds(uiGeometryBuffer);

        for (DockPanel panel : DockPanel.values()) {
            if (!dockSpace.isRenderable(panel)) continue;
            dockSpace.beginPanel(uiGeometryBuffer, panel);
            try {
                renderDockedPanel(panel);
            } finally {
                dockSpace.endPanel(uiGeometryBuffer);
            }
        }

        dockSpace.renderChrome(uiGeometryBuffer);

        MainMenuBar.render(uiGeometryBuffer, overlayManager.getActiveDropdownMenu(), sceneManager.getActiveScenePath(), sceneManager.getPlayState());

        dockSpace.renderDragOverlay(uiGeometryBuffer);

        if (overlayManager.getActiveDropdownMenu() != DropdownMenu.NONE) {
            MainMenuBar.renderDropdownMenuOverlay(uiGeometryBuffer, overlayManager.getActiveDropdownMenu(), commandHistory, gizmoStateController.is3DGridVisible(), lastNdcX, lastNdcY);
        }

        if (overlayManager.getActiveModal() == ModalState.ASSET_PICKER) {
            ModalDialogOverlay.renderAssetPickerModal(uiGeometryBuffer, overlayManager.getPickerFilterType(), assetDirectoryManager.getAssetDirectory());
        } else if (overlayManager.getActiveModal() == ModalState.PARENT_PICKER && overlayManager.getParentPickerTargetEntity() != null) {
            ModalDialogOverlay.renderEntityParentPickerModal(uiGeometryBuffer, overlayManager.getParentPickerTargetEntity(), sceneManager.getSceneEntities());
        } else if (overlayManager.getActiveModal() == ModalState.BUILD_EXPORTER) {
            com.retro.engine.editor.overlays.BuildExporterOverlay.render(uiGeometryBuffer);
        } else if (overlayManager.getActiveModal() == ModalState.PROJECT_SETTINGS) {
            ProjectSettingsOverlay.render(uiGeometryBuffer, projectSettings, lastNdcX, lastNdcY);
        }

        if (overlayManager.isContextMenuVisible()) {
            ContextMenuOverlay.render(uiGeometryBuffer, overlayManager.getContextMenuNdcX(), overlayManager.getContextMenuNdcY(), overlayManager.getContextMenuItems());
        }

        return commitUiVertices();
    }

    private float[] commitUiVertices() {
        int size = uiGeometryBuffer.getSize();
        if (cachedUiVertices.length < size) {
            cachedUiVertices = new float[size * 2];
        }
        System.arraycopy(uiGeometryBuffer.getArray(), 0, cachedUiVertices, 0, size);
        this.cachedUiVertexCount = size / UIGeometryBatch.VERTEX_STRIDE;

        return this.cachedUiVertices;
    }

    /** Renders one dock panel's content in its authored design space (mapped onto its tile by the dock). */
    private void renderDockedPanel(DockPanel panel) {
        switch (panel) {
            case SCENE -> {
                ViewportPanel.renderToolbar(uiGeometryBuffer, gizmoStateController.getGizmoMode());

                ColliderDebugRenderer.renderColliderWireframes(
                    uiGeometryBuffer, sceneManager.getSceneEntities(), sceneManager.getSelectedEntity(),
                    cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(),
                    gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw()
                );

                Entity selected = sceneManager.getSelectedEntity();
                // Bones are an authoring aid: hidden while the simulation runs, shown in edit mode and while paused
                if (sceneManager.getPlayState() != PlayState.SIMULATING && overlayManager.getActiveModal() == ModalState.NONE) {
                    GizmoRenderer.renderSkeletons(
                        uiGeometryBuffer, sceneManager.getSceneEntities(), sceneManager, selected, getSelectedBoneIndex(),
                        cameraController, gizmoStateController.getCamPanX(), gizmoStateController.getCamPanY(),
                        gizmoStateController.getCamZoom(), gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw()
                    );
                }

                if (selected != null && overlayManager.getActiveModal() == ModalState.NONE) {
                    float[] anchor = gizmoStateController.getGizmoAnchor(this);
                    GizmoRenderer.render3DGizmosAtWorldPosition(
                        uiGeometryBuffer, anchor[0], anchor[1], anchor[2], gizmoStateController.getGizmoMode(),
                        gizmoStateController.getActiveAxis(), cameraController, gizmoStateController.getCamPanX(),
                        gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(),
                        gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw()
                    );
                }

                ViewportPanel.renderSelectionOverlay(uiGeometryBuffer, this);

                ViewportPanel.renderCameraPreviewHUD(uiGeometryBuffer, selected, cameraPreviewTexture);

                EngineConsolePanel.renderProfilerHUD(uiGeometryBuffer, lastDeltaTime);
            }
            case HIERARCHY -> HierarchyPanel.render(uiContext, sceneManager.getSceneEntities());
            case INSPECTOR -> {
                if (sceneManager.getSelectionCount() > 1) {
                    InspectorPanel.renderMultiSelection(uiContext, sceneManager.getSelectedEntities(), sceneManager.getSelectedEntity());
                } else {
                    InspectorPanel.render(uiContext, sceneManager.getSelectedEntity(), getSelectedBone(), getSelectedBoneIndex(), assetDirectoryManager.getAssetDirectory());
                }
            }
            case ANIMATOR -> AnimatorGraphPanel.renderCanvas(uiGeometryBuffer, getActiveAnimatorController(), getActiveAnimator(), selectedStateName);
            case ANIMATOR_PARAMETERS -> AnimatorGraphPanel.renderParameters(uiGeometryBuffer, getActiveAnimatorController(), getActiveAnimator());
            case ANIMATOR_INSPECTOR -> AnimatorGraphPanel.renderInspector(uiGeometryBuffer, getActiveAnimatorController(), getActiveAnimator(), selectedStateName, selectedTransition, getRecordingAnimator());
            case ASSETS -> AssetBrowserPanel.render(uiContext, assetDirectoryManager.getAssetDirectory(), assetDirectoryManager.getCurrentFolderPath(), assetDirectoryManager.getActiveProjectPath());
            case CONSOLE -> EngineConsolePanel.renderConsole(uiGeometryBuffer);
        }
    }

    /**
     * Unbinds delegates, clears workspace overlays, resets command history,
     * and releases event listener references to prevent dangling callbacks and UI memory leaks.
     */
    public void dispose() {
        overlayManager.closeModal();
        overlayManager.closeContextMenu();
        overlayManager.closeDropdownMenu();
        assetDirectoryManager.saveManifest();
        assetDirectoryManager.clear();
        if (commandHistory != null) {
            commandHistory.clear();
        }
        if (uiGeometryBuffer != null) {
            uiGeometryBuffer.reset();
        }
        cameraController = null;
        activeAnimatorController = null;
        clearBoneSelection();
    }
}
