package com.retro.engine.editor.assets;

public class AssetEntry {
    public String name;
    public String absolutePath;
    public AssetType type;
    public String parentFolderPath;

    public AssetEntry(String name, String absolutePath, AssetType type) {
        this(name, absolutePath, type, "");
    }

    public AssetEntry(String name, String absolutePath, AssetType type, String parentFolderPath) {
        this.name = name;
        this.absolutePath = absolutePath;
        this.type = type;
        this.parentFolderPath = (parentFolderPath != null) ? parentFolderPath : "";
    }
}
