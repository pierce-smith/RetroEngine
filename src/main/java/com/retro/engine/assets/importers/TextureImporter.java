package com.retro.engine.assets.importers;

import com.retro.engine.core.EngineContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Importer and loader for 2D texture assets (PNG, JPG, TGA, etc.)
 * featuring multi-location file resolution (working directory, Assets/, Downloads/).
 */
public class TextureImporter {

    public static PixelBufferData loadTexture(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }

        File file = resolveTextureFile(filePath);
        if (file == null || !file.exists()) {
            System.err.println("[TextureImporter] Texture file not found on disk: " + filePath);
            return null;
        }

        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                System.err.println("[TextureImporter] Could not decode image: " + file.getAbsolutePath());
                return null;
            }

            int width = img.getWidth();
            int height = img.getHeight();
            int[] argb = new int[width * height];
            img.getRGB(0, 0, width, height, argb, 0, width);

            byte[] pixels = new byte[width * height * 4];
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                pixels[i * 4 + 0] = (byte) ((p >> 16) & 0xFF); // R
                pixels[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
                pixels[i * 4 + 2] = (byte) (p & 0xFF);         // B
                pixels[i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
            }

            String fileName = file.getName();
            PixelBufferData texture = new PixelBufferData(fileName, file.getAbsolutePath(), width, height, 4, pixels);
            System.out.println("[TextureImporter] Loaded texture asset: " + texture);
            return texture;
        } catch (Exception e) {
            System.err.println("[TextureImporter] Failed to load " + filePath + ": " + e.getMessage());
            return null;
        }
    }

    public static File resolveTextureFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        File direct = new File(filePath);
        if (direct.exists()) return direct;

        String clean = filePath.replace('\\', '/').trim();

        // 1. Try relative to working directory
        File userDirFile = new File(System.getProperty("user.dir"), clean);
        if (userDirFile.exists()) return userDirFile;

        // 2. Try relative to Assets/
        File assetsRelative = new File("Assets", clean);
        if (assetsRelative.exists()) return assetsRelative;

        // 3. Strip leading "Assets/"
        if (clean.toLowerCase().startsWith("assets/")) {
            String stripped = clean.substring(7);
            File strippedFile = new File(stripped);
            if (strippedFile.exists()) return strippedFile;

            File userDirStripped = new File(System.getProperty("user.dir"), stripped);
            if (userDirStripped.exists()) return userDirStripped;
        }

        // 4. Recursive search by filename in working directory, Assets/, and Downloads
        String fileName = new File(clean).getName();
        // Runtime builds never scan user.dir ("/" when launched from Finder) or ~/Downloads: those walks trip macOS
        // iCloud Drive / Downloads / network-volume permission prompts. Exported assets resolve from Game_Data/.
        if (fileName.isEmpty() || EngineContext.isRuntimeProcess()) return null;
        {
            File rootAssets = new File("Assets");
            if (rootAssets.exists() && rootAssets.isDirectory()) {
                File found = findFileByName(rootAssets, fileName, 0);
                if (found != null && found.exists()) return found;
            }

            File userDir = new File(System.getProperty("user.dir"));
            if (userDir.exists() && userDir.isDirectory()) {
                File found = findFileByName(userDir, fileName, 0);
                if (found != null && found.exists()) return found;
            }

            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                File downloadsDir = new File(userHome, "Downloads");
                if (downloadsDir.exists() && downloadsDir.isDirectory()) {
                    File homeFound = findFileByName(downloadsDir, fileName, 0);
                    if (homeFound != null && homeFound.exists()) return homeFound;
                }
            }
        }

        return null;
    }

    private static File findFileByName(File dir, String name, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > 5) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) {
                return f;
            } else if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("build") && !f.getName().equals("node_modules")) {
                File sub = findFileByName(f, name, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }
}
