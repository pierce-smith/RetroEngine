package com.retro.engine.assets;

import java.util.UUID;

/**
 * Metadata record for a project asset, holding virtual folder organization
 * and a persistent 128-bit UUID stored in the project's centralized .retro/asset_registry.bin manifest.
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
}
