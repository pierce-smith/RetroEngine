package com.retro.engine.assets;

public class AssetMeta {
    public String name;
    public String absolutePath;
    public AssetType type;
    public String parentFolderPath;

    public AssetMeta(String name, String absolutePath, AssetType type) {
        this(name, absolutePath, type, "");
    }

    public AssetMeta(String name, String absolutePath, AssetType type, String parentFolderPath) {
        this.name = name;
        this.absolutePath = absolutePath;
        this.type = type;
        this.parentFolderPath = (parentFolderPath != null) ? parentFolderPath : "";
    }
}
