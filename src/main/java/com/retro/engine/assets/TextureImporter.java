package com.retro.engine.assets;

import com.retro.engine.core.EngineContext;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Importer and loader for 2D texture assets (PNG, JPG, TGA, etc.)
 * resolving paths against the working directory and the open project (see ProjectFileLocator).
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

        File inProject = ProjectFileLocator.resolveInProject(clean);
        if (inProject != null) return inProject;

        // 4. Search by filename. Exported games resolve from Game_Data/ and never search; the editor searches the
        // open project only (see ProjectFileLocator for why it must not walk user.dir or the home folder).
        if (EngineContext.isRuntimeProcess()) return null;
        return ProjectFileLocator.findByName(new File(clean).getName());
    }

}
