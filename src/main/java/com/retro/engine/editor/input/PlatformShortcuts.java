package com.retro.engine.editor.input;

import java.util.Locale;

/**
 * Host-aware shortcut labels for menus and panel hints. macOS shows Macintosh modifier symbols
 * (⌘S, ⇧⌘Z, ⌘Q); Windows/Linux show PC chords (Ctrl + S, Ctrl + Shift + Z, Alt + F4).
 *
 * Labels only: key dispatch lives in {@link HotkeyRouter}, which already treats Ctrl and Cmd (Super)
 * as the same primary modifier on every platform. Quit is handled natively by the OS (the Cocoa app
 * menu's ⌘Q, the window manager's Alt + F4).
 */
public enum PlatformShortcuts {
    OPEN("⌘O", "Ctrl + O"),
    SAVE("⌘S", "Ctrl + S"),
    UNDO("⌘Z", "Ctrl + Z"),
    REDO("⇧⌘Z", "Ctrl + Shift + Z"),
    DUPLICATE("⌘D", "Ctrl + D"),
    DELETE("⌫", "Del"),
    QUIT("⌘Q", "Alt + F4"),
    FOCUS_CAMERA("F", "F"),
    TOGGLE_CONSOLE("~", "~");

    public static final boolean IS_MAC = detectMac(System.getProperty("os.name"));

    private final String macLabel;
    private final String pcLabel;

    PlatformShortcuts(String macLabel, String pcLabel) {
        this.macLabel = macLabel;
        this.pcLabel = pcLabel;
    }

    /** Label for the host OS. */
    public String label() {
        return IS_MAC ? macLabel : pcLabel;
    }

    public String macLabel() { return macLabel; }
    public String pcLabel() { return pcLabel; }

    static boolean detectMac(String osName) {
        if (osName == null) return false;
        String os = osName.toLowerCase(Locale.ROOT);
        return os.startsWith("mac") || os.contains("darwin") || os.contains("os x");
    }
}
