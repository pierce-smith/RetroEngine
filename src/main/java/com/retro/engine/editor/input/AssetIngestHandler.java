package com.retro.engine.editor.input;

import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * AssetIngestHandler handles file drop parsing and extension classification
 * when external files (3D models, textures, scenes) are ingested into the editor.
 */
public class AssetIngestHandler {

    public static List<AssetEntry> processDroppedFiles(List<String> filePaths) {
        List<AssetEntry> entries = new ArrayList<>();
        if (filePaths == null) return entries;

        for (String path : filePaths) {
            File file = new File(path);
            String name = file.getName();
            String ext = getFileExtension(name).toLowerCase();
            AssetType type = switch (ext) {
                case "obj", "fbx", "gltf", "glb" -> AssetType.MESH_3D;
                case "png", "jpg", "tga" -> AssetType.TEXTURE;
                case "scene" -> AssetType.SCENE;
                default -> AssetType.UNKNOWN;
            };
            entries.add(new AssetEntry(name, path, type));
        }
        return entries;
    }

    public static String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return (dot == -1) ? "" : fileName.substring(dot + 1);
    }
}
