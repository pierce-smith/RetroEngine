package com.retro.engine.editor.ui.theme;

/**
 * macOS "Dark Graphite" editor palette as packed 0xRRGGBB values, consumed by the packed-color
 * overloads in {@link com.retro.engine.editor.ui.batch.UIGeometryBatch}.
 */
public final class EditorTheme {

    private EditorTheme() {}

    // Surfaces
    public static final int BACKGROUND = 0x1C1C20;     // Deep charcoal: window, tab strips, splitters
    public static final int SURFACE = 0x26262B;        // Slate gray: panel / dock surfaces
    public static final int SURFACE_RAISED = 0x2F2F35; // Cards, section headers, buttons
    public static final int SURFACE_SUNKEN = 0x1F1F24; // Text fields, value wells
    public static final int BORDER = 0x3A3A42;         // Subtle metallic borders & dividers
    public static final int BORDER_STRONG = 0x4A4A54;

    // Accents
    public static final int ACCENT = 0x0A84FF;         // Apple system blue
    public static final int GRAPHITE = 0x5E5E6E;

    // Text
    public static final int TEXT = 0xF2F2F7;
    public static final int TEXT_MUTED = 0x8E8E93;
    public static final int TEXT_DISABLED = 0x5A5A60;

    // Vector axis badges
    public static final int AXIS_X = 0xFF453A;
    public static final int AXIS_Y = 0x30D158;
    public static final int AXIS_Z = 0x0A84FF;

    // System colors (status, categories, file types)
    public static final int RED = 0xFF453A;
    public static final int ORANGE = 0xFF9F0A;
    public static final int YELLOW = 0xFFD60A;
    public static final int GREEN = 0x30D158;
    public static final int TEAL = 0x64D2FF;
    public static final int PURPLE = 0xBF5AF2;
    public static final int PINK = 0xFF375F;

    // Animator graph nodes
    public static final int NODE_ENTRY = 0xD97706;     // Muted amber
    public static final int NODE_ANY_STATE = 0x475569; // Slate
    public static final int NODE_ACTIVE = ACCENT;
    public static final int NODE_INACTIVE = SURFACE;
    public static final int SHADOW = 0x000000;

    // Dope sheet channels
    public static final int CHANNEL_TRANSLATION = TEAL;
    public static final int CHANNEL_ROTATION = GREEN;
    public static final int CHANNEL_SCALE = ORANGE;
    public static final int PLAYHEAD = RED;

    /** Corner radius (in NDC Y units) for cards, buttons and fields. */
    public static final float RADIUS = 0.010f;
    public static final float RADIUS_SMALL = 0.006f;
    public static final float HAIRLINE = 0.002f;

    public static int axisColor(int axis) {
        return switch (axis) {
            case 0 -> AXIS_X;
            case 1 -> AXIS_Y;
            default -> AXIS_Z;
        };
    }
}
