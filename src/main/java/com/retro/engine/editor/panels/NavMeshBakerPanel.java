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
 * scene as {@code <Scene>.navmesh}, which is reloaded whenever the scene is opened. Lays itself out against its docked
 * {@link PanelRect}: the parameter rows flow into up to three columns when the tile is wide, so the panel also fits a
 * shallow bottom strip. Rendering and {@link #handleClick} share the same geometry helpers.
 */
public final class NavMeshBakerPanel {

    private static final float HEADER_H = 0.05f, ROW_H = 0.075f, PAD = 0.02f;
    private static final float BTN_W = 0.045f;
    /** Narrowest parameter column; wider tiles fit more columns (up to MAX_COLUMNS). */
    private static final float MIN_COLUMN_W = 0.40f;
    private static final int MAX_COLUMNS = 3;
    private static final float COLUMN_GAP = 0.01f;
    /** BAKE / CLEAR never stretch wider than this, so they stay button-shaped in wide tiles. */
    private static final float MAX_ACTION_W = 0.5f;

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

    // --- Layout (shared by render and handleClick; everything derives from the docked rect) ---
    private static PanelRect rect() { return DockPanel.NAVIGATION.rect; }

    private static int columns() {
        float w = rect().width() - PAD;
        return Math.max(1, Math.min(MAX_COLUMNS, (int) (w / MIN_COLUMN_W)));
    }

    private static int rowsPerColumn() {
        int c = columns();
        return (LABELS.length + c - 1) / c;
    }

    private static float columnWidth() {
        int c = columns();
        return (rect().width() - PAD - (c - 1) * COLUMN_GAP) / c;
    }

    /** Parameter i fills its column top-down, then the next column. */
    private static float cellX0(int i) { return rect().x0 + PAD * 0.5f + (i / rowsPerColumn()) * (columnWidth() + COLUMN_GAP); }
    private static float cellX1(int i) { return cellX0(i) + columnWidth(); }
    private static float rowTop(int i) { return rect().y1 - HEADER_H - PAD - (i % rowsPerColumn()) * ROW_H; }
    private static float plusX1(int i) { return cellX1(i) - PAD * 0.5f; }
    private static float plusX0(int i) { return plusX1(i) - BTN_W; }
    private static float minusX1(int i) { return plusX0(i) - 0.006f; }
    private static float minusX0(int i) { return minusX1(i) - BTN_W; }

    private static float buttonsY0() { return rect().y1 - HEADER_H - PAD - rowsPerColumn() * ROW_H - 0.02f - ROW_H; }
    private static float actionsX0() { return rect().x0 + PAD; }
    private static float actionsX1() { return Math.min(rect().x1 - PAD, actionsX0() + MAX_ACTION_W); }
    private static float actionsMidX() { return (actionsX0() + actionsX1()) * 0.5f; }

    public static void render(UIGeometryBatch.Buffer buffer, SceneWorld world) {
        refresh(world);
        PanelRect r = rect();
        UIGeometryBatch.addQuad(buffer, r.x0, r.y0, r.x1, r.y1, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, r.x0, r.y1 - HEADER_H, r.x1, r.y1, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addTruncatedText(buffer, "NAVMESH BAKER", r.x0 + PAD, r.y1 - HEADER_H * 0.5f + 0.009f, r.x1 - PAD, 1.0f, EditorTheme.TEXT, 1.0f);

        for (int i = 0; i < LABELS.length; i++) {
            float top = rowTop(i), bottom = top - ROW_H + 0.01f, mid = (top + bottom) * 0.5f;
            float cx0 = cellX0(i), cx1 = cellX1(i);
            UIGeometryBatch.addRoundedCard(buffer, cx0, bottom, cx1, top, 0.008f, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER, 1.0f);
            float minusX0 = minusX0(i);
            // The label gets whatever the value leaves
            float valueX0 = minusX0 - 0.01f - UIGeometryBatch.textWidth(buffer, valueText[i], 0.85f);
            UIGeometryBatch.addTruncatedText(buffer, LABELS[i], cx0 + PAD * 0.5f, mid + 0.008f, valueX0 - 0.01f, 0.85f, EditorTheme.TEXT_MUTED, 1.0f);
            UIGeometryBatch.addRightAlignedText(buffer, valueText[i], minusX0 - 0.01f, mid + 0.008f, 0.85f, EditorTheme.TEXT, 1.0f);
            drawButton(buffer, minusX0, bottom + 0.008f, minusX1(i), top - 0.008f, "-", EditorTheme.SURFACE_SUNKEN);
            drawButton(buffer, plusX0(i), bottom + 0.008f, plusX1(i), top - 0.008f, "+", EditorTheme.SURFACE_SUNKEN);
        }

        float by0 = buttonsY0(), by1 = by0 + ROW_H - 0.01f, midX = actionsMidX();
        drawButton(buffer, actionsX0(), by0, midX - 0.005f, by1, "BAKE", EditorTheme.ACCENT);
        drawButton(buffer, midX + 0.005f, by0, actionsX1(), by1, "CLEAR", EditorTheme.SURFACE_RAISED);

        // Mesh info + status: beside the actions when the tile is wide, below them otherwise
        float infoX0, ty;
        if (r.x1 - PAD - (actionsX1() + 0.03f) >= MIN_COLUMN_W) {
            infoX0 = actionsX1() + 0.03f;
            ty = by1 - 0.02f;
        } else {
            infoX0 = r.x0 + PAD;
            ty = by0 - 0.05f;
        }
        UIGeometryBatch.addTruncatedText(buffer, meshInfo, infoX0, ty, r.x1 - PAD, 0.85f, EditorTheme.TEXT, 1.0f);
        // Bake results ("... polys ... ms -> Scene.navmesh") are wider than a narrow column: wrap into the space left
        int lines = Math.max(1, Math.min(6, (int) ((ty - 0.05f - (r.y0 + 0.01f)) / 0.038f) + 1));
        UIGeometryBatch.addWrappedText(buffer, status, infoX0, ty - 0.05f, r.x1 - PAD, 0.038f, lines, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void drawButton(UIGeometryBatch.Buffer buffer, float x0, float y0, float x1, float y1, String text, int color) {
        UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, 0.006f, color, 1.0f);
        float tw = UIGeometryBatch.textWidth(buffer, text, 0.85f);
        UIGeometryBatch.addBoundedText(buffer, text, (x0 + x1 - tw) * 0.5f, (y0 + y1) * 0.5f + 0.008f, x1, 0.85f, EditorTheme.TEXT, 1.0f);
    }

    /** Click in screen NDC (the dock maps reflowing panels 1:1). */
    public static void handleClick(float x, float y, EditorWorkspace workspace) {
        if (!rect().contains(x, y)) return;
        for (int i = 0; i < LABELS.length; i++) {
            float top = rowTop(i), bottom = top - ROW_H + 0.01f;
            if (y < bottom || y > top || x < cellX0(i) || x > cellX1(i)) continue;
            if (x >= plusX0(i) && x <= plusX1(i)) set(i, get(i) + STEPS[i]);
            else if (x >= minusX0(i) && x <= minusX1(i)) set(i, get(i) - STEPS[i]);
            return;
        }
        float by0 = buttonsY0(), by1 = by0 + ROW_H - 0.01f, midX = actionsMidX();
        if (y < by0 || y > by1) return;
        SceneWorld world = workspace.getSceneManager();
        if (x >= actionsX0() && x < midX) bake(world);
        else if (x > midX && x <= actionsX1()) clear(world);
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
