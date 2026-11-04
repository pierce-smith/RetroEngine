package com.retro.engine.editor.panels;

import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.navigation.NavMesh;
import com.retro.engine.navigation.NavMeshBaker;
import com.retro.engine.ui.UIGeometryBatch;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Dockable NAVIGATION panel: agent / voxel bake parameters (stepped with - / + buttons), BAKE and CLEAR. Baking rasterizes
 * the scene's static colliders through {@link NavMeshBaker}, installs the mesh for play mode and writes it next to the
 * scene as {@code <Scene>.navmesh}, which is reloaded whenever the scene is opened. Drawn in the Inspector's design rect.
 */
public final class NavMeshBakerPanel {

    private static final float MIN_X = 0.55f, MAX_X = 1.00f, MIN_Y = -0.50f, MAX_Y = 0.88f;
    private static final float HEADER_H = 0.05f, ROW_H = 0.075f, PAD = 0.02f;
    private static final float BTN_W = 0.045f;

    private static final String[] LABELS = {"AGENT HEIGHT", "AGENT RADIUS", "MAX SLOPE", "STEP HEIGHT", "CELL SIZE", "CELL HEIGHT"};
    private static final float[] STEPS = {0.1f, 0.05f, 1.0f, 0.05f, 0.05f, 0.05f};
    private static final float[] MINS = {0.2f, 0.0f, 0.0f, 0.0f, 0.05f, 0.02f};
    private static final float[] MAXS = {10.0f, 5.0f, 89.0f, 5.0f, 2.0f, 1.0f};

    private static final NavMeshBaker.Settings settings = new NavMeshBaker.Settings();
    private static final String[] valueText = new String[LABELS.length];
    private static String status = "Not baked.";
    private static String meshInfo = "No NavMesh loaded.";
    private static NavMesh shownMesh = null;
    private static boolean infoValid = false;

    private NavMeshBakerPanel() {}

    private static float get(int i) {
        return switch (i) {
            case 0 -> settings.agentHeight;
            case 1 -> settings.agentRadius;
            case 2 -> settings.maxSlope;
            case 3 -> settings.stepHeight;
            case 4 -> settings.cellSize;
            default -> settings.cellHeight;
        };
    }

    private static void set(int i, float v) {
        v = Math.max(MINS[i], Math.min(MAXS[i], Math.round(v / STEPS[i]) * STEPS[i]));
        switch (i) {
            case 0 -> settings.agentHeight = v;
            case 1 -> settings.agentRadius = v;
            case 2 -> settings.maxSlope = v;
            case 3 -> settings.stepHeight = v;
            case 4 -> settings.cellSize = v;
            default -> settings.cellHeight = v;
        }
        infoValid = false;
    }

    /** Text is rebuilt only when a value or the loaded mesh changes, so idle frames do not allocate. */
    private static void refresh(SceneWorld world) {
        NavMesh mesh = world != null ? world.getNavigation().getMesh() : null;
        if (mesh != shownMesh) {
            shownMesh = mesh;
            if (mesh != null) {
                // Show the parameters the loaded mesh was baked with
                settings.agentHeight = mesh.agentHeight; settings.agentRadius = mesh.agentRadius;
                settings.maxSlope = mesh.maxSlope; settings.stepHeight = mesh.stepHeight;
                settings.cellSize = mesh.cellSize; settings.cellHeight = mesh.cellHeight;
            }
            infoValid = false;
        }
        if (infoValid) return;
        for (int i = 0; i < LABELS.length; i++) {
            valueText[i] = String.format(Locale.ROOT, i == 2 ? "%.0f" : "%.2f", get(i));
        }
        meshInfo = (mesh == null) ? "No NavMesh loaded."
                : mesh.polyCount + " polys, " + mesh.getLinkCount() + " links";
        infoValid = true;
    }

    private static float rowTop(int i) { return MAX_Y - HEADER_H - PAD - i * ROW_H; }

    private static float buttonsY0() { return rowTop(LABELS.length) - 0.02f - ROW_H; }

    public static void render(UIGeometryBatch.Buffer buffer, SceneWorld world) {
        refresh(world);
        UIGeometryBatch.addQuad(buffer, MIN_X, MIN_Y, MAX_X, MAX_Y, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, MIN_X, MAX_Y - HEADER_H, MAX_X, MAX_Y, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "NAVMESH BAKER", MIN_X + PAD, MAX_Y - HEADER_H * 0.5f + 0.009f, MAX_X - PAD, 1.0f, EditorTheme.TEXT, 1.0f);

        for (int i = 0; i < LABELS.length; i++) {
            float top = rowTop(i), bottom = top - ROW_H + 0.01f, mid = (top + bottom) * 0.5f;
            UIGeometryBatch.addRoundedCard(buffer, MIN_X + PAD * 0.5f, bottom, MAX_X - PAD * 0.5f, top, 0.008f,
                    EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, LABELS[i], MIN_X + PAD, mid + 0.008f, MAX_X - 0.2f, 0.85f, EditorTheme.TEXT_MUTED, 1.0f);
            float plusX1 = MAX_X - PAD, plusX0 = plusX1 - BTN_W;
            float minusX1 = plusX0 - 0.006f, minusX0 = minusX1 - BTN_W;
            UIGeometryBatch.addRightAlignedText(buffer, valueText[i], minusX0 - 0.01f, mid + 0.008f, 0.85f, EditorTheme.TEXT, 1.0f);
            drawButton(buffer, minusX0, bottom + 0.008f, minusX1, top - 0.008f, "-", EditorTheme.SURFACE_SUNKEN);
            drawButton(buffer, plusX0, bottom + 0.008f, plusX1, top - 0.008f, "+", EditorTheme.SURFACE_SUNKEN);
        }

        float by0 = buttonsY0(), by1 = by0 + ROW_H - 0.01f, midX = (MIN_X + MAX_X) * 0.5f;
        drawButton(buffer, MIN_X + PAD, by0, midX - 0.005f, by1, "BAKE", EditorTheme.ACCENT);
        drawButton(buffer, midX + 0.005f, by0, MAX_X - PAD, by1, "CLEAR", EditorTheme.SURFACE_RAISED);

        float ty = by0 - 0.05f;
        UIGeometryBatch.addBoundedText(buffer, meshInfo, MIN_X + PAD, ty, MAX_X - PAD, 0.85f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addTruncatedText(buffer, status, MIN_X + PAD, ty - 0.05f, MAX_X - PAD, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void drawButton(UIGeometryBatch.Buffer buffer, float x0, float y0, float x1, float y1, String text, int color) {
        UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, 0.006f, color, 1.0f);
        float tw = UIGeometryBatch.textWidth(buffer, text, 0.85f);
        UIGeometryBatch.addBoundedText(buffer, text, (x0 + x1 - tw) * 0.5f, (y0 + y1) * 0.5f + 0.008f, x1, 0.85f, EditorTheme.TEXT, 1.0f);
    }

    /** Click in this panel's design space. */
    public static void handleClick(float x, float y, EditorWorkspace workspace) {
        if (x < MIN_X || x > MAX_X || y < MIN_Y || y > MAX_Y) return;
        for (int i = 0; i < LABELS.length; i++) {
            float top = rowTop(i), bottom = top - ROW_H + 0.01f;
            if (y < bottom || y > top) continue;
            float plusX1 = MAX_X - PAD, plusX0 = plusX1 - BTN_W;
            float minusX1 = plusX0 - 0.006f, minusX0 = minusX1 - BTN_W;
            if (x >= plusX0 && x <= plusX1) set(i, get(i) + STEPS[i]);
            else if (x >= minusX0 && x <= minusX1) set(i, get(i) - STEPS[i]);
            return;
        }
        float by0 = buttonsY0(), by1 = by0 + ROW_H - 0.01f, midX = (MIN_X + MAX_X) * 0.5f;
        if (y < by0 || y > by1) return;
        SceneWorld world = workspace.getSceneManager();
        if (x >= MIN_X + PAD && x < midX) bake(world);
        else if (x > midX && x <= MAX_X - PAD) clear(world);
    }

    public static void bake(SceneWorld world) {
        if (world == null) return;
        if (world.getPlayState() != SceneWorld.PlayState.EDITING) {
            report(EngineConsolePanel.LogLevel.WARN, "Stop play mode before baking the NavMesh.");
            return;
        }
        long t0 = System.nanoTime();
        NavMeshBaker.Result result = NavMeshBaker.bake(world.getSceneEntities(), world, settings);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (result.mesh() == null) {
            report(EngineConsolePanel.LogLevel.WARN, result.message());
            return;
        }
        world.getNavigation().setMesh(result.mesh());
        String scene = world.resolveScenePath(world.getActiveScenePath());
        if (scene == null) {
            report(EngineConsolePanel.LogLevel.WARN, result.message() + " Save the scene, then bake again to write the .navmesh.");
            return;
        }
        File file = new File(NavMesh.pathForScene(scene));
        try {
            result.mesh().save(file);
            report(EngineConsolePanel.LogLevel.INFO, result.message() + " " + ms + " ms -> " + file.getName());
        } catch (IOException e) {
            report(EngineConsolePanel.LogLevel.ERROR, "Failed to write " + file + ": " + e.getMessage());
        }
    }

    public static void clear(SceneWorld world) {
        if (world == null) return;
        world.getNavigation().setMesh(null);
        String scene = world.resolveScenePath(world.getActiveScenePath());
        File file = (scene != null) ? new File(NavMesh.pathForScene(scene)) : null;
        if (file != null && file.isFile() && !file.delete()) {
            report(EngineConsolePanel.LogLevel.ERROR, "Could not delete " + file);
            return;
        }
        report(EngineConsolePanel.LogLevel.INFO, "NavMesh cleared.");
    }

    private static void report(EngineConsolePanel.LogLevel level, String message) {
        status = message;
        infoValid = false;
        EngineConsolePanel.log(level, "[NavMesh] " + message);
    }
}
