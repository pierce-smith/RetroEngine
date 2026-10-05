package com.retro.engine.content.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class TextureLoader {

    public static TextureData loadTexture(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("[TextureLoader] Texture file not found: " + filePath);
            return null;
        }

        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                System.err.println("[TextureLoader] Could not decode image: " + filePath);
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
            TextureData texture = new TextureData(fileName, filePath, width, height, 4, pixels);
            System.out.println("[TextureLoader] Loaded texture asset: " + texture);
            return texture;
        } catch (Exception e) {
            System.err.println("[TextureLoader] Failed to load " + filePath + ": " + e.getMessage());
            return null;
        }
    }
}
