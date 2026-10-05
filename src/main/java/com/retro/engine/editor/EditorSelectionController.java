package com.retro.engine.editor;

import com.retro.engine.editor.EditorPanelManager.AssetEntry;
import com.retro.engine.editor.EditorPanelManager.SceneEntity;

import java.util.List;

/**
 * EditorSelectionController handles panel hit-testing, mouse click routing,
 * active entity selection, and property mutation for the Retro Engine Editor.
 * Decoupled from low-level graphics backends and raw windowing code.
 */
public class EditorSelectionController {

    public enum PanelType {
        TOP_BAR,
        SCENE_HIERARCHY,
        VIEWPORT_3D,
        INSPECTOR,
        ASSET_DIRECTORY,
        NONE
    }

    private PanelType activeHoveredPanel = PanelType.NONE;
    private PanelType activeFocusedPanel = PanelType.NONE;

    // Layout region bounds for 1280x720 IDE
    public static final float TOP_BAR_HEIGHT = 30.0f;
    public static final float SIDE_PANEL_WIDTH = 280.0f;
    public static final float BOTTOM_PANEL_HEIGHT = 200.0f;
    public static final float VIEWPORT_X = 284.0f;
    public static final float VIEWPORT_Y = 30.0f;
    public static final float VIEWPORT_WIDTH = 712.0f;
    public static final float VIEWPORT_HEIGHT = 486.0f;

    private int selectedHierarchyIndex = -1;
    private int selectedAssetIndex = -1;

    public EditorSelectionController() {}

    public EditorSelectionController(EditorPanelManager panelManager) {}

    /**
     * Identifies which panel region contains the given mouse coordinates.
     */
    public PanelType hitTest(double mouseX, double mouseY, float windowWidth, float windowHeight) {
        if (mouseY < TOP_BAR_HEIGHT) {
            return PanelType.TOP_BAR;
        }
        if (mouseY >= windowHeight - BOTTOM_PANEL_HEIGHT) {
            return PanelType.ASSET_DIRECTORY;
        }
        if (mouseX < SIDE_PANEL_WIDTH) {
            return PanelType.SCENE_HIERARCHY;
        }
        if (mouseX >= windowWidth - SIDE_PANEL_WIDTH) {
            return PanelType.INSPECTOR;
        }
        if (mouseX >= VIEWPORT_X && mouseX <= VIEWPORT_X + VIEWPORT_WIDTH &&
            mouseY >= VIEWPORT_Y && mouseY <= VIEWPORT_Y + VIEWPORT_HEIGHT) {
            return PanelType.VIEWPORT_3D;
        }
        return PanelType.NONE;
    }

    /**
     * Processes mouse click events, routing interactions to the targeted editor panel.
     */
    public void handleMouseClick(double mouseX, double mouseY, int button, EditorPanelManager panelManager) {
        PanelType clickedPanel = hitTest(mouseX, mouseY, 1280.0f, 720.0f);
        this.activeFocusedPanel = clickedPanel;

        switch (clickedPanel) {
            case SCENE_HIERARCHY -> processHierarchyClick(mouseY, panelManager);
            case INSPECTOR -> processInspectorClick(mouseX, mouseY, panelManager);
            case ASSET_DIRECTORY -> processAssetClick(mouseX, mouseY, panelManager);
            case VIEWPORT_3D -> System.out.println("[SelectionController] Focus: 3D Viewport | Mouse: (" + (int) mouseX + ", " + (int) mouseY + ")");
            default -> {}
        }
    }

    /**
     * Handles item selection inside the Scene Hierarchy panel (Left).
     */
    private void processHierarchyClick(double mouseY, EditorPanelManager panelManager) {
        List<SceneEntity> entities = panelManager.getSceneEntities();
        if (entities.isEmpty()) return;

        double startY = TOP_BAR_HEIGHT + 30.0;
        double itemHeight = 24.0;

        int index = (int) ((mouseY - startY) / itemHeight);
        if (index >= 0 && index < entities.size()) {
            selectedHierarchyIndex = index;
            SceneEntity target = entities.get(index);
            panelManager.selectEntity(target);
            System.out.println("[Scene Hierarchy] Selected Entity [" + index + "]: " + target.name);
        }
    }

    /**
     * Handles property field clicks inside the Inspector panel (Right).
     */
    private void processInspectorClick(double mouseX, double mouseY, EditorPanelManager panelManager) {
        SceneEntity active = panelManager.getSelectedEntity();
        if (active == null) {
            System.out.println("[Inspector] No entity selected.");
            return;
        }

        // Toggles or increments transform values on click for interactive testing
        if (mouseY >= 100 && mouseY <= 130) {
            active.transform.posX += 0.5f;
            System.out.println("[Inspector] Mutated " + active.name + " PosX -> " + active.transform.posX);
        } else if (mouseY >= 135 && mouseY <= 165) {
            active.transform.posY += 0.5f;
            System.out.println("[Inspector] Mutated " + active.name + " PosY -> " + active.transform.posY);
        } else if (mouseY >= 170 && mouseY <= 200) {
            active.transform.rotY += 15.0f;
            System.out.println("[Inspector] Mutated " + active.name + " RotY -> " + active.transform.rotY);
        }
    }

    /**
     * Handles asset selection inside the Asset Directory panel (Bottom).
     */
    private void processAssetClick(double mouseX, double mouseY, EditorPanelManager panelManager) {
        List<AssetEntry> assets = panelManager.getAssetDirectory();
        if (assets.isEmpty()) return;

        double startX = 20.0;
        double cardWidth = 140.0;

        int index = (int) ((mouseX - startX) / cardWidth);
        if (index >= 0 && index < assets.size()) {
            selectedAssetIndex = index;
            AssetEntry asset = assets.get(index);
            System.out.println("[Asset Directory] Focused Asset [" + index + "]: " + asset.name);
        }
    }

    public PanelType getActiveHoveredPanel() {
        return activeHoveredPanel;
    }

    public PanelType getActiveFocusedPanel() {
        return activeFocusedPanel;
    }

    public int getSelectedHierarchyIndex() {
        return selectedHierarchyIndex;
    }

    public int getSelectedAssetIndex() {
        return selectedAssetIndex;
    }
}
