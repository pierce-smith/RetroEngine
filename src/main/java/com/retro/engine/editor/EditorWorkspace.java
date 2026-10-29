package com.retro.engine.editor;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.animation.state.AnimatorTransition;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.editor.commands.CommandStack;
import com.retro.engine.editor.gizmos.ColliderDebugRenderer;
import com.retro.engine.editor.gizmos.GizmoState;
import com.retro.engine.editor.gizmos.GizmoSystem;
import com.retro.engine.editor.ui.UIContext;
import com.retro.engine.editor.ui.animator.AnimatorGraphPanel;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.dock.DockPanel;
import com.retro.engine.editor.ui.dock.DockSpace;
import com.retro.engine.editor.ui.overlays.ContextMenuOverlay;
import com.retro.engine.editor.ui.overlays.ModalDialogOverlay;
import com.retro.engine.editor.ui.overlays.OverlaySystem;
import com.retro.engine.editor.ui.overlays.ProjectLauncherOverlay;
import com.retro.engine.editor.ui.panels.*;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntitySpawner;
import com.retro.engine.scene.world.SceneWorld;
import com.retro.engine.scene.world.SceneWorld.PlayState;
import com.retro.engine.scene.components.media.AnimatorComponent;

import java.util.List;

/**
 * EditorWorkspace is the central Session Context & Service Registry for Retro Engine.
 * SRP Responsibility: Owns top-level window/viewport bounds, camera references, active workspace tab state,
 * and exposes direct getters for core domain managers (SceneWorld, AssetDatabase, GizmoState, OverlaySystem, CommandStack).
 */
@SuppressWarnings("unused")
public class EditorWorkspace {

    public enum GizmoMode { TRANSLATE, ROTATE, SCALE }
    public enum GizmoAxis { NONE, X, Y, Z }
    public enum DropdownMenu { NONE, FILE, EDIT, ENTITY, VIEW }
    public enum ModalState { NONE, ASSET_PICKER, PARENT_PICKER, BUILD_EXPORTER }
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
            if (animator == null && selected.getMeshAssetPath() != null && !selected.getMeshAssetPath().trim().isEmpty()) {
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
        ctrl.setInitialState("Idle");

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
        InspectorPanel.resetScroll();
    }

    // --- Active Project ---
    public boolean hasActiveProject() { return assetDirectoryManager.hasActiveProject(); }
    public String getActiveProjectPath() { return assetDirectoryManager.getActiveProjectPath(); }

    /** Unbinds the active project (flushing its manifest) and resets the scene; the launcher takes over the UI. */
    public void closeProject() {
        sceneManager.newScene();
        commandHistory.clear();
        overlayManager.closeModal();
        overlayManager.closeContextMenu();
        overlayManager.closeDropdownMenu();
        assetDirectoryManager.saveManifest();
        assetDirectoryManager.clear();
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
            com.retro.engine.editor.ui.overlays.BuildExporterOverlay.render(uiGeometryBuffer);
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
                if (selected != null && overlayManager.getActiveModal() == ModalState.NONE) {
                    SceneWorld.WorldTransform wt = sceneManager.getComputedWorldTransform(selected);
                    GizmoSystem.render3DGizmosAtWorldPosition(
                        uiGeometryBuffer, wt.px(), wt.py(), wt.pz(), gizmoStateController.getGizmoMode(),
                        gizmoStateController.getActiveAxis(), cameraController, gizmoStateController.getCamPanX(),
                        gizmoStateController.getCamPanY(), gizmoStateController.getCamZoom(),
                        gizmoStateController.getCamPitch(), gizmoStateController.getCamYaw()
                    );
                }

                ViewportPanel.renderCameraPreviewHUD(uiGeometryBuffer, selected, cameraPreviewTexture);

                EngineConsolePanel.renderProfilerHUD(uiGeometryBuffer, lastDeltaTime);
            }
            case HIERARCHY -> HierarchyPanel.render(uiContext, sceneManager.getSceneEntities());
            case INSPECTOR -> InspectorPanel.render(uiContext, sceneManager.getSelectedEntity(), assetDirectoryManager.getAssetDirectory());
            case ANIMATOR -> AnimatorGraphPanel.renderCanvas(uiGeometryBuffer, getActiveAnimatorController(), selectedStateName);
            case ANIMATOR_PARAMETERS -> AnimatorGraphPanel.renderParameters(uiGeometryBuffer, getActiveAnimatorController());
            case ANIMATOR_INSPECTOR -> AnimatorGraphPanel.renderInspector(uiGeometryBuffer, getActiveAnimatorController(), selectedStateName, selectedTransition);
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
    }
}
