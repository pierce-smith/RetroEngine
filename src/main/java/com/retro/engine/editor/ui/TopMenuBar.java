package com.retro.engine.editor.ui;

import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.editor.EditorPanelManager.DropdownMenu;
import com.retro.engine.editor.commands.CommandHistory;

import java.io.File;
import java.util.List;

public class TopMenuBar {

    public static void render(List<Float> verts, DropdownMenu activeDropdown, String activeScenePath, PlayState playState) {
        UIBatchBuilder.addQuad(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.10f, 0.10f, 0.14f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, -1.0f, 0.92f, 1.0f, 1.0f, 0.002f, 0.20f, 0.20f, 0.28f, 1.0f);

        UIBatchBuilder.addBoundedText(verts, "RETRO ENGINE", -0.98f, 0.94f, -0.75f, 1.2f, 0.25f, 0.80f, 1.0f, 1.0f);

        renderHeaderMenuButton(verts, "FILE", -0.72f, -0.62f, activeDropdown == DropdownMenu.FILE);
        renderHeaderMenuButton(verts, "EDIT", -0.61f, -0.51f, activeDropdown == DropdownMenu.EDIT);
        renderHeaderMenuButton(verts, "ENTITY", -0.50f, -0.38f, activeDropdown == DropdownMenu.ENTITY);
        renderHeaderMenuButton(verts, "VIEW", -0.37f, -0.27f, activeDropdown == DropdownMenu.VIEW);

        renderPlayControls(verts, playState);

        String sceneName = new File(activeScenePath).getName();
        UIBatchBuilder.addBoundedText(verts, "SCENE: " + sceneName, 0.45f, 0.94f, 0.98f, 1.0f, 0.7f, 0.7f, 0.8f, 1.0f);
    }

    private static void renderPlayControls(List<Float> verts, PlayState playState) {
        float minX = -0.15f;
        float maxX = 0.25f;

        UIBatchBuilder.addQuad(verts, minX, 0.925f, maxX, 0.995f, 0.08f, 0.09f, 0.12f, 1.0f);
        UIBatchBuilder.addFrameBorder(verts, minX, 0.925f, maxX, 0.995f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);

        boolean isSimulating = (playState == PlayState.SIMULATING);
        boolean isPaused = (playState == PlayState.PAUSED);
        boolean isEditing = (playState == PlayState.EDITING);

        float rPlay = isSimulating ? 0.2f : 0.14f;
        float gPlay = isSimulating ? 0.8f : 0.18f;
        float bPlay = isSimulating ? 0.3f : 0.22f;
        UIBatchBuilder.addQuad(verts, -0.14f, 0.935f, -0.02f, 0.985f, rPlay, gPlay, bPlay, 1.0f);
        if (isSimulating) {
            UIBatchBuilder.addFrameBorder(verts, -0.14f, 0.935f, -0.02f, 0.985f, 0.002f, 0.4f, 1.0f, 0.5f, 1.0f);
        }
        UIBatchBuilder.addBoundedText(verts, "PLAY", -0.125f, 0.945f, -0.035f, 1.0f, isSimulating ? 1.0f : 0.7f, 1.0f, isSimulating ? 1.0f : 0.7f, 1.0f);

        float rPause = isPaused ? 0.8f : 0.14f;
        float gPause = isPaused ? 0.7f : 0.18f;
        float bPause = isPaused ? 0.2f : 0.22f;
        UIBatchBuilder.addQuad(verts, -0.01f, 0.935f, 0.11f, 0.985f, rPause, gPause, bPause, 1.0f);
        if (isPaused) {
            UIBatchBuilder.addFrameBorder(verts, -0.01f, 0.935f, 0.11f, 0.985f, 0.002f, 1.0f, 0.9f, 0.3f, 1.0f);
        }
        UIBatchBuilder.addBoundedText(verts, "PAUSE", 0.005f, 0.945f, 0.095f, 1.0f, isPaused ? 1.0f : 0.7f, isPaused ? 1.0f : 0.7f, 0.7f, 1.0f);

        float rStop = isEditing ? 0.22f : 0.65f;
        float gStop = isEditing ? 0.14f : 0.18f;
        float bStop = isEditing ? 0.18f : 0.22f;
        UIBatchBuilder.addQuad(verts, 0.12f, 0.935f, 0.23f, 0.985f, rStop, gStop, bStop, 1.0f);
        UIBatchBuilder.addBoundedText(verts, "STOP", 0.135f, 0.945f, 0.215f, 1.0f, isEditing ? 0.6f : 1.0f, isEditing ? 0.6f : 0.8f, isEditing ? 0.6f : 0.8f, 1.0f);
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
                String undoLabel = "Undo " + (commandHistory.canUndo() ? "(Cmd+Z)" : "[Disabled]");
                String redoLabel = "Redo " + (commandHistory.canRedo() ? "(Cmd+Shift+Z)" : "[Disabled]");
                renderDropdownItem(verts, undoLabel, minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, redoLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "Delete Selected (Del)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case ENTITY -> {
                renderDropdownItem(verts, "+ Spawn 3D Cube", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(verts, "+ Spawn Light Source", minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(verts, "+ Spawn Camera", minX + 0.01f, 0.67f, maxX - 0.01f);
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
