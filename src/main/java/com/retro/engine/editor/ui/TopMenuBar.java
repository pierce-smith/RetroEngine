package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.commands.CommandHistory;

import java.io.File;
import java.util.List;

/**
 * TopMenuBar encapsulates rendering for the top header menu bar and dropdown overlays.
 */
public class TopMenuBar {

    public static void render(List<Float> verts, DropdownMenu activeDropdownMenu, String activeScenePath) {
        UIBatchBuilder.addQuad(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.10f, 0.10f, 0.14f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.002f, 0.20f, 0.20f, 0.28f, 1.0f);

        UIBatchBuilder.addBoundedText(verts, "RETRO ENGINE", -0.98f, 0.94f, -0.75f, 1.2f, 0.25f, 0.80f, 1.0f, 1.0f);

        renderHeaderMenuButton(verts, "FILE", -0.72f, -0.62f, activeDropdownMenu == DropdownMenu.FILE);
        renderHeaderMenuButton(verts, "EDIT", -0.61f, -0.51f, activeDropdownMenu == DropdownMenu.EDIT);
        renderHeaderMenuButton(verts, "ENTITY", -0.50f, -0.38f, activeDropdownMenu == DropdownMenu.ENTITY);
        renderHeaderMenuButton(verts, "VIEW", -0.37f, -0.27f, activeDropdownMenu == DropdownMenu.VIEW);

        String sceneName = (activeScenePath != null) ? new File(activeScenePath).getName() : "Untitled.scene";
        UIBatchBuilder.addBoundedText(verts, "SCENE: " + sceneName, 0.35f, 0.94f, 0.98f, 1.0f, 0.7f, 0.7f, 0.8f, 1.0f);
    }

    private static void renderHeaderMenuButton(List<Float> verts, String label, float minX, float maxX, boolean isActive) {
        float r = isActive ? 0.22f : 0.14f;
        float g = isActive ? 0.28f : 0.14f;
        float b = isActive ? 0.38f : 0.18f;

        UIBatchBuilder.addQuad(verts, minX, 0.93f, maxX, 0.99f, r, g, b, 1.0f);
        if (isActive) {
            UIBatchBuilder.addFrameBorder(verts, minX, 0.93f, maxX, 0.99f, 0.002f, 0.0f, 0.6f, 1.0f, 1.0f);
        }
        UIBatchBuilder.addBoundedText(verts, label, minX + 0.015f, 0.945f, maxX - 0.005f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void renderDropdownMenuOverlay(List<Float> verts, DropdownMenu menu, CommandHistory commandHistory, boolean show3DGrid) {
        if (menu == DropdownMenu.NONE) return;

        float minX = switch (menu) {
            case FILE -> -0.72f;
            case EDIT -> -0.61f;
            case ENTITY -> -0.50f;
            case VIEW -> -0.37f;
            default -> -0.72f;
        };
        float maxX = minX + 0.32f;
        float minY = 0.58f;
        float maxY = 0.92f;

        UIBatchBuilder.addQuad(verts, minX, minY, maxX, maxY, 0.12f, 0.12f, 0.16f, 0.98f);
        UIBatchBuilder.addFrameBorder(verts, minX, minY, maxX, maxY, 0.003f, 0.0f, 0.55f, 0.90f, 1.0f);

        switch (menu) {
            case FILE -> {
                renderDropdownItem(verts, "New Scene", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, "Open Scene... (Cmd+O)", minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "Save Scene... (Cmd+S)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case EDIT -> {
                boolean canUndo = commandHistory != null && commandHistory.canUndo();
                boolean canRedo = commandHistory != null && commandHistory.canRedo();
                String undoLabel = "Undo " + (canUndo ? "(Cmd+Z)" : "[Disabled]");
                String redoLabel = "Redo " + (canRedo ? "(Cmd+Shift+Z)" : "[Disabled]");
                renderDropdownItem(verts, undoLabel, minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, redoLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "Delete Selected (Del)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case ENTITY -> {
                renderDropdownItem(verts, "+ Spawn 3D Cube", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, "+ Spawn Light Source", minX + 0.01f, 0.76f, maxX - 0.01f);
            }
            case VIEW -> {
                renderDropdownItem(verts, "Reset Viewport Camera (F)", minX + 0.01f, 0.85f, maxX - 0.01f);
                String gridLabel = show3DGrid ? "Hide 3D Grid" : "Show 3D Grid";
                renderDropdownItem(verts, gridLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
            }
            default -> {}
        }
    }

    private static void renderDropdownItem(List<Float> verts, String text, float minX, float y, float maxX) {
        UIBatchBuilder.addQuad(verts, minX, y - 0.02f, maxX, y + 0.05f, 0.16f, 0.18f, 0.24f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, minX, y - 0.02f, maxX, y + 0.05f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);
        UIBatchBuilder.addBoundedText(verts, text, minX + 0.01f, y + 0.01f, maxX - 0.01f, 1.0f, 0.95f, 0.95f, 0.95f, 1.0f);
    }
}
