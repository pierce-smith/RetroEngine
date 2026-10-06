package com.retro.engine.editor;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

/**
 * EditorSelectionController handles panel region hit-testing and input routing:
 * - Clicking 3D asset cards in Asset Directory spawns entities in Scene Hierarchy.
 * - Clicking texture asset cards links textures to the active selected entity.
 */
public class EditorSelectionController {

    public enum PanelType {
        TOP_BAR, SCENE_HIERARCHY, VIEWPORT_3D, INSPECTOR, ASSET_DIRECTORY, NONE
    }

    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;

    private EditorPanelManager panelManager;

    public EditorSelectionController() { this(null); }

    public EditorSelectionController(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public void setPanelManager(EditorPanelManager panelManager) {
        this.panelManager = panelManager;
    }

    public PanelType hitTest(double mouseX, double mouseY, float windowWidth, float windowHeight) {
        if (mouseY < TOP_BAR_HEIGHT) return PanelType.TOP_BAR;
        if (mouseY >= windowHeight - BOTTOM_PANEL_HEIGHT) return PanelType.ASSET_DIRECTORY;
        if (mouseX < SIDE_PANEL_WIDTH) return PanelType.SCENE_HIERARCHY;
        if (mouseX >= windowWidth - SIDE_PANEL_WIDTH) return PanelType.INSPECTOR;
        return PanelType.VIEWPORT_3D;
    }

    public void handleMouseClick(double mouseX, double mouseY, int button) {
        handleMouseClick(mouseX, mouseY, button, 1, this.panelManager);
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, EditorPanelManager manager) {
        handleMouseClick(mouseX, mouseY, button, 1, manager);
    }

    public void handleMouseClick(double mouseX, double mouseY, int button, int action, EditorPanelManager manager) {
        EditorPanelManager activeManager = manager != null ? manager : this.panelManager;
        if (activeManager == null) return;

        PanelType clickedPanel = hitTest(mouseX, mouseY, 1280.0f, 720.0f);

        switch (clickedPanel) {
            case SCENE_HIERARCHY -> processHierarchyClick(mouseY, activeManager);
            case INSPECTOR -> processInspectorClick(mouseX, mouseY, activeManager);
            case ASSET_DIRECTORY -> processAssetClick(mouseX, mouseY, activeManager);
            default -> {}
        }
    }

    private void processHierarchyClick(double mouseY, EditorPanelManager manager) {
        List<SceneEntity> entities = manager.getSceneEntities();
        if (entities.isEmpty()) return;

        double startY = TOP_BAR_HEIGHT + 30.0;
        double itemHeight = 24.0;

        int index = (int) ((mouseY - startY) / itemHeight);
        if (index >= 0 && index < entities.size()) {
            manager.selectEntity(entities.get(index));
            System.out.println("[Scene Hierarchy] Selected Entity: " + entities.get(index).name);
        }
    }

    private void processInspectorClick(double mouseX, double mouseY, EditorPanelManager manager) {
        SceneEntity active = manager.getSelectedEntity();
        if (active == null) return;

        // Click texture selector box to cycle through available loaded textures
        if (mouseY >= 260.0 && mouseY <= 290.0) {
            List<AssetEntry> assets = manager.getAssetDirectory();
            List<AssetEntry> textures = assets.stream().filter(a -> a.type == EditorPanelManager.AssetType.TEXTURE).toList();
            if (!textures.isEmpty()) {
                AssetEntry nextTex = textures.get(0);
                manager.assignTextureToSelectedEntity(nextTex);
            }
        }
    }

    private void processAssetClick(double mouseX, double mouseY, EditorPanelManager manager) {
        List<AssetEntry> assets = manager.getAssetDirectory();
        if (assets.isEmpty()) return;

        double startX = 20.0;
        double cardWidth = 230.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size()) {
            AssetEntry clickedAsset = assets.get(index);
            if (clickedAsset.type == EditorPanelManager.AssetType.MESH_3D) {
                manager.spawnEntityFromAsset(clickedAsset);
            } else if (clickedAsset.type == EditorPanelManager.AssetType.TEXTURE) {
                manager.assignTextureToSelectedEntity(clickedAsset);
            }
        }
    }
}
