package com.retro.engine.editor.ui.overlays;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.EditorWorkspace.ModalState;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.build.StandaloneExporter;
import com.retro.engine.editor.build.StandaloneExporter.Platform;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;

import java.io.File;

/**
 * "File > Export Standalone Build..." modal: export folder, executable name, target platform, startup scene and
 * whether to ship controller haptics (SDL), then runs {@link StandaloneExporter} on a background thread with live progress. Rendering and hit-testing share
 * the layout constants below.
 */
public final class BuildExporterOverlay {

    private static final float CARD_X0 = -0.56f, CARD_X1 = 0.56f;
    private static final float CARD_Y0 = -0.54f, CARD_Y1 = 0.42f;

    private static final float LABEL_X = -0.52f;
    private static final float WELL_X0 = -0.22f, WELL_X1 = 0.30f;
    private static final float BTN_X0 = 0.32f, BTN_X1 = 0.52f;
    private static final float ROW_H = 0.072f;

    private static final float PATH_Y = 0.22f, NAME_Y = 0.10f, PLATFORM_Y = -0.02f, SCENE_Y = -0.14f, HAPTICS_Y = -0.26f;
    private static final float PROGRESS_Y = -0.37f;
    private static final float ACTION_Y0 = -0.50f, ACTION_Y1 = -0.42f;
    private static final float CANCEL_X0 = 0.04f, CANCEL_X1 = 0.26f;
    private static final float EXPORT_X0 = 0.29f, EXPORT_X1 = 0.52f;
    private static final float CLOSE_X0 = CARD_X1 - 0.075f, CLOSE_X1 = CARD_X1 - 0.02f;
    private static final float CLOSE_Y0 = CARD_Y1 - 0.075f, CLOSE_Y1 = CARD_Y1 - 0.02f;

    private static final Platform[] PLATFORMS = Platform.values();

    private static File exportDir;
    private static String executableName = "Game";
    private static Platform platform = Platform.host();
    private static String startupScene = "";
    // Defaults to what the project appears to use; SDL adds a few MB per platform
    private static boolean includeHaptics = true;
    private static boolean hapticsDetected = true;

    private static volatile boolean running = false;
    private static volatile float progress = 0.0f;
    private static volatile String status = "";
    private static volatile boolean statusIsError = false;

    private BuildExporterOverlay() {}

    public static boolean isRunning() { return running; }

    /** Opens the modal with defaults derived from the active project. */
    public static void open(EditorWorkspace workspace) {
        if (workspace == null || !workspace.hasActiveProject()) return;
        File project = new File(workspace.getActiveProjectPath());
        if (!running) {
            exportDir = new File(project, "Build");
            try {
                ProjectWorkspace.ProjectSettings settings = ProjectWorkspace.readSettings(project);
                executableName = sanitizeName(settings.projectName);
                startupScene = settings.startupScene != null ? settings.startupScene : "";
            } catch (Exception e) {
                executableName = sanitizeName(project.getName());
                startupScene = "";
            }
            if (startupScene.isEmpty() || !new File(project, startupScene).isFile()) {
                String active = projectRelative(project, workspace.getSceneManager().getActiveScenePath());
                startupScene = (active != null) ? active : "";
            }
            hapticsDetected = StandaloneExporter.projectUsesHaptics(project);
            includeHaptics = hapticsDetected;
            status = "";
            statusIsError = false;
            progress = 0.0f;
        }
        workspace.getOverlayManager().setActiveModal(ModalState.BUILD_EXPORTER);
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    public static void render(UIGeometryBatch.Buffer buffer) {
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, 0x000000, 0.65f);
        UIGeometryBatch.addRoundedCard(buffer, CARD_X0, CARD_Y0, CARD_X1, CARD_Y1, 0.016f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 0.98f);

        float headerY0 = CARD_Y1 - 0.09f;
        UIGeometryBatch.addRoundedQuad(buffer, CARD_X0 + 0.003f, headerY0, CARD_X1 - 0.003f, CARD_Y1 - 0.003f, 0.014f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, CARD_X0 + 0.003f, headerY0, CARD_X1 - 0.003f, CARD_Y1 - 0.02f, EditorTheme.SURFACE_RAISED, 1.0f);
        UIGeometryBatch.addQuad(buffer, CARD_X0, headerY0, CARD_X1, headerY0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "EXPORT STANDALONE BUILD", CARD_X0 + 0.03f, CARD_Y1 - 0.035f, CLOSE_X0 - 0.02f, 1.0f, EditorTheme.TEXT, 1.0f);

        if (!running) {
            UIGeometryBatch.addRoundedQuad(buffer, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 0.90f);
            centeredTextIn(buffer, "X", CLOSE_X0, CLOSE_X1, (CLOSE_Y0 + CLOSE_Y1) * 0.5f, 0.9f, EditorTheme.TEXT);
        }

        fieldRow(buffer, "EXPORT PATH", PATH_Y, exportDir != null ? exportDir.getAbsolutePath() : "", "BROWSE...");
        fieldRow(buffer, "EXECUTABLE NAME", NAME_Y, executableName, "EDIT...");
        fieldRow(buffer, "STARTUP SCENE", SCENE_Y, startupScene.isEmpty() ? "(NONE SELECTED)" : startupScene, "SELECT...");
        fieldRow(buffer, "CONTROLLER RUMBLE", HAPTICS_Y, includeHaptics ? "INCLUDED (SDL, +2-4 MB)" : "EXCLUDED (VIBRATION CALLS DO NOTHING)",
            includeHaptics ? "EXCLUDE" : "INCLUDE");
        String hapticsHint = hapticsDetected ? "PROJECT USES RUMBLE (VIBRATE CALLS OR CHARACTER CONTROLLER)" : "NO RUMBLE USE FOUND IN SCRIPTS OR SCENES";
        UIGeometryBatch.addTruncatedText(buffer, hapticsHint, WELL_X0, HAPTICS_Y - ROW_H * 0.5f - 0.012f, BTN_X1, 0.62f,
            includeHaptics || !hapticsDetected ? EditorTheme.TEXT_MUTED : EditorTheme.YELLOW, 1.0f);

        rowLabel(buffer, "TARGET PLATFORM", PLATFORM_Y);
        for (int i = 0; i < PLATFORMS.length; i++) {
            float x0 = platformX0(i), x1 = platformX1(i);
            float y0 = PLATFORM_Y - ROW_H * 0.5f, y1 = PLATFORM_Y + ROW_H * 0.5f;
            boolean selected = PLATFORMS[i] == platform;
            if (selected) {
                UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
            } else {
                UIGeometryBatch.addRoundedCard(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
            }
            centeredTextIn(buffer, PLATFORMS[i].label, x0, x1, PLATFORM_Y, 0.72f, selected ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED);
        }
        if (platform != Platform.host()) {
            UIGeometryBatch.addTruncatedText(buffer, "CROSS-OS: SCRIPT LAUNCHER + TARGET JDK REQUIRED (NATIVE LAUNCHERS BUILD ON THEIR OWN OS)",
                WELL_X0, PLATFORM_Y - ROW_H * 0.5f - 0.012f, BTN_X1, 0.62f, EditorTheme.YELLOW, 1.0f);
        }

        // Progress bar + status line
        float py0 = PROGRESS_Y - 0.012f, py1 = PROGRESS_Y + 0.012f;
        UIGeometryBatch.addRoundedQuad(buffer, LABEL_X, py0, BTN_X1, py1, 0.008f, EditorTheme.SURFACE_SUNKEN, 1.0f);
        float p = Math.max(0.0f, Math.min(1.0f, progress));
        if (p > 0.0f) {
            UIGeometryBatch.addRoundedQuad(buffer, LABEL_X, py0, LABEL_X + (BTN_X1 - LABEL_X) * p, py1, 0.008f, statusIsError ? EditorTheme.RED : EditorTheme.ACCENT, 1.0f);
        }
        String line = status;
        if (!line.isEmpty()) {
            UIGeometryBatch.addTruncatedText(buffer, line, LABEL_X, py0 - 0.022f, BTN_X1, 0.75f, statusIsError ? EditorTheme.RED : EditorTheme.TEXT_MUTED, 1.0f);
        }

        // Actions
        int actionText = running ? EditorTheme.TEXT_DISABLED : EditorTheme.TEXT;
        UIGeometryBatch.addRoundedCard(buffer, CANCEL_X0, ACTION_Y0, CANCEL_X1, ACTION_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "CLOSE", CANCEL_X0, CANCEL_X1, (ACTION_Y0 + ACTION_Y1) * 0.5f, 0.9f, actionText);
        UIGeometryBatch.addRoundedQuad(buffer, EXPORT_X0, ACTION_Y0, EXPORT_X1, ACTION_Y1, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, running ? 0.45f : 1.0f);
        centeredTextIn(buffer, running ? "EXPORTING..." : "EXPORT BUILD", EXPORT_X0, EXPORT_X1, (ACTION_Y0 + ACTION_Y1) * 0.5f, 0.9f, actionText);
    }

    private static void fieldRow(UIGeometryBatch.Buffer buffer, String label, float midY, String value, String button) {
        rowLabel(buffer, label, midY);
        float y0 = midY - ROW_H * 0.5f, y1 = midY + ROW_H * 0.5f;
        UIGeometryBatch.addRoundedQuad(buffer, WELL_X0, y0, WELL_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_SUNKEN, 1.0f);
        UIGeometryBatch.addTruncatedText(buffer, value, WELL_X0 + 0.014f, midY + 0.8f * 0.009f, WELL_X1 - 0.014f, 0.8f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addRoundedCard(buffer, BTN_X0, y0, BTN_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, button, BTN_X0, BTN_X1, midY, 0.8f, running ? EditorTheme.TEXT_DISABLED : EditorTheme.TEXT);
    }

    private static void rowLabel(UIGeometryBatch.Buffer buffer, String label, float midY) {
        UIGeometryBatch.addBoundedText(buffer, label, LABEL_X, midY + 0.82f * 0.009f, WELL_X0 - 0.02f, 0.82f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static float platformX0(int i) {
        float w = (BTN_X1 - WELL_X0 - 0.02f * (PLATFORMS.length - 1)) / PLATFORMS.length;
        return WELL_X0 + i * (w + 0.02f);
    }

    private static float platformX1(int i) {
        float w = (BTN_X1 - WELL_X0 - 0.02f * (PLATFORMS.length - 1)) / PLATFORMS.length;
        return platformX0(i) + w;
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    /** Left-button press while the modal is open. Clicks outside the card are swallowed. */
    public static void handleClick(float ndcX, float ndcY, EditorWorkspace workspace) {
        if (workspace == null || running) return;
        File project = new File(workspace.getActiveProjectPath());

        if (inRect(ndcX, ndcY, CLOSE_X0, CLOSE_Y0, CLOSE_X1, CLOSE_Y1) || inRect(ndcX, ndcY, CANCEL_X0, ACTION_Y0, CANCEL_X1, ACTION_Y1)) {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
            workspace.getOverlayManager().setActiveModal(ModalState.NONE);
        } else if (inRow(ndcX, ndcY, PATH_Y)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String picked = NativeFileDialog.selectFolderDialog("Select Target Export Folder",
                (exportDir != null && exportDir.isDirectory() ? exportDir : project).getAbsolutePath() + File.separator);
            if (picked != null) exportDir = new File(picked).getAbsoluteFile();
        } else if (inRow(ndcX, ndcY, NAME_Y)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String name = NativeFileDialog.promptInput("Executable Name", "Executable / bundle name:", executableName);
            if (name != null) {
                name = name.trim();
                if (StandaloneExporter.isValidExecutableName(name)) {
                    executableName = name;
                } else {
                    setStatus("INVALID NAME: USE LETTERS, DIGITS, SPACE, DOT, DASH OR UNDERSCORE", true, 0.0f);
                }
            }
        } else if (inRow(ndcX, ndcY, SCENE_Y)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            String picked = NativeFileDialog.openFileDialog("Select Startup Scene", workspace.getProjectScenesDir(), "Retro Scene (*.rscene)", "rscene");
            if (picked != null) {
                String rel = projectRelative(project, picked);
                if (rel == null) {
                    setStatus("STARTUP SCENE MUST BE INSIDE THIS PROJECT'S ASSETS/ FOLDER", true, 0.0f);
                } else {
                    startupScene = rel;
                }
            }
        } else if (inRow(ndcX, ndcY, HAPTICS_Y)) {
            UISoundPlayer.play(UISound.CLICK);
            includeHaptics = !includeHaptics;
        } else if (inRect(ndcX, ndcY, EXPORT_X0, ACTION_Y0, EXPORT_X1, ACTION_Y1)) {
            startExport(project);
        } else {
            for (int i = 0; i < PLATFORMS.length; i++) {
                if (inRect(ndcX, ndcY, platformX0(i), PLATFORM_Y - ROW_H * 0.5f, platformX1(i), PLATFORM_Y + ROW_H * 0.5f)) {
                    UISoundPlayer.play(UISound.CLICK);
                    platform = PLATFORMS[i];
                }
            }
        }
    }

    private static void startExport(File project) {
        if (exportDir == null) { setStatus("SELECT AN EXPORT PATH", true, 0.0f); return; }
        if (startupScene.isEmpty()) { setStatus("SELECT A STARTUP SCENE", true, 0.0f); return; }

        File targetJdk = null;
        if (platform != Platform.host()) {
            String jdk = NativeFileDialog.selectFolderDialog("Select " + platform.label + " JDK home (contains jmods/) for the embedded runtime",
                System.getProperty("user.home") + File.separator);
            if (jdk == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
            targetJdk = new File(jdk);
            if (!new File(targetJdk, "jmods").isDirectory()) {
                setStatus("SELECTED FOLDER HAS NO JMODS/ DIRECTORY", true, 0.0f);
                return;
            }
        }

        UISoundPlayer.play(UISound.SPAWN);
        StandaloneExporter.Request request = new StandaloneExporter.Request(project, exportDir, executableName, platform, startupScene,
            targetJdk, includeHaptics);
        running = true;
        setStatus("STARTING EXPORT", false, 0.01f);
        Thread worker = new Thread(() -> {
            try {
                File out = new StandaloneExporter(request, (f, msg) -> setStatus(msg, false, f)).export();
                setStatus("BUILD READY: " + out.getAbsolutePath(), false, 1.0f);
                System.out.println("[Exporter] Build ready: " + out.getAbsolutePath());
            } catch (Exception e) {
                String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
                System.err.println("[Exporter] Export failed: " + msg);
                setStatus("EXPORT FAILED: " + msg, true, 1.0f);
            } finally {
                running = false;
            }
        }, "StandaloneExporter");
        worker.setDaemon(true);
        worker.start();
    }

    /** Fixed export folder used by non-interactive/test exports. */
    public static final File TEST_EXPORT_DIR = new File("/tmp/RetroExportTest");

    /**
     * Non-interactive export for tests/CI: no NativeFileDialog prompts, runs synchronously on the calling thread.
     * Writes into {@link #TEST_EXPORT_DIR}; {@code gameDataOnly} stops after the RpackCooker batch step (no jpackage).
     */
    public static File exportNonInteractive(File project, String scene, Platform target, boolean gameDataOnly) throws Exception {
        File targetJdk = (target == Platform.host()) ? null : new File(System.getProperty("java.home"));
        StandaloneExporter.Request request = new StandaloneExporter.Request(project.getAbsoluteFile(), TEST_EXPORT_DIR,
            sanitizeName(project.getName()), target, scene, targetJdk, StandaloneExporter.projectUsesHaptics(project));
        StandaloneExporter exporter = new StandaloneExporter(request, (f, msg) -> {
            setStatus(msg, false, f);
            System.out.println("[Exporter] " + Math.round(f * 100) + "% " + msg);
        });
        return gameDataOnly ? exporter.exportGameDataOnly() : exporter.export();
    }

    private static void setStatus(String message, boolean isError, float fraction) {
        status = (message != null) ? message.replace('\n', ' ') : "";
        statusIsError = isError;
        progress = fraction;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** "Assets/..." path relative to the project, or null when the file is not under the project's Assets/. */
    private static String projectRelative(File project, String path) {
        if (path == null || path.isBlank()) return null;
        java.nio.file.Path assets = new File(project, AssetDatabase.ASSETS_DIR).getAbsoluteFile().toPath().normalize();
        java.nio.file.Path p = new File(path).getAbsoluteFile().toPath().normalize();
        if (!p.startsWith(assets)) return null;
        return AssetDatabase.ASSETS_DIR + "/" + assets.relativize(p).toString().replace('\\', '/');
    }

    private static String sanitizeName(String name) {
        String clean = (name != null) ? name.replaceAll("[^A-Za-z0-9 _.-]", "").trim() : "";
        while (clean.startsWith(".") || clean.startsWith("-") || clean.startsWith("_") || clean.startsWith(" ")) clean = clean.substring(1);
        return StandaloneExporter.isValidExecutableName(clean) ? clean : "Game";
    }

    private static boolean inRow(float x, float y, float midY) {
        return inRect(x, y, BTN_X0, midY - ROW_H * 0.5f, BTN_X1, midY + ROW_H * 0.5f)
            || inRect(x, y, WELL_X0, midY - ROW_H * 0.5f, WELL_X1, midY + ROW_H * 0.5f);
    }

    private static boolean inRect(float x, float y, float x0, float y0, float x1, float y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }
}
