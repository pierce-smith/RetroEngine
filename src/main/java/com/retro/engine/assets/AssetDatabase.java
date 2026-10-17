package com.retro.engine.assets;

import com.retro.engine.assets.importers.TextureImporter;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.SceneSerializer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry managing decoupled virtual asset directory entries, 128-bit UUID indirection mappings,
 * and .rmeta sidecar file synchronization without modifying physical disk directories.
 *
 * Hardened with CopyOnWriteArrayList and ConcurrentHashMap for high-performance multi-threaded
 * asset queries and Virtual Thread streaming lookups.
 */
public class AssetDatabase {

    private final List<AssetMeta> assetDirectory = new CopyOnWriteArrayList<>();
    private final Map<String, AssetMeta> uuidToAssetMap = new ConcurrentHashMap<>();
    private final Map<String, String> pathToUuidMap = new ConcurrentHashMap<>();

    private String currentFolderPath = "";
    private String activeProjectPath = "";

    public List<AssetMeta> getAssetDirectory() {
        return Collections.unmodifiableList(assetDirectory);
    }

    public List<AssetMeta> getMutableAssetDirectory() {
        return assetDirectory;
    }

    public String getCurrentFolderPath() {
        return currentFolderPath;
    }

    public String getActiveProjectPath() {
        return activeProjectPath;
    }

    public void setCurrentFolderPath(String path) {
        String cleanPath = (path != null) ? path.trim() : "";
        assert (cleanPath.isEmpty() || assetDirectory.stream().anyMatch(a -> a.type == AssetType.FOLDER && a.absolutePath.equalsIgnoreCase(cleanPath)))
            : "[FATAL] AssetDatabase currentFolderPath set to non-existent virtual folder: " + cleanPath;
        this.currentFolderPath = cleanPath;
    }

    // --- 128-BIT UUID REGISTRY LOOKUPS ---

    public AssetMeta getAssetByUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) return null;
        return uuidToAssetMap.get(uuid.trim());
    }

    public List<AssetMeta> getAssetsByType(AssetType targetType) {
        List<AssetMeta> matches = new ArrayList<>();
        if (targetType == null) return matches;
        for (AssetMeta meta : assetDirectory) {
            if (meta.type == targetType) {
                matches.add(meta);
            }
        }
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

    public void registerAssetUuid(AssetMeta asset) {
        if (asset == null) return;

        asset.ensureMetaSidecar();

        if (asset.uuid == null || asset.uuid.trim().isEmpty()) return;

        uuidToAssetMap.put(asset.uuid, asset);
        if (asset.absolutePath != null && !asset.absolutePath.trim().isEmpty()) {
            String norm = normalizePath(asset.absolutePath).toLowerCase();
            pathToUuidMap.put(norm, asset.uuid);

            String sanitized = SceneSerializer.sanitizeAssetPath(asset.absolutePath).toLowerCase();
            if (!sanitized.isEmpty()) {
                pathToUuidMap.put(sanitized, asset.uuid);
            }

            String fileName = new File(norm).getName().toLowerCase();
            if (!fileName.isEmpty()) {
                pathToUuidMap.put(fileName, asset.uuid);
            }
        }
    }

    public void unregisterAssetUuid(AssetMeta asset) {
        if (asset == null) return;
        if (asset.uuid != null) uuidToAssetMap.remove(asset.uuid);
        if (asset.absolutePath != null && !asset.absolutePath.trim().isEmpty()) {
            String norm = normalizePath(asset.absolutePath).toLowerCase();
            pathToUuidMap.remove(norm);
            String fileName = new File(norm).getName().toLowerCase();
            if (!fileName.isEmpty()) {
                pathToUuidMap.remove(fileName);
            }
        }
    }

    // --- DECOUPLED VIRTUAL DIRECTORY & WORKSPACE MANAGEMENT ---

    public void navigateUpFolder() {
        if (currentFolderPath == null || currentFolderPath.isEmpty()) return;
        int lastSlash = currentFolderPath.lastIndexOf('/');
        if (lastSlash != -1) {
            currentFolderPath = currentFolderPath.substring(0, lastSlash);
        } else {
            currentFolderPath = "";
        }
    }

    public void createNewFolder() {
        int count = 1;
        for (AssetMeta entry : assetDirectory) {
            if (entry.type == AssetType.FOLDER && entry.parentFolderPath.equalsIgnoreCase(currentFolderPath)) {
                count++;
            }
        }
        String defaultName = "New_Folder_" + count;
        String inputName = NativeFileDialog.promptInput("Create New Folder", "Enter folder name:", defaultName);
        if (inputName == null) return;
        String folderName = !inputName.trim().isEmpty() ? inputName.trim() : defaultName;

        String virtualPath = currentFolderPath.isEmpty() ? folderName : currentFolderPath + "/" + folderName;
        AssetMeta folderMeta = new AssetMeta(folderName, virtualPath, AssetType.FOLDER, currentFolderPath);
        assetDirectory.add(folderMeta);
        registerAssetUuid(folderMeta);
        System.out.println("[AssetDatabase] Created new virtual folder: " + folderName + " in " + (currentFolderPath.isEmpty() ? "Assets" : currentFolderPath));
    }

    public void restoreAssetDirectory(List<AssetMeta> entries) {
        if (entries == null) return;
        for (AssetMeta entry : entries) {
            if (entry == null) continue;
            boolean exists = assetDirectory.stream().anyMatch(e ->
                (e.absolutePath != null && e.absolutePath.equalsIgnoreCase(entry.absolutePath)) ||
                (e.name != null && e.name.equalsIgnoreCase(entry.name) && e.type == entry.type && e.parentFolderPath.equalsIgnoreCase(entry.parentFolderPath))
            );
            if (!exists) {
                assetDirectory.add(entry);
                registerAssetUuid(entry);
            }
        }
    }

    public void addAssetDirectoryIfNotExists(String absolutePath, AssetType type) {
        addAssetDirectoryIfNotExists(absolutePath, type, currentFolderPath);
    }

    public void addAssetDirectoryIfNotExists(String absolutePath, AssetType type, String parentFolder) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) return;

        String cleanPath = normalizePath(absolutePath);
        File file = new File(cleanPath);
        String fileName = file.getName();
        String parentPath = (parentFolder != null) ? parentFolder : "";

        if (type == AssetType.UNKNOWN || type == null) {
            String ext = getFileExtension(fileName).toLowerCase();
            type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
                case "wav", "mp3", "ogg" -> AssetType.AUDIO;
                case "rscene", "scene" -> AssetType.SCENE;
                case "java" -> AssetType.SCRIPT;
                default -> AssetType.UNKNOWN;
            };
        }

        for (AssetMeta entry : assetDirectory) {
            if (entry.absolutePath != null) {
                String entryClean = normalizePath(entry.absolutePath);
                if (entryClean.equalsIgnoreCase(cleanPath) ||
                    entryClean.endsWith("/" + cleanPath) ||
                    cleanPath.endsWith("/" + entryClean) ||
                    (entry.name != null && entry.name.equalsIgnoreCase(fileName) && entry.type == type && entry.parentFolderPath.equalsIgnoreCase(parentPath))) {
                    if (entry.type == AssetType.UNKNOWN && type != AssetType.UNKNOWN) {
                        entry.type = type;
                    }
                    registerAssetUuid(entry);
                    return;
                }
            }
        }
        AssetMeta newAsset = new AssetMeta(fileName, absolutePath, type, parentPath);
        assetDirectory.add(newAsset);
        registerAssetUuid(newAsset);
    }

    public void scanWorkspaceDirectory(String rootDirPath) {
        if (rootDirPath == null || rootDirPath.trim().isEmpty()) return;
        File rootDir = new File(rootDirPath);
        if (!rootDir.exists() || !rootDir.isDirectory()) return;

        this.activeProjectPath = normalizePath(rootDirPath);
        System.out.println("[AssetDatabase] Scanning workspace directory for raw assets & .rmeta sidecars: " + activeProjectPath);

        try {
            Path rootPath = rootDir.toPath();
            Files.walk(rootPath)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String relPath = normalizePath(rootPath.relativize(path).toString());
                    String lowerRel = relPath.toLowerCase();

                    // Filter out internal sidecars, git, and engine/editor internals
                    if (lowerRel.endsWith(".rmeta") || lowerRel.endsWith(".meta") ||
                        lowerRel.startsWith(".git") || lowerRel.contains("/.git") ||
                        lowerRel.startsWith("editor/") || lowerRel.contains("/editor/") ||
                        lowerRel.startsWith("engine/") || lowerRel.contains("/engine/")) {
                        return;
                    }

                    registerDiscoveredFile(path, relPath);
                });
        } catch (IOException e) {
            System.err.println("[AssetDatabase] Workspace scan failed: " + e.getMessage());
        }
    }

    private void registerDiscoveredFile(Path filePath, String relPath) {
        String absPath = filePath.toAbsolutePath().toString();
        String name = filePath.getFileName().toString();
        String ext = getFileExtension(name).toLowerCase();

        int lastSlash = relPath.lastIndexOf('/');
        String parentFolder = (lastSlash != -1) ? relPath.substring(0, lastSlash) : "";

        AssetType type = switch (ext) {
            case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
            case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
            case "wav", "mp3", "ogg" -> AssetType.AUDIO;
            case "rscene", "scene" -> AssetType.SCENE;
            case "java" -> AssetType.SCRIPT;
            default -> AssetType.UNKNOWN;
        };

        if (type != AssetType.UNKNOWN) {
            addAssetDirectoryIfNotExists(absPath, type, parentFolder);
        }
    }

    public void renameFolder(AssetMeta folder, String newName) {
        if (folder == null || folder.type != AssetType.FOLDER || newName == null || newName.trim().isEmpty()) return;

        String oldFolderName = folder.name;
        String cleanNewName = newName.trim();
        if (cleanNewName.equalsIgnoreCase(oldFolderName)) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String oldVirtualPath = parentPath.isEmpty() ? oldFolderName : parentPath + "/" + oldFolderName;
        String newVirtualPath = parentPath.isEmpty() ? cleanNewName : parentPath + "/" + cleanNewName;

        unregisterAssetUuid(folder);
        folder.name = cleanNewName;
        folder.absolutePath = newVirtualPath;
        registerAssetUuid(folder);

        for (AssetMeta entry : assetDirectory) {
            if (entry.parentFolderPath != null) {
                if (entry.parentFolderPath.equalsIgnoreCase(oldVirtualPath)) {
                    entry.parentFolderPath = newVirtualPath;
                } else if (entry.parentFolderPath.toLowerCase().startsWith(oldVirtualPath.toLowerCase() + "/")) {
                    entry.parentFolderPath = newVirtualPath + entry.parentFolderPath.substring(oldVirtualPath.length());
                }
            }
        }

        if (currentFolderPath.equalsIgnoreCase(oldVirtualPath)) {
            currentFolderPath = newVirtualPath;
        } else if (currentFolderPath.toLowerCase().startsWith(oldVirtualPath.toLowerCase() + "/")) {
            currentFolderPath = newVirtualPath + currentFolderPath.substring(oldVirtualPath.length());
        }
        System.out.println("[AssetDatabase] Renamed virtual folder '" + oldFolderName + "' to '" + cleanNewName + "'");
    }

    public void promptRenameFolder(AssetMeta folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Folder", "Enter new folder name:", folder.name);
        if (input != null && !input.trim().isEmpty()) {
            renameFolder(folder, input.trim());
        }
    }

    public void promptRenameAsset(AssetMeta asset) {
        if (asset == null || asset.type == AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Asset", "Enter new asset name:", asset.name);
        if (input != null && !input.trim().isEmpty()) {
            unregisterAssetUuid(asset);
            asset.name = input.trim();
            registerAssetUuid(asset);
            System.out.println("[AssetDatabase] Renamed virtual asset entry to '" + asset.name + "'");
        }
    }

    public void deleteFolder(AssetMeta folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String folderVirtualPath = parentPath.isEmpty() ? folder.name : parentPath + "/" + folder.name;

        for (AssetMeta entry : assetDirectory) {
            if (entry.parentFolderPath != null && entry.parentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
                entry.parentFolderPath = parentPath;
            }
        }

        unregisterAssetUuid(folder);
        assetDirectory.remove(folder);

        if (currentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
            currentFolderPath = parentPath;
        }
        System.out.println("[AssetDatabase] Deleted virtual folder '" + folder.name + "' (reparented contents to '" + (parentPath.isEmpty() ? "Assets" : parentPath) + "')");
    }

    public void removeAssetFromDirectory(AssetMeta asset) {
        if (asset != null) {
            unregisterAssetUuid(asset);
            assetDirectory.remove(asset);
            System.out.println("[AssetDatabase] Removed asset '" + asset.name + "' from virtual directory.");
        }
    }

    public void onFilesDropped(List<String> filePaths) {
        if (filePaths == null) return;
        for (String path : filePaths) {
            if (path == null || path.trim().isEmpty()) continue;
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();
            AssetType type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
                case "wav", "mp3", "ogg" -> AssetType.AUDIO;
                case "rscene", "scene" -> AssetType.SCENE;
                case "java" -> AssetType.SCRIPT;
                default -> AssetType.UNKNOWN;
            };
            addAssetDirectoryIfNotExists(path, type);
        }
    }

    public void clear() {
        this.assetDirectory.clear();
        this.uuidToAssetMap.clear();
        this.pathToUuidMap.clear();
        this.currentFolderPath = "";
        this.activeProjectPath = "";
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').trim();
    }

    private String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }
}
