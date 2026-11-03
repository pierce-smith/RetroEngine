package com.retro.engine.editor;

import com.retro.engine.core.ProjectWorkspace;

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
 * Backs the {@code File > Recent Projects} section of the editor menu bar: the last {@value #MAX_ENTRIES} opened project
 * folders, newest first, persisted as {@code {"recentProjects": [...]}} in {@code ~/.retroengine/editor_config.json}.
 * The list is cached so the menu can be rendered every frame without disk access.
 */
public final class RecentProjectsMenu {

    public static final int MAX_ENTRIES = 5;
    private static final String KEY = "recentProjects";
    private static final Path CONFIG = Path.of(System.getProperty("user.home"), ".retroengine", "editor_config.json");

    private static volatile List<String> cached;

    private RecentProjectsMenu() {}

    /** Recent project folder paths, newest first (at most {@value #MAX_ENTRIES}). */
    public static List<String> entries() {
        List<String> list = cached;
        if (list == null) {
            list = Collections.unmodifiableList(read());
            cached = list;
        }
        return list;
    }

    /** Display label for a menu row: the folder name. */
    public static String label(String path) {
        String name = new File(path).getName();
        return name.isEmpty() ? path : name;
    }

    public static synchronized void record(File projectDir) {
        String p = projectDir.getAbsoluteFile().getPath();
        List<String> paths = read();
        paths.remove(p);
        paths.add(0, p);
        while (paths.size() > MAX_ENTRIES) paths.remove(paths.size() - 1);
        write(paths);
    }

    public static synchronized void remove(String path) {
        List<String> paths = read();
        if (paths.remove(path)) write(paths);
    }

    /** Opens the recent entry at {@code index}, dropping it from the list if the folder is no longer a project. */
    public static void open(EditorWorkspace workspace, int index) {
        List<String> list = entries();
        if (index < 0 || index >= list.size()) return;
        String path = list.get(index);
        File dir = new File(path);
        if (!ProjectWorkspace.isProjectDirectory(dir)) {
            System.err.println("[RecentProjects] Project folder no longer exists: " + path);
            remove(path);
            return;
        }
        if (workspace.hasActiveProject()) workspace.closeProject();
        try {
            ProjectWorkspace.openProject(workspace, dir);
        } catch (IOException e) {
            System.err.println("[RecentProjects] Could not open " + path + ": " + e.getMessage());
        }
    }

    // --- editor_config.json persistence (minimal JSON: one string array under "recentProjects") ---

    private static List<String> read() {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(CONFIG)) return out;
        try {
            String json = Files.readString(CONFIG, StandardCharsets.UTF_8);
            int key = json.indexOf("\"" + KEY + "\"");
            int open = key < 0 ? -1 : json.indexOf('[', key);
            if (open < 0) return out;
            int i = open + 1;
            while (i < json.length() && json.charAt(i) != ']') {
                char c = json.charAt(i);
                if (c == '"') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < json.length() && json.charAt(i) != '"') {
                        char ch = json.charAt(i);
                        if (ch == '\\' && i + 1 < json.length()) {
                            char esc = json.charAt(++i);
                            switch (esc) {
                                case 'n' -> sb.append('\n');
                                case 't' -> sb.append('\t');
                                case 'r' -> sb.append('\r');
                                case 'b' -> sb.append('\b');
                                case 'f' -> sb.append('\f');
                                case 'u' -> {
                                    if (i + 4 < json.length()) {
                                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                        i += 4;
                                    }
                                }
                                default -> sb.append(esc);
                            }
                        } else {
                            sb.append(ch);
                        }
                        i++;
                    }
                    String p = sb.toString().trim();
                    if (!p.isEmpty() && !out.contains(p) && out.size() < MAX_ENTRIES) out.add(p);
                }
                i++;
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[RecentProjects] Could not read " + CONFIG + ": " + e.getMessage());
        }
        return out;
    }

    private static void write(List<String> paths) {
        StringBuilder sb = new StringBuilder("{\n  \"").append(KEY).append("\": [");
        for (int i = 0; i < paths.size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append('"');
            for (char c : paths.get(i).toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
        sb.append(paths.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        try {
            Files.createDirectories(CONFIG.getParent());
            Path temp = CONFIG.resolveSibling(CONFIG.getFileName() + ".tmp");
            Files.writeString(temp, sb, StandardCharsets.UTF_8);
            Files.move(temp, CONFIG, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[RecentProjects] Could not write " + CONFIG + ": " + e.getMessage());
        }
        cached = null;
    }
}
