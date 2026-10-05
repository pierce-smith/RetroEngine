package com.retro.engine.content.texture;

public class TextureData {
    public int width;
    public int height;
    public int channels;
    public String filePath;

    public TextureData(int width, int height, int channels, String filePath) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.filePath = filePath;
    }
}
