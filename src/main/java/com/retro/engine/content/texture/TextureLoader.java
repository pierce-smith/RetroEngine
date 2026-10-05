package com.retro.engine.content.texture;

import java.io.File;

public class TextureLoader {

    public static TextureData loadTexture(String filePath) {
        if (filePath == null) return null;
        File file = new File(filePath);
        if (!file.exists()) return null;

        System.out.println("[TextureLoader] Ingested texture asset: " + file.getName());
        return new TextureData(512, 512, 4, filePath);
    }
}
