package com.retro.engine.core;

import java.io.File;
import java.util.Locale;

/**
 * The operating system's standard per-user folder for application data, where games keep their saves:
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support}</li>
 *   <li>Windows: {@code %APPDATA%} (the roaming AppData folder)</li>
 *   <li>Linux and others: {@code $XDG_DATA_HOME}, or {@code ~/.local/share} when it is unset</li>
 * </ul>
 * The system property {@code retroengine.userDataDir} replaces the OS folder (tests, portable installs).
 */
public final class UserFolders {

    public static final String OVERRIDE_PROPERTY = "retroengine.userDataDir";

    private UserFolders() {}

    /** The OS application data folder (not created here). */
    public static File dataRoot() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) return new File(override).getAbsoluteFile();
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return new File(home, "Library/Application Support");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return new File(appData);
            return new File(home, "AppData\\Roaming");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank() && new File(xdg).isAbsolute()) return new File(xdg);
        return new File(home, ".local/share");
    }

    /**
     * Turns a game's name into a folder name that is valid on Windows, macOS and Linux. Spaces and letters of any
     * script are kept (players see this folder); characters Windows forbids, control characters and trailing dots or
     * spaces are not, and Windows device names such as {@code CON} get an underscore.
     */
    public static String folderName(String name) {
        if (name == null) return "Game";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 32 || c == 127 || "<>:\"/\\|?*".indexOf(c) >= 0;
            out.append(bad ? '_' : c);
        }
        String s = out.toString().strip();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).strip();
        if (s.isEmpty()) return "Game";
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (base.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) s = s + "_";
        return s;
    }
}
