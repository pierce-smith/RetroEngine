package com.retro.engine.assets;

import com.retro.engine.core.NativeFileDialog;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.graphics.ShaderSources;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Disk-mirrored asset registry for the active project's Assets/ folder.
 *
 * The Assets/ directory on disk is the single source of truth: every entry (folders included) is a real
 * file or directory beneath it, and every browser operation (new folder, rename, delete, import) acts on
 * disk first, then re-syncs. Assets/ is the browser root; nothing outside it is ever registered or reachable.
 *
 * 128-bit UUIDs persist in {@code <project>/.retro/asset_registry.bin}, keyed by project-relative path, so
 * scene references survive restarts. Only {@link #openProject(String)} clears and re-scans the registry;
 * scene loads never touch it. A background watcher picks up changes made outside the editor.
 *
 * The watcher also drives hot reload: edited .java scripts under Assets/ are invalidated in the ScriptEngine, and
 * edited shader files under the engine's shader source root (when bound) are reported to the shader change listener.
 *
 * Hardened with CopyOnWriteArrayList and ConcurrentHashMap for multi-threaded asset queries and
 * Virtual Thread streaming lookups.
 */
public class AssetDatabase {

    public static final String ASSETS_DIR = "Assets";
    public static final String COOKED_DIR = "Cooked";
    public static final String INTERNAL_DIR = ".retro";
    public static final String MANIFEST_FILE = "asset_registry.bin";

    private static final int MANIFEST_MAGIC = 0x52524547; // 'RREG'
    private static final int MANIFEST_VERSION = 3;        // v3: disk-mirrored, every path project-relative
    private static final long WATCH_INTERVAL_MS = 1000L;

    // Swapped wholesale on each re-sync so readers never observe a half-built directory
    private volatile List<AssetMeta> assetDirectory = new CopyOnWriteArrayList<>();
    private final Map<String, AssetMeta> uuidToAssetMap = new ConcurrentHashMap<>();
    private final Map<String, String> pathToUuidMap = new ConcurrentHashMap<>();

    // Manifest UUIDs (lowercase project-relative path -> uuid) applied to files as the scan discovers them
    private final Map<String, String> manifestUuidSeed = new HashMap<>();
    // Runtime builds only: manifest entries ("Assets/..." in original case -> UUID) whose raw source was stripped at
    // export because a cooked .rpack replaces it. Kept for the whole session so every rescan re-registers them.
    private final Map<String, String> cookedOnlyAssets = new HashMap<>();

    private String currentFolderPath = "";
    private String activeProjectPath = "";

    private volatile boolean filesChangedOnDisk = false;
    private Thread watcherThread;

    // Hot reload: script and shader sources the watcher saw modified, dispatched on the main thread by pollFileChanges()
    private final ConcurrentLinkedQueue<String> modifiedSources = new ConcurrentLinkedQueue<>();
    private volatile String shaderSourceRoot = null;
    private volatile Consumer<List<String>> shaderChangeListener = null;

    public List<AssetMeta> getAssetDirectory() {
        return Collections.unmodifiableList(assetDirectory);
    }

    public List<AssetMeta> getMutableAssetDirectory() {
        return assetDirectory;
    }

    /** Current browser folder, relative to Assets/ ("" is the Assets/ root). */
    public String getCurrentFolderPath() {
        return currentFolderPath;
    }

    public String getActiveProjectPath() {
        return activeProjectPath;
    }

    public boolean hasActiveProject() {
        return activeProjectPath != null && !activeProjectPath.isEmpty();
    }

    /**
     * Mod VFS: mounts an extra mod folder (a folder with mod.json, or a folder of mod folders) for the active project
     * and reloads the mod set so it joins the overlay stack in load order. Returns false without an active project.
     */
    public boolean mountModVFS(String modFolderPath) {
        if (!hasActiveProject() || modFolderPath == null || modFolderPath.isBlank()) return false;
        com.retro.engine.modding.ModLoader.mountModFolder(new java.io.File(modFolderPath.trim()), new java.io.File(activeProjectPath));
        return true;
    }

    /**
     * Final on-disk path of an asset through the mod VFS: {@code mod://} URIs, then the top-most mod overlay copy,
     * then the project's own Assets/ file. Accepts absolute, {@code Assets/...} and Assets-relative paths.
     */
    public String resolveAsset(String path) {
        if (path == null || path.isBlank()) return path;
        String resolved = com.retro.engine.modding.ModLoader.resolveAssetPath(path);
        if (!resolved.equals(path) || new java.io.File(path).isAbsolute() || !hasActiveProject()) return resolved;
        String rel = path.trim().replace('\\', '/');
        if (rel.startsWith(ASSETS_DIR + "/")) rel = rel.substring(ASSETS_DIR.length() + 1);
        java.io.File f = new java.io.File(getProjectAssetsPath(), rel);
        return f.isFile() ? f.getAbsolutePath() : path;
    }

    /** Absolute path of the active project's Assets/ folder, or "" when no project is open. */
    public String getProjectAssetsPath() {
        return hasActiveProject() ? activeProjectPath + "/" + ASSETS_DIR : "";
    }

    /** Navigates the browser; only "" (Assets/ root) or an existing folder beneath it is accepted. */
    public void setCurrentFolderPath(String path) {
        String cleanPath = (path != null) ? normalizePath(path) : "";
        while (cleanPath.endsWith("/")) cleanPath = cleanPath.substring(0, cleanPath.length() - 1);
        if (!cleanPath.isEmpty() && findFolder(cleanPath) == null) {
            System.err.println("[AssetDatabase] Ignoring navigation outside Assets/ or to a missing folder: " + cleanPath);
            return;
        }
        this.currentFolderPath = cleanPath;
        saveManifest();
    }

    public void navigateUpFolder() {
        if (currentFolderPath == null || currentFolderPath.isEmpty()) return; // Assets/ is the hard root
        int lastSlash = currentFolderPath.lastIndexOf('/');
        currentFolderPath = (lastSlash != -1) ? currentFolderPath.substring(0, lastSlash) : "";
        saveManifest();
    }

    // --- 128-BIT UUID REGISTRY LOOKUPS ---

    public AssetMeta getAssetByUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return null;
        return uuidToAssetMap.get(uuid.trim());
    }

    /** Case-insensitive alphabetical order by asset name, with the path as a stable tiebreak for duplicate names. */
    public static final Comparator<AssetMeta> NAME_ORDER = Comparator
        .comparing((AssetMeta m) -> m.name != null ? m.name.toLowerCase() : "")
        .thenComparing(m -> m.absolutePath != null ? m.absolutePath : "");

    /** All assets of {@code targetType}, sorted alphabetically by name. */
    public List<AssetMeta> getAssetsByType(AssetType targetType) {
        return filterByTypeSorted(assetDirectory, targetType);
    }

    /** Assets of {@code targetType} from {@code assets}, sorted alphabetically by name (a new list). */
    public static List<AssetMeta> filterByTypeSorted(List<AssetMeta> assets, AssetType targetType) {
        List<AssetMeta> matches = new ArrayList<>();
        if (targetType == null || assets == null) return matches;
        for (AssetMeta meta : assets) {
            if (meta != null && meta.type == targetType) {
                matches.add(meta);
            }
        }
        matches.sort(NAME_ORDER);
        return matches;
    }

    public String resolvePathByUuid(String uuid) {
        AssetMeta meta = getAssetByUuid(uuid);
        if (meta == null) return null;
        if (meta.absolutePath != null && new File(meta.absolutePath).exists()) {
            return meta.absolutePath;
        }
        File resolvedTex = TextureImporter.resolveTextureFile(meta.absolutePath != null ? meta.absolutePath : meta.name);
        if (resolvedTex != null && resolvedTex.exists()) {
            return resolvedTex.getAbsolutePath();
        }
        File resolvedMesh = MeshImporter.resolveModelFile(meta.absolutePath != null ? meta.absolutePath : meta.name);
        if (resolvedMesh != null && resolvedMesh.exists()) {
            return resolvedMesh.getAbsolutePath();
        }
        return meta.absolutePath;
    }

    public String resolveUuidByPath(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String norm = normalizePath(path).toLowerCase();
        String uuid = pathToUuidMap.get(norm);
        if (uuid != null) return uuid;

        String sanitized = SceneSerializer.sanitizeAssetPath(path).toLowerCase();
        if (!sanitized.isEmpty()) {
            uuid = pathToUuidMap.get(sanitized);
            if (uuid != null) return uuid;
        }

        String fileName = new File(norm).getName().toLowerCase();
        if (!fileName.isEmpty()) {
            uuid = pathToUuidMap.get(fileName);
            if (uuid != null) return uuid;
        }

        return null;
    }

    private static void indexAsset(AssetMeta asset, Map<String, AssetMeta> byUuid, Map<String, String> byPath) {
        byUuid.put(asset.uuid, asset);
        if (asset.type == AssetType.FOLDER || asset.absolutePath == null || asset.absolutePath.isEmpty()) return;
        String norm = asset.absolutePath.replace('\\', '/').toLowerCase();
        byPath.put(norm, asset.uuid);
        String sanitized = SceneSerializer.sanitizeAssetPath(asset.absolutePath).toLowerCase();
        if (!sanitized.isEmpty()) byPath.putIfAbsent(sanitized, asset.uuid);
        String fileName = new File(norm).getName();
        if (!fileName.isEmpty()) byPath.putIfAbsent(fileName, asset.uuid);
    }

    // --- PROJECT BINDING ---

    /**
     * Binds the registry to a project root: clears all state, ensures Assets/ and .retro/ exist, seeds UUIDs
     * from .retro/asset_registry.bin, mirrors Assets/ from disk, and starts the folder watcher.
     * This is the only entry point that clears the registry.
     */
    public boolean openProject(String projectRootPath) {
        if (projectRootPath == null || projectRootPath.trim().isEmpty()) return false;
        File root = new File(projectRootPath.trim()).getAbsoluteFile();
        if (!root.isDirectory()) return false;

        clear();
        this.activeProjectPath = normalizePath(root.getAbsolutePath());
        com.retro.engine.assets.ProjectFileLocator.setProjectRoot(root);
        new File(root, ASSETS_DIR).mkdirs();
        new File(root, INTERNAL_DIR).mkdirs();

        String savedFolder = loadManifestSeed();
        rescanProjectAssets();
        manifestUuidSeed.clear();
        if (!savedFolder.isEmpty() && findFolder(savedFolder) != null) currentFolderPath = savedFolder;
        saveManifest();

        com.retro.engine.scripting.ScriptEngine.setProjectAssetsRoot(getProjectAssetsPath());
        startWatching();
        System.out.println("[AssetDatabase] Bound to project " + activeProjectPath + " (" + assetDirectory.size() + " entries under Assets/).");
        return true;
    }

    /**
     * Re-syncs the registry with the Assets/ tree on disk. Known paths keep their UUIDs; new files and folders
     * are added; anything no longer on disk is dropped. The result is ordered folders-first, then by name.
     */
    public void rescanProjectAssets() {
        if (!hasActiveProject()) return;
        File assetsDir = new File(getProjectAssetsPath());
        if (!assetsDir.isDirectory()) return;

        Map<String, AssetMeta> previous = new HashMap<>();
        for (AssetMeta meta : assetDirectory) {
            previous.put(normalizePath(meta.absolutePath).toLowerCase(), meta);
        }

        List<AssetMeta> next = new ArrayList<>();
        final Path rootPath = assetsDir.toPath();
        try (var stream = Files.walk(rootPath)) {
            stream.forEach(path -> {
                if (path.equals(rootPath)) return;
                String relPath = normalizePath(rootPath.relativize(path).toString());
                if (isIgnoredRelativePath(relPath)) return;

                boolean isDir = Files.isDirectory(path);
                String abs = normalizePath(path.toAbsolutePath().toString());
                String name = path.getFileName().toString();
                int lastSlash = relPath.lastIndexOf('/');
                String parent = (lastSlash != -1) ? relPath.substring(0, lastSlash) : "";
                AssetType type = isDir ? AssetType.FOLDER : classifyFile(name);

                AssetMeta meta = previous.get(abs.toLowerCase());
                if (meta != null) {
                    meta.name = name;
                    meta.absolutePath = abs;
                    meta.parentFolderPath = parent;
                    meta.type = type;
                } else {
                    String seededUuid = manifestUuidSeed.get((ASSETS_DIR + "/" + relPath).toLowerCase());
                    meta = new AssetMeta(seededUuid, name, abs, type, parent);
                }
                next.add(meta);
            });
        } catch (IOException | UncheckedIOException e) {
            System.err.println("[AssetDatabase] Assets/ scan failed: " + e.getMessage());
            return;
        }
        addCookedOnlyAssets(next, previous);

        next.sort(Comparator
            .comparing((AssetMeta m) -> m.parentFolderPath.toLowerCase())
            .thenComparing(m -> m.type != AssetType.FOLDER)
            .thenComparing(m -> m.name.toLowerCase()));

        Map<String, AssetMeta> newByUuid = new HashMap<>();
        Map<String, String> newByPath = new HashMap<>();
        for (AssetMeta meta : next) indexAsset(meta, newByUuid, newByPath);

        this.assetDirectory = new CopyOnWriteArrayList<>(next);
        uuidToAssetMap.putAll(newByUuid);
        uuidToAssetMap.keySet().retainAll(newByUuid.keySet());
        pathToUuidMap.putAll(newByPath);
        pathToUuidMap.keySet().retainAll(newByPath.keySet());

        // The browser can never sit in a folder that no longer exists on disk
        while (!currentFolderPath.isEmpty() && findFolder(currentFolderPath) == null) {
            int lastSlash = currentFolderPath.lastIndexOf('/');
            currentFolderPath = (lastSlash != -1) ? currentFolderPath.substring(0, lastSlash) : "";
        }
        saveManifest();
    }

    /** Registers stripped sources that still stream from Cooked/, so scene UUIDs resolve to their original paths. */
    private void addCookedOnlyAssets(List<AssetMeta> next, Map<String, AssetMeta> previous) {
        if (cookedOnlyAssets.isEmpty()) return;
        java.util.Set<String> onDisk = new java.util.HashSet<>();
        for (AssetMeta meta : next) onDisk.add(normalizePath(meta.absolutePath).toLowerCase());
        for (Map.Entry<String, String> entry : cookedOnlyAssets.entrySet()) {
            String abs = normalizePath(activeProjectPath + "/" + entry.getKey());
            if (onDisk.contains(abs.toLowerCase())) continue;
            String relPath = entry.getKey().substring(ASSETS_DIR.length() + 1);
            String name = relPath.substring(relPath.lastIndexOf('/') + 1);
            int lastSlash = relPath.lastIndexOf('/');
            String parent = (lastSlash != -1) ? relPath.substring(0, lastSlash) : "";
            AssetMeta meta = previous.get(abs.toLowerCase());
            if (meta == null) meta = new AssetMeta(entry.getValue(), name, abs, classifyFile(name), parent);
            next.add(meta);
        }
    }

    /** Runtime builds: remembers a manifest entry whose raw file is absent but whose cooked package exists. */
    private void noteCookedOnlyAsset(String storedPath, String uuid) {
        if (!storedPath.startsWith(ASSETS_DIR + "/")) return;
        File raw = new File(activeProjectPath, storedPath);
        if (raw.exists()) return;
        File cooked = com.retro.engine.assets.RpackCookerCli.cookedPathFor(
            new File(activeProjectPath, ASSETS_DIR), new File(activeProjectPath, COOKED_DIR), raw);
        if (cooked.isFile()) cookedOnlyAssets.put(storedPath, uuid);
    }

    private static boolean isIgnoredRelativePath(String relPath) {
        String lowerRel = relPath.toLowerCase();
        // Hidden files/dirs (.DS_Store, .git), legacy sidecars, in-flight temp files, and scene backups
        return lowerRel.startsWith(".") || lowerRel.contains("/.") ||
               lowerRel.endsWith(".rmeta") || lowerRel.endsWith(".meta") || lowerRel.endsWith(".tmp") ||
               lowerRel.endsWith(".bak");
    }

    // --- LIVE ASSETS/ FOLDER WATCHER ---

    /**
     * Polls the Assets/ tree on a virtual thread (portable; macOS WatchService is itself a slow poller) and
     * flags changes. The re-sync runs on the main thread via {@link #pollFileChanges()} so registry mutation
     * never races the UI.
     */
    private void startWatching() {
        stopWatching();
        final String assetsRoot = getProjectAssetsPath();
        final String shaderRoot = shaderSourceRoot;
        final long[] lastSignature = { computeTreeSignature(assetsRoot), shaderRoot != null ? computeTreeSignature(shaderRoot) : 0L };
        final Map<String, Long> scriptStamps = stampSources(assetsRoot, AssetDatabase::isScriptSource);
        final Map<String, Long> shaderStamps = shaderRoot != null ? stampSources(shaderRoot, ShaderSources::isShaderFile) : new HashMap<>();
        watcherThread = Thread.ofVirtual().name("asset-folder-watcher").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long signature = computeTreeSignature(assetsRoot);
                if (signature != lastSignature[0]) {
                    lastSignature[0] = signature;
                    collectModifiedSources(assetsRoot, scriptStamps, AssetDatabase::isScriptSource);
                    filesChangedOnDisk = true;
                }
                if (shaderRoot != null) {
                    long shaderSignature = computeTreeSignature(shaderRoot);
                    if (shaderSignature != lastSignature[1]) {
                        lastSignature[1] = shaderSignature;
                        collectModifiedSources(shaderRoot, shaderStamps, ShaderSources::isShaderFile);
                    }
                }
            }
        });
    }

    private void stopWatching() {
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
        filesChangedOnDisk = false;
        modifiedSources.clear();
    }

    private static boolean isScriptSource(String fileName) {
        return classifyFile(fileName) == AssetType.SCRIPT;
    }

    /** Modification stamp of every matching (non-hidden) file under root, keyed by normalized absolute path. */
    private static Map<String, Long> stampSources(String root, Predicate<String> matches) {
        Map<String, Long> stamps = new HashMap<>();
        File rootDir = new File(root);
        if (!rootDir.isDirectory()) return stamps;
        try (var stream = Files.walk(rootDir.toPath())) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (name.startsWith(".") || !matches.test(name)) return;
                File f = path.toFile();
                if (f.isFile()) stamps.put(normalizePath(f.getAbsolutePath()), 31 * f.lastModified() + f.length());
            });
        } catch (IOException | UncheckedIOException ignored) {
            // Files vanishing mid-walk: whatever was stamped is compared now, the rest on the next signature change
        }
        return stamps;
    }

    /** Watcher thread: queues sources that are new or whose stamp changed since the last walk, then adopts the new stamps. */
    private void collectModifiedSources(String root, Map<String, Long> stamps, Predicate<String> matches) {
        Map<String, Long> current = stampSources(root, matches);
        for (Map.Entry<String, Long> entry : current.entrySet()) {
            if (!entry.getValue().equals(stamps.get(entry.getKey()))) modifiedSources.add(entry.getKey());
        }
        stamps.clear();
        stamps.putAll(current);
    }

    /**
     * Editor hot reload: also watches {@code root} (the engine's shader source folder) and reports modified shader files
     * to {@code listener} from {@link #pollFileChanges()} on the main thread. Pass a null root to stop watching shaders.
     */
    public void watchShaderSources(String root, Consumer<List<String>> listener) {
        this.shaderSourceRoot = (root != null && new File(root).isDirectory()) ? normalizePath(new File(root).getAbsolutePath()) : null;
        this.shaderChangeListener = listener;
        // A project may already be bound (and its watcher running) before the editor wires this up
        if (hasActiveProject()) startWatching();
    }

    private static long computeTreeSignature(String root) {
        File rootDir = new File(root);
        if (!rootDir.isDirectory()) return 0L;
        long sig = 1125899906842597L;
        Path rootPath = rootDir.toPath();
        try (var stream = Files.walk(rootPath)) {
            for (Path p : (Iterable<Path>) stream.sorted()::iterator) {
                File f = p.toFile();
                sig = 31 * sig + rootPath.relativize(p).toString().hashCode();
                sig = 31 * sig + (f.isFile() ? f.length() ^ f.lastModified() : 7L);
            }
        } catch (IOException | UncheckedIOException ignored) {
            // Files vanishing mid-walk: report a change so the next poll settles
            return sig ^ System.nanoTime();
        }
        return sig;
    }

    /** Main-thread hook: re-syncs with Assets/ if the watcher saw files added, removed, or modified, then dispatches hot reloads. */
    public void pollFileChanges() {
        if (!hasActiveProject()) return;
        if (filesChangedOnDisk) {
            filesChangedOnDisk = false;
            rescanProjectAssets();
        }
        dispatchModifiedSources();
    }

    /** Main thread: invalidates edited scripts (hot-swapped on the next script tick) and forwards edited shaders. */
    private void dispatchModifiedSources() {
        List<String> shaders = null;
        for (String path; (path = modifiedSources.poll()) != null; ) {
            if (isScriptSource(path)) {
                com.retro.engine.scripting.ScriptEngine.invalidateScript(path);
            } else {
                if (shaders == null) shaders = new ArrayList<>();
                shaders.add(path);
            }
        }
        Consumer<List<String>> listener = shaderChangeListener;
        if (shaders != null && listener != null) listener.accept(shaders);
    }

    // --- DISK-BACKED BROWSER OPERATIONS ---

    public void createNewFolder() {
        if (!hasActiveProject()) return;
        File parentDir = currentDirectory();
        String defaultName = uniqueChild(parentDir, "New Folder").getName();
        String inputName = NativeFileDialog.promptInput("Create New Folder", "Enter folder name:", defaultName);
        if (inputName == null) return;
        String folderName = inputName.trim().isEmpty() ? defaultName : inputName.trim();
        if (!isValidName(folderName)) {
            NativeFileDialog.showError("Create New Folder", "Invalid folder name: " + folderName);
            return;
        }
        File dir = new File(parentDir, folderName);
        if (dir.exists()) {
            NativeFileDialog.showError("Create New Folder", "An item with that name already exists.");
            return;
        }
        if (!dir.mkdirs()) {
            NativeFileDialog.showError("Create New Folder", "Could not create folder on disk.");
            return;
        }
        rescanProjectAssets();
        System.out.println("[AssetDatabase] Created folder " + toProjectRelative(dir.getAbsolutePath()));
    }

    public void promptRenameFolder(AssetMeta folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Folder", "Enter new folder name:", folder.name);
        if (input != null && !input.trim().isEmpty()) {
            renameFolder(folder, input.trim());
        }
    }

    public void renameFolder(AssetMeta folder, String newName) {
        if (folder == null || folder.type != AssetType.FOLDER || newName == null) return;
        String oldRel = relativePathOf(folder);
        String parent = folder.parentFolderPath;
        if (renameOnDisk(folder, newName.trim(), "Rename Folder") && currentFolderPath != null) {
            String newRel = parent.isEmpty() ? newName.trim() : parent + "/" + newName.trim();
            if (currentFolderPath.equalsIgnoreCase(oldRel)) {
                currentFolderPath = newRel;
            } else if (currentFolderPath.toLowerCase().startsWith(oldRel.toLowerCase() + "/")) {
                currentFolderPath = newRel + currentFolderPath.substring(oldRel.length());
            }
            rescanProjectAssets();
        }
    }

    public void promptRenameAsset(AssetMeta asset) {
        if (asset == null || asset.type == AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Asset", "Enter new file name:", asset.name);
        if (input == null || input.trim().isEmpty()) return;

        String newName = input.trim();
        String ext = fullExtension(asset.name);
        if (!ext.isEmpty() && !newName.toLowerCase().endsWith(ext.toLowerCase())) {
            newName += ext; // keep the file type when the user types a bare name
        }
        if (renameOnDisk(asset, newName, "Rename Asset")) {
            rescanProjectAssets();
        }
    }

    /** Moves a file or folder to a sibling name, rewriting registry paths in place so UUIDs follow the move. */
    private boolean renameOnDisk(AssetMeta asset, String newName, String dialogTitle) {
        if (newName.equals(asset.name)) return false;
        if (!isValidName(newName)) {
            NativeFileDialog.showError(dialogTitle, "Invalid name: " + newName);
            return false;
        }
        File source = new File(asset.absolutePath);
        File target = new File(source.getParentFile(), newName);
        if (!isInsideAssets(source) || !isInsideAssets(target)) return false;
        if (target.exists() && !target.getAbsolutePath().equalsIgnoreCase(source.getAbsolutePath())) {
            NativeFileDialog.showError(dialogTitle, "An item with that name already exists.");
            return false;
        }
        try {
            Files.move(source.toPath(), target.toPath());
        } catch (IOException e) {
            NativeFileDialog.showError(dialogTitle, "Rename failed: " + e.getMessage());
            return false;
        }

        String oldAbs = normalizePath(source.getAbsolutePath());
        String newAbs = normalizePath(target.getAbsolutePath());
        for (AssetMeta meta : assetDirectory) {
            String p = normalizePath(meta.absolutePath);
            if (p.equalsIgnoreCase(oldAbs)) {
                meta.absolutePath = newAbs;
            } else if (p.toLowerCase().startsWith(oldAbs.toLowerCase() + "/")) {
                meta.absolutePath = newAbs + p.substring(oldAbs.length());
            }
        }
        System.out.println("[AssetDatabase] Renamed " + toProjectRelative(oldAbs) + " -> " + toProjectRelative(newAbs));
        return true;
    }

    /** Deletes a folder and everything inside it from disk after confirmation. */
    public void deleteFolder(AssetMeta folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;
        File dir = new File(folder.absolutePath);
        if (!isInsideAssets(dir) || isAssetsRoot(dir)) return;
        if (!NativeFileDialog.confirm("Delete Folder",
                "Permanently delete the folder " + folder.name + " and everything inside it from disk?", false)) {
            return;
        }
        try (var stream = Files.walk(dir.toPath())) {
            for (Path p : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | UncheckedIOException e) {
            NativeFileDialog.showError("Delete Folder", "Delete failed: " + e.getMessage());
        }
        rescanProjectAssets();
        System.out.println("[AssetDatabase] Deleted folder " + toProjectRelative(dir.getAbsolutePath()));
    }

    /** Deletes an asset file from disk after confirmation. */
    public void removeAssetFromDirectory(AssetMeta asset) {
        if (asset == null || asset.type == AssetType.FOLDER) return;
        File file = new File(asset.absolutePath);
        if (!isInsideAssets(file)) return;
        if (!NativeFileDialog.confirm("Delete Asset", "Permanently delete " + asset.name + " from disk?", false)) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            NativeFileDialog.showError("Delete Asset", "Delete failed: " + e.getMessage());
        }
        rescanProjectAssets();
        System.out.println("[AssetDatabase] Deleted " + toProjectRelative(file.getAbsolutePath()));
    }

    /**
     * Imports dropped / picked files into the current Assets/ folder. Files already inside Assets/ are used in
     * place; anything outside is copied in (never overwriting). Returns the in-project path for each input,
     * falling back to the original path if a copy failed, so callers can bind the result directly.
     */
    public List<String> onFilesDropped(List<String> filePaths) {
        List<String> resolved = new ArrayList<>();
        if (filePaths == null || !hasActiveProject()) return resolved;

        File targetDir = currentDirectory();
        for (String path : filePaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File source = new File(path.trim()).getAbsoluteFile();
            if (isInsideAssets(source)) {
                resolved.add(normalizePath(source.getAbsolutePath()));
                continue;
            }
            File target = uniqueChild(targetDir, source.getName());
            try {
                copyRecursive(source.toPath(), target.toPath());
                resolved.add(normalizePath(target.getAbsolutePath()));
                System.out.println("[AssetDatabase] Imported " + source.getName() + " -> " + toProjectRelative(target.getAbsolutePath()));
            } catch (IOException | UncheckedIOException e) {
                System.err.println("[AssetDatabase] Import failed for " + source + ": " + e.getMessage());
                resolved.add(path);
            }
        }
        rescanProjectAssets();
        return resolved;
    }

    /**
     * Legacy registration hook: files are discovered from disk, so this only triggers a re-sync when the path is
     * inside Assets/. Paths outside Assets/ are never registered.
     */
    public void addAssetDirectoryIfNotExists(String absolutePath, AssetType type) {
        addAssetDirectoryIfNotExists(absolutePath, type, null);
    }

    public void addAssetDirectoryIfNotExists(String absolutePath, AssetType type, String parentFolder) {
        if (absolutePath == null || absolutePath.trim().isEmpty() || !hasActiveProject()) return;
        File file = new File(absolutePath.trim()).getAbsoluteFile();
        if (!isInsideAssets(file)) {
            System.out.println("[AssetDatabase] " + file.getName() + " is outside Assets/ and will not appear in the asset browser.");
            return;
        }
        if (resolveUuidByPath(normalizePath(file.getAbsolutePath())) == null) {
            rescanProjectAssets();
        }
    }

    // --- MANIFEST (.retro/asset_registry.bin) ---

    public File getManifestFile() {
        return hasActiveProject() ? new File(activeProjectPath + "/" + INTERNAL_DIR, MANIFEST_FILE) : null;
    }

    /** Writes every UUID mapping (project-relative paths) and the browser folder to the manifest (atomic replace). */
    public void saveManifest() {
        if (!hasActiveProject()) return;
        File manifest = getManifestFile();
        File tmp = new File(manifest.getParentFile(), MANIFEST_FILE + ".tmp");
        manifest.getParentFile().mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp.toPath())))) {
            dos.writeInt(MANIFEST_MAGIC);
            dos.writeInt(MANIFEST_VERSION);
            dos.writeUTF(currentFolderPath != null ? currentFolderPath : "");
            List<AssetMeta> snapshot = assetDirectory;
            dos.writeInt(snapshot.size());
            for (AssetMeta meta : snapshot) {
                UUID u = parseUuid(meta.uuid);
                dos.writeLong(u.getMostSignificantBits());
                dos.writeLong(u.getLeastSignificantBits());
                dos.writeUTF(meta.name != null ? meta.name : "");
                dos.writeUTF(toProjectRelative(meta.absolutePath));
                dos.writeUTF(meta.type.name());
                dos.writeUTF(meta.parentFolderPath != null ? meta.parentFolderPath : "");
            }
        } catch (IOException e) {
            System.err.println("[AssetDatabase] Failed to write asset manifest: " + e.getMessage());
            return;
        }

        try {
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            try {
                Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[AssetDatabase] Failed to commit asset manifest: " + e.getMessage());
            }
        }
    }

    /** Loads manifest UUIDs into the scan seed; returns the saved browser folder. Older manifests' virtual folders simply never match disk. */
    private String loadManifestSeed() {
        manifestUuidSeed.clear();
        cookedOnlyAssets.clear();
        boolean runtime = com.retro.engine.core.EngineContext.isRuntimeProcess();
        File manifest = getManifestFile();
        if (manifest == null || !manifest.isFile()) return "";

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(manifest.toPath())))) {
            if (dis.readInt() != MANIFEST_MAGIC) {
                System.err.println("[AssetDatabase] Ignoring invalid asset manifest: " + manifest.getAbsolutePath());
                return "";
            }
            int version = dis.readInt();
            if (version > MANIFEST_VERSION) {
                System.err.println("[AssetDatabase] Asset manifest version " + version + " is newer than supported; ignoring.");
                return "";
            }
            String savedFolder = dis.readUTF();
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String uuid = new UUID(dis.readLong(), dis.readLong()).toString();
                dis.readUTF(); // name
                String storedPath = normalizePath(dis.readUTF());
                dis.readUTF(); // type
                dis.readUTF(); // parent
                manifestUuidSeed.put(storedPath.toLowerCase(), uuid);
                if (runtime) noteCookedOnlyAsset(storedPath, uuid);
            }
            return savedFolder;
        } catch (IOException e) {
            System.err.println("[AssetDatabase] Failed to read asset manifest: " + e.getMessage());
            return "";
        }
    }

    /**
     * Looks up an asset's UUID in the manifest of the project that contains it, without binding a registry.
     * Returns null when the file is not inside a project or has no manifest entry.
     */
    public static String lookupManifestUuid(File assetFile) {
        if (assetFile == null) return null;
        File abs = assetFile.getAbsoluteFile();
        for (File dir = abs.getParentFile(); dir != null; dir = dir.getParentFile()) {
            File manifest = new File(new File(dir, INTERNAL_DIR), MANIFEST_FILE);
            if (!manifest.isFile()) continue;

            String rootPrefix = dir.getAbsolutePath().replace('\\', '/') + "/";
            String target = abs.getAbsolutePath().replace('\\', '/');
            String relTarget = target.startsWith(rootPrefix) ? target.substring(rootPrefix.length()) : target;
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(manifest.toPath())))) {
                if (dis.readInt() != MANIFEST_MAGIC) return null;
                dis.readInt();
                dis.readUTF();
                int count = dis.readInt();
                for (int i = 0; i < count; i++) {
                    UUID u = new UUID(dis.readLong(), dis.readLong());
                    dis.readUTF();
                    String path = dis.readUTF();
                    dis.readUTF();
                    dis.readUTF();
                    if (path.equalsIgnoreCase(relTarget) || path.equalsIgnoreCase(target)) return u.toString();
                }
            } catch (IOException ignored) {}
            return null;
        }
        return null;
    }

    // --- HELPERS ---

    public static AssetType classifyFile(String fileName) {
        if (fileName == null) return AssetType.UNKNOWN;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".prefab.bin") || lower.endsWith(".prefab")) return AssetType.PREFAB;
        int dot = lower.lastIndexOf('.');
        String ext = (dot == -1) ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
            case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
            case "wav", "mp3", "ogg" -> AssetType.AUDIO;
            case "rscene", "scene" -> AssetType.SCENE;
            case "java" -> AssetType.SCRIPT;
            case "rmat" -> AssetType.MATERIAL;
            case "ranim" -> AssetType.ANIMATION;
            default -> AssetType.UNKNOWN;
        };
    }

    /** Folder path relative to Assets/ (the form used by currentFolderPath and parentFolderPath). */
    public static String relativePathOf(AssetMeta folder) {
        return folder.parentFolderPath.isEmpty() ? folder.name : folder.parentFolderPath + "/" + folder.name;
    }

    private AssetMeta findFolder(String relPath) {
        for (AssetMeta meta : assetDirectory) {
            if (meta.type == AssetType.FOLDER && relativePathOf(meta).equalsIgnoreCase(relPath)) return meta;
        }
        return null;
    }

    private File currentDirectory() {
        File dir = currentFolderPath.isEmpty() ? new File(getProjectAssetsPath()) : new File(getProjectAssetsPath(), currentFolderPath);
        return (dir.isDirectory() && isInsideAssets(dir)) ? dir : new File(getProjectAssetsPath());
    }

    private boolean isAssetsRoot(File file) {
        try {
            return file.getCanonicalFile().equals(new File(getProjectAssetsPath()).getCanonicalFile());
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the file is Assets/ itself or anything beneath it (canonicalized, so ../ and symlinks can't escape). */
    private boolean isInsideAssets(File file) {
        if (!hasActiveProject() || file == null) return false;
        try {
            Path root = new File(getProjectAssetsPath()).getCanonicalFile().toPath();
            Path target = file.getAbsoluteFile().getCanonicalFile().toPath();
            return target.startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.startsWith(".") || name.length() > 200) return false;
        for (char c : name.toCharArray()) {
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    /** ".prefab.bin" style compound extensions are preserved as a unit. */
    private static String fullExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".prefab.bin")) return name.substring(name.length() - ".prefab.bin".length());
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(dot) : "";
    }

    /** "name.ext", or "name (2).ext", "name (3).ext"... - whichever does not yet exist in dir. */
    private static File uniqueChild(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        String ext = fullExtension(name);
        String base = name.substring(0, name.length() - ext.length());
        for (int i = 2; ; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.copy(source, target);
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
    }

    private String toProjectRelative(String path) {
        String clean = normalizePath(path);
        String prefix = activeProjectPath + "/";
        if (!activeProjectPath.isEmpty() && clean.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return clean.substring(prefix.length());
        }
        return clean;
    }

    private static UUID parseUuid(String uuid) {
        try {
            return UUID.fromString(uuid);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(String.valueOf(uuid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public void clear() {
        stopWatching();
        this.assetDirectory = new CopyOnWriteArrayList<>();
        this.uuidToAssetMap.clear();
        this.pathToUuidMap.clear();
        this.manifestUuidSeed.clear();
        this.cookedOnlyAssets.clear();
        this.currentFolderPath = "";
        if (hasActiveProject()) com.retro.engine.scripting.ScriptEngine.setProjectAssetsRoot(null);
        this.activeProjectPath = "";
        com.retro.engine.assets.ProjectFileLocator.setProjectRoot(null);
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').trim();
    }
}
