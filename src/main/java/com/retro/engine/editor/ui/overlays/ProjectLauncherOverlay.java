package com.retro.engine.editor.ui.overlays;

import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;

import java.io.File;
import java.io.IOException;

/**
 * Fullscreen startup launcher shown whenever the editor has no bound project directory.
 * Offers New Project (name + location + optional starter content) and Open Project (folder picker).
 * Rendering and hit-testing share the layout constants below.
 */
public final class ProjectLauncherOverlay {

    private static final float CARD_X0 = -0.42f, CARD_X1 = 0.42f;
    private static final float CARD_Y0 = -0.46f, CARD_Y1 = 0.46f;

    private static final float BTN_X0 = -0.34f, BTN_X1 = 0.34f;
    private static final float NEW_Y0 = 0.00f, NEW_Y1 = 0.13f;
    private static final float OPEN_Y0 = -0.19f, OPEN_Y1 = -0.06f;

    private static String statusMessage = "";
    private static boolean statusIsError = false;

    private ProjectLauncherOverlay() {}

    public static void setStatus(String message, boolean isError) {
        statusMessage = (message != null) ? message : "";
        statusIsError = isError;
    }

    public static void render(UIGeometryBatch.Buffer buffer, float hoverX, float hoverY) {
        UIGeometryBatch.addQuad(buffer, -1.0f, -1.0f, 1.0f, 1.0f, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addRoundedCard(buffer, CARD_X0, CARD_Y0, CARD_X1, CARD_Y1, 0.02f, EditorTheme.SURFACE, EditorTheme.BORDER_STRONG, 1.0f);

        centeredText(buffer, "RETRO ENGINE", CARD_Y1 - 0.10f, 2.0f, EditorTheme.TEXT);
        centeredText(buffer, "PROJECT LAUNCHER", CARD_Y1 - 0.18f, 1.0f, EditorTheme.TEXT_MUTED);
        UIGeometryBatch.addQuad(buffer, CARD_X0 + 0.04f, CARD_Y1 - 0.24f, CARD_X1 - 0.04f, CARD_Y1 - 0.24f + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        boolean hoverNew = inRect(hoverX, hoverY, BTN_X0, NEW_Y0, BTN_X1, NEW_Y1);
        boolean hoverOpen = inRect(hoverX, hoverY, BTN_X0, OPEN_Y0, BTN_X1, OPEN_Y1);

        UIGeometryBatch.addRoundedQuad(buffer, BTN_X0, NEW_Y0, BTN_X1, NEW_Y1, EditorTheme.RADIUS, EditorTheme.ACCENT, hoverNew ? 1.0f : 0.85f);
        centeredTextIn(buffer, "+ NEW PROJECT", BTN_X0, BTN_X1, (NEW_Y0 + NEW_Y1) * 0.5f, 1.2f, EditorTheme.TEXT);

        UIGeometryBatch.addRoundedCard(buffer, BTN_X0, OPEN_Y0, BTN_X1, OPEN_Y1, EditorTheme.RADIUS,
            hoverOpen ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "OPEN PROJECT...", BTN_X0, BTN_X1, (OPEN_Y0 + OPEN_Y1) * 0.5f, 1.2f, EditorTheme.TEXT);

        if (!statusMessage.isEmpty()) {
            float y = CARD_Y0 + 0.13f;
            float w = UIGeometryBatch.textWidth(buffer, statusMessage, 0.85f);
            float x = Math.max(CARD_X0 + 0.03f, -w * 0.5f);
            UIGeometryBatch.addTruncatedText(buffer, statusMessage, x, y + 0.85f * 0.009f, CARD_X1 - 0.03f, 0.85f,
                statusIsError ? EditorTheme.RED : EditorTheme.TEXT_MUTED, 1.0f);
        }
        centeredText(buffer, "PROJECTS CONTAIN ASSETS/, COOKED/ AND .RETRO/", CARD_Y0 + 0.05f, 0.75f, EditorTheme.TEXT_DISABLED);
    }

    /** Handles a left-button press while the launcher is visible. */
    public static void handleClick(float ndcX, float ndcY, EditorWorkspace workspace) {
        if (workspace == null) return;
        if (inRect(ndcX, ndcY, BTN_X0, NEW_Y0, BTN_X1, NEW_Y1)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            promptNewProject(workspace);
        } else if (inRect(ndcX, ndcY, BTN_X0, OPEN_Y0, BTN_X1, OPEN_Y1)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            promptOpenProject(workspace);
        }
    }

    private static void promptNewProject(EditorWorkspace workspace) {
        String name = NativeFileDialog.promptInput("New Project", "Project name:", "MyRetroProject");
        if (name == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
        name = name.trim();
        if (name.isEmpty() || !name.matches("[A-Za-z0-9 _.-]+") || name.startsWith(".")) {
            fail("Invalid project name. Use letters, digits, spaces, dot, dash or underscore.");
            return;
        }

        String parent = NativeFileDialog.selectFolderDialog("Choose Project Root Location", System.getProperty("user.home") + File.separator);
        if (parent == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }

        boolean starter = NativeFileDialog.confirm("New Project",
            "Include Starter Content?\n\nPrimitive models, a default material and texture, a test script and Main.rscene will be copied into Assets/.", true);

        try {
            File dir = ProjectWorkspace.createProject(workspace, new File(parent), name, starter);
            setStatus("", false);
            UISoundPlayer.play(UISound.SPAWN);
            System.out.println("[Launcher] Project ready: " + dir.getAbsolutePath());
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    private static void promptOpenProject(EditorWorkspace workspace) {
        String picked = NativeFileDialog.selectFolderDialog("Open RetroEngine Project Folder", System.getProperty("user.home") + File.separator);
        if (picked == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
        File dir = new File(picked).getAbsoluteFile();

        try {
            if (!ProjectWorkspace.isProjectDirectory(dir)) {
                boolean init = NativeFileDialog.confirm("Open Project",
                    "No ProjectSettings.bin was found in\n" + dir.getAbsolutePath() + "\n\nInitialize this folder as a RetroEngine project?", false);
                if (!init) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
                ProjectWorkspace.initializeLayout(dir, dir.getName(), "");
            }
            ProjectWorkspace.openProject(workspace, dir);
            setStatus("", false);
            UISoundPlayer.play(UISound.SPAWN);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    private static void fail(String message) {
        String msg = (message != null && !message.isEmpty()) ? message : "Unknown error";
        System.err.println("[Launcher] " + msg);
        setStatus(msg.toUpperCase(), true);
        UISoundPlayer.play(UISound.CLOSE_MODAL);
        NativeFileDialog.showError("Project Launcher", msg.replace('"', ' ').replace('\'', ' '));
    }

    private static boolean inRect(float x, float y, float x0, float y0, float x1, float y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        centeredTextIn(buffer, text, CARD_X0, CARD_X1, midY, size, rgb);
    }
}
