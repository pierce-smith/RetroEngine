package com.retro.engine.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The project file, {@code <Project>/<Name>.rproj}: one binary container for everything that describes a project.
 *
 * <pre>
 *   int    MAGIC 'RPRO'
 *   int    FORMAT_VERSION
 *   int    sectionCount
 *   per section: int tag, int length, byte[length] payload
 *   int    CRC-32 of every byte before it
 * </pre>
 *
 * Sections are opaque here. {@link #SETTINGS} holds the {@link ProjectSettings} record and {@link #ASSET_DIRECTORY}
 * holds the asset directory (path to UUID map) that {@code AssetDatabase} keeps. Their payloads are byte-for-byte
 * what the old {@code ProjectSettings.bin} and {@code .retro/asset_registry.bin} files held, each with its own
 * magic and version, so their readers didn't change and {@link #migrateLegacy} only has to wrap the old files.
 * Unknown sections are kept on rewrite, so an older engine doesn't drop data a newer one added.
 *
 * <p>Settings and the asset directory are written by different threads (the asset watcher runs in the background),
 * so every write is a read-modify-write of the whole file under one lock, committed with an atomic rename.
 */
public final class ProjectFile {

    public static final String EXTENSION = ".rproj";

    public static final int SETTINGS = 0x53455454;        // 'SETT'
    public static final int ASSET_DIRECTORY = 0x41534554; // 'ASET'

    static final int MAGIC = 0x5250524F; // 'RPRO'
    static final int FORMAT_VERSION = 1;
    private static final int MAX_SECTIONS = 256;

    /** Pre-.rproj layout, read only by {@link #migrateLegacy}. */
    public static final String LEGACY_SETTINGS_FILE = "ProjectSettings.bin";
    public static final String LEGACY_INTERNAL_DIR = ".retro";
    public static final String LEGACY_ASSET_REGISTRY_FILE = "asset_registry.bin";
    public static final String LEGACY_PREFAB_EXTENSION = ".prefab.bin";
    public static final String PREFAB_EXTENSION = ".rprefab";

    private static final Object LOCK = new Object();

    private ProjectFile() {}

    // ------------------------------------------------------------------------------------------
    // Locating
    // ------------------------------------------------------------------------------------------

    /**
     * The project file in {@code dir}, or null. With several, {@code <folder name>.rproj} wins, then the first by name,
     * so a copied or renamed project folder still opens.
     */
    public static File find(File dir) {
        if (dir == null) return null;
        File[] candidates = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION));
        if (candidates == null || candidates.length == 0) return null;
        if (candidates.length == 1) return candidates[0];
        Arrays.sort(candidates, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : candidates) {
            if (f.getName().equalsIgnoreCase(dir.getName() + EXTENSION)) return f;
        }
        Log.warn("ProjectFile", "Several " + EXTENSION + " files in " + dir + "; using " + candidates[0].getName() + ".");
        return candidates[0];
    }

    /** A project folder: it has a .rproj, or the pre-.rproj ProjectSettings.bin that opening will migrate. */
    public static boolean isProjectDirectory(File dir) {
        return dir != null && (find(dir) != null || new File(dir, LEGACY_SETTINGS_FILE).isFile());
    }

    /** File name for a new project file: the project name with characters file systems reject replaced. */
    static String fileNameFor(String projectName, File dir) {
        String base = projectName != null ? projectName.trim() : "";
        if (base.isEmpty() && dir != null) base = dir.getName();
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "Project";
        return base + EXTENSION;
    }

    // ------------------------------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------------------------------

    /**
     * One section's payload, or null when the project has no file or no such section. Before migration, the
     * legacy files stand in for their sections, so read-only callers (recent projects, the launcher) work on
     * unmigrated projects without changing them.
     */
    public static byte[] readSection(File dir, int tag) throws IOException {
        synchronized (LOCK) {
            File file = find(dir);
            if (file != null) return readAll(file).get(tag);
            File legacy = legacyFileFor(dir, tag);
            return (legacy != null && legacy.isFile()) ? Files.readAllBytes(legacy.toPath()) : null;
        }
    }

    /**
     * Replaces one section, keeping every other section, and creates the project file when there is none.
     * {@code projectName} names a new file and is ignored when one exists.
     */
    public static void writeSection(File dir, int tag, byte[] payload, String projectName) throws IOException {
        if (payload == null) throw new IllegalArgumentException("payload");
        synchronized (LOCK) {
            File file = find(dir);
            Map<Integer, byte[]> sections = file != null ? readAll(file) : new LinkedHashMap<>();
            if (file == null) file = new File(dir, fileNameFor(projectName, dir));
            sections.put(tag, payload);
            writeAll(file, sections);
        }
    }

    static Map<Integer, byte[]> readAll(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length < 16) throw new IOException(file.getName() + " is too short to be a project file.");
        int crcAt = bytes.length - 4;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, crcAt);
        int stored = ((bytes[crcAt] & 0xFF) << 24) | ((bytes[crcAt + 1] & 0xFF) << 16) | ((bytes[crcAt + 2] & 0xFF) << 8) | (bytes[crcAt + 3] & 0xFF);
        if ((int) crc.getValue() != stored) throw new IOException(file.getName() + " is damaged (checksum mismatch).");

        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes, 0, crcAt))) {
            if (in.readInt() != MAGIC) throw new IOException(file.getName() + " is not a RetroEngine project file.");
            int version = in.readInt();
            if (version > FORMAT_VERSION) {
                throw new IOException(file.getName() + " is project file format " + version + ", newer than this engine supports.");
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_SECTIONS) throw new IOException(file.getName() + " has an invalid section count: " + count);
            Map<Integer, byte[]> sections = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                int tag = in.readInt();
                int length = in.readInt();
                if (length < 0 || length > in.available()) throw new IOException(file.getName() + " has a truncated section.");
                byte[] payload = new byte[length];
                in.readFully(payload);
                sections.put(tag, payload);
            }
            return sections;
        }
    }

    private static void writeAll(File file, Map<Integer, byte[]> sections) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(sections.size());
            for (Map.Entry<Integer, byte[]> s : sections.entrySet()) {
                out.writeInt(s.getKey());
                out.writeInt(s.getValue().length);
                out.write(s.getValue());
            }
        }
        CRC32 crc = new CRC32();
        byte[] body = bytes.toByteArray();
        crc.update(body);
        int c = (int) crc.getValue();
        byte[] all = Arrays.copyOf(body, body.length + 4);
        all[body.length] = (byte) (c >>> 24);
        all[body.length + 1] = (byte) (c >>> 16);
        all[body.length + 2] = (byte) (c >>> 8);
        all[body.length + 3] = (byte) c;

        File parent = file.getAbsoluteFile().getParentFile();
        Path tmp = new File(parent, file.getName() + ".tmp").toPath();
        Files.write(tmp, all);
        try {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File legacyFileFor(File dir, int tag) {
        if (tag == SETTINGS) return new File(dir, LEGACY_SETTINGS_FILE);
        if (tag == ASSET_DIRECTORY) return new File(new File(dir, LEGACY_INTERNAL_DIR), LEGACY_ASSET_REGISTRY_FILE);
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Migration
    // ------------------------------------------------------------------------------------------

    /** What {@link #migrateLegacy} changed; empty when the project was already current. */
    public record Migration(File projectFile, List<String> renamedPrefabs) {
        public boolean changed() { return projectFile != null || !renamedPrefabs.isEmpty(); }
    }

    /**
     * Brings a pre-.rproj project up to date, once:
     * <ol>
     *   <li>ProjectSettings.bin and .retro/asset_registry.bin become the sections of a new .rproj, and the old files
     *       are renamed to {@code .bak} rather than deleted;</li>
     *   <li>every {@code *.prefab.bin} under Assets/ is renamed to {@code *.rprefab}. Asset UUIDs carry over, because
     *       AssetDatabase reads legacy prefab paths in the asset directory as their .rprefab names.</li>
     * </ol>
     * Prefab renames also run on already-migrated projects, which picks up legacy prefabs copied in later. A file
     * whose new name is taken is left alone and logged.
     */
    public static Migration migrateLegacy(File projectDir) throws IOException {
        File dir = projectDir.getAbsoluteFile();
        File created = null;
        synchronized (LOCK) {
            File legacySettings = new File(dir, LEGACY_SETTINGS_FILE);
            if (find(dir) == null && legacySettings.isFile()) {
                Map<Integer, byte[]> sections = new LinkedHashMap<>();
                byte[] settingsBytes = Files.readAllBytes(legacySettings.toPath());
                sections.put(SETTINGS, settingsBytes);
                File legacyRegistry = legacyFileFor(dir, ASSET_DIRECTORY);
                if (legacyRegistry.isFile()) sections.put(ASSET_DIRECTORY, Files.readAllBytes(legacyRegistry.toPath()));

                String name = ProjectSettings.readPayload(settingsBytes, legacySettings.getName()).projectName;
                created = new File(dir, fileNameFor(name, dir));
                writeAll(created, sections);
                // Proves the new file reads back before the old ones are moved aside
                readAll(created);

                retire(legacySettings);
                if (legacyRegistry.isFile()) retire(legacyRegistry);
                Log.info("ProjectFile", "Migrated " + dir.getName() + " to " + created.getName()
                        + "; the old files are kept as .bak.");
            }
        }
        List<String> renamed = renameLegacyPrefabs(new File(dir, "Assets"));
        return new Migration(created, renamed);
    }

    private static void retire(File legacy) throws IOException {
        File bak = new File(legacy.getParentFile(), legacy.getName() + ".bak");
        Files.move(legacy.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<String> renameLegacyPrefabs(File assetsDir) throws IOException {
        List<String> renamed = new ArrayList<>();
        if (!assetsDir.isDirectory()) return renamed;
        List<Path> legacy;
        try (var walk = Files.walk(assetsDir.toPath())) {
            legacy = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(LEGACY_PREFAB_EXTENSION))
                    .toList();
        }
        for (Path p : legacy) {
            Path target = p.resolveSibling(toCurrentPrefabName(p.getFileName().toString()));
            if (Files.exists(target)) {
                Log.warn("ProjectFile", "Left " + p + " alone: " + target.getFileName() + " already exists.");
                continue;
            }
            Files.move(p, target);
            renamed.add(assetsDir.toPath().relativize(target).toString().replace('\\', '/'));
        }
        if (!renamed.isEmpty()) Log.info("ProjectFile", "Renamed " + renamed.size() + " prefab(s) to " + PREFAB_EXTENSION + ".");
        return renamed;
    }

    /** "X.prefab.bin" becomes "X.rprefab" (any case); anything else is returned unchanged. */
    public static String toCurrentPrefabName(String path) {
        if (path == null) return null;
        if (!path.toLowerCase(Locale.ROOT).endsWith(LEGACY_PREFAB_EXTENSION)) return path;
        return path.substring(0, path.length() - LEGACY_PREFAB_EXTENSION.length()) + PREFAB_EXTENSION;
    }

    /** "X.rprefab" becomes "X.prefab.bin" (any case); for content that still ships the old name, such as mods. */
    public static String toLegacyPrefabName(String path) {
        if (path == null) return null;
        if (!path.toLowerCase(Locale.ROOT).endsWith(PREFAB_EXTENSION)) return path;
        return path.substring(0, path.length() - PREFAB_EXTENSION.length()) + LEGACY_PREFAB_EXTENSION;
    }
}
