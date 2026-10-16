package com.retro.engine.editor.delegates;

import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Delegate managing virtual asset directory entries, folder creation/renaming/deletion,
 * and asset drop operations with parentFolderPath preservation.
 */
public class AssetDirectoryManager {

    private final List<AssetEntry> assetDirectory = new ArrayList<>();
    private String currentFolderPath = "";

    public List<AssetEntry> getAssetDirectory() {
        return Collections.unmodifiableList(assetDirectory);
    }

    public List<AssetEntry> getMutableAssetDirectory() {
        return assetDirectory;
    }

    public String getCurrentFolderPath() {
        return currentFolderPath;
    }

    public void setCurrentFolderPath(String path) {
        String cleanPath = (path != null) ? path.trim() : "";
        assert (cleanPath.isEmpty() || assetDirectory.stream().anyMatch(a -> a.type == AssetType.FOLDER && a.absolutePath.equalsIgnoreCase(cleanPath))) : "[FATAL] AssetDirectoryManager currentFolderPath set to non-existent virtual folder: " + cleanPath;
        this.currentFolderPath = cleanPath;
    }

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
        for (AssetEntry entry : assetDirectory) {
            if (entry.type == AssetType.FOLDER && entry.parentFolderPath.equalsIgnoreCase(currentFolderPath)) {
                count++;
            }
        }
        String defaultName = "New_Folder_" + count;
        String inputName = NativeFileDialog.promptInput("Create New Folder", "Enter folder name:", defaultName);
        if (inputName == null) return;
        String folderName = !inputName.trim().isEmpty() ? inputName.trim() : defaultName;

        String virtualPath = currentFolderPath.isEmpty() ? folderName : currentFolderPath + "/" + folderName;
        assetDirectory.add(new AssetEntry(folderName, virtualPath, AssetType.FOLDER, currentFolderPath));
        System.out.println("[AssetDirectoryManager] Created new folder: " + folderName + " in " + (currentFolderPath.isEmpty() ? "Assets" : currentFolderPath));
    }

    public void restoreAssetDirectory(List<AssetEntry> entries) {
        if (entries == null) return;
        for (AssetEntry entry : entries) {
            if (entry == null) continue;
            boolean exists = assetDirectory.stream().anyMatch(e ->
                (e.absolutePath != null && e.absolutePath.equalsIgnoreCase(entry.absolutePath)) ||
                (e.name != null && e.name.equalsIgnoreCase(entry.name) && e.type == entry.type && e.parentFolderPath.equalsIgnoreCase(entry.parentFolderPath))
            );
            if (!exists) {
                assetDirectory.add(entry);
            }
        }
    }

    public void addAssetDirectoryIfNotExists(String absolutePath, AssetType type) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) return;

        String cleanPath = normalizePath(absolutePath);
        File file = new File(cleanPath);
        String fileName = file.getName();

        if (type == AssetType.UNKNOWN || type == null) {
            String ext = getFileExtension(fileName).toLowerCase();
            type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "jpeg", "tga" -> AssetType.TEXTURE;
                case "wav", "mp3", "ogg" -> AssetType.AUDIO;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
        }

        for (AssetEntry entry : assetDirectory) {
            if (entry.absolutePath != null) {
                String entryClean = normalizePath(entry.absolutePath);
                if (entryClean.equalsIgnoreCase(cleanPath) ||
                    entryClean.endsWith("/" + cleanPath) ||
                    cleanPath.endsWith("/" + entryClean) ||
                    (entry.name != null && entry.name.equalsIgnoreCase(fileName) && entry.type == type)) {
                    if (entry.type == AssetType.UNKNOWN && type != AssetType.UNKNOWN) {
                        entry.type = type;
                    }
                    return;
                }
            }
        }
        assetDirectory.add(new AssetEntry(fileName, absolutePath, type, currentFolderPath));
    }

    public void renameFolder(AssetEntry folder, String newName) {
        if (folder == null || folder.type != AssetType.FOLDER || newName == null || newName.trim().isEmpty()) return;

        String oldFolderName = folder.name;
        String cleanNewName = newName.trim();
        if (cleanNewName.equalsIgnoreCase(oldFolderName)) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String oldVirtualPath = parentPath.isEmpty() ? oldFolderName : parentPath + "/" + oldFolderName;
        String newVirtualPath = parentPath.isEmpty() ? cleanNewName : parentPath + "/" + cleanNewName;

        folder.name = cleanNewName;
        folder.absolutePath = newVirtualPath;

        for (AssetEntry entry : assetDirectory) {
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
        System.out.println("[AssetDirectoryManager] Renamed folder '" + oldFolderName + "' to '" + cleanNewName + "'");
    }

    public void promptRenameFolder(AssetEntry folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Folder", "Enter new folder name:", folder.name);
        if (input != null && !input.trim().isEmpty()) {
            renameFolder(folder, input.trim());
        }
    }

    public void promptRenameAsset(AssetEntry asset) {
        if (asset == null || asset.type == AssetType.FOLDER) return;
        String input = NativeFileDialog.promptInput("Rename Asset", "Enter new asset name:", asset.name);
        if (input != null && !input.trim().isEmpty()) {
            asset.name = input.trim();
            System.out.println("[AssetDirectoryManager] Renamed asset to '" + asset.name + "'");
        }
    }

    public void deleteFolder(AssetEntry folder) {
        if (folder == null || folder.type != AssetType.FOLDER) return;

        String parentPath = (folder.parentFolderPath != null) ? folder.parentFolderPath : "";
        String folderVirtualPath = parentPath.isEmpty() ? folder.name : parentPath + "/" + folder.name;

        for (AssetEntry entry : assetDirectory) {
            if (entry.parentFolderPath != null && entry.parentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
                entry.parentFolderPath = parentPath;
            }
        }

        assetDirectory.remove(folder);

        if (currentFolderPath.equalsIgnoreCase(folderVirtualPath)) {
            currentFolderPath = parentPath;
        }
        System.out.println("[AssetDirectoryManager] Deleted folder '" + folder.name + "' (reparented contents to '" + (parentPath.isEmpty() ? "Assets" : parentPath) + "')");
    }

    public void removeAssetFromDirectory(AssetEntry asset) {
        if (asset != null) {
            assetDirectory.remove(asset);
            System.out.println("[AssetDirectoryManager] Removed asset '" + asset.name + "' from directory.");
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
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
            addAssetDirectoryIfNotExists(path, type);
        }
    }

    public void clear() {
        this.assetDirectory.clear();
        this.currentFolderPath = "";
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
