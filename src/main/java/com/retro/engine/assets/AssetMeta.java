package com.retro.engine.assets;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * Metadata record for a project asset, holding virtual folder organization
 * and a persistent 128-bit UUID stored via adjacent .rmeta sidecar files.
 *
 * Fully compliant with Phase 2 UUID indirection standards.
 */
public class AssetMeta {
    public String uuid;
    public String name;
    public String absolutePath;
    public AssetType type;
    public String parentFolderPath;

    public AssetMeta(String name, String absolutePath, AssetType type) {
        this(UUID.randomUUID().toString(), name, absolutePath, type, "");
    }

    public AssetMeta(String name, String absolutePath, AssetType type, String parentFolderPath) {
        this(UUID.randomUUID().toString(), name, absolutePath, type, parentFolderPath);
    }

    public AssetMeta(String uuid, String name, String absolutePath, AssetType type, String parentFolderPath) {
        this.uuid = (uuid != null && !uuid.trim().isEmpty()) ? uuid.trim() : UUID.randomUUID().toString();
        this.name = (name != null) ? name : "";
        this.absolutePath = (absolutePath != null) ? absolutePath : "";
        this.type = (type != null) ? type : AssetType.UNKNOWN;
        this.parentFolderPath = (parentFolderPath != null) ? parentFolderPath : "";
    }

    /**
     * Ensures an adjacent .rmeta sidecar file exists on disk for this asset.
     * Reads existing UUID if .rmeta exists, or writes a new .rmeta sidecar if missing.
     */
    public void ensureMetaSidecar() {
        if (absolutePath == null || absolutePath.trim().isEmpty() || type == AssetType.FOLDER) return;

        File assetFile = new File(absolutePath);
        if (!assetFile.exists()) return;

        File metaFile = new File(absolutePath + ".rmeta");
        if (!metaFile.exists()) {
            metaFile = new File(absolutePath + ".meta");
        }

        if (metaFile.exists()) {
            try {
                List<String> lines = Files.readAllLines(metaFile.toPath());
                for (String line : lines) {
                    if (line.startsWith("uuid=")) {
                        String loadedUuid = line.substring(5).trim();
                        if (!loadedUuid.isEmpty()) {
                            this.uuid = loadedUuid;
                        }
                    } else if (line.startsWith("type=") && this.type == AssetType.UNKNOWN) {
                        String loadedTypeStr = line.substring(5).trim();
                        try {
                            this.type = AssetType.valueOf(loadedTypeStr);
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            } catch (IOException e) {
                System.err.println("[AssetMeta] Failed to read .rmeta for " + name + ": " + e.getMessage());
            }
        } else {
            writeMetaSidecar();
        }
    }

    /**
     * Writes or overwrites the .rmeta sidecar file on disk.
     */
    public void writeMetaSidecar() {
        if (absolutePath == null || absolutePath.trim().isEmpty() || type == AssetType.FOLDER) return;

        File metaFile = new File(absolutePath + ".rmeta");
        try {
            if (metaFile.getParentFile() != null && !metaFile.getParentFile().exists()) {
                metaFile.getParentFile().mkdirs();
            }
            String content = "uuid=" + uuid + "\n" +
                             "type=" + type.name() + "\n";
            Files.writeString(metaFile.toPath(), content);
        } catch (IOException e) {
            System.err.println("[AssetMeta] Failed to write .rmeta sidecar for " + name + ": " + e.getMessage());
        }
    }
}
