package com.retro.engine.editor;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

public class EditorSelectionController {

    public enum PanelType {
        TOP_BAR,
        SCENE_HIERARCHY,
        VIEWPORT_3D,
        INSPECTOR,
        ASSET_DIRECTORY,
        NONE
    }

    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;

    private EditorPanelManager panelManager;

    public EditorSelectionController() {
        this(null);
    }

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

    public void handleMouseClick(double mouseX, double mouseY) {
        handleMouseClick(mouseX, mouseY, 0, 1, this.panelManager);
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
            SceneEntity target = entities.get(index);
            manager.selectEntity(target);
            System.out.println("[Scene Hierarchy] Selected Entity: " + target.name);
        }
    }

    private void processInspectorClick(double mouseX, double mouseY, EditorPanelManager manager) {
        SceneEntity active = manager.getSelectedEntity();
        if (active == null) return;

        if (mouseY >= 140 && mouseY <= 165) {
            active.transform.posX += 0.5f;
            if (active.transform.posX > 5.0f) active.transform.posX = -5.0f;
            System.out.println("[Inspector] Mutated " + active.name + " PosX -> " + active.transform.posX);
        } else if (mouseY >= 166 && mouseY <= 190) {
            active.transform.posY += 0.5f;
            if (active.transform.posY > 5.0f) active.transform.posY = -5.0f;
            System.out.println("[Inspector] Mutated " + active.name + " PosY -> " + active.transform.posY);
        } else if (mouseY >= 191 && mouseY <= 215) {
            active.transform.rotY += 15.0f;
            if (active.transform.rotY >= 360.0f) active.transform.rotY = 0.0f;
            System.out.println("[Inspector] Mutated " + active.name + " RotY -> " + active.transform.rotY);
        }
    }

    private void processAssetClick(double mouseX, double mouseY, EditorPanelManager manager) {
        List<AssetEntry> assets = manager.getAssetDirectory();
        if (assets.isEmpty()) return;

        double startX = 20.0;
        double cardWidth = 140.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size()) {
            AssetEntry asset = assets.get(index);
            SceneEntity active = manager.getSelectedEntity();
            if (asset.type == EditorPanelManager.AssetType.TEXTURE && active != null) {
                active.textureAssetPath = asset.absolutePath;
                System.out.println("[Asset Directory] Explicitly assigned texture '" + asset.name + "' to entity '" + active.name + "'");
            } else {
                System.out.println("[Asset Directory] Focused Asset: " + asset.name);
            }
        }
    }
}
