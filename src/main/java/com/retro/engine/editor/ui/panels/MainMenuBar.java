package com.retro.engine.editor.ui.panels;

import com.retro.engine.editor.EditorWorkspace.DropdownMenu;
import com.retro.engine.editor.commands.CommandStack;
import com.retro.engine.editor.input.PlatformShortcuts;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.world.SceneWorld.PlayState;

import java.util.List;

/**
 * MainMenuBar handles 2D UI vector geometry batching for top menu items,
 * dropdown overlays, title bar indicators, and simulation state controls.
 *
 * Owns the menu bar / dropdown geometry; {@code MainMenuBarInputDelegate} hit-tests against the
 * same helpers, so layout tweaks here never desynchronize clicks from what is drawn.
 */
public class MainMenuBar {

    public static final float BAR_Y0 = 0.92f, BAR_Y1 = 1.0f;

    // Play / Pause / Stop segmented control
    public static final float PLAY_X0 = -0.14f, PLAY_X1 = -0.02f;
    public static final float PAUSE_X0 = -0.01f, PAUSE_X1 = 0.10f;
    public static final float STOP_X0 = 0.11f, STOP_X1 = 0.22f;

    // Dropdown menus
    public static final float DROPDOWN_WIDTH = 0.42f;
    public static final float DROPDOWN_ITEM_H = 0.075f;
    public static final float DROPDOWN_PAD = 0.012f;

    private static final float MENU_TEXT = 1.0f;
    private static final float ITEM_TEXT = 0.95f;

    private static final StringBuilder sceneSb = new StringBuilder(96);

    // ------------------------------------------------------------------------------------------
    // Shared geometry
    // ------------------------------------------------------------------------------------------

    public static float titleMinX(DropdownMenu menu) {
        return switch (menu) {
            case EDIT -> -0.61f;
            case ENTITY -> -0.50f;
            case VIEW -> -0.37f;
            default -> -0.72f;
        };
    }

    public static float titleMaxX(DropdownMenu menu) {
        return switch (menu) {
            case EDIT -> -0.51f;
            case ENTITY -> -0.38f;
            case VIEW -> -0.27f;
            default -> -0.62f;
        };
    }

    private static final DropdownMenu[] MENUS = { DropdownMenu.FILE, DropdownMenu.EDIT, DropdownMenu.ENTITY, DropdownMenu.VIEW };

    /** Menu whose title spans ndcX, or NONE. */
    public static DropdownMenu titleAt(float ndcX) {
        for (DropdownMenu m : MENUS) {
            if (ndcX >= titleMinX(m) && ndcX <= titleMaxX(m)) return m;
        }
        return DropdownMenu.NONE;
    }

    public static int dropdownItemCount(DropdownMenu menu) {
        return switch (menu) {
            case FILE -> 6;
            case EDIT -> 3;
            case ENTITY -> 4;
            case VIEW -> 3;
            default -> 0;
        };
    }

    public static float dropdownMinX(DropdownMenu menu) { return titleMinX(menu); }
    public static float dropdownMaxX(DropdownMenu menu) { return titleMinX(menu) + DROPDOWN_WIDTH; }
    public static float dropdownMaxY() { return BAR_Y0; }
    public static float dropdownMinY(DropdownMenu menu) {
        return BAR_Y0 - DROPDOWN_PAD * 2.0f - dropdownItemCount(menu) * DROPDOWN_ITEM_H;
    }

    public static boolean dropdownContains(DropdownMenu menu, float ndcX, float ndcY) {
        return ndcX >= dropdownMinX(menu) && ndcX <= dropdownMaxX(menu) && ndcY >= dropdownMinY(menu) && ndcY <= dropdownMaxY();
    }

    /** Index of the dropdown row under the point, or -1 (outside, or in the menu's top/bottom padding). */
    public static int dropdownItemAt(DropdownMenu menu, float ndcX, float ndcY) {
        if (!dropdownContains(menu, ndcX, ndcY)) return -1;
        float fromTop = dropdownMaxY() - DROPDOWN_PAD - ndcY;
        if (fromTop < 0.0f) return -1;
        int idx = (int) (fromTop / DROPDOWN_ITEM_H);
        return idx < dropdownItemCount(menu) ? idx : -1;
    }

    private static float itemTop(int index) {
        return dropdownMaxY() - DROPDOWN_PAD - index * DROPDOWN_ITEM_H;
    }

    // ------------------------------------------------------------------------------------------
    // Menu bar
    // ------------------------------------------------------------------------------------------

    public static void render(UIGeometryBatch.Buffer buffer, DropdownMenu activeMenu, String activeScenePath, PlayState playState) {
        UIGeometryBatch.addQuad(buffer, -1.0f, BAR_Y0, 1.0f, BAR_Y1, EditorTheme.SURFACE, 1.0f);
        UIGeometryBatch.addQuad(buffer, -1.0f, BAR_Y0, 1.0f, BAR_Y0 + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        // App name, bold-white like the macOS application menu
        UIGeometryBatch.addRoundedQuad(buffer, -0.975f, 0.946f, -0.962f, 0.974f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "RETRO ENGINE", -0.952f, 0.970f, -0.75f, 1.1f, EditorTheme.TEXT, 1.0f);

        renderMenuTitle(buffer, "FILE", DropdownMenu.FILE, activeMenu);
        renderMenuTitle(buffer, "EDIT", DropdownMenu.EDIT, activeMenu);
        renderMenuTitle(buffer, "ENTITY", DropdownMenu.ENTITY, activeMenu);
        renderMenuTitle(buffer, "VIEW", DropdownMenu.VIEW, activeMenu);

        renderPlayControls(buffer, playState);

        // Document title (right side)
        UIGeometryBatch.addBoundedText(buffer, "SCENE", 0.45f, 0.969f, 0.55f, MENU_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
        sceneSb.setLength(0);
        sceneSb.append(extractSceneName(activeScenePath));
        UIGeometryBatch.addTruncatedText(buffer, sceneSb, 0.45f + UIGeometryBatch.textWidth(buffer, "SCENE ", MENU_TEXT), 0.969f, 0.98f, MENU_TEXT, EditorTheme.TEXT, 1.0f);
    }

    private static void renderMenuTitle(UIGeometryBatch.Buffer buffer, String title, DropdownMenu menu, DropdownMenu activeMenu) {
        float minX = titleMinX(menu), maxX = titleMaxX(menu);
        boolean active = (menu == activeMenu);
        if (active) {
            UIGeometryBatch.addRoundedQuad(buffer, minX, 0.932f, maxX, 0.988f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        }
        float textW = UIGeometryBatch.textWidth(buffer, title, MENU_TEXT);
        float textX = minX + Math.max(0.008f, ((maxX - minX) - textW) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, title, textX, 0.969f, maxX, MENU_TEXT, EditorTheme.TEXT, 1.0f);
    }

    private static void renderPlayControls(UIGeometryBatch.Buffer buffer, PlayState playState) {
        boolean isSimulating = (playState == PlayState.SIMULATING);
        boolean isPaused = (playState == PlayState.PAUSED);
        boolean isEditing = (playState == PlayState.EDITING);

        // Segmented control container
        UIGeometryBatch.addRoundedCard(buffer, PLAY_X0 - 0.008f, 0.930f, STOP_X1 + 0.008f, 0.990f,
                EditorTheme.RADIUS, EditorTheme.BACKGROUND, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addQuad(buffer, (PLAY_X1 + PAUSE_X0) * 0.5f - 0.001f, 0.940f, (PLAY_X1 + PAUSE_X0) * 0.5f + 0.001f, 0.980f, EditorTheme.BORDER, 1.0f);
        UIGeometryBatch.addQuad(buffer, (PAUSE_X1 + STOP_X0) * 0.5f - 0.001f, 0.940f, (PAUSE_X1 + STOP_X0) * 0.5f + 0.001f, 0.980f, EditorTheme.BORDER, 1.0f);

        float iconY0 = 0.950f, iconY1 = 0.970f;

        // PLAY
        if (isSimulating) segmentHighlight(buffer, PLAY_X0, PLAY_X1);
        int playColor = isSimulating ? EditorTheme.GREEN : EditorTheme.TEXT_MUTED;
        float ix = PLAY_X0 + 0.016f;
        UIGeometryBatch.addTriangle(buffer, ix, iconY0, ix, iconY1, ix + 0.013f, (iconY0 + iconY1) * 0.5f, playColor, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "PLAY", ix + 0.024f, 0.968f, PLAY_X1, 0.9f, isSimulating ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, 1.0f);

        // PAUSE
        if (isPaused) segmentHighlight(buffer, PAUSE_X0, PAUSE_X1);
        int pauseColor = isPaused ? EditorTheme.ORANGE : EditorTheme.TEXT_MUTED;
        ix = PAUSE_X0 + 0.014f;
        UIGeometryBatch.addQuad(buffer, ix, iconY0, ix + 0.004f, iconY1, pauseColor, 1.0f);
        UIGeometryBatch.addQuad(buffer, ix + 0.008f, iconY0, ix + 0.012f, iconY1, pauseColor, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "PAUSE", ix + 0.022f, 0.968f, PAUSE_X1, 0.9f, isPaused ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED, 1.0f);

        // STOP (only actionable while simulating or paused)
        int stopColor = isEditing ? EditorTheme.TEXT_DISABLED : EditorTheme.RED;
        ix = STOP_X0 + 0.016f;
        UIGeometryBatch.addRoundedQuad(buffer, ix, iconY0, ix + 0.011f, iconY1, 0.003f, stopColor, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "STOP", ix + 0.022f, 0.968f, STOP_X1, 0.9f, isEditing ? EditorTheme.TEXT_DISABLED : EditorTheme.TEXT, 1.0f);
    }

    private static void segmentHighlight(UIGeometryBatch.Buffer buffer, float x0, float x1) {
        UIGeometryBatch.addRoundedQuad(buffer, x0, 0.936f, x1, 0.984f, EditorTheme.RADIUS_SMALL, EditorTheme.BORDER, 1.0f);
    }

    // ------------------------------------------------------------------------------------------
    // Dropdowns
    // ------------------------------------------------------------------------------------------

    public static void renderDropdownMenuOverlay(UIGeometryBatch.Buffer buffer, DropdownMenu menu, CommandStack commandHistory, boolean show3DGrid) {
        renderDropdownMenuOverlay(buffer, menu, commandHistory, show3DGrid, Float.NaN, Float.NaN);
    }

    /** Dropdown with a macOS-style accent highlight on the row under (hoverX, hoverY). */
    public static void renderDropdownMenuOverlay(UIGeometryBatch.Buffer buffer, DropdownMenu menu, CommandStack commandHistory, boolean show3DGrid,
                                                 float hoverX, float hoverY) {
        if (dropdownItemCount(menu) == 0) return;
        float minX = dropdownMinX(menu), maxX = dropdownMaxX(menu);
        float minY = dropdownMinY(menu), maxY = dropdownMaxY();
        int hover = dropdownItemAt(menu, hoverX, hoverY);

        // Soft drop shadow, then the rounded menu surface
        UIGeometryBatch.addRoundedQuad(buffer, minX + 0.004f, minY - 0.010f, maxX + 0.004f, maxY - 0.004f, EditorTheme.RADIUS * 1.4f, 0x000000, 0.35f);
        UIGeometryBatch.addRoundedCard(buffer, minX, minY, maxX, maxY, EditorTheme.RADIUS * 1.4f, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 0.98f);

        switch (menu) {
            case FILE -> {
                renderDropdownItem(buffer, menu, 0, hover, "New Scene", null, true);
                renderDropdownItem(buffer, menu, 1, hover, "Open Scene...", PlatformShortcuts.OPEN.label(), true);
                renderDropdownItem(buffer, menu, 2, hover, "Save Scene...", PlatformShortcuts.SAVE.label(), true);
                renderSeparator(buffer, menu, 3);
                renderDropdownItem(buffer, menu, 3, hover, "Export Standalone Build...", null, true);
                renderSeparator(buffer, menu, 4);
                renderDropdownItem(buffer, menu, 4, hover, "Switch Project...", null, true);
                renderSeparator(buffer, menu, 5);
                renderDropdownItem(buffer, menu, 5, hover, PlatformShortcuts.IS_MAC ? "Quit Retro Engine" : "Exit", PlatformShortcuts.QUIT.label(), true);
            }
            case EDIT -> {
                renderDropdownItem(buffer, menu, 0, hover, "Undo", PlatformShortcuts.UNDO.label(), commandHistory.canUndo());
                renderDropdownItem(buffer, menu, 1, hover, "Redo", PlatformShortcuts.REDO.label(), commandHistory.canRedo());
                renderSeparator(buffer, menu, 2);
                renderDropdownItem(buffer, menu, 2, hover, "Delete Selected", PlatformShortcuts.DELETE.label(), true);
            }
            case ENTITY -> {
                renderDropdownItem(buffer, menu, 0, hover, "Spawn 3D Cube", null, true);
                renderDropdownItem(buffer, menu, 1, hover, "Spawn Light Source", null, true);
                renderDropdownItem(buffer, menu, 2, hover, "Spawn Camera", null, true);
                renderSeparator(buffer, menu, 3);
                renderDropdownItem(buffer, menu, 3, hover, "Save Selected as Prefab...", null, true);
            }
            case VIEW -> {
                renderDropdownItem(buffer, menu, 0, hover, "Reset Viewport Camera", PlatformShortcuts.FOCUS_CAMERA.label(), true);
                renderDropdownItem(buffer, menu, 1, hover, show3DGrid ? "Hide 3D Grid" : "Show 3D Grid", null, true);
                renderSeparator(buffer, menu, 2);
                renderDropdownItem(buffer, menu, 2, hover, "Reset Dock Layout", null, true);
            }
            default -> {}
        }
    }

    public static void renderDropdownMenuOverlay(List<Float> verts, DropdownMenu menu, CommandStack commandHistory, boolean show3DGrid) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        renderDropdownMenuOverlay(tempBuf, menu, commandHistory, show3DGrid);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    private static void renderDropdownItem(UIGeometryBatch.Buffer buffer, DropdownMenu menu, int index, int hover,
                                           String label, String shortcut, boolean enabled) {
        float minX = dropdownMinX(menu), maxX = dropdownMaxX(menu);
        float top = itemTop(index), bottom = top - DROPDOWN_ITEM_H;
        boolean hot = enabled && index == hover;

        if (hot) {
            UIGeometryBatch.addRoundedQuad(buffer, minX + 0.008f, bottom + 0.006f, maxX - 0.008f, top - 0.006f, EditorTheme.RADIUS_SMALL, EditorTheme.ACCENT, 1.0f);
        }
        float textTop = (top + bottom) * 0.5f + ITEM_TEXT * 0.009f;
        int labelColor = !enabled ? EditorTheme.TEXT_DISABLED : EditorTheme.TEXT;
        int shortcutColor = !enabled ? EditorTheme.TEXT_DISABLED : (hot ? EditorTheme.TEXT : EditorTheme.TEXT_MUTED);

        float shortcutLeft = maxX - 0.022f;
        if (shortcut != null) {
            UIGeometryBatch.addRightAlignedText(buffer, shortcut, maxX - 0.022f, textTop, ITEM_TEXT, shortcutColor, 1.0f);
            shortcutLeft -= UIGeometryBatch.textWidth(buffer, shortcut, ITEM_TEXT) + 0.02f;
        }
        UIGeometryBatch.addTruncatedText(buffer, label, minX + 0.026f, textTop, shortcutLeft, ITEM_TEXT, labelColor, 1.0f);
    }

    /** Hairline separator drawn on the boundary above the given row (no extra height, so hit rows stay uniform). */
    private static void renderSeparator(UIGeometryBatch.Buffer buffer, DropdownMenu menu, int beforeIndex) {
        float y = itemTop(beforeIndex);
        UIGeometryBatch.addQuad(buffer, dropdownMinX(menu) + 0.018f, y - 0.001f, dropdownMaxX(menu) - 0.018f, y + 0.001f, EditorTheme.BORDER_STRONG, 1.0f);
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
