package com.retro.engine.assets;

import java.io.File;

/**
 * Fallback lookup for asset paths that no longer resolve directly (moved files, paths saved on another machine).
 *
 * Searches only inside the open project. It must never walk user.dir, the home folder or ~/Downloads: a packaged app
 * launched from Finder has user.dir "/", and walking outside the project trips a macOS privacy prompt for every
 * protected location it touches (Desktop, Documents, Music, Photos, iCloud Drive, network volumes, ...).
 */
public final class ProjectFileLocator {

    private static final int MAX_DEPTH = 8;

    private static volatile File projectRoot;

    private ProjectFileLocator() {}

    /** Set by AssetDatabase when a project opens; null when it closes. */
    public static void setProjectRoot(File root) {
        projectRoot = root;
    }

    /** {@code path} relative to the open project root (handles "Assets/..." paths), or null if absent. */
    public static File resolveInProject(String path) {
        File root = projectRoot;
        if (root == null || path == null || path.isBlank()) return null;
        String clean = path.replace('\\', '/').trim();
        File f = new File(root, clean);
        if (f.exists()) return f;
        if (!clean.regionMatches(true, 0, "Assets/", 0, 7)) {
            f = new File(new File(root, "Assets"), clean);
            if (f.exists()) return f;
        }
        return null;
    }

    /** First file named {@code fileName} (case-insensitive) under the project's Assets/ folder, or null. */
    public static File findByName(String fileName) {
        File root = projectRoot;
        if (root == null || fileName == null || fileName.isEmpty()) return null;
        return find(new File(root, "Assets"), fileName, 0);
    }

    private static File find(File dir, String name, int depth) {
        if (depth > MAX_DEPTH) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                File sub = find(f, name, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }
}
