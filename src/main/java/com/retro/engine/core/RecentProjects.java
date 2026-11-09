package com.retro.engine.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Most-recently-opened projects for the launcher, stored per user in {@code ~/.retroengine/recent_projects.txt}
 * (one absolute folder path per line, newest first). Entries whose folder is no longer a project are dropped on load.
 * The parsed list is cached so the launcher can render it every frame without touching the disk.
 */
public final class RecentProjects {

    public static final int MAX_ENTRIES = 8;

    /** One launcher card: the project name from its project file plus its folder ({@code ~}-abbreviated for display). */
    public record Entry(File folder, String projectName, String folderPath) {}

    private static final String HOME = System.getProperty("user.home", "");

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".retroengine", "recent_projects.txt");
    private static volatile List<Entry> cached;

    private RecentProjects() {}

    /** Cached entries, newest first (loaded from disk on first use). */
    public static List<Entry> entries() {
        List<Entry> list = cached;
        if (list == null) {
            list = load();
            cached = list;
        }
        return list;
    }

    /** Moves {@code projectDir} to the top of the list and persists it. */
    public static synchronized void record(File projectDir) {
        File dir = projectDir.getAbsoluteFile();
        List<String> paths = readPaths();
        paths.remove(dir.getPath());
        paths.add(0, dir.getPath());
        while (paths.size() > MAX_ENTRIES) paths.remove(paths.size() - 1);
        writePaths(paths);
        cached = null;
    }

    /** Drops {@code projectDir} from the list (e.g. a card whose folder was moved or deleted). */
    public static synchronized void remove(File projectDir) {
        List<String> paths = readPaths();
        if (paths.remove(projectDir.getAbsoluteFile().getPath())) writePaths(paths);
        cached = null;
    }

    private static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        for (String p : readPaths()) {
            File dir = new File(p);
            if (!ProjectWorkspace.isProjectDirectory(dir)) continue;
            String name = dir.getName();
            try {
                ProjectSettings s = ProjectWorkspace.readSettings(dir);
                if (s.projectName != null && !s.projectName.isBlank()) name = s.projectName;
            } catch (IOException ignored) {
                // Unreadable settings: fall back to the folder name
            }
            String shown = dir.getPath();
            if (!HOME.isEmpty() && shown.startsWith(HOME + File.separator)) shown = "~" + shown.substring(HOME.length());
            out.add(new Entry(dir, name, shown));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> readPaths() {
        List<String> paths = new ArrayList<>();
        if (!Files.isRegularFile(FILE)) return paths;
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String p = line.trim();
                if (!p.isEmpty() && !paths.contains(p)) paths.add(p);
            }
        } catch (IOException e) {
            Log.error("RecentProjects", "Could not read " + FILE + ": " + e.getMessage());
        }
        return paths;
    }

    private static void writePaths(List<String> paths) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.write(temp, paths, StandardCharsets.UTF_8);
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.error("RecentProjects", "Could not write " + FILE + ": " + e.getMessage());
        }
    }
}
