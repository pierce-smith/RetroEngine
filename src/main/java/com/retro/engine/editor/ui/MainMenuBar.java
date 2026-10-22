package com.retro.engine.editor.ui;

import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.commands.CommandStack;
import com.retro.engine.scene.SceneWorld.PlayState;

import java.io.File;
import java.util.List;

/**
 * MainMenuBar handles 2D UI vector geometry batching for top menu items,
 * dropdown overlays, title bar indicators, and simulation state controls.
 */
public class MainMenuBar {

    public static void render(UIGeometryBatch.Buffer buffer, DropdownMenu activeMenu, String activeScenePath, PlayState playState) {
        // Top Menu Bar Background Quad (-1.0 to 1.0, 0.92 to 1.0)
        UIGeometryBatch.addQuad(buffer, -1.0f, 0.92f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, -1.0f, 0.92f, 1.0f, 1.0f, 0.002f, 0.25f, 0.25f, 0.32f, 1.0f);

        // Retro Engine Brand Title
        UIGeometryBatch.addBoundedText(buffer, "RETRO ENGINE", -0.98f, 0.95f, -0.75f, 1.2f, 0.25f, 0.80f, 1.0f, 1.0f);

        // Menu Dropdown Titles
        renderMenuTitle(buffer, "FILE", -0.72f, -0.62f, activeMenu == DropdownMenu.FILE);
        renderMenuTitle(buffer, "EDIT", -0.61f, -0.51f, activeMenu == DropdownMenu.EDIT);
        renderMenuTitle(buffer, "ENTITY", -0.50f, -0.38f, activeMenu == DropdownMenu.ENTITY);
        renderMenuTitle(buffer, "VIEW", -0.37f, -0.27f, activeMenu == DropdownMenu.VIEW);

        // Play / Pause / Stop Controls (-0.15f to 0.23f)
        renderPlayControls(buffer, playState);

        // Scene Title (Right Side: 0.45f to 0.98f)
        String sceneName = extractSceneName(activeScenePath);
        UIGeometryBatch.addBoundedText(buffer, "SCENE: " + sceneName.toUpperCase(), 0.45f, 0.95f, 0.98f, 1.0f, 0.7f, 0.7f, 0.8f, 1.0f);
    }

    private static void renderPlayControls(UIGeometryBatch.Buffer buffer, PlayState playState) {
        float minX = -0.15f;
        float maxX = 0.23f;

        UIGeometryBatch.addQuad(buffer, minX, 0.925f, maxX, 0.995f, 0.08f, 0.09f, 0.12f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, minX, 0.925f, maxX, 0.995f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);

        boolean isSimulating = (playState == PlayState.SIMULATING);
        boolean isPaused = (playState == PlayState.PAUSED);
        boolean isEditing = (playState == PlayState.EDITING);

        // PLAY Button
        float rPlay = isSimulating ? 0.2f : 0.14f;
        float gPlay = isSimulating ? 0.8f : 0.18f;
        float bPlay = isSimulating ? 0.3f : 0.22f;
        UIGeometryBatch.addQuad(buffer, -0.14f, 0.935f, -0.02f, 0.985f, rPlay, gPlay, bPlay, 1.0f);
        if (isSimulating) {
            UIGeometryBatch.addFrameBorder(buffer, -0.14f, 0.935f, -0.02f, 0.985f, 0.002f, 0.4f, 1.0f, 0.5f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "PLAY", -0.125f, 0.945f, -0.035f, 1.0f, isSimulating ? 1.0f : 0.7f, 1.0f, isSimulating ? 1.0f : 0.7f, 1.0f);

        // PAUSE Button
        float rPause = isPaused ? 0.8f : 0.14f;
        float gPause = isPaused ? 0.7f : 0.18f;
        float bPause = isPaused ? 0.2f : 0.22f;
        UIGeometryBatch.addQuad(buffer, -0.01f, 0.935f, 0.10f, 0.985f, rPause, gPause, bPause, 1.0f);
        if (isPaused) {
            UIGeometryBatch.addFrameBorder(buffer, -0.01f, 0.935f, 0.10f, 0.985f, 0.002f, 1.0f, 0.9f, 0.3f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, "PAUSE", 0.002f, 0.945f, 0.088f, 1.0f, isPaused ? 1.0f : 0.7f, isPaused ? 1.0f : 0.7f, 0.7f, 1.0f);

        // STOP Button
        float rStop = isEditing ? 0.22f : 0.65f;
        float gStop = isEditing ? 0.14f : 0.18f;
        float bStop = isEditing ? 0.18f : 0.22f;
        UIGeometryBatch.addQuad(buffer, 0.11f, 0.935f, 0.22f, 0.985f, rStop, gStop, bStop, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "STOP", 0.125f, 0.945f, 0.205f, 1.0f, isEditing ? 0.6f : 1.0f, isEditing ? 0.6f : 0.8f, isEditing ? 0.6f : 0.8f, 1.0f);
    }

    private static void renderMenuTitle(UIGeometryBatch.Buffer buffer, String title, float minX, float maxX, boolean active) {
        float r = active ? 0.22f : 0.14f;
        float g = active ? 0.28f : 0.14f;
        float b = active ? 0.38f : 0.18f;

        UIGeometryBatch.addQuad(buffer, minX, 0.93f, maxX, 0.99f, r, g, b, 1.0f);
        if (active) {
            UIGeometryBatch.addFrameBorder(buffer, minX, 0.93f, maxX, 0.99f, 0.002f, 0.0f, 0.6f, 1.0f, 1.0f);
        }
        UIGeometryBatch.addBoundedText(buffer, title, minX + 0.015f, 0.945f, maxX - 0.005f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void renderDropdownMenuOverlay(UIGeometryBatch.Buffer buffer, DropdownMenu menu, CommandStack commandHistory, boolean show3DGrid) {
        float minX = switch (menu) {
            case FILE -> -0.72f;
            case EDIT -> -0.61f;
            case ENTITY -> -0.50f;
            case VIEW -> -0.37f;
            default -> -0.72f;
        };
        float maxX = minX + 0.36f;
        float minY = 0.50f;
        float maxY = 0.92f;

        UIGeometryBatch.addQuad(buffer, minX, minY, maxX, maxY, 0.12f, 0.12f, 0.16f, 0.98f);
        UIGeometryBatch.addFrameBorder(buffer, minX, minY, maxX, maxY, 0.003f, 0.0f, 0.55f, 0.90f, 1.0f);

        switch (menu) {
            case FILE -> {
                renderDropdownItem(buffer, "New Scene", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(buffer, "Open Scene... (Cmd+O)", minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(buffer, "Save Scene... (Cmd+S)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case EDIT -> {
                String undoLabel = "Undo " + (commandHistory.canUndo() ? "(Cmd+Z)" : "[Disabled]");
                String redoLabel = "Redo " + (commandHistory.canRedo() ? "(Cmd+Shift+Z)" : "[Disabled]");
                renderDropdownItem(buffer, undoLabel, minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(buffer, redoLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(buffer, "Delete Selected (Del)", minX + 0.01f, 0.67f, maxX - 0.01f);
            }
            case ENTITY -> {
                renderDropdownItem(buffer, "+ Spawn 3D Cube", minX + 0.01f, 0.85f, maxX - 0.01f);
                renderDropdownItem(buffer, "+ Spawn Light Source", minX + 0.01f, 0.76f, maxX - 0.01f);
                renderDropdownItem(buffer, "+ Spawn Camera", minX + 0.01f, 0.67f, maxX - 0.01f);
                renderDropdownItem(buffer, "Save Selected as Prefab...", minX + 0.01f, 0.58f, maxX - 0.01f);
            }
            case VIEW -> {
                renderDropdownItem(buffer, "Reset Viewport Camera (F)", minX + 0.01f, 0.85f, maxX - 0.01f);
                String gridLabel = show3DGrid ? "Hide 3D Grid" : "Show 3D Grid";
                renderDropdownItem(buffer, gridLabel, minX + 0.01f, 0.76f, maxX - 0.01f);
            }
            default -> {}
        }
    }

    public static void renderDropdownMenuOverlay(List<Float> verts, DropdownMenu menu, CommandStack commandHistory, boolean show3DGrid) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderDropdownMenuOverlay(tempBuf, menu, commandHistory, show3DGrid);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    private static void renderDropdownItem(UIGeometryBatch.Buffer buffer, String text, float minX, float y, float maxX) {
        UIGeometryBatch.addQuad(buffer, minX, y - 0.02f, maxX, y + 0.05f, 0.16f, 0.18f, 0.24f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, minX, y - 0.02f, maxX, y + 0.05f, 0.001f, 0.25f, 0.28f, 0.35f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, text, minX + 0.01f, y + 0.01f, maxX - 0.01f, 1.0f, 0.95f, 0.95f, 0.95f, 1.0f);
    }

    private static String extractSceneName(String path) {
        if (path == null) return "Untitled";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String fileName = (lastSlash >= 0 && lastSlash < path.length() - 1) ? path.substring(lastSlash + 1) : path;
        if (fileName.toLowerCase().endsWith(".rscene")) {
            fileName = fileName.substring(0, fileName.length() - 7);
        }
        return fileName;
    }
}
