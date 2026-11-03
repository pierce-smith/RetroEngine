package com.retro.engine.editor.overlays;

import com.retro.engine.editor.templates.TemplateManager;
import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.core.RecentProjects;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.ui.UIGeometryBatch;
import com.retro.engine.ui.UISoundPlayer;
import com.retro.engine.ui.UISoundPlayer.UISound;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Fullscreen startup launcher shown whenever the editor has no bound project directory.
 * Left column: New Project (name + location + baseline template), Open Project (folder picker) and Open Mod
 * Workspace (Creation Kit mode: a vanilla project with one mod as the authoring target). Right column: cards for the
 * most recently opened projects (project name + folder); clicking one opens it.
 * Rendering and hit-testing share the layout constants below.
 */
public final class ProjectLauncherOverlay {

    private static final float CARD_X0 = -0.82f, CARD_X1 = 0.82f;
    private static final float CARD_Y0 = -0.66f, CARD_Y1 = 0.66f;
    private static final float HEADER_LINE_Y = CARD_Y1 - 0.24f;

    // Action column
    private static final float BTN_X0 = -0.76f, BTN_X1 = -0.20f;
    private static final float NEW_Y0 = 0.22f, NEW_Y1 = 0.33f;
    private static final float OPEN_Y0 = 0.07f, OPEN_Y1 = 0.18f;
    private static final float MOD_Y0 = -0.08f, MOD_Y1 = 0.03f;
    private static final float DIVIDER_X = -0.14f;

    // Recent projects column
    private static final float RECENT_X0 = -0.08f, RECENT_X1 = 0.76f;
    private static final float RECENT_HEADER_Y = 0.36f;
    private static final float RECENT_TOP = 0.32f;
    private static final float RECENT_CARD_H = 0.12f, RECENT_GAP = 0.02f;
    private static final int RECENT_VISIBLE = 5;

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

        centeredText(buffer, "RetroEngine", CARD_Y1 - 0.10f, 2.0f, EditorTheme.TEXT);
        centeredText(buffer, "PROJECT LAUNCHER", CARD_Y1 - 0.18f, 1.0f, EditorTheme.TEXT_MUTED);
        UIGeometryBatch.addQuad(buffer, CARD_X0 + 0.04f, HEADER_LINE_Y, CARD_X1 - 0.04f, HEADER_LINE_Y + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        // Actions
        UIGeometryBatch.addBoundedText(buffer, "START", BTN_X0, RECENT_HEADER_Y + 0.8f * 0.009f, BTN_X1, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
        boolean hoverNew = inRect(hoverX, hoverY, BTN_X0, NEW_Y0, BTN_X1, NEW_Y1);
        UIGeometryBatch.addRoundedQuad(buffer, BTN_X0, NEW_Y0, BTN_X1, NEW_Y1, EditorTheme.RADIUS, EditorTheme.ACCENT, hoverNew ? 1.0f : 0.85f);
        centeredTextIn(buffer, "+ NEW PROJECT", BTN_X0, BTN_X1, (NEW_Y0 + NEW_Y1) * 0.5f, 1.1f, EditorTheme.TEXT);
        actionButton(buffer, "OPEN PROJECT...", OPEN_Y0, OPEN_Y1, hoverX, hoverY);
        actionButton(buffer, "OPEN MOD WORKSPACE...", MOD_Y0, MOD_Y1, hoverX, hoverY);

        UIGeometryBatch.addQuad(buffer, DIVIDER_X, CARD_Y0 + 0.20f, DIVIDER_X + EditorTheme.HAIRLINE, HEADER_LINE_Y - 0.04f, EditorTheme.BORDER, 1.0f);

        // Recent projects
        UIGeometryBatch.addBoundedText(buffer, "RECENT PROJECTS", RECENT_X0, RECENT_HEADER_Y + 0.8f * 0.009f, RECENT_X1, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
        List<RecentProjects.Entry> recent = RecentProjects.entries();
        int shown = Math.min(recent.size(), RECENT_VISIBLE);
        if (shown == 0) {
            centeredTextIn(buffer, "NO RECENT PROJECTS", RECENT_X0, RECENT_X1, RECENT_TOP - RECENT_CARD_H * 0.5f, 0.9f, EditorTheme.TEXT_DISABLED);
        }
        for (int i = 0; i < shown; i++) {
            RecentProjects.Entry e = recent.get(i);
            float y1 = recentCardTop(i), y0 = y1 - RECENT_CARD_H;
            boolean hot = inRect(hoverX, hoverY, RECENT_X0, y0, RECENT_X1, y1);
            UIGeometryBatch.addRoundedCard(buffer, RECENT_X0, y0, RECENT_X1, y1, EditorTheme.RADIUS,
                hot ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, hot ? EditorTheme.ACCENT : EditorTheme.BORDER_STRONG, 1.0f);
            // Folder glyph: tab + body
            float gx = RECENT_X0 + 0.025f, gy = (y0 + y1) * 0.5f;
            UIGeometryBatch.addRoundedQuad(buffer, gx, gy + 0.018f, gx + 0.018f, gy + 0.028f, 0.003f, EditorTheme.ACCENT, 0.9f);
            UIGeometryBatch.addRoundedQuad(buffer, gx, gy - 0.025f, gx + 0.040f, gy + 0.022f, 0.004f, EditorTheme.ACCENT, 0.9f);
            float textX = gx + 0.065f, textMax = RECENT_X1 - 0.02f;
            UIGeometryBatch.addTruncatedText(buffer, e.projectName(), textX, y1 - 0.036f + 1.05f * 0.009f, textMax, 1.05f, EditorTheme.TEXT, 1.0f);
            UIGeometryBatch.addTruncatedText(buffer, e.folderPath(), textX, y0 + 0.036f + 0.8f * 0.009f, textMax, 0.8f, EditorTheme.TEXT_MUTED, 1.0f);
        }

        if (!statusMessage.isEmpty()) {
            float y = CARD_Y0 + 0.13f;
            float w = UIGeometryBatch.textWidth(buffer, statusMessage, 0.85f);
            float x = Math.max(CARD_X0 + 0.03f, -w * 0.5f);
            UIGeometryBatch.addTruncatedText(buffer, statusMessage, x, y + 0.85f * 0.009f, CARD_X1 - 0.03f, 0.85f,
                statusIsError ? EditorTheme.RED : EditorTheme.TEXT_MUTED, 1.0f);
        }
        centeredText(buffer, "PROJECTS CONTAIN ASSETS/, COOKED/ AND .RETRO/", CARD_Y0 + 0.05f, 0.75f, EditorTheme.TEXT_DISABLED);
    }

    private static void actionButton(UIGeometryBatch.Buffer buffer, String label, float y0, float y1, float hoverX, float hoverY) {
        boolean hot = inRect(hoverX, hoverY, BTN_X0, y0, BTN_X1, y1);
        UIGeometryBatch.addRoundedCard(buffer, BTN_X0, y0, BTN_X1, y1, EditorTheme.RADIUS,
            hot ? EditorTheme.BORDER : EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, label, BTN_X0, BTN_X1, (y0 + y1) * 0.5f, 1.1f, EditorTheme.TEXT);
    }

    private static float recentCardTop(int index) {
        return RECENT_TOP - index * (RECENT_CARD_H + RECENT_GAP);
    }

    /** Index of the recent-project card under the point, or -1. */
    private static int recentCardAt(float x, float y) {
        int shown = Math.min(RecentProjects.entries().size(), RECENT_VISIBLE);
        for (int i = 0; i < shown; i++) {
            float y1 = recentCardTop(i);
            if (inRect(x, y, RECENT_X0, y1 - RECENT_CARD_H, RECENT_X1, y1)) return i;
        }
        return -1;
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
        } else if (inRect(ndcX, ndcY, BTN_X0, MOD_Y0, BTN_X1, MOD_Y1)) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            promptOpenModWorkspace(workspace);
        } else {
            int card = recentCardAt(ndcX, ndcY);
            if (card >= 0) openRecent(workspace, RecentProjects.entries().get(card));
        }
    }

    private static void openRecent(EditorWorkspace workspace, RecentProjects.Entry entry) {
        UISoundPlayer.play(UISound.CLICK);
        if (!ProjectWorkspace.isProjectDirectory(entry.folder())) {
            RecentProjects.remove(entry.folder());
            fail("Project folder no longer exists: " + entry.folder().getAbsolutePath());
            return;
        }
        try {
            ProjectWorkspace.openProject(workspace, entry.folder());
            setStatus("", false);
            UISoundPlayer.play(UISound.SPAWN);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    /** FILE menu: closes Creation Kit mode when it is on, otherwise asks for a mod folder to author. */
    public static void toggleModWorkspace(EditorWorkspace workspace) {
        if (com.retro.engine.modding.ModWorkspace.isActive()) {
            try {
                com.retro.engine.modding.ModWorkspace.close(workspace);
                UISoundPlayer.play(UISound.CLOSE_MODAL);
            } catch (IOException e) {
                fail(e.getMessage());
            }
        } else {
            promptOpenModWorkspace(workspace);
        }
    }

    /**
     * Creation Kit mode: pick a mod folder inside {@code <Project>/Mods/} (or a project's Mods/ folder to create a new
     * mod in it). Folders without mod.json can be turned into a new mod skeleton.
     */
    public static void promptOpenModWorkspace(EditorWorkspace workspace) {
        String start = workspace.hasActiveProject()
            ? new File(workspace.getActiveProjectPath(), com.retro.engine.modding.ModLoader.MODS_DIR).getAbsolutePath()
            : System.getProperty("user.home");
        new File(start).mkdirs();
        String picked = NativeFileDialog.selectFolderDialog("Select a mod folder in <Project>/Mods/ (or a Mods/ folder to create one)", start + File.separator);
        if (picked == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
        File dir = new File(picked).getAbsoluteFile();

        try {
            if (dir.getName().equals(com.retro.engine.modding.ModLoader.MODS_DIR) && ProjectWorkspace.isProjectDirectory(dir.getParentFile())) {
                String name = NativeFileDialog.promptInput("New Mod", "Mod folder name:", "MyMod");
                if (name == null || name.isBlank()) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
                dir = new File(dir, name.trim().replaceAll("[^A-Za-z0-9 _.-]", ""));
            }
            if (!new File(dir, com.retro.engine.modding.ModManifest.FILE_NAME).isFile()) {
                if (com.retro.engine.modding.ModWorkspace.projectOf(dir) == null) {
                    fail("Mod folders must be inside a project's Mods/ folder: " + dir.getAbsolutePath());
                    return;
                }
                String suggested = dir.getName().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
                String id = NativeFileDialog.promptInput("New Mod", "mod_id (a-z, 0-9, _ . -):", suggested);
                if (id == null || id.isBlank()) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
                com.retro.engine.modding.ModWorkspace.createMod(dir, id.trim(), dir.getName());
            }
            com.retro.engine.modding.ModWorkspace.open(workspace, dir);
            setStatus("", false);
            UISoundPlayer.play(UISound.SPAWN);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    /** Name -> location -> template prompts, then creates and opens the project (closing any open one first). */
    public static void promptNewProject(EditorWorkspace workspace) {
        String name = NativeFileDialog.promptInput("New Project", "Project name:", "MyRetroProject");
        if (name == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }
        name = name.trim();
        if (name.isEmpty() || !name.matches("[A-Za-z0-9 _.-]+") || name.startsWith(".")) {
            fail("Invalid project name. Use letters, digits, spaces, dot, dash or underscore.");
            return;
        }

        String parent = NativeFileDialog.selectFolderDialog("Choose Project Root Location", System.getProperty("user.home") + File.separator);
        if (parent == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }

        TemplateManager.ProjectTemplate template = promptTemplate();
        if (template == null) { UISoundPlayer.play(UISound.CLOSE_MODAL); return; }

        // Fail before unbinding the current project if creation would be refused anyway
        File target = new File(parent, name);
        String[] existing = target.list();
        if (target.exists() && (!target.isDirectory() || (existing != null && existing.length > 0))) {
            fail("Target folder already exists and is not empty: " + target.getAbsolutePath());
            return;
        }

        try {
            if (workspace.hasActiveProject()) workspace.closeProject();
            File dir = ProjectWorkspace.createProject(workspace, new File(parent), name, template);
            setStatus("", false);
            UISoundPlayer.play(UISound.SPAWN);
            System.out.println("[Launcher] Project ready: " + dir.getAbsolutePath());
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    /** Template picker: accepts a template name or its list number. Returns null on cancel; re-prompts on bad input. */
    private static TemplateManager.ProjectTemplate promptTemplate() {
        java.util.List<TemplateManager.ProjectTemplate> templates = TemplateManager.getTemplates();
        StringBuilder msg = new StringBuilder("Choose a baseline template (name or number):\n");
        for (int i = 0; i < templates.size(); i++) {
            TemplateManager.ProjectTemplate t = templates.get(i);
            msg.append("\n").append(i + 1).append(". ").append(t.displayName()).append(" - ").append(t.description());
        }
        String fallback = TemplateManager.STARTER_CONTENT.displayName();
        while (true) {
            String answer = NativeFileDialog.promptInput("New Project", msg.toString(), fallback);
            if (answer == null) return null;
            answer = answer.trim();
            try {
                int idx = Integer.parseInt(answer) - 1;
                if (idx >= 0 && idx < templates.size()) return templates.get(idx);
            } catch (NumberFormatException ignored) {
                TemplateManager.ProjectTemplate byName = TemplateManager.byDisplayName(answer);
                if (byName != null) return byName;
            }
            fallback = answer;
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
