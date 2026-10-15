package com.retro.engine.editor.render;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.EditorPanelManager.ModalState;
import com.retro.engine.editor.input.GizmoInteractor;
import com.retro.engine.editor.ui.*;

import java.util.ArrayList;
import java.util.List;

/**
 * EditorUiComposer aggregates vertex batch generation across all UI panels,
 * 3D gizmos, top menus, and modal overlays into a single vertex array for RHI submission.
 */
public class EditorUiComposer {

    public static float[] composeUiVertices(EditorPanelManager manager) {
        if (manager == null) return new float[0];

        List<Float> verts = new ArrayList<>();
        SceneHierarchyPanel.render(verts, manager.getSceneEntities());
        InspectorPanel.render(verts, manager.getSelectedEntity(), manager.getAssetDirectory());
        AssetDirectoryPanel.render(verts, manager.getAssetDirectory());
        ViewportPanel.renderFraming(verts);
        ViewportPanel.renderToolbar(verts, manager.getCurrentGizmoMode());
        TopMenuBar.render(verts, manager.getActiveDropdownMenu(), manager.getActiveScenePath(), manager.getPlayState());

        SceneEntity activeEntity = manager.getSelectedEntity();
        if (activeEntity != null) {
            GizmoInteractor.render3DGizmos(
                verts,
                activeEntity,
                manager.getCurrentGizmoMode(),
                manager.getActiveGizmoAxis(),
                manager.getCameraController(),
                0.0f, 0.25f, -5.0f, 0.0f, 0.0f
            );
        }

        if (manager.getActiveDropdownMenu() != DropdownMenu.NONE) {
            TopMenuBar.renderDropdownMenuOverlay(verts, manager.getActiveDropdownMenu(), manager.getCommandHistory(), manager.is3DGridVisible());
        }
        if (manager.getActiveModal() == ModalState.ASSET_PICKER) {
            ModalOverlayManager.renderAssetPickerModal(verts, manager.getPickerFilterType(), manager.getAssetDirectory());
        }

        float[] array = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) array[i] = verts.get(i);
        return array;
    }
}
