package com.retro.engine.assets.cooker;

import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.TextureImporter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-Line Asset Cooker Utility for offline batch baking of raw 3D models
 * (.obj, .fbx, .gltf, .glb) and 2D textures (.png, .jpg, .tga) into binary
 * .rpack VRAM packages while preserving the project manifest's 128-bit UUID indirection.
 */
public class RpackCookerCli {

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println(" Retro Engine Offline Asset Cooker (.rpack) Utility");
        System.out.println("==========================================================================");

        if (args == null || args.length == 0) {
            printUsage();
            return;
        }

        String targetFile = null;
        String targetDir = null;
        String outputDir = "Cooked";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("--file") || arg.equalsIgnoreCase("-f")) {
                if (i + 1 < args.length) targetFile = args[++i];
            } else if (arg.equalsIgnoreCase("--dir") || arg.equalsIgnoreCase("-d")) {
                if (i + 1 < args.length) targetDir = args[++i];
            } else if (arg.equalsIgnoreCase("--out") || arg.equalsIgnoreCase("-o")) {
                if (i + 1 < args.length) outputDir = args[++i];
            } else if (targetFile == null && targetDir == null) {
                File testFile = new File(arg);
                if (testFile.isDirectory()) {
                    targetDir = arg;
                } else {
                    targetFile = arg;
                }
            } else if (i == 1 && targetFile != null) {
                outputDir = arg;
            }
        }

        if (targetFile != null) {
            cookSingleFile(targetFile, outputDir);
        } else if (targetDir != null) {
            cookDirectory(targetDir, outputDir);
        } else {
            printUsage();
        }
    }

    private static void cookSingleFile(String filePath, String outputDir) {
        File rawFile = new File(filePath);
        if (!rawFile.exists()) {
            System.err.println("[RpackCookerCli] Error: Input file not found -> " + filePath);
            return;
        }

        String ext = getFileExtension(rawFile.getName()).toLowerCase();
        String outFileName = getFileNameWithoutExtension(rawFile.getName()) + ".rpack";
        File outFile = new File(outputDir, outFileName);

        String uuid = resolveUuidSidecar(rawFile);

        switch (ext) {
            case "obj", "fbx", "gltf", "glb" -> {
                System.out.println("[RpackCookerCli] Cooking 3D Mesh: " + rawFile.getName() + " -> " + outFile.getPath());
                RpackCooker.cookMeshToRpack(rawFile.getAbsolutePath(), outFile.getAbsolutePath(), uuid);
            }
            case "png", "jpg", "jpeg", "tga" -> {
                System.out.println("[RpackCookerCli] Cooking 2D Texture: " + rawFile.getName() + " -> " + outFile.getPath());
                RpackCooker.cookTextureToRpack(rawFile.getAbsolutePath(), outFile.getAbsolutePath(), uuid);
            }
            default -> System.err.println("[RpackCookerCli] Unsupported asset extension: ." + ext);
        }
    }

    private static void cookDirectory(String inputDirPath, String outputDirPath) {
        File inDir = new File(inputDirPath);
        if (!inDir.exists() || !inDir.isDirectory()) {
            System.err.println("[RpackCookerCli] Error: Input directory not found -> " + inputDirPath);
            return;
        }

        List<File> rawAssets = new ArrayList<>();
        scanAssetsRecursive(inDir, rawAssets);

        System.out.println("[RpackCookerCli] Found " + rawAssets.size() + " raw asset(s) in " + inDir.getPath());
        int cookedCount = 0;

        for (File rawFile : rawAssets) {
            String relPath = inDir.toPath().relativize(rawFile.toPath()).toString();
            String outRelPath = getFileNameWithoutExtension(relPath) + ".rpack";
            File outFile = new File(outputDirPath, outRelPath);

            String ext = getFileExtension(rawFile.getName()).toLowerCase();
            String uuid = resolveUuidSidecar(rawFile);

            boolean success = false;
            if (isMeshExtension(ext)) {
                success = RpackCooker.cookMeshToRpack(rawFile.getAbsolutePath(), outFile.getAbsolutePath(), uuid);
            } else if (isTextureExtension(ext)) {
                success = RpackCooker.cookTextureToRpack(rawFile.getAbsolutePath(), outFile.getAbsolutePath(), uuid);
            }

            if (success) cookedCount++;
        }

        System.out.println("==========================================================================");
        System.out.println("[RpackCookerCli] Batch Cooking Complete: " + cookedCount + " / " + rawAssets.size() + " assets processed into " + outputDirPath + "/");
        System.out.println("==========================================================================");
    }

    private static void scanAssetsRecursive(File dir, List<File> outList) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("build")) {
                scanAssetsRecursive(f, outList);
            } else if (f.isFile()) {
                String ext = getFileExtension(f.getName()).toLowerCase();
                if (isMeshExtension(ext) || isTextureExtension(ext)) {
                    outList.add(f);
                }
            }
        }
    }

    private static boolean isMeshExtension(String ext) {
        return ext.equals("obj") || ext.equals("fbx") || ext.equals("gltf") || ext.equals("glb");
    }

    private static boolean isTextureExtension(String ext) {
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("tga");
    }

    private static String resolveUuidSidecar(File rawFile) {
        String uuid = com.retro.engine.assets.AssetDatabase.lookupManifestUuid(rawFile);
        return (uuid != null) ? uuid : java.util.UUID.randomUUID().toString();
    }

    private static String getFileExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot == -1) ? "" : name.substring(dot + 1);
    }

    private static String getFileNameWithoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot == -1) ? name : name.substring(0, dot);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java com.retro.engine.assets.cooker.RpackCookerCli --file <path-to-asset> [--out <output-dir>]");
        System.out.println("  java com.retro.engine.assets.cooker.RpackCookerCli --dir <path-to-assets-folder> [--out <output-dir>]");
    }
}
