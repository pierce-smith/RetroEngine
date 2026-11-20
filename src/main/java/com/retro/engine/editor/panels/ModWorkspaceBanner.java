package com.retro.engine.editor.panels;

import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.modding.ModManifest;
import com.retro.engine.modding.ModWorkspace;
import com.retro.engine.ui.UIGeometryBatch;

/**
 * The strip under the menu bar while a mod workspace is open: names the mod being authored, says what saving does
 * there, and offers CLOSE. The docked panels move down to make room ({@link DockSpace#areaTop()}).
 */
public final class ModWorkspaceBanner {

    public static final float HEIGHT = 0.05f;
    private static final float TEXT = 0.82f;
    private static final float BUTTON_W = 0.11f;
    private static final float PAD = 0.012f;

    private ModWorkspaceBanner() {}

    public static boolean isVisible() {
        return ModWorkspace.isActive();
    }

    private static float y1() { return DockSpace.AREA_Y1; }
    private static float y0() { return DockSpace.AREA_Y1 - HEIGHT; }
    private static float buttonX1() { return 1.0f - PAD; }
    private static float buttonX0() { return buttonX1() - BUTTON_W; }

    public static void render(UIGeometryBatch.Buffer buffer, float hoverX, float hoverY) {
        ModManifest mod = ModWorkspace.activeMod();
        if (mod == null) return;
        float y0 = y0(), y1 = y1(), mid = (y0 + y1) * 0.5f;
        float textY = mid + TEXT * 0.009f;

        UIGeometryBatch.addQuad(buffer, -1.0f, y0, 1.0f, y1, EditorTheme.BACKGROUND, 1.0f);
        UIGeometryBatch.addQuad(buffer, -1.0f, y0, 1.0f, y1, EditorTheme.MOD_ACCENT, 0.16f);
        UIGeometryBatch.addQuad(buffer, -1.0f, y0, 1.0f, y0 + EditorTheme.HAIRLINE, EditorTheme.MOD_ACCENT, 0.9f);

        float x = -1.0f + 0.02f;
        String title = "MOD WORKSPACE: " + mod.id;
        UIGeometryBatch.addBoundedText(buffer, title, x, textY, buttonX0() - PAD, TEXT, EditorTheme.MOD_ACCENT, 1.0f);
        x += UIGeometryBatch.textWidth(buffer, title, TEXT) + 0.03f;
        UIGeometryBatch.addTruncatedText(buffer, "Saving writes a patch into the mod; the game's scene files never change. Imports go into the mod.",
                x, textY, buttonX0() - PAD, TEXT, EditorTheme.TEXT_MUTED, 1.0f);

        boolean hot = isOverClose(hoverX, hoverY);
        float by0 = y0 + 0.008f, by1 = y1 - 0.008f;
        UIGeometryBatch.addRoundedCard(buffer, buttonX0(), by0, buttonX1(), by1, EditorTheme.RADIUS_SMALL,
                hot ? EditorTheme.MOD_ACCENT : EditorTheme.SURFACE_RAISED, EditorTheme.MOD_ACCENT, 1.0f);
        String label = "CLOSE";
        float lw = UIGeometryBatch.textWidth(buffer, label, 0.8f);
        UIGeometryBatch.addBoundedText(buffer, label, (buttonX0() + buttonX1() - lw) * 0.5f, mid + 0.8f * 0.009f, buttonX1(), 0.8f,
                hot ? EditorTheme.BACKGROUND : EditorTheme.TEXT, 1.0f);
    }

    private static boolean isOverClose(float x, float y) {
        return isVisible() && x >= buttonX0() && x <= buttonX1() && y >= y0() && y <= y1();
    }

    /** A press inside the banner: CLOSE leaves the workspace, the same as File > Close Mod Workspace. */
    public static void onClick(float ndcX, float ndcY, EditorWorkspace workspace) {
        if (isOverClose(ndcX, ndcY)) com.retro.engine.editor.overlays.ProjectLauncherOverlay.toggleModWorkspace(workspace);
    }
}
