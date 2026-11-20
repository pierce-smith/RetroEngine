package com.retro.engine.scripting.framework;

import com.retro.engine.api.PublicApi;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.editor.StandaloneExporter;
import com.retro.engine.scripting.ScriptEngine;

import com.retro.engine.core.UserFolders;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Persistent key/value data for gameplay scripts that belongs to the player rather than to one playthrough (settings,
 * unlocks, high scores). One file per game, shared by every save slot. Progress through the game (where the player is,
 * what happened in each level) goes in a save slot: see {@link com.retro.engine.save.SaveGame}.
 *
 * <p>Values are kept in memory and written to {@code <game>/Saves/save.properties} inside the operating system's
 * folder for application data when {@link #save()} is called, when Play stops in the editor, and when the game
 * process exits:</p>
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/<game>/Saves/}</li>
 *   <li>Windows: {@code %APPDATA%\<game>\Saves\}</li>
 *   <li>Linux: {@code ~/.local/share/<game>/Saves/} (or under {@code $XDG_DATA_HOME})</li>
 * </ul>
 * <p>{@code <game>} is the project's name for an exported game and the project's folder name in the editor.
 * Values are stored as text, so read them back with the typed getters. Scripts that need files of their own
 * (extra save slots, screenshots) can put them in {@link #getSaveFolder()}.</p>
 *
 * <p>Saves written by earlier betas to {@code ~/.retroengine/saves/} are copied to the new folder the first time a
 * game finds no save there; the old file is left in place.</p>
 *
 * <p>Setting values formats them as text and may allocate; that is fine for events such as a pickup or a checkpoint,
 * but avoid writing save data every frame. Reads of booleans / numbers parse the stored text on each call, so cache
 * values you need per frame in a field.</p>
 */
@PublicApi
public final class ScriptSaveData {

    private static final Properties values = new Properties();
    private static String loadedFor = null;
    private static String cachedForRoot = null;
    private static String cachedPath = null;
    private static String cachedLegacyPath = null;
    private static boolean dirty = false;
    private static boolean shutdownHookInstalled = false;

    private ScriptSaveData() {}

    // =========================================================================
    // Writing
    // =========================================================================

    /** Stores {@code value} (String.valueOf; null removes the key). Persisted on the next {@link #save()}. */
    public static synchronized void set(String key, Object value) {
        if (key == null || key.isEmpty()) return;
        ensureLoaded();
        if (value == null) {
            if (values.remove(key) != null) dirty = true;
            return;
        }
        String text = String.valueOf(value);
        Object old = values.setProperty(key, text);
        if (old == null || !old.equals(text)) dirty = true;
    }

    public static synchronized void remove(String key) {
        set(key, null);
    }

    /** Clears every key of the current project's save data (written on the next save). */
    public static synchronized void deleteAll() {
        ensureLoaded();
        if (!values.isEmpty()) dirty = true;
        values.clear();
    }

    // =========================================================================
    // Reading
    // =========================================================================

    public static synchronized boolean has(String key) {
        ensureLoaded();
        return key != null && values.containsKey(key);
    }

    public static synchronized String getString(String key, String defaultValue) {
        ensureLoaded();
        String v = (key != null) ? values.getProperty(key) : null;
        return v != null ? v : defaultValue;
    }

    /** "true" / "false" (any case), or "1" / "0". Anything else returns {@code defaultValue}. */
    public static synchronized boolean getBool(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        if (v.equalsIgnoreCase("true") || v.equals("1")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0")) return false;
        return defaultValue;
    }

    public static synchronized int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(v.trim());
            } catch (NumberFormatException e2) {
                return defaultValue;
            }
        }
    }

    public static synchronized float getFloat(String key, float defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /** Writes pending changes to disk now. Returns false when writing failed (the error is logged). */
    public static synchronized boolean save() {
        if (loadedFor == null || !dirty) return true;
        File file = new File(loadedFor);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("[ScriptSaveData] Could not create save folder " + dir.getAbsolutePath());
            return false;
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            values.store(bytes, "RetroEngine script save data");
            // A crash mid-write must never lose the player's previous save
            com.retro.engine.core.AtomicFile.write(file, bytes.toByteArray());
            dirty = false;
            return true;
        } catch (IOException e) {
            System.err.println("[ScriptSaveData] Saving " + file.getAbsolutePath() + " failed: " + e.getMessage());
            return false;
        }
    }

    /** Absolute path of the current project's save file. */
    public static synchronized String getSaveFilePath() {
        return resolveSavePath();
    }

    /**
     * Absolute path of the folder holding the current project's save file, for files a script writes itself. The
     * folder may not exist yet; create it before writing.
     */
    public static synchronized String getSaveFolder() {
        return new File(resolveSavePath()).getParent();
    }

    /**
     * Loads the save file of the currently open project on first access, and again whenever the project changes (the
     * previous project's pending changes are written first).
     */
    private static void ensureLoaded() {
        String path = resolveSavePath();
        if (path.equals(loadedFor)) return;
        if (loadedFor != null) save();
        values.clear();
        dirty = false;
        loadedFor = path;
        installShutdownHook();
        File file = new File(path);
        if (!file.isFile()) file = migrateLegacySave(file);
        if (file == null || !file.isFile()) return;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            values.load(in);
        } catch (IOException e) {
            System.err.println("[ScriptSaveData] Reading " + path + " failed: " + e.getMessage());
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * A save from before Beta 5 lived in {@code ~/.retroengine/saves/<name>.properties}. Copies it to {@code target}
     * (the old file stays) and returns the file to read: the copy, the old file when copying failed (the next save then
     * writes the new location), or null when there is no old save.
     */
    private static File migrateLegacySave(File target) {
        File legacy = cachedLegacyPath != null ? new File(cachedLegacyPath) : null;
        if (legacy == null || !legacy.isFile()) return null;
        try {
            Files.createDirectories(target.toPath().getParent());
            Files.copy(legacy.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("[ScriptSaveData] Moved save data from " + legacy + " to " + target);
            return target;
        } catch (IOException e) {
            System.err.println("[ScriptSaveData] Could not copy " + legacy + " to " + target + ": " + e.getMessage());
            dirty = true;
            return legacy;
        }
    }

    /** Cached per project root (compared by reference), so reads in a hot path do not rebuild the path string. */
    private static String resolveSavePath() {
        String assetsRoot = ScriptEngine.getProjectAssetsRoot();
        if (cachedPath != null && assetsRoot == cachedForRoot) return cachedPath;
        String project = projectName(assetsRoot);
        cachedForRoot = assetsRoot;
        cachedPath = new File(new File(new File(UserFolders.dataRoot(), UserFolders.folderName(project)), "Saves"),
                "save.properties").getAbsolutePath();
        cachedLegacyPath = legacySavePath(project);
        return cachedPath;
    }

    private static String projectName(String assetsRoot) {
        if (assetsRoot == null) return "default";
        File root = new File(assetsRoot).getAbsoluteFile();
        File projectDir = root.getName().equalsIgnoreCase("Assets") ? root.getParentFile() : root;
        if (projectDir == null || projectDir.getName().isEmpty()) return "default";
        return saveName(projectDir);
    }

    /** Where Beta 4 and earlier kept this project's save. */
    private static String legacySavePath(String project) {
        StringBuilder safe = new StringBuilder(project.length());
        for (int i = 0; i < project.length(); i++) {
            char c = project.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return new File(new File(new File(System.getProperty("user.home"), ".retroengine"), "saves"),
                safe + ".properties").getAbsolutePath();
    }

    /** Exported games all sit in a folder called Game_Data, so the folder can't tell them apart: use the project's name. */
    private static String saveName(File projectDir) {
        if (!StandaloneExporter.GAME_DATA_DIR.equals(projectDir.getName())) return projectDir.getName();
        try {
            String name = ProjectWorkspace.readSettings(projectDir).projectName;
            if (name != null && !name.isBlank()) return name;
        } catch (IOException e) {
            System.err.println("[ScriptSaveData] Could not read the project name in " + projectDir + ": " + e.getMessage());
        }
        return projectDir.getName();
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) return;
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread("ScriptSaveData-flush") {
            @Override
            public void run() {
                save();
            }
        });
    }
}
